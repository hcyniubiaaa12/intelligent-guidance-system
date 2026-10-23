# 06: 退出登录后留在游客态

**What to build:**

已登录用户点「退出登录」后，**留在 `/` 的游客态**，而不是被送去 `/login`。

连带清两样东西：`chat.reset()`（既有行为，防共用设备看到上一位患者的主诉与结论卡）与 **`guestDraft`**（防止上一位患者写的主诉留给下一个人）。

**已定的规则**

1. **退出登录的语义是"不再以这个身份操作"，不是"这个产品对我关门了"**。跳到登录页再让他自己点回首页，是一段没有信息量的绕路。
2. **`guestDraft` 必须清**。这条与工单 02 的"清除点显式写"是同一条：登出根本不经过对话页挂载，所以 `consumeGuestDraft()` 兜不到它。
3. **`apiLogout()` 的 401 不许挡住登出**。后端 `/api/auth/logout` **不在 permitAll 里**，token 已失效时它会返回 401——但本地清态必须照做，不能被一个失败的网络请求卡住。现有代码用 `finally` 保证了这一点，**保持**。
4. **不跳转**，包括不跳 `/login`。跳转后由路由守卫决定去向的写法要删掉。

**验收**

- [x] 已登录用户点「退出登录」⇒ **URL 仍是 `/`**（或从别的页面退回到 `/`），页面进入游客态（由工单 07 探针 7 / 本次补充探针 1 覆盖）
- [x] 左栏用户区变为「未 登 录」+「去登录」（由工单 07 探针 7 / 本次补充探针 1 覆盖）
- [x] 输入框为空、无锁定回执（会话状态已清）（由工单 07 探针 7 覆盖）
- [x] `guestDraft` 已清（再点「去登录」登录回来，不会看到上一位患者的内容）（由工单 07 探针 7 覆盖）
- [ ] token 失效的情况下点退出，本地仍然清干净、仍然留在游客态（不被 401 卡住或踢去登录页）——**本次补充探针 3 实测未通过**，见下方「评审修复后的补充验证」
- [x] 已登录 → 退出 → 再点「去登录」→ 登录 ⇒ 正常回到 `/`，可正常导诊（由本次补充探针 4 覆盖）
- [x] `npm run build` 通过；`npm run check` 全绿

> 原先这六条标为"归人工验收、本轮不勾"；2026-10-23 已由工单 07 探针与评审后的补充探针真机覆盖（playwright-core 无头 Chrome，非人眼目测）。其中第 5 条（token 失效时退出）实测未通过，缺陷仍在，保持未勾。

**Blocked by:** 03, 05

**Status:** resolved

## Comments

**为什么依赖 05**：退出后停留的位置是"游客态的 `/`"——如果游客态还没做出来（05），退出后用户会落在一个既不是游客态、也发不出消息的空白页上，比现状更糟。

### 实现说明

**落点**：只改 `frontend/src/views/patient/Chat.vue` 的 `onLogout()`（一行 HTML 未动）。手机端顶栏「退出」与左栏 `SideRail` 的 `@logout` 都指向同一个 `onLogout`，改一处即两处生效。

**最终顺序**（`finally` 内）：

```
startNew()
chat.clearGuestDraft()
user.logout()
if (router.currentRoute.value.path !== '/') router.push('/')
```

`turnAbort?.abort()` 仍在 `try` 之前，`await apiLogout()` 的 `finally` 结构原样保留（401 不挡登出）。

**三处偏离/取舍，各有理由**：

1. **`startNew()` 取代了原来的 `chat.reset()`**。工单要求「输入框为空、无锁定回执」，而 `reset()` 只清 `sessionId` / `entries` / `streaming`，**不碰**页面局部的 `replay` / `parts` / `partStage` / `draft`。只调 reset() 会留下「左栏已经是游客态、正文却停在一段只读回放里」的中间态（工单里点名的那种自相矛盾）。`startNew()` 正是这四样的归零口径，且它内部已调 `chat.reset()`，不必再补一次——多用一处口径就会分叉。副作用只有两个，都无碍：多清一次 `activeQ` / `sideOpen`（本来也该收），以及末尾 `nextTick(focus)` 把光标落进输入框（等待态输入框只读，焦点落上去也无害）。
2. **`clearGuestDraft()` 放在 `startNew()` 之后，作为清态的最后一步**。`startNew()` 不碰草稿（`reset()` 刻意不清——草稿不是"这次会话"的一部分），所以放前放后当下都正确；放最后的理由是让「登出后草稿一定为空」由**最后一步**保证：将来 `startNew()` 若被加了任何写草稿的动作，也覆不回来。工单 02 的接线断言（按文本匹配 `onLogout` 函数体里出现 `clearGuestDraft()`）不锁顺序，仍然通过。
3. **`router.push('/')` 保留了一个条件兜底**，不是"完全不跳转"的字面实现。工单规则 4 要删的是「跳登录页 / 跳转后由路由守卫决定去向」；本页就挂在 `/` 上，所以日常点退出这一行不执行（`currentRoute.path === '/'`）。留着它是为将来从别的路由点退出准备：那时候不跳才会把用户留在需要登录的页面上（且下一个 401 会把他送进登录页——正是本工单要消灭的路径）。已在代码注释里写明这条判据。

**未改 `utils/authRedirect.js`**：spec 明确「登录过期仍然跳登录页」，那一轮 401 路径不改成游客态，所以工单 02 Comments 里那句"若 06 把 401 也留游客态，那时回来补这一行"这一轮用不上。

**为什么游客态本身不用在这里摆**：`user.isGuest` 是 `!token` 的 computed，`user.logout()` 清 token 后即为 true，左栏用户区 / 顶栏 / 输入区闸门全部跟着它变（03/04/05 已接）。`user.logout()` 放在 `clearGuestDraft()` 之后，是为了"草稿清空"不被任何可能读 `isGuest` 的中间渲染打断（当下没有这种副作用，纯属把顺序钉死）。

**验证**：`npm run check` 全绿（153 + 8 + 15 = 176 条，与工单预期一致）；`npm run build` 通过（仅有既存的 chunk >500kB 警告）。`grep -n "router.push" Chat.vue` 确认 `onLogout` 里无 `'/login'`（另两处分别是 `goRegister` 与 `goLogin`，与本工单无关）。

**归人工验收（未勾）**：退出后留在游客态、左栏变「未 登 录 / 去登录」、token 失效时退出仍清干净、退出→登录→回 `/` 可正常导诊——这六条要起全栈真机点。

### 补充验证（2026-10-23）——第 5 条（token 失效时退出）实测未通过

六条里五条已由真机探针覆盖并勾上，**唯独「token 失效的情况下点退出，本地仍然清干净、仍然留在游客态」稳定失败**。复现：登录 → 把 `localStorage.token` 改成 `bad`（**不刷新**，让 store 仍认为已登录）→ 点「退出」→ 落到 `/login?redirect=/`。

**成因与规范 3 的落点冲突**：`apiLogout()` 拿坏 token 请求 `/api/auth/logout` 必然 401，axios 拦截器调 `onUnauthorized()`；此时 `isGuest` 仍为 false（改动只在 localStorage，store 里那份没动），判据落进「登录过期」分支 → 跳登录页。`finally` 照常清了本地态（这一点规范 3 是达成的），但紧接着的 `router.push('/')` 兜底没兜住：实测 URL 轨迹只有一次 `pushState`（`t=38ms` 到 `/login?redirect=/`），之后无跳转——执行兜底那行时 `currentRoute.value.path` 仍是 `/`，条件为假。

**核心矛盾**：「登出」这一次 401 是**预期内**的，它不代表"登录过期、需要重新登录"；但 `onUnauthorized()` 无从区分。这也解释了为什么工单 01 收成单一入口后这个问题才显形——合并后的入口只有「路由是否 public + 是否游客」两个变量，没有"这次请求是不是登出"。

**建议修法（未实施）**：给登出请求打标记跳过跳转（`http.post('/auth/logout', null, { skipAuthRedirect: true })` + 拦截器判 `err.config?.skipAuthRedirect`），并在 `onLogout` 的 `try` 后补 `catch`（当前只有 `try/finally`，401 会以未捕获 promise rejection 冒到控制台：`pageerror: Error: 未登录或登录已过期`）。**不要**改成"先 `user.logout()` 再调接口"——那样请求不带 token，后端真实登出会失效，等于假登出。
