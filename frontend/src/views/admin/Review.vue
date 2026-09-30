<template>
  <section class="a-panel">
    <div class="a-panel__head">
      <span class="a-panel__title">审核队列 · 错误模式聚合桶</span>
      <div class="a-panel__ops">
        <span class="a-panel__hint">共 {{ page.total }} 个待审</span>
        <button class="a-btn a-btn--ghost" :disabled="aggregating" @click="runAggregate">
          {{ aggregating ? '聚合中…' : '立即聚合' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="a-empty">加载中…</div>
    <div v-else-if="!buckets.length" class="a-empty">
      没有待审的聚合桶。全错记录要等每小时的归桶任务累计到阈值才会出现在这里。
    </div>
    <template v-else>
      <el-table :data="buckets" row-key="id" :expand-row-keys="expandedId ? [expandedId] : []">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-if="row.id !== expandedId || detailLoading" class="a-empty">加载中…</div>
            <div v-else-if="detail" class="rev">
                  <!-- 代表样本：最近 5 条，证据是当时的快照 -->
                  <div class="rev__label">代表样本 · 最近 {{ detail.samples.length }} 条</div>
                  <div v-if="!detail.samples.length" class="a-panel__hint">
                    这个桶还没有挂上记录（归桶发生在本页接通之前）。可以驳回或忽略。
                  </div>
                  <div v-for="sample in detail.samples" :key="sample.recordId" class="rev__sample">
                    <div class="rev__sample-head">
                      <span class="rev__symptom">{{ sample.symptom || '（主诉为空）' }}</span>
                      <button class="a-btn a-btn--ghost" @click="saveSampleCauses(sample)">
                        保存本条根因
                      </button>
                    </div>
                    <div class="a-chips">
                      <button
                        v-for="option in causeOptions"
                        :key="option.key"
                        class="a-chip"
                        :class="{ 'a-chip--on': sample.causes.includes(option.key) }"
                        @click="toggleCause(sample.causes, option.key)"
                      >
                        {{ option.label }}
                      </button>
                    </div>
                    <pre v-if="sample.evidence" class="rev__evidence">{{ prettyEvidence(sample.evidence) }}</pre>
                    <div v-else class="a-panel__hint">这条记录没有证据快照</div>
                  </div>

                  <!-- 桶级套用：与 approve / 驳回互不代劳 -->
                  <div class="rev__label">桶级根因 · 套用到桶内全部记录</div>
                  <div class="a-chips">
                    <button
                      v-for="option in causeOptions"
                      :key="option.key"
                      class="a-chip"
                      :class="{ 'a-chip--on': bucketCauses.includes(option.key) }"
                      @click="toggleCause(bucketCauses, option.key)"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                  <button class="a-btn a-btn--ghost" :disabled="acting" @click="applyBucket">
                    套用根因
                  </button>

                  <!-- 审核：主科室 + 交叉科室 + 回流预览 -->
                  <div class="rev__label">回流</div>
                  <div class="rev__form">
                    <label class="rev__field">
                      <span>主科室</span>
                      <select v-model="form.mainDeptId" @change="onMainDeptChange">
                        <option value="">选择主科室</option>
                        <option v-for="dept in depts" :key="dept.id" :value="dept.id">
                          {{ dept.enabled ? dept.name : dept.name + '（已停用）' }}
                        </option>
                      </select>
                    </label>
                    <div class="rev__field">
                      <span>交叉科室 · 预填可增删</span>
                      <div class="a-chips">
                        <button
                          v-for="dept in crossOptions"
                          :key="dept.id"
                          class="a-chip"
                          :class="{ 'a-chip--on': form.crossDeptIds.includes(dept.id) }"
                          @click="toggleCause(form.crossDeptIds, dept.id)"
                        >
                          {{ dept.enabled ? dept.name : dept.name + '（已停用）' }}
                        </button>
                      </div>
                    </div>
                    <div class="rev__ops">
                      <button class="a-btn a-btn--ghost" :disabled="acting || !form.mainDeptId" @click="preview">
                        预览生成
                      </button>
                      <button class="a-btn" :disabled="acting || !form.mainDeptId" @click="approve">
                        确认入库
                      </button>
                      <button class="a-btn a-btn--ghost" :disabled="acting" @click="reject">驳回</button>
                      <button class="a-btn a-btn--danger" :disabled="acting" @click="dismiss">忽略</button>
                    </div>
                    <textarea
                      v-if="form.previewed"
                      v-model="form.syntheticText"
                      class="a-input"
                      rows="5"
                      placeholder="鉴别诊断文本（可编辑后确认入库）"
                    />
                  </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="recDeptName" label="推荐科室" min-width="120" />
        <el-table-column prop="actualDeptName" label="实际科室" min-width="120" />
        <el-table-column prop="anchorText" label="锚点主诉" min-width="200" show-overflow-tooltip />
        <el-table-column prop="count" label="样本数" width="80" />
        <el-table-column label="升级时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link size="small" @click="toggle(row)">
              {{ expandedId === row.id ? '收起' : '审核' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        :layout="PAGE_LAYOUT_NO_TOTAL"
        :current-page="page.current"
        :page-size="page.size"
        :page-sizes="PAGE_SIZES"
        :total="page.total"
        background
        @current-change="loadBuckets"
        @size-change="onBucketSize"
      />
    </template>

    <!-- 终态桶：修正重审是人工动作，不受自动升级的幂等限制 -->
    <div class="a-panel__head" style="margin-top: 18px">
      <span class="a-panel__title">已终态</span>
      <span class="a-panel__hint">共 {{ terminalPage.total }} 个 · 修正重审回到待审</span>
    </div>
    <div v-if="!terminalBuckets.length" class="a-empty">还没有审核过的桶</div>
    <template v-else>
      <el-table :data="terminalBuckets" row-key="id">
        <el-table-column prop="recDeptName" label="推荐科室" min-width="120" />
        <el-table-column prop="actualDeptName" label="实际科室" min-width="120" />
        <el-table-column prop="anchorText" label="锚点主诉" min-width="200" show-overflow-tooltip />
        <el-table-column prop="count" label="样本数" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="a-tag" :class="statusTag(row.status)">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link size="small" @click="reReview(row)">修正重审</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        :layout="PAGE_LAYOUT_NO_TOTAL"
        :current-page="terminalPage.current"
        :page-size="terminalPage.size"
        :page-sizes="PAGE_SIZES"
        :total="terminalPage.total"
        background
        @current-change="loadTerminal"
        @size-change="onTerminalSize"
      />
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PAGE_LAYOUT_NO_TOTAL, PAGE_SIZES, applySizeChange } from '../../utils/pager'
import {
  aggregateBuckets, pagePendingBuckets, pageTerminalBuckets, getReviewBucket, listRootCauses, listReviewDepts,
  applyBucketCauses, updateRecordCauses, previewSyntheticChunk,
  approveBucket, rejectBucket, dismissBucket, reReviewBucket
} from '../../api/admin'

const buckets = ref([])
const page = reactive({ current: 1, size: 10, total: 0 })
const terminalBuckets = ref([])
const terminalPage = reactive({ current: 1, size: 10, total: 0 })

const STATUS_LABELS = { approved: '已入库', rejected: '已驳回', dismissed: '已忽略' }

function statusLabel(status) {
  return STATUS_LABELS[status] || status
}

function statusTag(status) {
  if (status === 'approved') return 'a-tag--ok'
  if (status === 'rejected') return 'a-tag--warn'
  return 'a-tag--plain'
}
const loading = ref(true)
const aggregating = ref(false)
const expandedId = ref('')
const detail = ref(null)
const detailLoading = ref(false)
const acting = ref(false)

const causeOptions = ref([])
const depts = ref([])
const bucketCauses = ref([])
const form = reactive({
  mainDeptId: '',
  crossDeptIds: [],
  syntheticText: '',
  previewed: false
})

const crossOptions = computed(() => depts.value.filter((dept) => dept.id !== form.mainDeptId))

function errText(e) {
  return e?.message || '操作失败，请稍后重试'
}

function fmtTime(value) {
  if (!value) return '—'
  const text = String(value)
  return text.length >= 16 ? text.slice(5, 16).replace('T', ' ') : text
}

/** 证据快照是 JSON 原文，排版后给人看；解析不了就原样展示，不藏数据 */
function prettyEvidence(raw) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function toggleCause(list, key) {
  const index = list.indexOf(key)
  if (index >= 0) list.splice(index, 1)
  else list.push(key)
}

async function loadTerminal(pageNo = terminalPage.current) {
  try {
    const data = await pageTerminalBuckets({ page: pageNo, size: terminalPage.size })
    terminalBuckets.value = data.records || []
    terminalPage.total = data.total || 0
    terminalPage.current = pageNo
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

/** 切换每页条数：回到第 1 页再拉一次（两个列表各一份） */
function onBucketSize(size) {
  applySizeChange(page, size, () => loadBuckets(1))
}

function onTerminalSize(size) {
  applySizeChange(terminalPage, size, () => loadTerminal(1))
}

async function reReview(bucket) {
  const extra = bucket.status === 'approved'
    ? '上次写入的映射台账和合成切片会撤掉，重新审核后再入库。'
    : '桶回到待审，重新走一遍审核。'
  try {
    await ElMessageBox.confirm(extra, '修正重审', {
      confirmButtonText: '修正重审', cancelButtonText: '取消', type: 'warning'
    })
  } catch {
    return
  }
  try {
    await reReviewBucket(bucket.id)
    ElMessage.success('已回到待审')
    await Promise.all([loadBuckets(page.current), loadTerminal(terminalPage.current)])
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

/** 归桶是整点定时任务，演示与排查等不了那一小时，这里手动跑一次 */
async function runAggregate() {
  aggregating.value = true
  try {
    const processed = await aggregateBuckets()
    ElMessage.success(processed ? `已归桶 ${processed} 条记录` : '没有新的全错记录需要聚合')
    await Promise.all([loadBuckets(1), loadTerminal(terminalPage.current)])
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    aggregating.value = false
  }
}

async function loadBuckets(pageNo = page.current) {
  loading.value = true
  try {
    const data = await pagePendingBuckets({ page: pageNo, size: page.size })
    buckets.value = data.records || []
    page.total = data.total || 0
    page.current = pageNo
    if (expandedId.value && !buckets.value.some((bucket) => bucket.id === expandedId.value)) {
      expandedId.value = ''
      detail.value = null
    }
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

async function toggle(bucket) {
  if (expandedId.value === bucket.id) {
    expandedId.value = ''
    detail.value = null
    return
  }
  expandedId.value = bucket.id
  detail.value = null
  detailLoading.value = true
  resetForm()
  try {
    detail.value = await getReviewBucket(bucket.id)
    form.mainDeptId = detail.value.suggestedMainDeptId || ''
    form.crossDeptIds = [...(detail.value.suggestedCrossDeptIds || [])]
  } catch (e) {
    expandedId.value = ''
    ElMessage.error(errText(e))
  } finally {
    detailLoading.value = false
  }
}

function resetForm() {
  form.mainDeptId = ''
  form.crossDeptIds = []
  form.syntheticText = ''
  form.previewed = false
  bucketCauses.value = []
}

/** 主科室一变，预填按集合差重算：方向里去掉新的主科室 */
function onMainDeptChange() {
  const bucket = detail.value?.bucket
  if (!bucket) return
  const direction = [bucket.recDeptId, bucket.actualDeptId].filter(
    (id, index, all) => id && id !== form.mainDeptId && all.indexOf(id) === index
  )
  form.crossDeptIds = direction
  form.previewed = false
}

async function applyBucket() {
  acting.value = true
  try {
    const affected = await applyBucketCauses(expandedId.value, bucketCauses.value)
    ElMessage.success(`已套用到 ${affected} 条记录`)
    detail.value = await getReviewBucket(expandedId.value)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    acting.value = false
  }
}

async function saveSampleCauses(sample) {
  try {
    await updateRecordCauses(sample.recordId, sample.causes)
    ElMessage.success('本条根因已保存')
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function preview() {
  acting.value = true
  try {
    const data = await previewSyntheticChunk(expandedId.value, {
      mainDeptId: form.mainDeptId,
      crossDeptIds: form.crossDeptIds
    })
    form.syntheticText = data.text || ''
    form.previewed = true
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    acting.value = false
  }
}

async function approve() {
  if (!form.previewed || !form.syntheticText.trim()) {
    ElMessage.warning('先预览生成，确认或修改文本后再入库')
    return
  }
  const main = depts.value.find((dept) => dept.id === form.mainDeptId)
  try {
    await ElMessageBox.confirm(
      `确认后写入映射台账，并把这段鉴别说明入库到「${main ? main.name : ''}」的回流容器。` +
        '台账立即生效；切片入库失败会在知识库页看到，重试要走修正重审。',
      '确认入库',
      { confirmButtonText: '确认入库', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await approveBucket(expandedId.value, {
      mainDeptId: form.mainDeptId,
      crossDeptIds: form.crossDeptIds,
      syntheticText: form.syntheticText
    })
    ElMessage.success('已入库。合成切片在后台写入，失败可在知识库页看到')
    expandedId.value = ''
    await Promise.all([loadBuckets(page.current), loadTerminal(terminalPage.current)])
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    acting.value = false
  }
}

async function reject() {
  try {
    await ElMessageBox.confirm(
      '驳回表示看过并判定不是系统的错（如患者挂错）。桶进入终态，新样本只累加、不再自动升级。',
      '驳回',
      { confirmButtonText: '驳回', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await act(() => rejectBucket(expandedId.value), '已驳回')
}

async function dismiss() {
  try {
    await ElMessageBox.confirm(
      '忽略表示没审就关闭（测试数据、重复桶）。不构成审核意见。',
      '忽略',
      { confirmButtonText: '忽略', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  await act(() => dismissBucket(expandedId.value), '已忽略')
}

async function act(call, success) {
  acting.value = true
  try {
    await call()
    ElMessage.success(success)
    expandedId.value = ''
    await Promise.all([loadBuckets(page.current), loadTerminal(terminalPage.current)])
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    acting.value = false
  }
}

onMounted(async () => {
  try {
    const [causes, deptList] = await Promise.all([listRootCauses(), listReviewDepts()])
    causeOptions.value = causes || []
    depts.value = deptList || []
  } catch (e) {
    ElMessage.error(errText(e))
  }
  await Promise.all([loadBuckets(1), loadTerminal(1)])
})
</script>

<style scoped>
.rev {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 6px 4px;
}
.rev__label {
  margin-top: 8px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
}
.rev__sample {
  padding: 10px 12px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 8px;
}
.rev__sample-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.rev__symptom {
  font-size: 13px;
  color: var(--ink);
}
.rev__evidence {
  margin: 4px 0 0;
  padding: 8px 10px;
  background: var(--wash);
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}
.rev__form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.rev__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-2);
}
.rev__field select {
  max-width: 320px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--line);
  background: var(--card);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink);
}
.rev__field select:focus {
  outline: none;
  border-color: var(--blue);
}
.rev__ops {
  display: flex;
  gap: 10px;
}
</style>
