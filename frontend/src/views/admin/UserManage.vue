<template>
  <section class="a-panel">
    <div class="a-tabs" style="margin-bottom: 16px">
      <button
        class="a-tab"
        :class="{ 'a-tab--on': tab === 'user' }"
        @click="switchTab('user')"
      >
        账号管理
      </button>
      <button
        class="a-tab"
        :class="{ 'a-tab--on': tab === 'word' }"
        @click="switchTab('word')"
      >
        敏感词库
      </button>

      <!-- 敏感词库顶部操作 -->
      <div v-if="tab === 'word'" class="a-table__ops" style="margin-left: auto; align-self: center">
        <el-button type="primary" size="small" @click="openAdd">添加词条</el-button>
        <el-button size="small" @click="openImport">批量导入</el-button>
        <el-button size="small" @click="exportWords">导出备份</el-button>
      </div>
    </div>

    <!-- 账号管理 -->
    <template v-if="tab === 'user'">
      <div class="a-table__ops" style="margin-bottom: 12px">
        <el-input
          v-model="userQuery"
          placeholder="搜索用户名 / 昵称"
          clearable
          style="width: 240px"
          @keyup.enter="loadUsers(1)"
          @clear="loadUsers(1)"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" size="small" @click="loadUsers(1)">搜索</el-button>
      </div>

      <p class="um-hint">{{ ruleHint }}</p>

      <el-table :data="users" v-loading="userLoading" empty-text="无匹配账号">
        <el-table-column prop="username" label="用户名" min-width="110" />
        <el-table-column prop="nickname" label="昵称" min-width="90" />
        <el-table-column label="角色" width="80">
          <template #default="{ row }">
            <el-tag :type="row.role === 'admin' ? 'primary' : 'info'" effect="plain" size="small">
              {{ row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'normal' ? 'success' : 'warning'" size="small">
              {{ row.status === 'normal' ? '正常' : '已封禁' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="`近 ${hitWindow} 分钟触发`" width="160">
          <template #default="{ row }">
            <template v-if="row.hits">
              <el-tag :type="row.hits.bannedHits ? 'danger' : 'info'" effect="plain" size="small">
                禁止 {{ row.hits.bannedHits }}
              </el-tag>
              <el-tag :type="row.hits.watchHits ? 'warning' : 'info'" effect="plain" size="small">
                观察 {{ row.hits.watchHits }}
              </el-tag>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="处置" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.muteUntil" type="danger" size="small">
              禁言至 {{ fmtTime(row.muteUntil) }}
            </el-tag>
            <el-tag v-else-if="row.hits && row.hits.warnCount" type="warning" effect="plain" size="small">
              已警告 {{ row.hits.warnCount }} 次
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="105">
          <template #default="{ row }">{{ fmtDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link size="small" @click="openDetail(row)">明细</el-button>
            <el-button
              v-if="row.muteUntil"
              link
              type="warning"
              size="small"
              @click="unmute(row)"
            >
              解除禁言
            </el-button>
            <el-button
              :type="row.status === 'normal' ? 'danger' : 'primary'"
              link
              size="small"
              @click="toggleBan(row)"
            >
              {{ row.status === 'normal' ? '封禁' : '解封' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="userPage.current"
        :page-size="userPage.size"
        :total="userPage.total"
        layout="total, prev, pager, next"
        background
        @current-change="loadUsers"
      />
    </template>

    <!-- 敏感词库 -->
    <template v-else>
      <!-- 处置规则：与词库同页，因为"哪些词算违规"和"触发多少次会怎样"是一件事的两半 -->
      <div class="a-panel" style="padding: 0 0 14px; border-bottom: 1px solid var(--line); margin-bottom: 14px">
        <div class="a-panel__head">
          <span class="a-panel__title">处置规则</span>
          <span class="a-panel__hint">滑动窗口内命中词次达阈值即处置，存 sys_config</span>
        </div>
        <div v-if="ruleLoading" class="a-empty">加载中…</div>
        <div v-else-if="!rules.length" class="a-empty">处置规则加载失败，可用下方默认值继续维护词库</div>
        <template v-else>
          <div class="um-rules">
            <div v-for="r in rules" :key="r.key" class="a-field">
              <label class="a-field__label">{{ r.label }}</label>
              <input v-model="r.value" class="a-field__input" type="number" step="1" />
              <div class="a-field__hint">
                {{ r.remark }} · 范围 {{ r.range }} · 默认 {{ r.defaultValue }}
              </div>
            </div>
          </div>
          <button class="a-btn" :disabled="ruleSaving" @click="saveRules">
            {{ ruleSaving ? '保存中…' : '保存规则' }}
          </button>
          <div class="a-field__hint">
            保存后立即生效；禁言阈值必须大于警告阈值，否则警告永远不会出现（后端会整批拒绝）
          </div>
        </template>
      </div>

      <el-table :data="words" v-loading="wordLoading" empty-text="词库为空">
        <el-table-column prop="word" label="词" min-width="140" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.type === 'banned' ? 'warning' : 'info'" size="small">
              {{ row.type === 'banned' ? '禁止词' : '观察词' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="hitCount" label="累计命中" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain" size="small">
              {{ row.enabled ? '启用中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="180">
          <template #default="{ row }">
            <el-button link size="small" @click="onToggle(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="row.type === 'watch'" type="primary" link size="small" @click="onConvert(row)">
              转禁止词
            </el-button>
            <el-button type="danger" link size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="wordPage.current"
        :page-size="wordPage.size"
        :total="wordPage.total"
        layout="total, prev, pager, next"
        background
        @current-change="loadWords"
      />
    </template>

    <!-- 添加词条弹窗 -->
    <el-dialog v-model="addVisible" title="添加词条" width="420px">
      <el-form label-width="72px">
        <el-form-item label="敏感词">
          <el-input v-model="wordForm.word" placeholder="输入敏感词" @keyup.enter="submitAdd" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="wordForm.type" style="width: 100%">
            <el-option label="禁止词（命中即拦截）" value="banned" />
            <el-option label="观察词（命中只记录）" value="watch" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAdd">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入弹窗 -->
    <el-dialog v-model="importVisible" title="批量导入" width="480px">
      <p style="font-size: 12.5px; color: var(--ink-2); margin-bottom: 10px">
        一行一词，自动去空行、去重、跳过已存在词条。
      </p>
      <el-input
        v-model="importText"
        type="textarea"
        :rows="7"
        placeholder="粘贴 txt 内容，一行一词"
      />
      <el-form label-width="72px" style="margin-top: 12px">
        <el-form-item label="导入类型">
          <el-select v-model="importType" style="width: 100%">
            <el-option label="禁止词（命中即拦截）" value="banned" />
            <el-option label="观察词（命中只记录）" value="watch" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 违规明细弹窗：窗口内触发了哪些词（各几次）+ 被处置过什么 -->
    <el-dialog v-model="detailVisible" :title="detailTitle" width="680px">
      <div v-if="detailLoading" class="a-empty">加载中…</div>
      <template v-else-if="detail">
        <p class="um-hint">
          统计窗口：最近 {{ detail.windowMinutes }} 分钟 ·
          禁止词 {{ detail.bannedHits }} 次 · 观察词 {{ detail.watchHits }} 次
          <template v-if="detail.muteUntil"> · 禁言至 {{ fmtDateTime(detail.muteUntil) }}</template>
        </p>

        <div class="um-sub">触发的词</div>
        <el-table :data="detail.words" size="small" empty-text="窗口内没有触发记录">
          <el-table-column prop="word" label="词" min-width="140" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag :type="row.type === 'banned' ? 'warning' : 'info'" size="small">
                {{ row.type === 'banned' ? '禁止词' : '观察词' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="count" label="词次" width="80" />
          <el-table-column label="最近命中" width="150">
            <template #default="{ row }">{{ fmtDateTime(row.lastAt) }}</template>
          </el-table-column>
        </el-table>

        <div class="um-sub">处置记录</div>
        <el-table :data="detail.records" size="small" empty-text="窗口内没有处置记录">
          <el-table-column label="处置" width="90">
            <template #default="{ row }">
              <el-tag :type="row.level === 'mute' ? 'danger' : 'warning'" size="small">
                {{ row.level === 'mute' ? '禁言' : '警告' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="触发类型" width="90">
            <template #default="{ row }">{{ row.hitType === 'banned' ? '禁止词' : '观察词' }}</template>
          </el-table-column>
          <el-table-column label="命中/阈值" width="110">
            <template #default="{ row }">{{ row.hitCount }} / {{ row.threshold }}</template>
          </el-table-column>
          <el-table-column label="窗口" width="80">
            <template #default="{ row }">{{ row.windowMinutes }} 分钟</template>
          </el-table-column>
          <el-table-column label="时间" min-width="150">
            <template #default="{ row }">{{ fmtDateTime(row.occurredAt) }}</template>
          </el-table-column>
        </el-table>
      </template>
      <div v-else class="a-empty">加载失败</div>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  pageUsers, banUser, unbanUser, unmuteUser, getUserViolations,
  pageWords, addWord, importWords, toggleWord, convertWordToBanned, deleteWord,
  getSensitiveRules, saveSensitiveRules
} from '../../api/admin'

const tab = ref('user')

function switchTab(t) {
  tab.value = t
}

function fmtDate(s) {
  return s ? String(s).slice(0, 10) : ''
}

// 后端下发的是 ISO 本地时间（2026-09-20T13:05:00），去 T 截到分钟即可，不引日期库
function fmtDateTime(s) {
  return s ? String(s).replace('T', ' ').slice(0, 16) : ''
}

function fmtTime(s) {
  return s ? String(s).replace('T', ' ').slice(11, 16) : ''
}

function errText(e) {
  return e?.message || '操作失败，请稍后重试'
}

// —— 处置规则（sys_config，敏感词库 tab）——
const rules = ref([])
const ruleLoading = ref(false)
const ruleSaving = ref(false)
const hitWindow = ref(60)

function ruleValue(key, fallback) {
  const rule = rules.value.find((r) => r.key === key)
  return rule ? Number(rule.value) : fallback
}

async function loadRules() {
  ruleLoading.value = true
  try {
    rules.value = await getSensitiveRules()
    hitWindow.value = ruleValue('sensitive.window.minutes', 60)
  } catch (e) {
    // 规则拉不到不该阻断用户管理：词库与账号管理仍然可用，只是提示条退回默认口径
    rules.value = []
  } finally {
    ruleLoading.value = false
  }
}

// 口径提示跟着配置走：写死数字的话，改了阈值页面就在撒谎
const ruleHint = computed(() => {
  const w = ruleValue('sensitive.window.minutes', hitWindow.value)
  if (!rules.value.length) {
    return `触发次数按最近 ${w} 分钟滑动窗口统计`
  }
  return `触发次数按最近 ${w} 分钟滑动窗口统计：禁止词满 `
    + `${ruleValue('sensitive.banned.warn.count', 10)} 次警告、`
    + `${ruleValue('sensitive.banned.mute.count', 30)} 次禁言；观察词满 `
    + `${ruleValue('sensitive.watch.warn.count', 25)} 次警告（观察词不禁言）`
})

async function saveRules() {
  ruleSaving.value = true
  try {
    await saveSensitiveRules(rules.value.map((r) => ({ key: r.key, value: String(r.value) })))
    ElMessage.success('处置规则已保存，即时生效')
    await loadRules()
    // 窗口与阈值变了，行上的统计口径跟着变：重拉一次当前页
    loadUsers(userPage.current)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    ruleSaving.value = false
  }
}

// —— 账号管理 ——
const users = ref([])
const userLoading = ref(false)
const userPage = reactive({ current: 1, size: 10, total: 0 })
const userQuery = ref('')

async function loadUsers(pageNo = userPage.current) {
  userLoading.value = true
  try {
    const data = await pageUsers({ current: pageNo, size: userPage.size, keyword: userQuery.value || undefined })
    users.value = data.records
    userPage.current = data.current
    userPage.total = data.total
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    userLoading.value = false
  }
}

async function toggleBan(u) {
  const banning = u.status === 'normal'
  try {
    if (banning) {
      await ElMessageBox.confirm(`封禁后该账号将无法登录，确认封禁「${u.username}」？`, '封禁账号', {
        confirmButtonText: '封禁',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await banUser(u.id)
      u.status = 'banned'
      ElMessage.success(`已封禁 ${u.username}（登录时即被拒绝）`)
    } else {
      await unbanUser(u.id)
      u.status = 'normal'
      ElMessage.success(`已解封 ${u.username}`)
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(errText(e))
  }
}

// 解除禁言：禁言本会到期自动解除，这里用于需要立即放行的场景
async function unmute(u) {
  try {
    await ElMessageBox.confirm(
      `立即解除「${u.username}」的禁言？解除后即可继续对话（禁言到期本会自动解除）。`,
      '解除禁言',
      { confirmButtonText: '解除', cancelButtonText: '取消', type: 'warning' }
    )
    const muted = await unmuteUser(u.id)
    u.muteUntil = null
    ElMessage.success(muted ? '已解除禁言' : '该用户当前不在禁言中')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(errText(e))
  }
}

// —— 违规明细 ——
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailUser = ref(null)
const detailTitle = computed(() => (detailUser.value ? `${detailUser.value.username} · 违规明细` : '违规明细'))

async function openDetail(u) {
  detailUser.value = u
  detail.value = null
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getUserViolations(u.id)
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    detailLoading.value = false
  }
}

// —— 敏感词库 ——
const words = ref([])
const wordLoading = ref(false)
const wordPage = reactive({ current: 1, size: 10, total: 0 })
const addVisible = ref(false)
const importVisible = ref(false)
const wordForm = reactive({ word: '', type: 'banned' })
const importText = ref('')
const importType = ref('banned')

function openAdd() {
  wordForm.word = ''
  wordForm.type = 'banned'
  addVisible.value = true
}

function openImport() {
  importText.value = ''
  importType.value = 'banned'
  importVisible.value = true
}

async function loadWords(pageNo = wordPage.current) {
  wordLoading.value = true
  try {
    const data = await pageWords({ current: pageNo, size: wordPage.size })
    words.value = data.records
    wordPage.current = data.current
    wordPage.total = data.total
  } catch (e) {
    ElMessage.error(errText(e))
  } finally {
    wordLoading.value = false
  }
}

async function submitAdd() {
  if (!wordForm.word.trim()) {
    ElMessage.warning('请输入敏感词')
    return
  }
  try {
    await addWord({ word: wordForm.word.trim(), type: wordForm.type })
    ElMessage.success('词条已添加')
    addVisible.value = false
    loadWords(1)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function submitImport() {
  if (!importText.value.trim()) {
    ElMessage.warning('请粘贴要导入的内容')
    return
  }
  try {
    const r = await importWords({ text: importText.value, type: importType.value })
    ElMessage.success(`导入 ${r.imported} 条，跳过重复 ${r.skipped} 条`)
    importVisible.value = false
    loadWords(1)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function onToggle(w) {
  try {
    await toggleWord(w.id)
    w.enabled = w.enabled ? 0 : 1
    ElMessage.success(w.enabled ? '已启用' : '已停用（停用词不参与入口校验）')
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function onConvert(w) {
  try {
    await convertWordToBanned(w.id)
    w.type = 'banned'
    ElMessage.success('已转为禁止词')
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

async function onDelete(w) {
  try {
    await ElMessageBox.confirm(`删除敏感词「${w.word}」？`, '删除词条', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteWord(w.id)
    ElMessage.success('已删除')
    loadWords(wordPage.current)
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(errText(e))
  }
}

// 导出备份：拉全量生成 txt 下载（总体架构 6.2 备份要求）
async function exportWords() {
  try {
    const data = await pageWords({ current: 1, size: 9999 })
    const lines = data.records.map((w) => w.word).join('\n')
    const blob = new Blob([lines], { type: 'text/plain;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'sensitive-words-backup.txt'
    a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success(`已导出 ${data.records.length} 条`)
  } catch (e) {
    ElMessage.error(errText(e))
  }
}

onMounted(() => {
  loadRules()
  loadUsers(1)
  loadWords(1)
})
</script>

<style scoped>
.um-hint {
  font-size: 12.5px;
  color: var(--ink-2);
  margin: 0 0 10px;
  line-height: 1.6;
}

.um-rules {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 4px 24px;
}

.um-sub {
  font-size: 12.5px;
  color: var(--ink-2);
  margin: 14px 0 6px;
}
</style>
