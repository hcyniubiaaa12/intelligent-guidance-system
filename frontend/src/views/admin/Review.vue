<template>
  <section class="a-panel">
    <div class="a-panel__head">
      <span class="a-panel__title">审核队列 · 错误模式聚合桶</span>
      <span class="a-panel__hint">展开行：审核表单（主科室 / 交叉科室 / 回流预览）+ 根因归因</span>
    </div>

    <table class="a-table">
      <thead>
        <tr>
          <th>错误方向（推荐 → 实际）</th>
          <th>代表样本</th>
          <th>累计次数</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="b in buckets" :key="b.id">
          <tr style="cursor: pointer" @click="openBucket(b)">
            <td>{{ b.rec }} → {{ b.actual }}</td>
            <td>{{ b.symptom }}</td>
            <td>{{ b.count }}</td>
            <td>
              <span class="a-tag" :class="statusOf(b).cls">{{ statusOf(b).text }}</span>
            </td>
            <td>
              <div class="a-table__ops" @click.stop>
                <button class="a-btn" @click="openBucket(b)">
                  {{ b.status === 'pending' ? '审核' : '查看 / 修正重审' }}
                </button>
              </div>
            </td>
          </tr>

          <!-- 展开子行：证据快照 + 审核表单 + 根因归因 -->
          <tr v-if="open === b.id" class="a-subrow">
            <td colspan="5">
              <div class="rev">
                <!-- 证据快照 -->
                <div class="a-eyebrow">证据快照</div>
                <div class="ev">
                  <div v-for="(e, i) in b.evidence" :key="i" class="ev__row">
                    <span class="ev__no">{{ e.rank }}</span>
                    <span class="ev__title">{{ e.title }}</span>
                    <span class="ev__score">{{ e.score }}</span>
                  </div>
                </div>
                <p class="a-panel__hint" style="margin-top: 8px">
                  模型原始输出：{{ b.rawOutput }}
                </p>

                <!-- 审核表单（待审核） -->
                <template v-if="b.status === 'pending'">
                  <div class="a-eyebrow" style="margin-top: 16px">审核 · 给出修正结论</div>
                  <div class="rev__form">
                    <div class="a-field">
                      <label class="a-field__label">主科室（必填）</label>
                      <select v-model="form.mainDept" class="a-field__input">
                        <option value="" disabled>选择该症状应首诊的科室</option>
                        <option v-for="d in depts" :key="d.name" :value="d.name">
                          {{ d.name }}{{ d.enabled ? '' : '（已停用）' }}
                        </option>
                      </select>
                      <div class="a-field__hint">停用科室可选：审核是知识层动作，与运营启停解耦</div>
                    </div>

                    <div class="a-field">
                      <label class="a-field__label">交叉科室（鉴别候选，可增删）</label>
                      <div class="a-chips" style="margin: 0 0 8px">
                        <button
                          v-for="c in form.cross"
                          :key="c"
                          class="a-chip a-chip--on"
                          @click="removeCross(c)"
                        >
                          {{ c }} ×
                        </button>
                        <span v-if="!form.cross.length" class="a-panel__hint">暂无</span>
                      </div>
                      <select v-model="crossPick" class="a-field__input" @change="addCross">
                        <option value="">+ 添加交叉科室</option>
                        <option v-for="d in crossOptions" :key="d.name" :value="d.name">
                          {{ d.name }}{{ d.enabled ? '' : '（已停用）' }}
                        </option>
                      </select>
                      <div class="a-field__hint">预填 = 桶方向 {推荐, 实际} − {主科室}</div>
                    </div>

                    <div class="a-field rev__preview">
                      <label class="a-field__label">回流预览（合成 chunk）</label>
                      <textarea
                        v-if="form.preview"
                        v-model="form.preview"
                        class="a-field__input"
                        rows="3"
                      />
                      <p v-else class="a-panel__hint">
                        点「生成预览」起草鉴别语料（真实实现由 LLM 生成），可编辑后定稿
                      </p>
                      <div class="a-table__ops" style="margin-top: 8px">
                        <button class="a-btn a-btn--ghost" :disabled="!form.mainDept" @click="generate">
                          {{ form.preview ? '重新生成' : '生成预览' }}
                        </button>
                      </div>
                    </div>
                  </div>

                  <div class="a-table__ops">
                    <button
                      class="a-btn"
                      :disabled="!form.mainDept || !form.preview"
                      @click="confirmBack(b)"
                    >
                      确认入库（回流）
                    </button>
                    <button class="a-btn a-btn--ghost" @click="setStatus(b, 'rejected')">驳回</button>
                    <button class="a-btn a-btn--ghost" @click="setStatus(b, 'dismissed')">忽略</button>
                  </div>
                </template>

                <!-- 终态：纠错入口 -->
                <template v-else>
                  <div class="rev__terminal">
                    该桶已是终态（{{ statusOf(b).text }}）。如需纠正，可发起修正重审回到待审重走审核。
                  </div>
                  <button class="a-btn a-btn--ghost" @click="reopen(b)">修正重审</button>
                </template>

                <!-- 根因归因（独立于审核） -->
                <div class="a-eyebrow" style="margin-top: 18px">根因归因 · 独立动作</div>
                <div class="a-chips">
                  <button
                    v-for="c in causes"
                    :key="c.key"
                    class="a-chip"
                    :class="{ 'a-chip--on': form.causes.includes(c.key) }"
                    @click="pickCause(c.key)"
                  >
                    {{ c.label }}
                  </button>
                </div>
                <div class="a-table__ops">
                  <button class="a-btn a-btn--ghost" @click="applyCauses(b)">
                    套用至全桶（{{ b.count }} 条）
                  </button>
                  <button class="a-btn a-btn--ghost" @click="toggleRecords">
                    {{ showRecords ? '收起逐条' : '逐条调整' }}
                  </button>
                </div>

                <div v-if="showRecords" class="rev__records">
                  <div v-for="r in b.records" :key="r.id" class="rev__record">
                    <span class="rev__record-text">{{ r.text }}</span>
                    <span v-if="r.causes.length" class="rev__record-causes">{{ r.causes.map(causeLabel).join('、') }}</span>
                    <span v-else class="a-panel__hint">未归因</span>
                    <button
                      class="a-btn a-btn--ghost"
                      @click="recPickId = recPickId === r.id ? '' : r.id"
                    >
                      改
                    </button>
                    <div v-if="recPickId === r.id" class="a-chips" style="flex-basis: 100%; margin: 6px 0 0">
                      <button
                        v-for="c in causes"
                        :key="c.key"
                        class="a-chip"
                        :class="{ 'a-chip--on': r.causes.includes(c.key) }"
                        @click="pickRecordCause(r, c.key)"
                      >
                        {{ c.label }}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </td>
          </tr>
        </template>
      </tbody>
    </table>

    <div v-if="toast" class="a-toast">{{ toast }}</div>
  </section>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'

// —— 假数据：科室池（含停用科室，选择器带标记可选中）——
const depts = [
  { name: '心血管内科', enabled: true },
  { name: '呼吸内科', enabled: true },
  { name: '消化内科', enabled: true },
  { name: '骨科', enabled: true },
  { name: '神经内科', enabled: true },
  { name: '皮肤科', enabled: false }
]

// 根因选项清单：与后端 feedback/enums/RootCauseKey 对齐——**存小写 key、显示中文 label**，
// 后端那份是唯一定义源（看板要按 key 聚合、并按 label 显示）。本页接真实接口时改为从后端取，
// 届时这份本地清单即可删除（见进度.md「下一步」）。
const causes = [
  { key: 'chunk_broken', label: '切分破碎' },
  { key: 'parse_missed', label: '解析遗漏' },
  { key: 'retrieval_fail', label: '检索失败' },
  { key: 'mapping_missing', label: '局部映射缺失' },
  { key: 'model_ignored_retrieval', label: '模型未依据检索' },
  { key: 'structure_fail', label: '结构化失败' },
  { key: 'patient_wrong', label: '患者挂错' }
]

const causeLabels = Object.fromEntries(causes.map((c) => [c.key, c.label]))

/** key → 中文名；字典外的 key 原样显示（与后端 labelOf 同口径，不把数据藏起来） */
function causeLabel(key) {
  return causeLabels[key] || key
}

// —— 假数据：聚合桶（方向 + 代表样本 + 桶内记录）——
const buckets = ref([
  {
    id: 'b1',
    rec: '神经内科',
    actual: '心血管内科',
    symptom: '太阳穴跳痛，伴心慌、活动后加重',
    count: 4,
    status: 'pending',
    rawOutput: '{"dept":"神经内科","confidence":0.58}',
    evidence: [
      { rank: 1, title: '神经内科科室介绍', score: '0.71' },
      { rank: 2, title: '偏头痛的典型表现', score: '0.66' },
      { rank: 3, title: '心源性头痛鉴别', score: '0.62' }
    ],
    records: [
      { id: 'r11', text: '太阳穴跳痛 心慌', causes: [] },
      { id: 'r12', text: '两侧太阳穴一阵阵跳痛，上楼明显', causes: [] },
      { id: 'r13', text: '太阳穴胀痛伴胸闷心慌', causes: [] },
      { id: 'r14', text: '头痛 心慌 太阳穴位置', causes: [] }
    ]
  },
  {
    id: 'b2',
    rec: '消化内科',
    actual: '呼吸内科',
    symptom: '半夜反酸烧心，平躺加重',
    count: 3,
    status: 'pending',
    rawOutput: '{"dept":"消化内科","confidence":0.64}',
    evidence: [
      { rank: 1, title: '胃食管反流病诊疗', score: '0.74' },
      { rank: 2, title: '夜间咳嗽的鉴别', score: '0.61' }
    ],
    records: [
      { id: 'r21', text: '半夜反酸烧心', causes: [] },
      { id: 'r22', text: '躺下就烧心，还咳嗽', causes: [] },
      { id: 'r23', text: '夜里反酸 睡不好', causes: [] }
    ]
  },
  {
    id: 'b3',
    rec: '骨科',
    actual: '神经内科',
    symptom: '颈肩僵硬伴手指放射性麻木',
    count: 3,
    status: 'approved',
    approvedMain: '骨科',
    approvedCross: ['神经内科'],
    approvedPreview: '颈肩僵硬伴手指放射性麻木：建议首诊骨科，同时需与神经内科相关病因鉴别。',
    rawOutput: '{"dept":"骨科","confidence":0.69}',
    evidence: [{ rank: 1, title: '颈椎病分型', score: '0.78' }],
    records: [
      { id: 'r31', text: '颈肩僵硬 手指发麻', causes: ['retrieval_fail'] },
      { id: 'r32', text: '脖子肩膀僵，手麻', causes: ['retrieval_fail'] },
      { id: 'r33', text: '颈肩不适伴上肢麻木', causes: ['retrieval_fail'] }
    ]
  }
])

const statusMap = {
  pending: { text: '待审核', cls: 'a-tag--warn' },
  approved: { text: '已回流', cls: 'a-tag--ok' },
  rejected: { text: '已驳回', cls: 'a-tag--plain' },
  dismissed: { text: '已忽略', cls: 'a-tag--plain' }
}

const open = ref('')
const form = reactive({ mainDept: '', cross: [], preview: '', causes: [] })
const crossPick = ref('')
const showRecords = ref(false)
const recPickId = ref('')
const toast = ref('')

const cur = computed(() => buckets.value.find((b) => b.id === open.value))

// 可选交叉科室 = 科室池 − 主科室 − 已选
const crossOptions = computed(() =>
  depts.filter((d) => d.name !== form.mainDept && !form.cross.includes(d.name))
)

// 预填规则：主科室选定后 = 桶方向 {推荐, 实际} − {主科室}
watch(
  () => form.mainDept,
  (m) => {
    const b = cur.value
    if (!b) return
    form.cross = [b.rec, b.actual].filter((d) => d !== m)
    form.preview = ''
  }
)

function openBucket(b) {
  if (open.value === b.id) {
    open.value = ''
    return
  }
  open.value = b.id
  form.mainDept = ''
  form.cross = []
  form.preview = ''
  form.causes = b.records[0]?.causes.slice() || []
  crossPick.value = ''
  showRecords.value = false
  recPickId.value = ''
  // 已回流桶再次打开：按既有结论回填（便于修正重审）
  if (b.status === 'approved' && b.approvedMain) {
    form.mainDept = b.approvedMain
    form.cross = [...(b.approvedCross || [])]
    form.preview = b.approvedPreview || ''
  }
}

function addCross() {
  if (crossPick.value && !form.cross.includes(crossPick.value)) {
    form.cross.push(crossPick.value)
  }
  crossPick.value = ''
}

function removeCross(c) {
  form.cross = form.cross.filter((x) => x !== c)
}

// 生成回流预览（假数据模板；真实实现 = LLM 生成 + 人工定稿）
function generate() {
  const b = cur.value
  form.preview = form.cross.length
    ? `${b.symptom}：建议首诊${form.mainDept}，同时需与${form.cross.join('、')}相关病因鉴别。`
    : `${b.symptom}：建议首诊${form.mainDept}。`
}

function confirmBack(b) {
  b.status = 'approved'
  b.approvedMain = form.mainDept
  b.approvedCross = [...form.cross]
  b.approvedPreview = form.preview
  showToast('已回流：映射台账已写入，合成 chunk 已提交链路 B')
}

function setStatus(b, s) {
  b.status = s
  open.value = ''
  showToast(s === 'rejected' ? '已驳回（审过否定，不产出）' : '已忽略（清理动作，不产出）')
}

// 修正重审：终态桶回 pending（幂等约束只针对自动升级）
function reopen(b) {
  b.status = 'pending'
  form.mainDept = b.approvedMain || ''
  form.cross = [...(b.approvedCross || [])]
  form.preview = b.approvedPreview || ''
  showToast('已退回待审（修正重审）')
}

function pickCause(c) {
  const i = form.causes.indexOf(c)
  i >= 0 ? form.causes.splice(i, 1) : form.causes.push(c)
}

function applyCauses(b) {
  b.records.forEach((r) => {
    r.causes = [...form.causes]
  })
  showToast(`已套用至全桶 ${b.count} 条记录`)
}

function toggleRecords() {
  showRecords.value = !showRecords.value
  recPickId.value = ''
}

function pickRecordCause(r, c) {
  const i = r.causes.indexOf(c)
  i >= 0 ? r.causes.splice(i, 1) : r.causes.push(c)
}

function statusOf(b) {
  return statusMap[b.status]
}

let toastTimer
function showToast(msg) {
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 2200)
}
</script>

<style scoped>
.rev {
  padding: 6px 2px 12px;
}
.rev__form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.rev__preview {
  grid-column: 1 / -1;
}
.rev__terminal {
  margin: 14px 0 10px;
  font-size: 12.5px;
  color: var(--ink-2);
}
.a-eyebrow {
  font-size: 11px;
  letter-spacing: .14em;
  color: var(--ink-2);
}
.ev {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ev__row {
  display: flex;
  gap: 10px;
  font-size: 12px;
}
.ev__no {
  width: 18px;
  color: var(--blue);
  font-weight: 600;
}
.ev__title {
  flex: 1;
}
.ev__score {
  color: var(--ink-2);
}
.rev__records {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rev__record {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 7px 10px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 5px;
  font-size: 12px;
}
.rev__record-text {
  flex: 1;
}
.rev__record-causes {
  color: var(--blue);
  font-size: 11.5px;
}
</style>
