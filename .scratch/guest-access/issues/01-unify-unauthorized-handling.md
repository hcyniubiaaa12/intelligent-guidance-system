# 01: 401 口径合并为一处

**What to build:**

前端现在有**两份**"未登录就跳登录"的实现，而且行为不一致：

- `api/http.js` 的 `redirectToLogin()` 会检查当前路由是否 `meta.public`；
- `utils/sse.js` 里另有一份同名函数，**不检查** `meta.public`。

抽出 `utils/authRedirect.js`，导出单一入口 `onUnauthorized()`，两个文件都改调它、**各自不再保留本地副本**。

```js
// 判据（唯一一处）
if (当前路由.meta.public) {
  // 不跳转：把"需要登录"交回调用方展示
  return false
}
// 否则：清 store + 跳登录
```

清理动作沿用现有语义（两个调用点现在都做同样的两件事）：`useChatStore().reset()` + `useUserStore().logout()`，然后跳 `/login?redirect=<当前 fullPath>`。

**已定的规则**

1. **判据不分"游客"与"登录过期"**。游客在发送闸处就被拦住、发不出请求；能走到 401 的只有 token 过期/失效。所以"过期 ⇒ 跳登录"原样保留，这次只是把两份实现合成一份。
2. **`sse.js` 那份必须一起改**——它是这次分叉的根源，不合并的话即使把 `/` 设成公开路由，游客发消息仍会被强制踹去登录页。
3. **返回值要能让调用方区分**：`onUnauthorized()` 返回"是否已跳转"，`http.js` / `sse.js` 据此决定后续（`http.js` 本来就走 reject；`sse.js` 本来就走 `handlers.onError`，**两者都不许因为这次改动吞掉错误**）。
4. **不动**：`http.js` 的请求拦截器（带 token）、业务码非 2000 的分支、`sse.js` 的读取与事件分发逻辑。

**验收**

- [ ] 全仓 grep `redirectToLogin` 只剩一处定义（在 `utils/authRedirect.js` 或等价的单一文件里）
- [ ] `api/http.js` 与 `utils/sse.js` 都改为调它，各自的本地副本已删
- [ ] 游客可访问的路由（`meta.public`）上收到 401 ⇒ 不跳转，调用方拿到错误
- [ ] 非 public 路由上收到 401 ⇒ 清 store + 跳 `/login?redirect=当前路径`
- [ ] `npm run build` 通过；`npm run check` 现有 161 条全绿
- [ ] 真机：把 token 改坏后发一条消息 ⇒ 跳登录页（回归"登录过期"路径没被打破）

**Blocked by:** 无

**Status:** ready-for-agent

## Comments

这条**先做**，后面几个工单都依赖它——取消登录跳转会改到这条路径。若先放开路由再合并 401，中间态会出现"游客能进页面、但点任何东西都被强制跳登录"，那是比现状更差的可观测状态。
