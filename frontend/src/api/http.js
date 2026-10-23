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
// 判据（当前路由是否 meta.public）也在那里，本文件不再自留副本。

http.interceptors.response.use(
  (res) => {
    const body = res.data
    // 非 Result 结构（如文件流）原样返回
    if (body === null || typeof body !== 'object' || body.code === undefined) return body
    if (body.code === 200) return body.data
    // 业务码 2000 = 未登录/登录已过期（HTTP 200 场景兜底，如登出后 token 失效）
    if (body.code === 2000) {
      // 公开路由上、游客态下 onUnauthorized 不跳转：错误照旧 reject 交调用方展示
      onUnauthorized()
      return Promise.reject(new Error(body.message || '未登录或登录已过期'))
    }
    return Promise.reject(Object.assign(new Error(body.message || '请求失败'), { code: body.code }))
  },
  (err) => {
    if (err.response?.status === 401) onUnauthorized()
    const message = err.response?.data?.message || err.message || '网络异常'
    return Promise.reject(Object.assign(new Error(message), { code: err.response?.data?.code }))
  }
)

export default http
