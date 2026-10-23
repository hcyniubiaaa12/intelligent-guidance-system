# 02: 游客草稿进 store——取走即清，`reset()` 不碰它

**What to build:**

游客会在输入框里写字、在人体图上选部位，然后点「去登录」。跳转那一刻对话页卸载，`draft` / `parts` / `partStage` 三个局部状态全部消失——**而这是我们要求他去登录的，不是他改了主意**。

在 chat store 里加一组**独立字段** `guestDraft`（`{ draft, parts, partStage }`），并暴露 `consumeGuestDraft()`。

```js
// 形状照搬既有的 consumeReplay()：取走并清空
function consumeGuestDraft() {
  const next = guestDraft.value
  guestDraft.value = null
  return next
}
```

**已定的规则**

1. **`reset()` 不许碰 `guestDraft`**。`reset()` 的语义是"清空这次会话"（开新咨询、挂号成功、登出三处调用），而草稿恰恰是要保住的东西。塞进去的后果是"游客点了去登录、回来一看输入框空了"——正是本工单要修的缺陷。
2. **清除点必须是显式的两处**：`consumeGuestDraft()` 那一次，以及**登出时**（登出根本不经过对话页挂载）。不靠"反正会被覆盖"这种隐式保证。
3. **不落 `localStorage`**：共用设备上会给下一个人看到上一位患者写的主诉。仓里已有同一条线（换账号不能看到上一位患者的主诉与结论卡）。
4. **空草稿不算草稿**：`draft` 与 `locations` 都为空时，不要存成一个"有草稿"的状态——否则登录回来会用空值覆写输入框。
5. **登录页完全不知道这件事**。让登录页去清对话页的内部状态是不该有的耦合；"取走即清"天然满足"清除点显式"。

**验收**

- [x] `stores/chat.js` 新增 `guestDraft` 与 `consumeGuestDraft()`；`reset()` 未新增对它的任何清理
- [x] 登出路径清空 `guestDraft`
- [x] 空草稿（无文字、无部位）不被当成有效草稿
- [x] 新增 `frontend/scripts/check-guest-draft.mjs`，四条断言：取走即清、`reset()` 后仍在、登出清空、空草稿无效
- [x] **先红后绿**：把 `reset()` 里误加 `guestDraft` 清理 → 该条变红；改回 → 全绿
- [x] 新脚本自动受 pre-commit 硬闸保护（闸是通配 `scripts/check-*.mjs`，不需要改 hook）
- [x] `npm run check` 全绿

**Blocked by:** 无（与 01 无代码交集，可并行；但两条都要改 store，串行更稳）

**Status:** resolved

## Comments

**为什么"取走即清"而不是"登录成功时清"**：登录成功后跳转 → 对话页重新挂载，两边谁先谁后不确定。取走即清把时序变成单向的：只有对话页挂载时那一次读取。

**为什么先于 03 做**：03 要让对话页在游客态下把草稿存进 store、挂载时取回。store 的语义先定死，组件侧才不会各写一套"要不要清"的判断。

### 实现说明

**落点**：`frontend/src/stores/chat.js` 新增 `guestDraft`（`ref(null)`，内存态、不落 localStorage）+ 三个动作 `saveGuestDraft()` / `consumeGuestDraft()` / `clearGuestDraft()`；`reset()` 的**函数体一行没动**，只补了一条注释写明"不许加"及原因（防后人顺手加）。

**为什么多了一个 `saveGuestDraft()`（工单原文只点名了 `consumeGuestDraft()`）**："空草稿不算草稿"这条规则必须有**唯一一处**判空，否则每个调用点各写一遍 trim + length 判断，迟早分叉。判空看 `trim` 后是否为空，但**存的是原文**（不替患者改写他写的字）；`partStage` 缺省补 `awaiting`（等待态是默认形态）。

**登出清空落在哪里**：落在调用方 `frontend/src/views/patient/Chat.vue` 的 `onLogout()`（`chat.reset()` 之后加一行 `chat.clearGuestDraft()`），**没有**放进 `stores/user.js` 的 `logout()`。理由：`user.logout()` 的三个调用方里有 `utils/authRedirect.js`（401 处置）——那里**不该**清草稿（"登录过期"不是"用户主动退出"）；把动作塞进 `user.logout()` 还会引入 `user → chat` 的 store 反向耦合，而仓里现有写法（`http.js`、`authRedirect.js`）都是**调用方依次调两个 store**。做成独立动作 `clearGuestDraft()` 的好处是"谁清草稿"永远能 grep 到，工单 06 改退出落点（退出后留在游客态）时只需移动那一行、不用再想"清还是不清"。

> 注意：`utils/authRedirect.js` 的 401 路径只调 `reset()`、**不**调 `clearGuestDraft()`——那里清草稿既无必要也无意义（过期用户被送进登录页，草稿他看不到）。工单 06 若把 401 也改成"留游客态"，那时要回来补这一行。

**脚本走的是"直接 import store"那条路**，没有抽零依赖的 `utils/guestDraft.js`。原因：要钉的四条规则里，「`reset()` 不碰它」**只有对着真 store 才断言得了**；抽成纯函数会让脚本断言一个与线上调用路径并不相同的副本，绿了也不算数。Pinia setup store 在 Node 里可以直接起来（`createPinia()` + `setActivePinia()`），`node_modules` 本来就是跑 `npm run check` 的前提，没有新增依赖。脚本头部注释写明了这个选择及理由。

**有一条约的是接线、不是纯规则**：「登出路径必须显式调 `clearGuestDraft()`」——读 `Chat.vue` 的 `onLogout()` 函数体按文本匹配。理由：动作测全绿而调用点漏写，正是"显式清除"这类约定最典型的失败形态。它只匹配函数体里出现这个名字，不锁语句顺序，重构挪位置不会误红。

**先红后绿的实测输出**：

1. 第一版（store 三动作就位、`Chat.vue` 尚未接线）→ 红，且红在那一条接线上：`✗ 1 条失败（14 条通过）：✗ ★ 登出路径（Chat.vue 的 onLogout）必须显式调 clearGuestDraft()`。
2. 接线后全绿（15 条）。
3. 按工单要求做反向验证：在 `reset()` 首行插入 `guestDraft.value = null` → 红，且**只有**那一条：

   ```
   ✗ 1 条失败（14 条通过）：

     ✗ ★ reset() 之后草稿仍在——开新咨询 / 挂号成功都不该清草稿
         期望 {"draft":"左腹部疼，两天了","parts":["左腹部"],"partStage":"locked"}
         实际 null
   ```

   同一条断言里的两个前置断言（`sessionId` 已清、`entries` 已清）仍然通过——说明红的是"草稿被误清"，不是 reset 根本没跑到。
4. 改回 → `npm run check` 三脚本全绿（153 + 8 + 15）。

**接闸**：`frontend/package.json` 的 `check` 在原有两条后用 `&&` 串上第三条；pre-commit 通配 `scripts/check-*.mjs`，**hook 一行未改**。（`npm run check` 的原有两条 153 + 8 = 161 未受影响。）
