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

- [x] 全仓 grep `redirectToLogin` 只剩一处定义（在 `utils/authRedirect.js` 或等价的单一文件里）
- [x] `api/http.js` 与 `utils/sse.js` 都改为调它，各自的本地副本已删
- [x] 游客可访问的路由（`meta.public`）上收到 401 ⇒ 不跳转，调用方拿到错误
- [x] 非 public 路由上收到 401 ⇒ 清 store + 跳 `/login?redirect=当前路径`
- [x] `npm run build` 通过；`npm run check` 现有 161 条全绿
- [x] 真机：把 token 改坏后发一条消息 ⇒ 跳登录页（回归"登录过期"路径没被打破）（由工单 07 探针 8 / 本次补充探针 7 覆盖）

**Blocked by:** 无

Status: resolved

## Comments

这条**先做**，后面几个工单都依赖它——取消登录跳转会改到这条路径。若先放开路由再合并 401，中间态会出现"游客能进页面、但点任何东西都被强制跳登录"，那是比现状更差的可观测状态。

### 实现说明（已落地）

**改了什么**

- 新增 `frontend/src/utils/authRedirect.js`，导出唯一入口 `onUnauthorized()`：读 `router.currentRoute.value`，
  `meta.public` ⇒ 直接 `return false`（**不碰 store、不跳转**）；否则 `useChatStore().reset()` + `useUserStore().logout()`
  ⇒（当前不在 `/login` 时）`router.push({ path: '/login', query: { redirect: fullPath } })` ⇒ `return true`。
- `frontend/src/api/http.js`：删掉本地 `redirectToLogin` 与随之无用的 `router` / 两个 store 的 import，两处调用点
  （业务码 2000 分支、响应错误里的 401）改调 `onUnauthorized()`。请求拦截器与其余分支一字未动。
- `frontend/src/utils/sse.js`：同样删掉本地副本与 `router` / store import，两处 401 / 2000 判断改调 `onUnauthorized()`。
  读取与事件分发逻辑一字未动。

**值得后人知道的坑**

1. **两份副本的差异不止"跳不跳"**：`http.js` 那份把清 store 写在 `meta.public` 判断**之前**——
   公开路由上它虽不跳转、却仍会清掉登录态；`sse.js` 那份则完全不看 `meta.public`，无条件清 + 无条件跳。
   合并后统一成"公开路由连 store 都不碰"：公开路由上收到 401 通常意味着调用方本来就没登录
   （游客请求被后端 401），这时再清一遍 store 是多余动作，还会把用户已经建立的登录态误清。
2. **"已在 `/login` 就不 push"这条边界保留了**（原 `http.js` 有）。现在的判据只有 `meta.public` 一处分叉，
   `/login` 自身不是 public，所以必须额外守住这条，否则登录页上触发的 401 会叠一条重复历史记录、
   且 `redirect` 会指回 `/login` 自己。
3. **返回值两个调用方目前都不消费**：`http.js` 照旧 reject、`sse.js` 照旧 `fail(message)`——返回值只是给后续工单
   （游客态下要在公开路由上展示"需要登录"）留的区分手段，**不要因为拿到 false 就把错误吞掉**。
4. **循环依赖照旧成立**：`authRedirect.js` → `router` → `stores/user`，这条边原本就在 `http.js` 里跑得通，
   换了个文件承载而已；`router/index.js` 不 import 本文件，无环。
5. 本工单**没有**给 `/` 加 `meta.public`——那是工单 02 的事。所以现在在两份实现合并之后，
   非 public 路由的行为与合并前完全一致（这是"先合并、后放开路由"的顺序要求，见上一条 Comment）。

