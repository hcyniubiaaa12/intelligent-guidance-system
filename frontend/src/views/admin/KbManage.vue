<template>
  <section class="a-panel">
    <div class="a-tabs" style="margin-bottom: 16px">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="a-tab"
        :class="{ 'a-tab--on': tab === t.key }"
        @click="switchTab(t.key)"
      >
        {{ t.label }}
      </button>
      <button class="a-btn" style="margin-left: auto; align-self: center" @click="openUpload">
        上传文档
      </button>
    </div>

    <!-- ============ 科室蓝本 ============ -->
    <template v-if="tab === 'dept'">
      <div class="kb-toolbar">
        <span class="a-panel__hint">
          停用只在导诊入口生效：挂号页不列、推荐校验过滤；切片与历史记录原样保留
        </span>
        <button class="a-btn a-btn--ghost" @click="loadDepts">刷新</button>
      </div>
      <el-table v-loading="deptLoading" :data="depts" empty-text="还没有科室">
        <el-table-column prop="name" label="科室" min-width="140" />
        <el-table-column prop="location" label="位置" min-width="150">
          <template #default="{ row }">{{ row.location || '—' }}</template>
        </el-table-column>
        <el-table-column label="切片数" width="90">
          <template #default="{ row }">
            <span :title="`${row.docCount} 份文档`">{{ row.chunkCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain" size="small">
              {{ row.enabled ? '启用中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link size="small" @click="openDeptEdit(row)">编辑</el-button>
            <el-button link size="small" @click="toggleDept(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- ============ 文档入库 ============ -->
    <template v-else-if="tab === 'doc'">
      <div class="kb-toolbar">
        <el-select v-model="docQuery.deptId" clearable placeholder="全部科室" style="width: 168px">
          <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-select v-model="docQuery.status" clearable placeholder="全部状态" style="width: 130px">
          <el-option label="解析中" value="parsing" />
          <el-option label="已完成" value="done" />
          <el-option label="失败" value="failed" />
        </el-select>
        <el-input
          v-model="docQuery.keyword"
          placeholder="文档标题"
          clearable
          style="width: 200px"
          @keyup.enter="searchDocs"
        />
        <button class="a-btn" @click="searchDocs">查询</button>
        <button class="a-btn a-btn--ghost" @click="resetDocQuery">重置</button>
        <span class="a-panel__hint" style="margin-left: auto">共 {{ docPage.total }} 份</span>
      </div>

      <el-table v-loading="docLoading" :data="docs" empty-text="还没有文档，点右上角「上传文档」开始">
        <el-table-column prop="title" label="文档标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="deptName" label="所属科室" width="120" />
        <el-table-column label="入库进度" min-width="230">
          <template #default="{ row }">
            <!-- 进度按阶段显示真实量：解析的百分比来自外部服务、写入的计数来自后端。
                 两段耗时差着数量级，拼成一根 0–100% 的条必须拍一个分段比例，而它在任何一份
                 文档上都不会对——症状是"卡在 60% 不动"，人读成"卡死了"（详见前端设计方案 §3.8） -->
            <div
              v-if="progressOf(row).ratio !== null"
              class="a-progress"
              style="margin-bottom: 5px"
            >
              <div
                class="a-progress__fill"
                :class="progressOf(row).tone"
                :style="{ width: progressOf(row).ratio + '%' }"
              />
            </div>
            <span class="a-panel__hint" :class="{ 'kb-fail': row.status === 'failed' }">
              {{ progressOf(row).text }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag :type="statusTag(row).type" :effect="statusTag(row).effect" size="small">
              {{ statusTag(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <el-button link size="small" @click="openChunks(row)">查看切片</el-button>
            <el-button
              v-if="row.status === 'failed' && row.reprocessable"
              type="primary"
              link
              size="small"
              @click="onReprocess(row)"
            >
              重新处理
            </el-button>
            <el-button type="danger" link size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="docPage.current"
        :page-size="docPage.size"
        :total="docPage.total"
        layout="total, prev, pager, next"
        background
        @current-change="loadDocs"
      />
    </template>

    <!-- ============ 映射台账 ============ -->
    <template v-else-if="tab === 'map'">
      <div class="kb-toolbar">
        <el-input
          v-model="mapQuery.keyword"
          placeholder="症状关键字"
          clearable
          style="width: 220px"
          @keyup.enter="searchMappings"
        />
        <button class="a-btn" @click="searchMappings">查询</button>
        <span class="a-panel__hint">
          台账是审核结论的留痕（回流 approve 写 source=feedback），只读；运行时 RAG 不读它
        </span>
        <span class="a-panel__hint" style="margin-left: auto">共 {{ mapPage.total }} 条</span>
      </div>
      <!-- 只读展示表用本方案 .a-table 手写（§3.5），总数在工具栏 hint 里，不在分页器重复 -->
      <div v-if="mapLoading" class="a-empty">加载中…</div>
      <div v-else-if="!mappings.length" class="a-empty">
        台账为空：还没有审核回流产出的映射（写入唯一路径是链路 C 的 approve）
      </div>
      <template v-else>
        <table class="a-table">
          <thead>
            <tr>
              <th>症状</th>
              <th style="width: 130px">主科室</th>
              <th style="width: 200px">交叉科室</th>
              <th style="width: 110px">来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in mappings" :key="m.id">
              <td>{{ m.symptom }}</td>
              <td>{{ m.mainDeptName }}</td>
              <td>{{ m.crossDeptNames || '—' }}</td>
              <td>
                <span class="a-tag" :class="m.source === 'feedback' ? 'a-tag--ok' : 'a-tag--plain'">
                  {{ sourceLabel(m.source) }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        <el-pagination
          v-model:current-page="mapPage.current"
          :page-size="mapPage.size"
          :total="mapPage.total"
          layout="prev, pager, next"
          @current-change="loadMappings"
        />
      </template>
    </template>

    <!-- ============ 术语白名单 ============ -->
    <template v-else>
      <div class="kb-toolbar">
        <el-input
          v-model="termQuery.keyword"
          placeholder="术语"
          clearable
          style="width: 180px"
          @keyup.enter="searchTerms"
        />
        <el-select v-model="termQuery.enabled" clearable placeholder="全部状态" style="width: 130px">
          <el-option label="已生效" :value="true" />
          <el-option label="待审核/已停用" :value="false" />
        </el-select>
        <button class="a-btn" @click="searchTerms">查询</button>
        <span class="a-panel__hint">
          白名单是 chat 入口的防误杀闸门，启停即时生效；唯一生效源是本表，ES 聚合只是候选池
        </span>
        <span class="a-panel__hint" style="margin-left: auto">共 {{ termPage.total }} 条</span>
      </div>
      <el-table v-loading="termLoading" :data="terms" empty-text="白名单为空">
        <el-table-column prop="term" label="术语" min-width="160" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.type === 'part' ? '部位' : '症状' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="120">
          <template #default="{ row }">
            {{ row.source === 'llm_extract' ? '模型抽取' : '人工维护' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'warning'" effect="plain" size="small">
              {{ row.enabled ? '已生效' : '未生效' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link size="small" @click="onToggleTerm(row)">
              {{ row.enabled ? '停用' : '确认启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="termPage.current"
        :page-size="termPage.size"
        :total="termPage.total"
        layout="total, prev, pager, next"
        background
        @current-change="loadTerms"
      />
    </template>

    <!-- 上传弹窗 -->
    <el-dialog v-model="uploadVisible" title="上传文档" width="500px">
      <el-form label-width="80px">
        <el-form-item label="所属科室">
          <el-select v-model="uploadForm.deptId" placeholder="选择科室" style="width: 100%">
            <el-option
              v-for="d in depts"
              :key="d.id"
              :label="d.enabled ? d.name : `${d.name}（已停用）`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="文档标题">
          <el-input v-model="uploadForm.title" placeholder="留空则用文件名" />
        </el-form-item>
        <el-form-item label="文件">
          <el-upload
            ref="uploadRef"
            class="a-upload"
            drag
            :auto-upload="false"
            :limit="1"
            :accept="ACCEPT"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            :on-exceed="onFileExceed"
          >
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
            <div class="el-upload__text">把文件拖到这里，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">
                支持 pdf / docx / png（走 DocumentMind 解析，按量计费）、txt / md / html（本地读），
                单个文件不超过 50MB。上传后立即返回，解析与入库在后台跑，进度看列表。
              </div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="uploading"
          :disabled="!uploadFile || !uploadForm.deptId"
          @click="submitUpload"
        >
          开始入库
        </el-button>
      </template>
    </el-dialog>

    <!-- 科室编辑弹窗 -->
    <el-dialog v-model="deptEditVisible" title="编辑科室" width="460px">
      <el-form label-width="80px">
        <el-form-item label="科室名">
          <el-input v-model="deptForm.name" placeholder="如：心血管内科" />
        </el-form-item>
        <el-form-item label="位置">
          <el-input v-model="deptForm.location" placeholder="如：门诊楼 3F 东区" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="deptForm.intro" type="textarea" :rows="3" placeholder="选填" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="deptForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <div class="a-field__hint">
        科室名必须全库唯一——模型输出的是科室名字符串，写库与推荐校验都按名字回填实体，
        重名会让同一条主诉落到不同科室。停用后挂号页不再列出、推荐校验会过滤掉它。
      </div>
      <template #footer>
        <el-button @click="deptEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="deptSaving" @click="submitDept">保存</el-button>
      </template>
    </el-dialog>

    <!-- 切片抽屉 -->
    <el-drawer v-model="chunksVisible" :title="chunksTitle" size="620px">
      <div v-if="chunksLoading" class="a-empty">加载中…</div>
      <div v-else-if="!chunks.length" class="a-empty">这份文档还没有切片</div>
      <div v-else class="kb-chunks">
        <div v-for="c in chunks" :key="c.id" class="kb-chunk">
          <div class="kb-chunk__head">
            <span class="kb-chunk__seq">#{{ c.seq }}</span>
            <span class="kb-chunk__title">{{ c.title }}</span>
            <span class="a-panel__hint">{{ (c.content || '').length }} 字</span>
          </div>
          <pre class="kb-chunk__body">{{ c.content }}</pre>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import {
  pageKbDocs, uploadKbDoc, getKbChunks, reprocessKbDoc, deleteKbDoc,
  listKbDepts, updateKbDept, pageKbMappings, pageKbTerms, toggleKbTerm
} from '../../api/admin'

const tabs = [
  { key: 'dept', label: '科室蓝本' },
  { key: 'doc', label: '文档入库' },
  { key: 'map', label: '映射台账' },
  { key: 'term', label: '术语白名单' }
]
const tab = ref('doc')

function switchTab(t) {
  tab.value = t
  if (t === 'dept' && !depts.value.length) loadDepts()
  if (t === 'map') loadMappings()
  if (t === 'term') loadTerms()
}

function errText(e) {
  return e?.message || '操作失败，请稍后重试'
}

// 后端下发的是 ISO 本地时间，去 T 截到分钟即可，不引日期库
function fmtDateTime(s) {
  return s ? String(s).replace('T', ' ').slice(0, 16) : ''
}

// —— 科室（四个 tab 都要用：上传表单的选项、科室 tab 的行）——
const depts = ref([])
const deptLoading = ref(false)
const deptEditVisible = ref(false)
const deptSaving = ref(false)
const deptForm = reactive({ id: '', name: '', location: '', intro: '', enabled: true })

async function loadDepts() {
  deptLoading.value = true
  try {
    depts.value = await listKbDepts()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    deptLoading.value = false
  }
}

function openDeptEdit(row) {
  Object.assign(deptForm, {
    id: row.id,
    name: row.name,
    location: row.location || '',
    intro: row.intro || '',
    enabled: row.enabled === 1
  })
  deptEditVisible.value = true
}

async function submitDept() {
  deptSaving.value = true
  try {
    await updateKbDept(deptForm.id, {
      name: deptForm.name,
      location: deptForm.location,
      intro: deptForm.intro,
      enabled: deptForm.enabled
    })
    deptEditVisible.value = false
    ElMessage.success('科室已保存')
    await loadDepts()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    deptSaving.value = false
  }
}

async function toggleDept(row) {
  const off = row.enabled === 1
  if (off) {
    try {
      await ElMessageBox.confirm(
        `停用后挂号页不再列出「${row.name}」，推荐校验也会过滤掉它；` +
          '该科室的切片与历史导诊记录原样保留，随时可以再启用。',
        '停用科室',
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' }
      )
    } catch (e) {
      return
    }
  }
  try {
    await updateKbDept(row.id, {
      name: row.name,
      location: row.location,
      intro: row.intro,
      enabled: !off
    })
    ElMessage.success(off ? `已停用 ${row.name}` : `已启用 ${row.name}`)
    await loadDepts()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

// —— 文档入库 ——
const docs = ref([])
const docLoading = ref(false)
const docQuery = reactive({ deptId: '', status: '', keyword: '' })
const docPage = reactive({ current: 1, size: 10, total: 0 })
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadRef = ref(null)
/** 当前选中的文件（el-upload 自己维护列表，这里只留"那一个"） */
const uploadFile = ref(null)
const uploadForm = reactive({ deptId: '', title: '' })
/** 与后端白名单一致（判定权在后端，这里只用于文件选择框的过滤） */
const ACCEPT = '.pdf,.docx,.png,.txt,.md,.html,.htm'
/** 与后端 multipart 上限一致；超了只提示，判定仍归后端 */
const MAX_BYTES = 50 * 1024 * 1024

// 有文档在跑就每 3s 刷一次列表（进度是后端算的，前端只看）；都停了就停表
let timer = null
let parsingIds = new Set()

function syncTimer() {
  const busy = docs.value.some((d) => d.status === 'parsing')
  if (busy && !timer) timer = setInterval(loadDocs, 3000)
  if (!busy && timer) {
    clearInterval(timer)
    timer = null
  }
}

/** 上一轮还在跑的文档这轮收敛了就提示一次（失败也提示，失败原因就在行上） */
function notifyFinished() {
  const nowParsing = new Set(docs.value.filter((d) => d.status === 'parsing').map((d) => d.id))
  for (const doc of docs.value) {
    if (parsingIds.has(doc.id) && !nowParsing.has(doc.id)) {
      if (doc.status === 'done') ElMessage.success(`《${doc.title}》入库完成，共 ${doc.chunkTotal} 片`)
      else if (doc.status === 'failed') ElMessage.error(`《${doc.title}》入库失败：${doc.failReason}`)
    }
  }
  parsingIds = nowParsing
}

async function loadDocs() {
  docLoading.value = true
  try {
    const data = await pageKbDocs({
      page: docPage.current,
      size: docPage.size,
      deptId: docQuery.deptId || undefined,
      status: docQuery.status || undefined,
      keyword: docQuery.keyword || undefined
    })
    docs.value = data.records || []
    docPage.total = data.total || 0
    notifyFinished()
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    docLoading.value = false
    syncTimer()
  }
}

function searchDocs() {
  docPage.current = 1
  loadDocs()
}

function resetDocQuery() {
  docQuery.deptId = ''
  docQuery.status = ''
  docQuery.keyword = ''
  searchDocs()
}

/**
 * 进度文案与进度条：**只显示真实知道的量**。
 * parse 阶段是外部服务给的真百分比；split 阶段没有任何数字（切出多少片是切分的结果）；
 * embed 阶段是自己的计数。ratio 为 null 就不画条——不画比画错强。
 */
function progressOf(row) {
  const task = row.task
  if (row.status === 'done') {
    return { text: `已完成 · ${row.chunkTotal || 0} 片`, ratio: 100, tone: 'a-progress__fill--ok' }
  }
  if (row.status === 'failed') {
    return { text: row.failReason || '入库失败', ratio: null, tone: 'a-progress__fill--warn' }
  }
  if (!task) {
    return { text: '排队中…', ratio: null }
  }
  if (task.stage === 'parse') {
    const pct = Math.max(0, Math.min(99, task.done || 0))
    return { text: `解析中 ${pct}%`, ratio: pct, tone: '' }
  }
  if (task.stage === 'split') {
    return { text: '正在切分', ratio: null }
  }
  if (task.stage === 'embed') {
    const total = task.total || 0
    const done = task.done || 0
    return {
      text: `写入 ${done} / ${total}`,
      ratio: total ? Math.round((done / total) * 100) : null,
      tone: ''
    }
  }
  return { text: '处理中…', ratio: null }
}

function statusTag(row) {
  if (row.status === 'done') return { label: '已完成', type: 'success', effect: 'plain' }
  if (row.status === 'failed') return { label: '失败', type: 'danger', effect: 'plain' }
  return { label: '解析中', type: 'primary', effect: 'plain' }
}

function openUpload() {
  tab.value = 'doc'
  uploadForm.deptId = docQuery.deptId || (depts.value.find((d) => d.enabled === 1) || {}).id || ''
  uploadForm.title = ''
  uploadFile.value = null
  uploadRef.value?.clearFiles() // 上一次开窗选过的文件不能跟着留下来
  uploadVisible.value = true
}

function onFileChange(file) {
  uploadFile.value = file.raw || null
  if (file.size > MAX_BYTES) {
    ElMessage.warning(`「${file.name}」${(file.size / 1024 / 1024).toFixed(1)}MB，超过 50MB 上限，后端会拒收`)
  }
}

function onFileRemove() {
  uploadFile.value = null
}

/** limit=1 时再选一个文件会被拦下，改成**替换**：选错文件后第一反应是重选，不是先删 */
function onFileExceed(files) {
  uploadRef.value.clearFiles()
  uploadRef.value.handleStart(files[0])
}

async function submitUpload() {
  if (!uploadForm.deptId) {
    ElMessage.warning('请选择所属科室')
    return
  }
  if (!uploadFile.value) {
    ElMessage.warning('请选择要上传的文件')
    return
  }
  uploading.value = true
  try {
    await uploadKbDoc(uploadFile.value, uploadForm.deptId, uploadForm.title)
    uploadVisible.value = false
    docPage.current = 1
    await loadDocs()
    ElMessage.success('已开始入库，进度看列表（解析按页计费，期间不要重复上传同一份文件）')
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    uploading.value = false
  }
}

async function onReprocess(row) {
  try {
    await ElMessageBox.confirm(
      '重新处理会**先删掉这份文档的全部切片再做一遍**，过程中它的内容检索不到；' +
        '失败时要再点一次才会补上。要换内容请改用「删除后重新上传」。',
      '重新处理',
      { confirmButtonText: '重新处理', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await reprocessKbDoc(row.id)
    ElMessage.success('已提交重新处理')
    await loadDocs()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.title}」会终止它正在跑的任务，并清掉切片与向量、ES 索引和 MinIO 原文件。` +
        '该操作不可撤销，要恢复只能重新上传。',
      '删除文档',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteKbDoc(row.id)
    ElMessage.success('已删除')
    await loadDocs()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

// —— 查看切片 ——
const chunksVisible = ref(false)
const chunksLoading = ref(false)
const chunks = ref([])
const chunksTitle = ref('切片')

async function openChunks(row) {
  chunksTitle.value = `${row.title} · 切片`
  chunks.value = []
  chunksVisible.value = true
  chunksLoading.value = true
  try {
    chunks.value = await getKbChunks(row.id)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    chunksLoading.value = false
  }
}

// —— 映射台账（只读）——
const mappings = ref([])
const mapLoading = ref(false)
const mapQuery = reactive({ keyword: '' })
const mapPage = reactive({ current: 1, size: 10, total: 0 })

async function loadMappings() {
  mapLoading.value = true
  try {
    const data = await pageKbMappings({
      page: mapPage.current,
      size: mapPage.size,
      keyword: mapQuery.keyword || undefined
    })
    mappings.value = data.records || []
    mapPage.total = data.total || 0
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    mapLoading.value = false
  }
}

function searchMappings() {
  mapPage.current = 1
  loadMappings()
}

function sourceLabel(source) {
  if (source === 'feedback') return '回流审核'
  if (source === 'manual') return '人工'
  return '初始化'
}

// —— 术语白名单 ——
const terms = ref([])
const termLoading = ref(false)
const termQuery = reactive({ keyword: '', enabled: '' })
const termPage = reactive({ current: 1, size: 10, total: 0 })

async function loadTerms() {
  termLoading.value = true
  try {
    const data = await pageKbTerms({
      page: termPage.current,
      size: termPage.size,
      keyword: termQuery.keyword || undefined,
      enabled: termQuery.enabled === '' ? undefined : termQuery.enabled
    })
    terms.value = data.records || []
    termPage.total = data.total || 0
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    termLoading.value = false
  }
}

function searchTerms() {
  termPage.current = 1
  loadTerms()
}

async function onToggleTerm(row) {
  try {
    const updated = await toggleKbTerm(row.id)
    row.enabled = updated.enabled
    ElMessage.success(updated.enabled ? `「${row.term}」已启用，入口校验即时生效` : `「${row.term}」已停用`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

onMounted(async () => {
  await loadDepts()
  await loadDocs()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.kb-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.kb-fail {
  color: var(--err);
}
.kb-chunks {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.kb-chunk {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px;
  background: var(--card);
}
.kb-chunk__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.kb-chunk__seq {
  font-size: 11px;
  color: var(--ink-2);
}
.kb-chunk__title {
  font-size: 13px;
  color: var(--ink);
  font-weight: 700;
}
.kb-chunk__body {
  margin: 0;
  font-family: var(--sans);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink-2);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
