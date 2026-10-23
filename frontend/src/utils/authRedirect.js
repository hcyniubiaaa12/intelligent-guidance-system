// 401 统一口径：全仓唯一一处"未登录就跳登录"的实现。
// 两个调用方——`api/http.js`（JSON 接口）与 `utils/sse.js`（对话流）。
// 曾经各有一份同名副本且判据不同（sse 那份不看 meta.public），
// 症状是公开路由上游客一发消息就被强制踹去登录页。
import router from '../router'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'

/**
 * 收到 401 / 业务码 2000 时的唯一处置入口。
 *
 * 两种情形：
 *   - **游客**（未持有 token）在公开路由上 ⇒ 不跳转、不碰 store，**"需要登录"交回调用方**展示；
 *   - 其余情形（持有 token 却在公开路由上被 401 = **登录过期**；或不在公开路由上）⇒
 *     清 store 并跳登录页（当前已停在 `/login` 时不再重复 push）。
 *
 * 不返回任何值：两个调用方都不靠返回值决定行为——`http.js` 继续 reject、`sse.js` 继续走
 * `handlers.onError`，**这条路上任何错误都不许被吞掉**。
 *
 * <p>判据不是「当前路由是否 public」一条，而是「**public 且确实是游客**」：
 * 只看路由会把「登录过期」也一并放过——`/` 是公开路由，过期用户在这里发消息被 401 后
 * 既不清 token 也不跳登录页，界面维持着"已登录"的假象、每次操作都失败、且没有任何出路。
 * 游客与过期的分界是**手里有没有 token**（读 `isGuest`，与全仓唯一的游客判定同源），
 * 不是路由。
 */
export function onUnauthorized() {
  const route = router.currentRoute.value
  const user = useUserStore()

  // 公开路由 + 游客：不跳转、不碰 store。
  // 游客根本走不到这里（`Chat.vue` 的 `send()` 入口就拦下了，请求不发），这条是兜底：
  // 万一将来某个公开页对游客发了请求，也不该把一个只是"先看看"的人踹去登录页。
  if (route.meta.public && user.isGuest) return

  // 到此要么是登录过期（手上有 token 但后端不认），要么在需要登录的路由上。
  // 清 store（token/role/nickname 同步清 localStorage），避免守卫仍判定已登录。
  // 连同对话 store 一起清：共用设备上换账号后不能看到上一位患者的主诉与结论卡。
  useChatStore().reset()
  user.logout()

  // 已经在登录页就不再 push（否则多叠一条重复的历史记录，redirect 也会绕回自己）
  if (route.path !== '/login') {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
  }
}
