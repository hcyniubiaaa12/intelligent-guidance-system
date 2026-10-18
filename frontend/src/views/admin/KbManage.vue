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
        <button class="a-btn" @click="openDeptCreate">新 增 科 室</button>
      </div>
      <el-table v-loading="deptLoading" :data="deptRows" empty-text="还没有科室">
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
      <!-- 科室是**全量拉取**（上传表单与筛选下拉都要用整份列表），所以这里只做前端分页 -->
      <el-pagination
        v-model:current-page="deptPage.current"
        :page-size="deptPage.size"
        :page-sizes="PAGE_SIZES"
        :total="depts.length"
        :layout="PAGE_LAYOUT"
        background
        @size-change="onDeptSize"
      />
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
          placeholder="请输入文档标题"
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
                 文档上都不会对——症状是"卡在 60% 不动"，人读成"卡死了"（详见前端设计方案-管理端 §3.8） -->
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
        :page-sizes="PAGE_SIZES"
        :total="docPage.total"
        :layout="PAGE_LAYOUT"
        background
        @current-change="loadDocs"
        @size-change="onDocSize"
      />
    </template>

    <!-- ============ 映射台账 ============ -->
    <template v-else-if="tab === 'map'">
      <div class="kb-toolbar">
        <el-input
          v-model="mapQuery.keyword"
          placeholder="请输入症状"
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
          :page-sizes="PAGE_SIZES"
          :total="mapPage.total"
          :layout="PAGE_LAYOUT_NO_TOTAL"
          background
          @current-change="loadMappings"
          @size-change="onMapSize"
        />
      </template>
    </template>

    <!-- ============ 术语白名单 ============ -->
    <template v-else>
      <!-- 审核开关与词表同页：抽出了什么 / 要不要我确认，是一件事的两半 -->
      <div class="a-panel" style="padding: 0 0 14px; border-bottom: 1px solid var(--line); margin-bottom: 14px">
        <div class="a-panel__head">
          <span class="a-panel__title">术语审核</span>
          <span class="a-panel__hint">模型抽取的词默认待确认；关掉开关则抽取即生效，存 sys_config</span>
        </div>
        <div v-if="termRuleLoading" class="a-empty">加载中…</div>
        <div v-else-if="!termRule" class="a-empty">审核开关加载失败，实际生效口径以下方「待确认」计数为准</div>
        <template v-else>
          <div class="a-field">
            <label class="a-field__label">{{ termRule.label }}</label>
            <div class="a-chips" style="margin: 0">
              <button
                class="a-chip"
                :class="{ 'a-chip--on': termRule.value === 'true' }"
                :disabled="termRuleSaving"
                @click="termRule.value = 'true'"
              >
                开启
              </button>
              <button
                class="a-chip"
                :class="{ 'a-chip--on': termRule.value !== 'true' }"
                :disabled="termRuleSaving"
                @click="termRule.value = 'false'"
              >
                关闭
              </button>
            </div>
            <div class="a-field__hint">
              {{ termRule.remark }}
              <template v-if="termRule.range"> · 取值范围 {{ termRule.range }}</template>
              · 默认 {{ termRule.defaultValue }}
            </div>
          </div>
          <button class="a-btn" :disabled="termRuleSaving" @click="saveTermRule">
            {{ termRuleSaving ? '保存中…' : '保存开关' }}
          </button>
          <div class="a-field__hint">保存后立即生效；关掉只影响之后抽取的词，已待确认的词仍需下方「全部启用」</div>
        </template>
      </div>

      <div class="kb-toolbar">
        <el-input
          v-model="termQuery.keyword"
          placeholder="请输入术语"
          clearable
          style="width: 180px"
          @keyup.enter="searchTerms"
        />
        <el-select v-model="termQuery.source" clearable placeholder="全部来源" style="width: 140px">
          <el-option label="模型抽取" value="llm_extract" />
          <el-option label="人工维护" value="manual" />
        </el-select>
        <el-select v-model="termQuery.enabled" clearable placeholder="全部状态" style="width: 130px">
          <el-option label="已生效" :value="true" />
          <el-option label="未生效" :value="false" />
        </el-select>
        <button class="a-btn" @click="searchTerms">查询</button>
        <span class="a-panel__hint">
          白名单是 chat 入口的防误杀闸门，启停即时生效；唯一生效源是本表，ES 聚合只是候选池
        </span>
        <span class="a-panel__hint" style="margin-left: auto">共 {{ termPage.total }} 条</span>
        <button class="a-btn" @click="openTermCreate">新 增 术 语</button>
      </div>

      <!-- 「待确认」= 模型抽取且未生效；人工停用的词不算——那是人的决定，不能被一键启用推翻 -->
      <div class="kb-pending">
        <span class="a-panel__hint">
          待确认
          <b>{{ termPendingCount }}</b>
          条模型抽取的术语尚未生效
          <template v-if="termPendingCount">（人工停用的词不计入，也不会被批量启用翻回来）</template>
        </span>
        <button class="a-btn" :disabled="!termPendingCount || termBatchEnabling" @click="onBatchEnable">
          {{ termBatchEnabling ? '启用中…' : '全 部 启 用' }}
        </button>
      </div>

      <el-table v-loading="termLoading" :data="terms" empty-text="白名单为空">
        <el-table-column prop="term" label="术语" min-width="160" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ termTypeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="120">
          <template #default="{ row }">
            {{ row.source === 'llm_extract' ? '模型抽取' : '人工维护' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <!-- 两种"未生效"必须分开：同一列混着写，管理员会以为人工停用的词也能被一键启用 -->
            <el-tag v-if="row.enabled" type="success" effect="plain" size="small">已生效</el-tag>
            <el-tag v-else-if="row.source === 'llm_extract'" type="warning" effect="plain" size="small">
              待确认
            </el-tag>
            <el-tag v-else type="info" effect="plain" size="small">已停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link size="small" @click="openTermEdit(row)">编辑</el-button>
            <el-button link size="small" @click="onToggleTerm(row)">
              {{ row.enabled ? '停用' : '确认启用' }}
            </el-button>
            <el-button type="danger" link size="small" @click="onDeleteTerm(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="termPage.current"
        :page-size="termPage.size"
        :page-sizes="PAGE_SIZES"
        :total="termPage.total"
        :layout="PAGE_LAYOUT"
        background
        @current-change="loadTerms"
        @size-change="onTermSize"
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
            :show-file-list="false"
            :accept="ACCEPT"
            :on-change="onFileChange"
            :on-exceed="onFileExceed"
          >
            <!-- 选中后文件卡片**画在拖拽框里**（不是下面另起一列）：
                 文件名超长省略号截断（全名看 title），点 ✕ 移除重选；
                 拖拽框本身仍可点击/拖入——limit=1 时走 onFileExceed 替换逻辑 -->
            <template v-if="uploadFile">
              <div class="a-upload__file" :title="uploadFile.name">
                <el-icon class="a-upload__file-icon"><document /></el-icon>
                <span class="a-upload__file-name">{{ uploadFile.name }}</span>
                <span class="a-upload__file-size">{{ uploadFileSize }}</span>
                <el-icon class="a-upload__file-remove" title="移除，重新选择" @click.stop="removeUploadFile"><close /></el-icon>
              </div>
              <div class="a-upload__swap">再点这里或拖入新文件即可替换</div>
            </template>
            <template v-else>
              <el-icon class="el-icon--upload"><upload-filled /></el-icon>
              <div class="el-upload__text">把文件拖到这里，或<em>点击选择</em></div>
            </template>
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
    <el-dialog v-model="deptEditVisible" :title="deptForm.id ? '编辑科室' : '新增科室'" width="460px">
      <el-form label-width="80px">
        <el-form-item label="科室名">
          <!-- 下拉给常见科室，但**允许手输**（allow-create）：硬编码列表是快捷方式不是白名单，
               做成死列表将来加一个名单外的科室就得改前端 -->
          <el-select
            v-model="deptForm.name"
            filterable
            allow-create
            default-first-option
            placeholder="选择常见科室，或直接输入"
            style="width: 100%"
          >
            <el-option v-for="n in COMMON_DEPTS" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="位置">
          <el-select
            v-model="deptForm.location"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="选择常见位置，或直接输入（选填）"
            style="width: 100%"
          >
            <el-option v-for="n in COMMON_LOCATIONS" :key="n" :label="n" :value="n" />
          </el-select>
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

    <!-- 术语弹窗（新增与编辑共用一份表单状态：标题按是否带 id 切换） -->
    <el-dialog v-model="termEditVisible" :title="termForm.id ? '编辑术语' : '新增术语'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="术语">
          <el-input v-model="termForm.term" placeholder="2–20 个字，不含空格或换行" @keyup.enter="submitTerm" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="termForm.type" style="width: 100%">
            <el-option label="部位" value="part" />
            <el-option label="症状" value="symptom" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="a-field__hint">
        <template v-if="termForm.id">
          保存后判定立即生效；来源与启停状态保持不变——启停请用列表里的按钮，编辑这里不接管。
        </template>
        <template v-else>
          新增即启用，chat 入口判定立即生效。单字会被拒绝——白名单用子串匹配，单字会让几乎所有主诉都被判「含医学术语」。
          同一个词可以同时作为「部位」与「症状」各占一行。
        </template>
      </div>
      <template #footer>
        <el-button @click="termEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="termSaving" @click="submitTerm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 切片抽屉 -->
    <el-drawer v-model="chunksVisible" :title="chunksTitle" size="620px">
      <div v-if="chunksLoading" class="a-empty">加载中…</div>
      <div v-else-if="!chunks.length" class="a-empty">
        {{ chunkPage.total ? '本页没有切片' : '这份文档还没有切片' }}
      </div>
      <div v-else class="kb-chunks">
        <div v-for="c in chunks" :key="c.id" class="kb-chunk">
          <div class="kb-chunk__head">
            <span class="kb-chunk__seq">#{{ c.seq }}</span>
            <span class="a-tag" :class="chunkTagClass(c.chunkType)">
              {{ chunkTypeText(c.chunkType) }}
            </span>
            <span class="kb-chunk__title">{{ c.title || '（无标题）' }}</span>
            <!-- 本标题下第几块：只在正文片上有值（标题片是节名本身、表格片是整块语义单元，都不占号） -->
            <span v-if="c.noInSection" class="kb-chunk__no">
              第 {{ c.noInSection }} 块 / 共 {{ c.sectionTotal }} 块
            </span>
            <span v-if="!isTitleChunk(c)" class="a-panel__hint">
              {{ (c.content || '').length }} 字
            </span>
          </div>
          <!-- 标题片的正文与 title 列**是同一个字符串**，再渲染一遍 body 就是两行一模一样 -->
          <pre v-if="!isTitleChunk(c)" class="kb-chunk__body">{{ c.content }}</pre>
        </div>
      </div>
      <!-- 分页在抽屉底部常驻：一份几百片的文档不该一屏全渲染出来（2026-10-14 起后端按页取） -->
      <el-pagination
        v-if="chunkPage.total > 0"
        v-model:current-page="chunkPage.current"
        :page-size="chunkPage.size"
        :page-sizes="PAGE_SIZES"
        :total="chunkPage.total"
        :layout="PAGE_LAYOUT"
        background
        style="margin-top: 12px"
        @current-change="loadChunks"
        @size-change="onChunkSize"
      />
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Document, UploadFilled } from '@element-plus/icons-vue'
import { PAGE_LAYOUT, PAGE_LAYOUT_NO_TOTAL, PAGE_SIZES, applySizeChange } from '../../utils/pager'
import {
  pageKbDocs, uploadKbDoc, getKbChunks, reprocessKbDoc, deleteKbDoc,
  listKbDepts, updateKbDept, createKbDept, pageKbMappings, pageKbTerms, toggleKbTerm, createKbTerm,
  updateKbTerm, deleteKbTerm, batchEnableKbTerms, getKbParams, saveKbParams
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
  if (t === 'term') {
    loadTermRule()
    loadTerms()
  }
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
const deptPage = reactive({ current: 1, size: 10 })
// 科室做**前端分页**：这份列表还要供上传表单与筛选下拉使用，只能是全量的，
// 后端 /kb/depts 因此不分页（科室本来就少）
const deptRows = computed(() =>
  depts.value.slice((deptPage.current - 1) * deptPage.size, deptPage.current * deptPage.size)
)
const deptEditVisible = ref(false)
const deptSaving = ref(false)
const deptForm = reactive({ id: '', name: '', location: '', intro: '', enabled: true })

// 新增/编辑科室的快捷选项：前端硬编码的常见值，配合 allow-create 仍可手输名单外的
const COMMON_DEPTS = [
  '心血管内科', '呼吸内科', '消化内科', '神经内科', '内分泌科', '肾内科',
  '普外科', '骨科', '泌尿外科', '皮肤科', '眼科', '耳鼻喉科',
  '口腔科', '妇科', '产科', '儿科', '急诊科', '肿瘤科',
  '感染科', '精神心理科', '康复医学科', '中医科'
]
const COMMON_LOCATIONS = [
  '门诊楼 1F 东区', '门诊楼 1F 西区', '门诊楼 2F 东区', '门诊楼 2F 西区',
  '门诊楼 3F 东区', '门诊楼 3F 西区', '门诊楼 4F 东区', '门诊楼 4F 西区',
  '急诊楼 1F', '医技楼 1F'
]

function onDeptSize(size) {
  deptPage.size = size
  deptPage.current = 1
}

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

/** 新增：空表单、默认启用；id 为空时 submitDept 走 create 分支 */
function openDeptCreate() {
  Object.assign(deptForm, { id: '', name: '', location: '', intro: '', enabled: true })
  deptEditVisible.value = true
}

async function submitDept() {
  deptSaving.value = true
  try {
    const payload = {
      name: deptForm.name,
      location: deptForm.location,
      intro: deptForm.intro,
      enabled: deptForm.enabled
    }
    if (deptForm.id) {
      await updateKbDept(deptForm.id, payload)
      deptEditVisible.value = false
      ElMessage.success('科室已保存')
    } else {
      await createKbDept(payload)
      deptEditVisible.value = false
      ElMessage.success(`科室「${deptForm.name}」已创建，挂号页与候选科室清单立即生效`)
      await loadDepts()
      // listAll 按 created_at 升序，新科室排在**最后一页**：翻过去让它露脸，否则像没创建上
      deptPage.current = Math.max(1, Math.ceil(depts.value.length / deptPage.size))
      return
    }
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

/** 切换每页条数：回到第 1 页再拉一次 */
function onDocSize(size) {
  applySizeChange(docPage, size, loadDocs)
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
    // 切分之后还有一步术语抽取（入库即抽取），两者都计入 split 阶段；
    // 仍无百分比——切出多少片是切分的结果，抽取也不是可线性推进的量
    return { text: '正在切分与提取术语', ratio: null }
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

/** 选中文件的大小（画在拖拽框内的文件卡片上）；小于 1MB 用 KB，别显示成"0.0 MB" */
const uploadFileSize = computed(() => {
  const file = uploadFile.value
  if (!file) return ''
  const mb = file.size / 1024 / 1024
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(file.size / 1024))} KB`
})

/** 自己维护的移除（show-file-list 关了，el-upload 的 onRemove 不会再触发） */
function removeUploadFile() {
  uploadFile.value = null
  uploadRef.value?.clearFiles()
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
/** 抽屉当前看的是哪份文档：翻页/切条数要带着它重新请求 */
const chunksDocId = ref('')
const chunkPage = reactive({ current: 1, size: 10, total: 0 })

/** 片类型展示文案：后端给的是枚举编码值（小写），文案只在这里映射一次 */
const CHUNK_TYPE_TEXT = {
  title: '标题',
  text: '正文',
  table: '表格',
  figure: '图表',
  image: '图片',
  formula: '公式',
  code: '代码',
  header: '页眉',
  footer: '页脚',
  unknown: '未识别'
}
// 页眉 / 页脚 / 未识别是**正常不该出现**的类型（needHeaderFooter=false；unknown = 类型表没映射上）：
// 用醒目的珊瑚色 tag，看到即是信号——这比"靠肉眼看内容像不像页眉"可靠得多
const ANOMALY_TYPES = new Set(['header', 'footer', 'unknown'])

function chunkTypeText(type) {
  return CHUNK_TYPE_TEXT[type] || CHUNK_TYPE_TEXT.unknown
}

function chunkTagClass(type) {
  if (!type || type === 'title') return '' // 标题：默认蓝底
  if (type === 'table') return 'a-tag--ok'
  if (ANOMALY_TYPES.has(type)) return 'a-tag--warn'
  return 'a-tag--plain' // 正文 / 图片 / 公式 / 代码：安静的灰底
}

function isTitleChunk(c) {
  return (c.chunkType || '') === 'title'
}

async function loadChunks(pageNo = chunkPage.current) {
  chunksLoading.value = true
  try {
    const data = await getKbChunks(chunksDocId.value, pageNo, chunkPage.size)
    chunks.value = data.records || []
    chunkPage.total = data.total || 0
    chunkPage.current = pageNo
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    chunksLoading.value = false
  }
}

function onChunkSize(size) {
  // 统一下沉到 utils/pager：先改 current 再请求，否则 el-pagination 会连发两次（见那里的注释）
  applySizeChange(chunkPage, size, () => loadChunks(1))
}

async function openChunks(row) {
  chunksDocId.value = row.id
  chunksTitle.value = `${row.title} · 切片`
  chunks.value = []
  chunkPage.current = 1
  chunkPage.total = 0
  chunksVisible.value = true
  await loadChunks(1)
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

function onMapSize(size) {
  applySizeChange(mapPage, size, loadMappings)
}

function sourceLabel(source) {
  if (source === 'feedback') return '回流审核'
  if (source === 'manual') return '人工'
  return '初始化'
}

/** 术语类型编码 → 中文（表格、新增 toast、编辑 toast 共用这一处，避免三份拷贝迟早对不上） */
function termTypeLabel(type) {
  return type === 'part' ? '部位' : '症状'
}

// —— 术语白名单 ——
const terms = ref([])
const termLoading = ref(false)
const termQuery = reactive({ keyword: '', enabled: '', source: '' })
const termPage = reactive({ current: 1, size: 10, total: 0 })
/** 「待确认」条数：模型抽取且未生效。**与筛选无关**——它答的是"这一库里积压了多少待确认"，
 *  所以固定按最严口径另查一次，而不是用当前列表的 total（那样切个筛选数字就变了，会骗人） */
const termPendingCount = ref(0)
const termBatchEnabling = ref(false)

async function loadTerms() {
  termLoading.value = true
  try {
    const data = await pageKbTerms({
      page: termPage.current,
      size: termPage.size,
      keyword: termQuery.keyword || undefined,
      enabled: termQuery.enabled === '' ? undefined : termQuery.enabled,
      source: termQuery.source || undefined
    })
    terms.value = data.records || []
    termPage.total = data.total || 0
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    termLoading.value = false
  }
  // 待确认计数与列表同源但不同口径：固定 source=llm_extract + enabled=false 查一次 total。
  // size=1 就够——只要 total，但取不到时（接口失败）不要把它显示成 0，那会让人以为没有待确认
  try {
    const pending = await pageKbTerms({
      page: 1, size: 1, source: 'llm_extract', enabled: false
    })
    termPendingCount.value = pending.total || 0
  } catch {
    // 计数失败不影响列表本身：按钮靠 termPendingCount 为 0 禁用，此刻它保持上一次的值，
    // 比"静默变成 0 然后允许点空列表"要好
  }
}

function searchTerms() {
  termPage.current = 1
  loadTerms()
}

function onTermSize(size) {
  applySizeChange(termPage, size, loadTerms)
}

async function onToggleTerm(row) {
  try {
    const updated = await toggleKbTerm(row.id)
    row.enabled = updated.enabled
    ElMessage.success(updated.enabled ? `「${row.term}」已启用，入口校验即时生效` : `「${row.term}」已停用`)
    // 停用一条 llm_extract 的词会让它立刻变成「待确认」（口径 source=llm_extract && enabled=0），
    // 重拉列表顺带重算待确认计数——否则徽标与「全部启用」按钮停在翻转前的状态
    await loadTerms()
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function onDeleteTerm(row) {
  try {
    // 三件事缺一不可：说清"不再是入口判定的词"、说清"旧记录不受影响"、给出恢复路径。
    // 不能写成"永久删除"——它是逻辑删除，同词新增就能复活（见数据库设计.md §0.1）
    await ElMessageBox.confirm(
      `删除「${row.term}」后它不再参与入口判定，管理端列表里也不再显示。` +
        '历史导诊记录不受影响；日后需要它时，用同一个词重新新增即可恢复。',
      '删除术语',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteKbTerm(row.id)
    // 删掉的是本页最后一条且不在第 1 页 → 先回退一页，否则会停在一页空白上
    if (terms.value.length === 1 && termPage.current > 1) {
      termPage.current -= 1
    }
    await loadTerms()
    ElMessage.success(`术语「${row.term}」已删除，入口校验即时生效`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

/**
 * 「全部启用」：先把待确认的 id 清单拿全（可能不止一页），再一次性提交。
 *
 * <p>**为什么先取 id 再发请求**：接口收 id 清单而不是"全部"——一个不可撤销的大动作不该有
 * 无参数的形态，清单同时也让 confirm 里能写出准确的条数（工单验收第 10 条）。
 *
 * <p>成功后**清空筛选并回第 1 页**：刚启用的词不在当前筛选条件下的概率很高
 * （比如正按"未生效"筛），留着筛选会让管理员看不到任何变化、以为点了没生效。
 */
async function onBatchEnable() {
  const ids = []
  for (let page = 1; ; page++) {
    const data = await pageKbTerms({
      page, size: 100, source: 'llm_extract', enabled: false
    })
    ids.push(...(data.records || []).map((t) => t.id))
    if (ids.length >= (data.total || 0) || !(data.records || []).length) break
  }
  if (!ids.length) {
    ElMessage.warning('没有待确认的术语')
    await loadTerms()
    return
  }
  try {
    // 说清条数与后果：启用不是破坏性操作，但候选池大时会显著改变患者端行为
    await ElMessageBox.confirm(
      `将启用 ${ids.length} 条模型抽取的术语，它们会立即参与入口判定。` +
        '之后新患者的主诉只要含其中任一词，就不再被模板追问。',
      '全部启用待确认术语',
      { confirmButtonText: '全部启用', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  termBatchEnabling.value = true
  try {
    const count = await batchEnableKbTerms(ids)
    // 清筛选 + 回第 1 页：否则刚启用的词可能落在筛选外，看不到变化
    Object.assign(termQuery, { keyword: '', enabled: '', source: '' })
    termPage.current = 1
    await loadTerms()
    ElMessage.success(`已启用 ${count} 条术语，入口校验即时生效`)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    termBatchEnabling.value = false
  }
}

// —— 术语人工审核开关（与词表同页：抽出了什么 / 要不要我确认，是一件事的两半）——
const termRule = ref(null)
const termRuleLoading = ref(false)
const termRuleSaving = ref(false)

async function loadTermRule() {
  termRuleLoading.value = true
  try {
    const params = await getKbParams()
    // 只认术语审核开关这一项，其余（切分三档）当前页面没有端点可改，不渲染
    termRule.value = params.find((p) => p.key === 'term.manual.review') || null
  } catch (e) {
    termRule.value = null
    ElMessage.error(errText(e))
  } finally {
    termRuleLoading.value = false
  }
}

async function saveTermRule() {
  if (!termRule.value) return
  termRuleSaving.value = true
  try {
    await saveKbParams([{ key: termRule.value.key, value: String(termRule.value.value) }])
    ElMessage.success('术语审核开关已保存，立即生效')
    termRule.value = await getKbParams().then((p) => p.find((x) => x.key === 'term.manual.review'))
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    termRuleSaving.value = false
  }
}

const termEditVisible = ref(false)
const termSaving = ref(false)
/** 新增与编辑共用：带 id 即编辑（预填当前行），不带即新增 */
const termForm = reactive({ id: '', term: '', type: 'symptom' })

/** 新增：空表单。类型默认「症状」——真实主诉里补得最多的是口语症状词 */
function openTermCreate() {
  Object.assign(termForm, { id: '', term: '', type: 'symptom' })
  termEditVisible.value = true
}

/** 编辑：预填当前行的词面与类型（启停不在这个表单里） */
function openTermEdit(row) {
  Object.assign(termForm, { id: row.id, term: row.term, type: row.type })
  termEditVisible.value = true
}

async function submitTerm() {
  termSaving.value = true
  try {
    if (termForm.id) {
      const updated = await updateKbTerm(termForm.id, { term: termForm.term, type: termForm.type })
      termEditVisible.value = false
      // 编辑是就地修正：**不动筛选、不回第 1 页**——管理员看着的那一行还在原处
      await loadTerms()
      const typeLabel = termTypeLabel(updated.type)
      ElMessage.success(`术语已改为「${updated.term}」（${typeLabel}），入口校验即时生效`)
    } else {
      const created = await createKbTerm({ term: termForm.term, type: termForm.type })
      termEditVisible.value = false
      // 清空筛选并回到第 1 页：否则新词可能落在筛选条件外，管理员看不到自己刚加的东西、以为没成功
      Object.assign(termQuery, { keyword: '', enabled: '', source: '' })
      termPage.current = 1
      await loadTerms()
      const typeLabel = termTypeLabel(created.type)
      ElMessage.success(`术语「${created.term}」（${typeLabel}）已新增，入口校验即时生效`)
    }
  } catch (e) {
    // 校验/判重的规则在 kb：直接用后端文案，前端不另拼一套判断
    ElMessage.error(errText(e))
  } finally {
    termSaving.value = false
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
/* 「待确认 N · 全部启用」：横条跟着工具栏同一套间距，按钮贴右端 */
.kb-pending {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: var(--wash);
  border-radius: 8px;
}
.kb-pending .a-panel__hint b {
  color: var(--coral);
}
.kb-pending button {
  margin-left: auto;
}
.kb-fail {
  color: var(--err);
}
/* 选中文件画进拖拽框：长文件名省略号截断（全名 title），✕ 移除重选 */
/* EP 的 .el-upload 是 inline-flex 收缩布局、无宽度约束——不钉死它，拖拽框和提示
   会按内容撑到比弹窗还宽（真踩过：长文件名把卡片顶出边框、大小被挤出屏幕） */
.a-upload { display: block; width: 100%; }
.a-upload :deep(.el-upload) {
  display: block;
  width: 100%;
}
.a-upload :deep(.el-upload-dragger) {
  box-sizing: border-box;
  width: 100%;
  padding: 28px 16px;
}
.a-upload__file {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 0 12px;
}
.a-upload__file-icon { flex: none; color: var(--el-color-primary); }
.a-upload__file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.a-upload__file-size { flex: none; font-size: 11px; color: var(--el-text-color-secondary); }
.a-upload__file-remove {
  flex: none;
  cursor: pointer;
  color: var(--el-text-color-secondary);
}
.a-upload__file-remove:hover { color: var(--el-color-danger); }
.a-upload__swap { font-size: 11px; color: var(--el-text-color-secondary); margin-top: 8px; }
/* 切片卡片：**沿用原有样式**（2026-10-01 曾擅自改成"大纲+左竖条"被否——
   用户只要换块内的文字展示，不是换整套布局）。以下只保留块内新增的两处：类型 tag 与块号 */
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
.kb-chunk__no {
  font-size: 11px;
  color: var(--ink-2);
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
