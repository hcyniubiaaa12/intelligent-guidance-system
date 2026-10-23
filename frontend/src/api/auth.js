import http from './http'

// 鉴权接口（链路 D）：登录 / 注册（默认患者角色）/ 登出
// resolve 值 = LoginVO：{ token, userId, username, nickname, role }

export function login(data) {
  return http.post('/auth/login', data)
}

export function register(data) {
  return http.post('/auth/register', data)
}

// 登出：后端删 Redis 登录态（按 tokenId），登出即时失效。
// `skipAuthRedirect`：token 已失效时这个接口**必然** 401，但那正是最该清干净的时候——
// 交给拦截器接管就会把人踹去登录页，与「退出登录后留在游客态」直接打架（见 `http.js` 那段注释）。
export function logout() {
  return http.post('/auth/logout', null, { skipAuthRedirect: true })
}
