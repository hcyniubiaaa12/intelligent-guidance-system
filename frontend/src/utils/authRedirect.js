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
 * @returns {boolean} 是否由本函数接管了"未登录"这件事。
 *   - 公开路由（`meta.public`）⇒ false：不跳转，**"需要登录"交回调用方**展示；
 *   - 其余路由 ⇒ true：已清 store 并跳登录页（当前已停在 `/login` 时不再重复 push）。
 *   两个调用方都不靠返回值决定要不要报错——`http.js` 继续 reject、`sse.js` 继续走
 *   `handlers.onError`，**这条路上任何错误都不许被吞掉**。
 */
export function onUnauthorized() {
  const route = router.currentRoute.value

  // 公开路由（游客可访问，如 /preview、游客态首页）：不跳转。
  // 路由守卫对 public 路由本就放行，这里必须同口径；否则一进页面就被接口 401 踹回登录页。
  if (route.meta.public) return false

  // 清 store（token/role/nickname 同步清 localStorage），避免守卫仍判定已登录。
  // 连同对话 store 一起清：共用设备上换账号后不能看到上一位患者的主诉与结论卡。
  useChatStore().reset()
  useUserStore().logout()

  // 已经在登录页就不再 push（否则多叠一条重复的历史记录，redirect 也会绕回自己）
  if (route.path !== '/login') {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
  }
  return true
}
