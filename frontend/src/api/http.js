import axios from 'axios'
import { onUnauthorized } from '../utils/authRedirect'

// HTTP 统一封装：拦截器带 JWT、401 跳登录
// 约定：resolve 直接给 Result.data（业务数据本体）；业务错误（HTTP 200 但 code!==200）
// reject 携带 code/message 的 Error，调用方 catch 后展示 message 即可
const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 401 的处置（清 store + 跳登录）全仓只有一处：`utils/authRedirect.js`。
// 判据（公开路由 且 确实是游客）也在那里，本文件不再自留副本。
//
// **例外：`config.skipAuthRedirect` 的请求自己处置 401。** 目前只有登出（`api/auth.js`）用它。
// 为什么必须留这个口子：`POST /api/auth/logout` 不在后端 permitAll 里，token 已失效时它**必然** 401，
// 而那正是最该清干净的时候——由拦截器接管就会把人踹去登录页，与「退出登录后留在游客态」直接打架。
// **不能改成"先清本地态再调接口"**：那样请求不再带 token，后端那份 Redis 登录态就真删不掉了。
const skipRedirect = (config) => config?.skipAuthRedirect === true

http.interceptors.response.use(
  (res) => {
    const body = res.data
    // 非 Result 结构（如文件流）原样返回
    if (body === null || typeof body !== 'object' || body.code === undefined) return body
    if (body.code === 200) return body.data
    // 业务码 2000 = 未登录/登录已过期（HTTP 200 场景兜底，如登出后 token 失效）
    if (body.code === 2000) {
      // 公开路由上、游客态下 onUnauthorized 不跳转：错误照旧 reject 交调用方展示
      if (!skipRedirect(res.config)) onUnauthorized()
      return Promise.reject(new Error(body.message || '未登录或登录已过期'))
    }
    return Promise.reject(Object.assign(new Error(body.message || '请求失败'), { code: body.code }))
  },
  (err) => {
    if (err.response?.status === 401 && !skipRedirect(err.config)) onUnauthorized()
    const message = err.response?.data?.message || err.message || '网络异常'
    return Promise.reject(Object.assign(new Error(message), { code: err.response?.data?.code }))
  }
)

export default http
