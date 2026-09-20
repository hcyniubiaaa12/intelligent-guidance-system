<template>
  <div v-if="loading" class="a-empty">加载中…</div>
  <div v-else-if="loadError" class="a-empty">{{ loadError }}</div>
  <template v-else>
    <!-- KPI 指标卡：环形 + 标签/数值/附注 -->
    <section class="a-kpis">
      <div v-for="k in kpis" :key="k.label" class="a-kpi">
        <div class="a-kpi__ring" :class="{ 'a-kpi__ring--warn': k.warn }">
          <svg width="56" height="56" viewBox="0 0 56 56">
            <circle cx="28" cy="28" r="24" fill="none" stroke="#EDF1F9" stroke-width="5" />
            <circle
              cx="28"
              cy="28"
              r="24"
              fill="none"
              :stroke="k.warn ? '#E06B4D' : '#4468B8'"
              stroke-width="5"
              stroke-linecap="round"
              :stroke-dasharray="`${((k.pct || 0) * 150.8).toFixed(1)} 150.8`"
            />
          </svg>
          <span class="a-kpi__ring-num">{{ k.ring }}</span>
        </div>
        <div>
          <div class="a-kpi__label">{{ k.label }}</div>
          <div class="a-kpi__value">{{ k.value }}</div>
          <div class="a-kpi__yoy" :class="toneClass(k.tone)">{{ k.note }}</div>
        </div>
      </div>
    </section>

    <!-- 双列图表面板 -->
    <section class="a-grid">
      <!-- 柱状趋势：蓝=命中 / coral=未命中 堆叠 -->
      <div class="a-panel">
        <div class="a-panel__head">
          <span class="a-panel__title">每日导诊量 · 命中构成</span>
          <span class="a-legend">
            <span><i class="a-legend__dot" style="background: #4468B8" />命中</span>
            <span><i class="a-legend__dot" style="background: #E06B4D" />未命中</span>
          </span>
        </div>
        <div v-if="trendTotal === 0" class="a-empty">近 {{ days }} 天还没有已确认挂号的记录</div>
        <div v-else class="a-chart">
          <div v-for="d in trendBars" :key="d.date" class="a-chart__col">
            <div class="a-chart__stack">
              <div class="a-chart__miss" :style="{ height: d.missPct + '%' }" />
              <div class="a-chart__hit" :style="{ height: d.hitPct + '%' }" />
            </div>
            <div class="a-chart__label">{{ d.label }}</div>
          </div>
        </div>
      </div>

      <!-- 分布条：错误根因分布 -->
      <div class="a-panel">
        <div class="a-panel__head">
          <span class="a-panel__title">错误根因分布</span>
          <span class="a-panel__hint">按最新归因</span>
        </div>
        <div v-if="!rootCauses.length" class="a-empty">暂无归因数据（审核页标注根因后自动汇总）</div>
        <div v-else class="a-dist">
          <div v-for="r in rootCauses" :key="r.name" class="a-dist__row">
            <span class="a-dist__name">{{ r.name }}</span>
            <span class="a-dist__track">
              <span class="a-dist__fill" :style="{ width: r.pct + '%' }" />
            </span>
            <span class="a-dist__pct">{{ r.pct }}%</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 全宽表格：最近导诊记录 -->
    <section class="a-panel">
      <div class="a-panel__head">
        <span class="a-panel__title">最近导诊记录</span>
        <span class="a-panel__hint">共 {{ page.total }} 条</span>
      </div>
      <div v-if="!records.length" class="a-empty">还没有导诊记录</div>
      <template v-else>
        <table class="a-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>主诉摘要</th>
              <th>推荐科室</th>
              <th>置信度</th>
              <th>实际科室</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in records" :key="r.id">
              <td>{{ fmtTime(r.time) }}</td>
              <td>{{ r.complaint }}</td>
              <td>{{ r.recDept }}</td>
              <td>{{ r.confidence === null ? '—' : r.confidence + '%' }}</td>
              <td>{{ r.actualDept || '—' }}</td>
              <td>
                <span class="a-tag" :class="tagClass(r.tone)">{{ r.result }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <el-pagination
          layout="prev, pager, next"
          :current-page="page.current"
          :page-size="page.size"
          :total="page.total"
          @current-change="loadRecords"
        />
      </template>
    </section>
  </template>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getDashboardOverview, pageGuideRecords } from '../../api/admin'

// 看板口径固定近 7 天（页面 meta 已声明），后端上限 90
const days = 7

const kpis = ref([])
const trend = ref([])
const rootCauses = ref([])
const records = ref([])
const page = reactive({ current: 1, size: 10, total: 0 })
const loading = ref(true)
const loadError = ref('')

function errText(e) {
  return e?.message || '操作失败，请稍后重试'
}

function toneClass(tone) {
  if (tone === 'up') return 'a-kpi__yoy--up'
  if (tone === 'down') return 'a-kpi__yoy--down'
  return ''
}

function tagClass(tone) {
  if (tone === 'ok') return 'a-tag--ok'
  if (tone === 'warn') return 'a-tag--warn'
  return 'a-tag--plain'
}

// 后端返 ISO 串（与 UserManage 的 fmtDate 同源约定），截成 MM-DD HH:mm
function fmtTime(value) {
  if (!value) return '—'
  const text = String(value)
  return text.length >= 16 ? text.slice(5, 16).replace('T', ' ') : text
}

const trendTotal = computed(() =>
  trend.value.reduce((sum, d) => sum + (d.hit || 0) + (d.miss || 0), 0)
)

// 柱高按当日总量归一化：真实数据可能远超 100 条，直接当百分比会溢出容器。
// 留 20% 余量给下方的星期标签（原设计稿的最大值也在 80% 附近）。
const trendBars = computed(() => {
  const max = Math.max(...trend.value.map((d) => (d.hit || 0) + (d.miss || 0)), 1)
  return trend.value.map((d) => ({
    ...d,
    hitPct: (((d.hit || 0) / max) * 80).toFixed(1),
    missPct: (((d.miss || 0) / max) * 80).toFixed(1)
  }))
})

async function loadOverview() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await getDashboardOverview(days)
    kpis.value = data.kpis || []
    trend.value = data.trend || []
    rootCauses.value = data.rootCauses || []
  } catch (e) {
    loadError.value = errText(e)
  } finally {
    loading.value = false
  }
}

async function loadRecords(pageNo = page.current) {
  try {
    const data = await pageGuideRecords({ current: pageNo, size: page.size })
    records.value = data.records
    page.current = data.current
    page.total = data.total
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

onMounted(() => {
  loadOverview()
  loadRecords(1)
})
</script>
