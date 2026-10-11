import axios from 'axios'
import router from '../router'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'

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

function redirectToLogin() {
  // 清 store（token/role/nickname 同步清 localStorage），避免守卫仍判定已登录
  // 连同对话 store 一起清：共用设备上换账号后不能看到上一位患者的主诉与结论卡
  useChatStore().reset()
  useUserStore().logout()
  // 公开路由（如人体图离线预览 /preview）路由守卫已放行，这里必须同口径——否则一进页面就被接口 401 踹回登录页
  if (router.currentRoute.value.path !== '/login' && !router.currentRoute.value.meta.public) {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

http.interceptors.response.use(
  (res) => {
    const body = res.data
    // 非 Result 结构（如文件流）原样返回
    if (body === null || typeof body !== 'object' || body.code === undefined) return body
    if (body.code === 200) return body.data
    // 业务码 2000 = 未登录/登录已过期（HTTP 200 场景兜底，如登出后 token 失效）
    if (body.code === 2000) {
      redirectToLogin()
      return Promise.reject(new Error(body.message || '未登录或登录已过期'))
    }
    return Promise.reject(Object.assign(new Error(body.message || '请求失败'), { code: body.code }))
  },
  (err) => {
    if (err.response?.status === 401) redirectToLogin()
    const message = err.response?.data?.message || err.message || '网络异常'
    return Promise.reject(Object.assign(new Error(message), { code: err.response?.data?.code }))
  }
)

export default http
