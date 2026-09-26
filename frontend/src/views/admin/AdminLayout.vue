<template>
  <div class="admin-root">
    <div class="a-shell">
      <!-- 侧栏：蓝色实底，选中态深蓝，待办 coral 药丸徽标；可折叠，底部用户区含退出 -->
      <aside class="a-side" :class="{ 'a-side--collapsed': collapsed }">
        <div class="a-side__brand">
          <template v-if="!collapsed">
            智能导诊 · 控制台
            <small>OPERATIONS CONSOLE</small>
          </template>
          <button
            class="a-side__toggle"
            :title="collapsed ? '展开侧栏' : '收起侧栏'"
            @click="collapsed = !collapsed"
          >
            <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </button>
        </div>
        <nav class="a-nav">
          <router-link
            v-for="item in navs"
            :key="item.path"
            :to="item.path"
            class="a-nav__item"
            :class="{ 'a-nav__item--on': route.path.startsWith(item.path) }"
            :title="collapsed ? item.label : undefined"
          >
            <span v-if="collapsed" class="a-nav__abbr">{{ item.abbr }}</span>
            <template v-else>
              {{ item.label }}
              <span v-if="item.badge" class="a-nav__badge">{{ item.badge }}</span>
            </template>
          </router-link>
        </nav>
        <div class="a-side__user">
          <el-dropdown trigger="click" @command="onUserCommand">
            <button class="a-side__user-btn" :title="collapsed ? user.nickname || '管理员' : undefined">
              <span class="a-side__avatar">{{ (user.nickname || '管')[0] }}</span>
              <template v-if="!collapsed">
                <span class="a-side__user-name">{{ user.nickname || '管理员' }}</span>
                <el-icon class="a-side__caret"><ArrowUp /></el-icon>
              </template>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </aside>

      <!-- 主区 -->
      <div class="a-main">
        <header class="a-topbar">
          <div>
            <div class="a-topbar__title">{{ route.meta.title || '控制台' }}</div>
            <div class="a-topbar__sub">{{ route.meta.sub || '' }}</div>
          </div>
          <!-- 区间切换只在"区间"有意义的页面出现（看板）；其余页面显示的是死按钮，
               点它没有任何反应，却像是能改变数据口径 —— 宁可没有 -->
          <div v-if="isDashboard" class="a-topbar__ops">
            <button
              v-for="d in RANGES"
              :key="d"
              class="a-btn"
              :class="activeDays === d ? '' : 'a-btn--ghost'"
              @click="switchRange(d)"
            >
              近 {{ d }} 天
            </button>
          </div>
        </header>

        <div class="a-content">
          <router-view />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Fold, Expand, ArrowUp } from '@element-plus/icons-vue'
import { useUserStore } from '../../stores/user'
import { logout as apiLogout } from '../../api/auth'
import '../../styles/admin.css'

const route = useRoute()
const router = useRouter()
const user = useUserStore()

// 侧栏折叠（默认展开）
const collapsed = ref(false)

// 退出登录：先调后端删 Redis 登录态（登出即时失效），再清本地态跳登录页；
// 接口失败也照常清本地态，避免卡死在控制台
async function onLogout() {
  try {
    await ElMessageBox.confirm('退出后将返回登录页，确认退出登录？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await apiLogout()
  } finally {
    user.logout()
    router.push('/login')
    ElMessage.success('已退出登录')
  }
}

// 侧栏用户区下拉命令
function onUserCommand(cmd) {
  if (cmd === 'logout') onLogout()
}

// 区间切换：状态放在**路由 query**（`?days=30`）而不是组件间传参——
// 页面刷新/分享链接后区间不丢，也不需要在顶栏与看板之间搭一层状态。
const RANGES = [7, 30]
const isDashboard = computed(() => route.name === 'dashboard')
const activeDays = computed(() => Number(route.query.days) || 7)

function switchRange(days) {
  if (days !== activeDays.value) {
    router.replace({ path: route.path, query: { ...route.query, days } })
  }
}

// abbr 为折叠态两字缩写。
// badge 是**待办数**，必须来自实时数据——审核队列的待办数要等链路 C 后半（聚合归桶）才有来源，
// 在那之前一律 0（不显示）。写死一个数字最像"有内容"，而它会被当成待办数去处理。
const navs = [
  { path: '/admin/dashboard', label: '数据看板', abbr: '看板', badge: 0 },
  { path: '/admin/kb', label: '知识库管理', abbr: '知识', badge: 0 },
  { path: '/admin/review', label: '审核队列', abbr: '审核', badge: 0 },
  { path: '/admin/users', label: '用户管理', abbr: '用户', badge: 0 },
  { path: '/admin/llm', label: 'LLM 配置', abbr: '模型', badge: 0 }
]
</script>
