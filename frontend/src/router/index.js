import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/common/Login.vue') },
  // —— 患者端（Vant，手机）——
  { path: '/', name: 'chat', component: () => import('../views/patient/Chat.vue') },
  { path: '/register', name: 'sim-register', component: () => import('../views/patient/SimRegister.vue') },
  // 就诊记录已并入首页左侧栏（点会话即只读回放）；老链接重定向回首页，不留 404
  { path: '/records', redirect: '/' },
  // —— 管理端（Element Plus，PC，校验 ROLE_ADMIN）——
  {
    path: '/admin',
    component: () => import('../views/admin/AdminLayout.vue'),
    meta: { requiresAdmin: true },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/admin/Dashboard.vue'),
        meta: { title: '数据看板', sub: '近 7 天 · 口径：已反馈导诊记录' }
      },
      {
        path: 'kb',
        name: 'kb',
        component: () => import('../views/admin/KbManage.vue'),
        meta: { title: '知识库管理', sub: '科室 / 文档 / 映射 / 术语白名单' }
      },
      {
        path: 'review',
        name: 'review',
        component: () => import('../views/admin/Review.vue'),
        meta: { title: '审核队列', sub: '错误模式聚合 · 人工确认后回流' }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('../views/admin/UserManage.vue'),
        meta: { title: '用户管理', sub: '账号状态 · 敏感词库' }
      },
      {
        path: 'llm',
        name: 'llm-config',
        component: () => import('../views/admin/LlmConfig.vue'),
        meta: { title: 'LLM 配置', sub: 'DeepSeek 对话 / 阿里 embedding / rerank' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：未登录跳登录；admin 路由校验 ROLE_ADMIN
router.beforeEach((to) => {
  const user = useUserStore()
  if (to.path !== '/login' && !user.isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && !user.isAdmin) {
    return { path: '/' }
  }
})

export default router
