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

- [ ] `stores/chat.js` 新增 `guestDraft` 与 `consumeGuestDraft()`；`reset()` 未新增对它的任何清理
- [ ] 登出路径清空 `guestDraft`
- [ ] 空草稿（无文字、无部位）不被当成有效草稿
- [ ] 新增 `frontend/scripts/check-guest-draft.mjs`，四条断言：取走即清、`reset()` 后仍在、登出清空、空草稿无效
- [ ] **先红后绿**：把 `reset()` 里误加 `guestDraft` 清理 → 该条变红；改回 → 全绿
- [ ] 新脚本自动受 pre-commit 硬闸保护（闸是通配 `scripts/check-*.mjs`，不需要改 hook）
- [ ] `npm run check` 全绿

**Blocked by:** 无（与 01 无代码交集，可并行；但两条都要改 store，串行更稳）

**Status:** ready-for-agent

## Comments

**为什么"取走即清"而不是"登录成功时清"**：登录成功后跳转 → 对话页重新挂载，两边谁先谁后不确定。取走即清把时序变成单向的：只有对话页挂载时那一次读取。

**为什么先于 03 做**：03 要让对话页在游客态下把草稿存进 store、挂载时取回。store 的语义先定死，组件侧才不会各写一套"要不要清"的判断。
