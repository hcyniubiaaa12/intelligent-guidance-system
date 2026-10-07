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

    <!-- 「现在有没有活」得先看得见：这句话是**进度**口径（还有多少条没归桶），
         聚合后 toast 那句是**结果**口径（归桶了几条），两者对不上是正常的。
         整行常驻、四态，「加载中 / 真的没有 / 取不到」必须长得不一样——
         把它们压成同一个「什么都不显示」，等于让人无从判断该不该点按钮 -->
    <p class="rev__hint" :class="{ 'rev__hint--todo': reviewStore.pendingRecordCount > 0 }">
      <template v-if="reviewStore.recordCountFailed">待归桶数量暂时取不到，可稍后重进本页</template>
      <template v-else-if="reviewStore.pendingRecordCount === null">正在读取待归桶数量…</template>
      <template v-else-if="reviewStore.pendingRecordCount > 0">
        当前有 {{ reviewStore.pendingRecordCount }} 条错误样本待归桶，可点「立即聚合」
      </template>
      <template v-else>当前没有待聚合的错误样本</template>
    </p>

    <div v-if="loading" class="a-empty">加载中…</div>
    <div v-else-if="!buckets.length" class="a-empty">
      没有待审的聚合桶。全错记录要等每小时的归桶任务累计到阈值才会出现在这里。
    </div>
    <template v-else>
      <el-table
        :data="buckets"
        row-key="id"
        :expand-row-keys="expandedId ? [expandedId] : []"
        @expand-change="onExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-if="row.id !== expandedId || detailLoading" class="a-empty">加载中…</div>
            <div v-else-if="detail" class="rev">
                  <!-- 代表样本：最近 5 条，证据是当时的快照 -->
                  <div class="rev__label">代表样本 · 最近 {{ detail.samples.length }} 条</div>
                  <div v-if="!detail.samples.length" class="a-panel__hint">
                    这个桶还没有挂上记录（归桶发生在本页接通之前）。可以驳回或忽略。
                  </div>
                  <!-- 样本默认只占一行：主诉 + 结论摘要 + 有无检索-引用错位。
                       证据、根因、原始数据都收在展开里——一屏塞不下 5 份证据，是这一页最挤的根源 -->
                  <div v-for="sample in orderedSamples" :key="sample.recordId" class="rev__sample">
                    <button
                      class="rev__sample-head"
                      :class="{ 'rev__sample-head--open': isSampleOpen(sample.recordId) }"
                      @click="toggleSample(sample.recordId)"
                    >
                      <span class="rev__symptom">{{ sample.symptom || '（主诉为空）' }}</span>
                      <!-- 只标异常、不标「正常」：这些记录进桶本身就因为系统错了，
                           说它「正常」自相矛盾；而「没标」本身就是「无异常」 -->
                      <span v-if="summary(sample).drift" class="rev__flag">引用错位</span>
                      <span class="rev__sum">{{ sampleSummaryText(sample) }}</span>
                      <span class="rev__toggle">{{ isSampleOpen(sample.recordId) ? '收起 ▴' : '证据 ▸' }}</span>
                    </button>

                    <template v-if="isSampleOpen(sample.recordId)">
                      <div class="rev__sample-ops">
                        <span class="rev__label">本条根因</span>
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
                      <EvidenceReplay v-if="sample.evidence" :evidence="sample.evidence" />
                      <div v-else class="a-panel__hint">这条记录没有证据快照</div>
                    </template>
                  </div>

                  <!-- 桶级一键套用已移除（2026-07-11）：它会把桶内每条记录整份覆盖成同一组根因，
                       包括管理员已逐条改过的；同方向一个桶里的样本未必是同一成因。
                       归因一律逐条设，在上方每条样本的展开区里 -->
                  <!-- 审核：主科室 + 交叉科室 + 回流预览 -->
                  <div class="rev__label">回流</div>
                  <div class="rev__form">
                    <label class="rev__field">
                      <span>主科室</span>
                      <!-- 用 EP 的 el-select（管理端表单一律走 EP，原生 <select> 的长相由浏览器决定） -->
                      <el-select
                        v-model="form.mainDeptId"
                        class="rev__select"
                        placeholder="选择主科室"
                        @change="onMainDeptChange"
                      >
                        <el-option
                          v-for="dept in depts"
                          :key="dept.id"
                          :label="dept.enabled ? dept.name : dept.name + '（已停用）'"
                          :value="dept.id"
                        />
                      </el-select>
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
            <!-- detail 为 null 又不在加载中：正常流程不会走到（取数失败会把行收起来），
                 但不能留一个什么都不画的分支——空白比一句提示更让人困惑 -->
            <div v-else class="a-empty">这个桶的内容没取到，收起后可重新点开</div>
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
  updateRecordCauses, previewSyntheticChunk,
  approveBucket, rejectBucket, dismissBucket, reReviewBucket
} from '../../api/admin'
import { useReviewStore } from '../../stores/review'
import { evidenceHint, parseEvidence } from '../../utils/evidenceReplay'
import EvidenceReplay from './EvidenceReplay.vue'

// 待归桶样本数 + 待审桶数（侧栏徽标）都从 store 读：本页的动作会同时改这两个数
const reviewStore = useReviewStore()

const buckets = ref([])
const page = reactive({ current: 1, size: 10, total: 0 })
const terminalBuckets = ref([])
const terminalPage = reactive({ current: 1, size: 10, total: 0 })

const STATUS_LABELS = { approved: '已审核', rejected: '已驳回', dismissed: '已忽略' }

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
const form = reactive({
  mainDeptId: '',
  crossDeptIds: [],
  syntheticText: '',
  previewed: false
})
// per-bucket 表单缓存：收起/切桶时存起来，回来时还原。
// 预览生成的合成文本是**可手改**的（管理员入库前会编辑），一收起就清空等于逼人重写一遍
const formCache = reactive({})

const crossOptions = computed(() => depts.value.filter((dept) => dept.id !== form.mainDeptId))

// —— 样本折叠 + 异常置顶 ——
// 折叠那一行要显示的东西全在 evidence 里；这里复用与 EvidenceReplay 同一套纯函数，
// 判定口径只有一处。父子各解析一次（一条 5KB 的 JSON），不值得为此改组件接口。
const sampleSummaries = computed(() => {
  const map = {}
  for (const sample of detail.value.samples || []) {
    const parsed = parseEvidence(sample.evidence)
    const model = parsed.model
    map[sample.recordId] = {
      ok: parsed.ok,
      drift: Boolean(evidenceHint(parsed)),
      dept: model ? model.dept : '',
      confidence: model ? model.confidence : null,
      citedText: parsed.cited.length
        ? `引用 ${parsed.cited.map((no) => `注${no}`).join('、')}`
        : '未引用任何注'
    }
  }
  return map
})

function summary(sample) {
  return sampleSummaries.value[sample.recordId]
    || { ok: false, drift: false, dept: '', confidence: null, citedText: '' }
}

function sampleSummaryText(sample) {
  const info = summary(sample)
  if (!info.ok) return '证据结构不可解析'
  const parts = []
  if (info.dept) parts.push(info.dept)
  if (info.confidence !== null) parts.push(`置信度 ${info.confidence.toFixed(2)}`)
  if (info.citedText) parts.push(info.citedText)
  return parts.join(' · ')
}

// 错位样本排前面——归因先看异常。Array.prototype.sort 在现代引擎里稳定，同组内保持原顺序
const orderedSamples = computed(() => {
  const list = [...(detail.value.samples || [])]
  return list.sort((a, b) => Number(summary(b).drift) - Number(summary(a).drift))
})

const openSamples = ref([])

function isSampleOpen(recordId) {
  return openSamples.value.includes(recordId)
}

function toggleSample(recordId) {
  const pos = openSamples.value.indexOf(recordId)
  if (pos >= 0) openSamples.value.splice(pos, 1)
  else openSamples.value.push(recordId)
}

function errText(e) {
  return e?.message || '操作失败，请稍后重试'
}

function fmtTime(value) {
  if (!value) return '—'
  const text = String(value)
  return text.length >= 16 ? text.slice(5, 16).replace('T', ' ') : text
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
    await reloadAfterReview()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

/**
 * 归桶是整点定时任务，演示与排查等不了那一小时，这里手动跑一次。
 * 这一路的超时单独放宽（见 api/admin.js）：聚合同步跑批、会被全局 15s 掐断。
 */
async function runAggregate() {
  // 先记下提示行里的数字，聚合后拿它算差额
  const before = reviewStore.pendingRecordCount
  aggregating.value = true
  try {
    const processed = await aggregateBuckets()
    ElMessage.success(aggregateMessage(before, processed))
    await Promise.all([
      loadBuckets(1),
      loadTerminal(terminalPage.current),
      reviewStore.refreshAll()
    ])
  } catch (e) {
    ElMessage.error(errText(e))
    // 失败也可能是「已有聚合在跑」（6004）——那个任务同样会动这两个数，照样刷新
    await reviewStore.refreshAll()
  } finally {
    aggregating.value = false
  }
}

/**
 * 聚合结果话术。提示行说的是**进度**（待归桶 N 条），这句说的是**结果**（归桶 M 条）。
 * M 小于 N 不算少算了——抽不出主诉的记录会被标记「已处理」但不进桶（ClusteringService.clusterOne），
 * 把差额明说出来，否则用户会以为系统漏了一批。
 */
function aggregateMessage(before, processed) {
  if (!processed) return '没有新的全错记录需要聚合'
  const skipped = typeof before === 'number' ? before - processed : 0
  return skipped > 0
    ? `已归桶 ${processed} 条记录，另有 ${skipped} 条因无法提取主诉已跳过`
    : `已归桶 ${processed} 条记录`
}

/**
 * 审核动作后的统一收口：刷两个列表 + 刷侧栏徽标。
 * 通过 / 驳回 / 忽略 / 修正重审这四个动作都会改变待审桶数。若各写一份 reload，
 * 下次再加动作时极容易漏掉徽标那一行——那正是这次要修的「会说谎的徽标」的复发路径。
 */
async function reloadAfterReview() {
  await Promise.all([
    loadBuckets(page.current),
    loadTerminal(terminalPage.current),
    reviewStore.refreshPendingBuckets()
  ])
}

async function loadBuckets(pageNo = page.current) {
  loading.value = true
  try {
    const data = await pagePendingBuckets({ page: pageNo, size: page.size })
    buckets.value = data.records || []
    page.total = data.total || 0
    page.current = pageNo
    if (expandedId.value && !buckets.value.some((bucket) => bucket.id === expandedId.value)) {
      collapse()
    }
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    loading.value = false
  }
}

/** 收起前把表单存进 per-bucket 缓存。直接切桶也走这里，所以 A→B→A 回来内容还在 */
function stashForm() {
  if (!expandedId.value) return
  formCache[expandedId.value] = { ...form }
}

function restoreForm(bucketId) {
  const cached = formCache[bucketId]
  if (!cached) return false
  Object.assign(form, cached)
  return true
}

/** 桶已经 approve / 驳回 / 忽略了，缓存留着只是脏数据 */
function forgetForm(bucketId) {
  delete formCache[bucketId]
}

function collapse() {
  stashForm()
  expandedId.value = ''
  detail.value = null
  // 换桶/收起时把展开的样本也收掉：留下的 recordId 属于上一个桶，留着只是噪音
  openSamples.value = []
}

/** 展开一个桶：拉详情 + 预填表单。三角与「审核」按钮共用这一段 */
async function openBucket(bucket) {
  if (expandedId.value === bucket.id && detail.value) return
  const targetId = bucket.id
  if (expandedId.value && expandedId.value !== targetId) stashForm()
  const restored = restoreForm(targetId)
  expandedId.value = targetId
  detail.value = null
  detailLoading.value = true
  if (!restored) resetForm()
  try {
    const data = await getReviewBucket(targetId)
    // 详情还没回来时用户可能已经切到别的桶了（点得快、或直接点了另一行的三角）——
    // 迟到的响应不能盖到新版面上，否则展开的桶和显示的详情对不上
    if (expandedId.value !== targetId) return
    detail.value = data
    // 还原过就不再用建议值覆盖：管理员可能已经改过主科室/交叉科室，甚至已经生成过文本
    if (!restored) {
      form.mainDeptId = data.suggestedMainDeptId || ''
      form.crossDeptIds = [...(data.suggestedCrossDeptIds || [])]
    }
  } catch (e) {
    if (expandedId.value !== targetId) return
    collapse()
    ElMessage.error(errText(e))
  } finally {
    // 已经换了桶就别关 loading，那是后一次请求的状态
    if (expandedId.value === targetId) detailLoading.value = false
  }
}

async function toggle(bucket) {
  if (expandedId.value === bucket.id) {
    collapse()
    return
  }
  await openBucket(bucket)
}

/**
 * 表格自带的展开三角点的是 Element Plus 内部的 toggleRowExpansion，它只动自己的 expandRows，
 * 不会写我们的 expandedId —— 于是 :expand-row-keys 与子行的 row.id !== expandedId 恒为真，
 * 子行永远停在「加载中…」。把三角的展开态同步回来，两个入口就走同一套逻辑。
 *
 * <p>第二个参数在「展开列」场景下是当前**已展开行的数组**（不是布尔值），见 element-plus
 * store/expand.mjs：emit("expand-change", row, expandRows.slice())。
 * 而 :expand-row-keys 变化走的 setExpandRowKeys 不发事件，所以这里不会和 toggle() 互相触发。
 */
function onExpandChange(row, expandedRows) {
  const expanded = Array.isArray(expandedRows)
    ? expandedRows.some((item) => item.id === row.id)
    : Boolean(expandedRows)
  if (expanded) {
    openBucket(row)
  } else if (expandedId.value === row.id) {
    collapse()
  }
}

function resetForm() {
  form.mainDeptId = ''
  form.crossDeptIds = []
  form.syntheticText = ''
  form.previewed = false
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

/** 逐条保存根因：只写这一条记录，不动同桶其他样本 */
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
    forgetForm(expandedId.value)
    expandedId.value = ''
    await reloadAfterReview()
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
    forgetForm(expandedId.value)
    expandedId.value = ''
    await reloadAfterReview()
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
  await Promise.all([
    loadBuckets(1),
    loadTerminal(1),
    // 无 keep-alive，每次进本页都会重新挂载：顺手把两个计数刷成最新的
    reviewStore.refreshAll()
  ])
})
</script>

<style scoped>
/* 根因 / 科室 chip 的选中态要「点了就是」：admin.css 里 .a-chip 带 180ms 过渡，
   点下去有个渐变过程，看起来像没点上。本页取消过渡（LlmConfig 页未动） */
.a-chip {
  transition: none;
}

/* 待归桶提示：常驻一行，两种状态。有活时左侧竖条转 coral、数字加重——
   「有没有活」要一眼看得出来，不能只靠读一句话 */
.rev__hint {
  margin: 0 0 12px;
  padding: 6px 10px;
  border-left: 2px solid var(--line);
  font-size: 11px; /* 管理端字阶只有 15/13.5/13/11，这行是标签提示，跟 .a-panel__hint 同档 */
  line-height: 1.6;
  color: var(--ink-2);
}
.rev__hint--todo {
  border-left-color: var(--coral);
  color: var(--ink);
  font-weight: 600;
}
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
/* 折叠头：整行可点。默认按钮有 padding/border/background，全部复位 */
.rev__sample-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: var(--sans);
  text-align: left;
  color: var(--ink);
  cursor: pointer;
}
.rev__sample-head--open {
  color: var(--blue);
}
/* 异常标记：只在有引用错位时出现，且是这三行里唯一的彩色 pill */
.rev__flag {
  flex: none;
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--coral-soft);
  color: var(--coral);
  font-size: 11px;
}
.rev__symptom {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--ink);
}
.rev__sum {
  flex: 1;
  min-width: 0;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--ink-2);
}
.rev__toggle {
  flex: none;
  font-size: 11px;
  color: var(--blue);
}
.rev__sample-ops {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
/* el-select 的宽度在组件上给（EP 不吃 max-width），样式交给 element-override.css */
.rev__select {
  width: 320px;
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
.rev__ops {
  display: flex;
  gap: 10px;
}
</style>
