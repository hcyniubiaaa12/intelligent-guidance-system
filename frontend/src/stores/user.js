import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 登录态 store
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const role = ref(localStorage.getItem('role') || '')
  const nickname = ref(localStorage.getItem('nickname') || '')

  // 游客判定：**全仓唯一入口**。= 未持有 token。
  // 不在别处另写 `!token`，也不保留与它互为反面的第二个导出（如旧的 `isLogin`）——
  // 同一个事实有两个可分别使用的名字时，下一个人会各挑一个，两处判定迟早分叉。
  const isGuest = computed(() => !token.value)
  // role 取枚举编码值（user.role 存英文小写，见《数据库设计.md》§0）
  const isAdmin = computed(() => role.value === 'admin')

  function setLogin(data) {
    token.value = data.token
    role.value = data.role
    nickname.value = data.nickname
    localStorage.setItem('token', data.token)
    localStorage.setItem('role', data.role)
    localStorage.setItem('nickname', data.nickname)
  }

  function logout() {
    token.value = ''
    role.value = ''
    nickname.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('role')
    localStorage.removeItem('nickname')
  }

  return { token, role, nickname, isGuest, isAdmin, setLogin, logout }
})
