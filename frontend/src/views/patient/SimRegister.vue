<template>
  <div class="patient-root p-reg">
    <!-- 顶栏：病历抬头式 -->
    <header class="p-topbar">
      <div class="p-topbar__title">模拟挂号</div>
      <div class="p-topbar__sub">就 诊 科 室 · 选 定</div>
    </header>

    <main class="p-reg__body">
      <!-- 成功态（register_success；埋点由后端挂号接口补记） -->
      <section v-if="done" class="p-card p-reg__done">
        <div class="p-eyebrow">挂 号 成 功</div>
        <div class="p-card__dept">{{ booked.deptName }}</div>
        <p class="p-card__note">
          已为您登记就诊科室。请携此信息前往门诊导医台取号。
        </p>
        <div class="p-card__foot">
          <span class="p-eyebrow">就 诊 信 息</span>
          <p class="p-cite"><span class="p-cite__no">科室</span>　{{ booked.deptName }}</p>
          <p class="p-cite"><span class="p-cite__no">位置</span>　{{ booked.location }}</p>
          <p class="p-cite"><span class="p-cite__no">状态</span>　register_success</p>
        </div>
        <button class="p-btn p-btn--ghost p-reg__gap" @click="backToChat">
          返回继续咨询
        </button>
      </section>

      <!-- 选科室态 -->
      <template v-else>
        <!-- 缺少导诊记录（直接访问本页）／加载或挂号失败：说清原因与下一步 -->
        <div v-if="!recordId" class="p-error p-reg__tip">
          缺少导诊记录，请先在对话页完成一次分诊再来挂号。
          <button class="p-error__retry" @click="backToChat">返回对话页</button>
        </div>
        <!-- 失败提示必须给出下一步：列表加载失败可重试，提交失败（已挂过号/科室停用/网络）回对话页 -->
        <div v-else-if="error" class="p-error p-reg__tip">
          {{ error.message }}
          <button v-if="error.action === 'reload'" class="p-error__retry" @click="loadDepts">
            重新加载
          </button>
          <button v-else class="p-error__retry" @click="backToChat">返回对话页</button>
        </div>

        <div class="p-eyebrow p-reg__eb">
          {{ orderedDepts.length ? sectionEyebrow : '科 室 列 表 · 全 部 启 用' }}
        </div>

        <p v-if="loading" class="p-empty">正在加载科室…</p>
        <p v-else-if="!depts.length" class="p-empty">暂无可挂号科室，请稍后重试</p>
        <div v-else class="p-deptlist">
          <button
            v-for="d in orderedDepts"
            :key="d.id"
            class="p-dept"
            :class="{ 'p-dept--rec': d.id === recDeptId, 'p-dept--on': d.id === selected?.id }"
            @click="pick(d)"
          >
            <span>
              {{ d.name }}
              <span class="p-cite p-dept__loc">{{ d.location }}</span>
            </span>
            <span v-if="d.id === recDeptId" class="p-dept__hint">推 荐</span>
            <span v-else-if="recRank(d.id)" class="p-dept__hint p-dept__hint--alt">备选 {{ recRank(d.id) }}</span>
          </button>
        </div>

        <button
          class="p-btn p-reg__gap"
          :disabled="!selected || submitting || !recordId"
          @click="confirm"
        >
          确认挂号
        </button>
      </template>
    </main>

    <!-- 步进流程条 -->
    <nav class="p-steps">
      <span class="p-steps__item p-steps__item--done"><span class="p-steps__no">1</span>导诊结论</span>
      <span class="p-steps__link" />
      <span class="p-steps__item" :class="done ? 'p-steps__item--done' : 'p-steps__item--now'">
        <span class="p-steps__no">2</span>模拟挂号
      </span>
      <span class="p-steps__link" />
      <span class="p-steps__item" :class="{ 'p-steps__item--done': done, 'p-steps__item--now': !done }">
        <span class="p-steps__no">3</span>确认完成
      </span>
    </nav>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listDepts, confirmRegister } from '../../api/chat'
import { track } from '../../utils/track'
import '../../styles/patient.css'

const route = useRoute()
const router = useRouter()

// recordId 是挂号与埋点的唯一凭证：由对话页结论卡跳转时带上
const recordId = route.query.recordId || ''
// 推荐科室 id：结论卡带来的 top1 科室，列表内 teal 实心高亮 + 「推 荐」标记
const recDeptId = route.query.deptId || ''

const depts = ref([])
const selected = ref(null)
const loading = ref(false)
const submitting = ref(false)
const booked = ref(null)
const done = ref(false)
const error = ref(null) // { message, action: 'reload' | '' }

// Top3 推荐科室的 id（按推荐次序，逗号分隔，对话页结论卡带过来）。
// 只有 top1 的 id 是权威 rec_dept；整串用于**排序**——推荐科室要排在列表最前，
// 其余科室仍按后端给的原序（创建时间序）跟在其后
const recOrder = computed(() => {
  const raw = route.query.rec
  return (typeof raw === 'string' ? raw.split(',') : [])
    .map((s) => s.trim())
    .filter(Boolean)
})

/** 某科室是 Top3 的第几位（0 = 不在 Top3 里，即非推荐科室；1 = top1 走「推 荐」不显示数字） */
function recRank(deptId) {
  const i = recOrder.value.indexOf(deptId)
  return i > 0 ? i + 1 : 0
}

/** 推荐科室按推荐次序提到最前，其余保持后端原序 */
const orderedDepts = computed(() => {
  const rank = (d) => {
    const i = recOrder.value.indexOf(d.id)
    return i === -1 ? recOrder.value.length : i
  }
  // 稳定排序：同 rank（都是非推荐科室）保持后端返回的先后
  return depts.value.map((d, i) => ({ d, i })).sort((a, b) => rank(a.d) - rank(b.d) || a.i - b.i).map((x) => x.d)
})

// 列表顶部小标题：有推荐科室时说明「推荐在前、其余在后」，否则沿用原来的「全部启用」
const sectionEyebrow = computed(() =>
  recOrder.value.length ? '系 统 推 荐 在 前 · 其 余 科 室 在 下' : '科 室 列 表 · 全 部 启 用'
)

// sim_register 触点：同一次访问里每个科室只上报一次，反复切换不刷量
const trackedDeptIds = new Set()

async function loadDepts() {
  loading.value = true
  error.value = null
  try {
    depts.value = await listDepts()
    // 推荐科室默认选中；科室已停用（不在启用列表）则不预选，由患者自行选择
    selected.value = depts.value.find((d) => d.id === recDeptId) || null
  } catch (e) {
    depts.value = []
    selected.value = null
    error.value = { message: e.message || '科室列表加载失败，请重新加载', action: 'reload' }
  } finally {
    loading.value = false
  }
}

function pick(dept) {
  selected.value = dept
  if (recordId && !trackedDeptIds.has(dept.id)) {
    trackedDeptIds.add(dept.id)
    track('sim_register', { recordId, deptId: dept.id })
  }
}

async function confirm() {
  if (!selected.value || submitting.value || !recordId) return
  submitting.value = true
  error.value = null
  try {
    // 成功 = 写 actual_dept / 命中标记 / 会话置 closed；register_success 埋点由后端补记
    booked.value = await confirmRegister({ recordId, deptId: selected.value.id })
    done.value = true
  } catch (e) {
    error.value = { message: e.message || '挂号失败，请稍后重试', action: '' }
  } finally {
    submitting.value = false
  }
}

function backToChat() {
  router.push('/')
}

onMounted(loadDepts)
</script>

<style scoped>
/* 挂号页 —— 方向 04「陪诊伙伴」
   除了「这一页怎么摆」，一律吃共享件 patient.css：科室列表 `.p-dept*`、卡片 `.p-card*`、
   按钮 `.p-btn*`、步进条 `.p-steps*`、提示 `.p-error*` 都在那里——本页不再各写一份
   （两份定义里页面那份会静默覆盖全局，改一处漏一处）。 */

.p-reg {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg);
}
.p-reg__body {
  flex: 1;
  overflow-y: auto;
  /* 桌面端由 patient.css §5 收成 1000px 居中并把左右内边距放宽到 32px */
  padding: 20px 16px;
}
/* 成功态：卡里科室名紧跟 eyebrow，别留一道缝 */
.p-reg__done .p-card__dept { margin-top: 4px; }
/* 页面内提示：与科室列表保持间距，不挤在一起 */
.p-reg__tip { margin-bottom: 12px; }
/* 区块小标题（原来写在行内，魔法数字收进类） */
.p-reg__eb { margin-bottom: 12px; }
/* 卡与主按钮之间的间距（同理，原来写在行内） */
.p-reg__gap { margin-top: 16px; }
</style>
