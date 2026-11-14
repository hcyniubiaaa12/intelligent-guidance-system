<template>
  <section class="p-mem" aria-label="我记住的">
    <div class="p-mem__head">
      <div class="p-mem__headtext">
        <span class="p-mem__title">我记住的</span>
        <span class="p-mem__hint">自动记下的，每条都带着您的原话</span>
      </div>

      <!-- 手写开关（患者端无组件库）：role=switch + aria-checked 是读屏与键盘的契约。
           关闭只停抽取与注入，旧记忆仍在下面可见并标「已暂停」。 -->
      <button
        type="button"
        class="p-mem__switch"
        role="switch"
        :aria-checked="memoryEnabled"
        :disabled="state !== 'ready' || toggling || busy"
        @click="toggleEnabled"
      >
        <span class="p-mem__switch-track" aria-hidden="true">
          <span class="p-mem__switch-knob"></span>
        </span>
        <span class="p-mem__switch-label">自动记忆</span>
      </button>
    </div>

    <!-- 三态：加载中 / 取回 / 失败。失败给「重试」而不是留一片空白——
         「取不到」与「没有」是两件事，写成一个字都看不见会让人以为记忆丢了。 -->
    <p v-if="state === 'loading'" class="p-mem__state">正在加载…</p>

    <div v-else-if="state === 'failed'" class="p-mem__failure">
      <p class="p-mem__state">记忆加载失败，请重试</p>
      <button type="button" class="p-mem__retry" @click="load">重试</button>
    </div>

    <template v-else>
      <p v-if="!memoryEnabled" class="p-mem__paused">
        自动记忆已暂停——旧记忆还留着，只是暂时不参与回答；您的健康档案不受影响。
      </p>

      <p v-if="actionError" class="p-mem__err" role="alert">{{ actionError }}</p>

      <p v-if="isEmpty" class="p-mem__empty">
        还没有记住什么。聊几句之后，我会把您的偏好和长期情况记在这里。
      </p>

      <template v-else>
        <div v-for="group in groups" :key="group.key" class="p-mem__group">
          <div class="p-mem__ghead">
            <span class="p-mem__glabel">{{ group.label }}</span>
            <span class="p-mem__quota">{{ group.items.length }} / {{ group.quotaMax }}</span>
          </div>

          <article v-for="item in group.items" :key="item.id" class="p-mem__item">
            <!-- 编辑态：只改值。键、原话出处、观察时间都由后端保留，页面照旧显示。 -->
            <div v-if="editingId === item.id" class="p-mem__edit">
              <label class="p-mem__key" :for="`mem-edit-${item.id}`">{{ item.key }}</label>
              <input
                :id="`mem-edit-${item.id}`"
                ref="editInput"
                v-model="editingValue"
                class="p-mem__input"
                type="text"
                maxlength="60"
                :disabled="busy"
                @keydown.enter.prevent="saveEdit(item)"
                @keydown.esc="cancelEdit"
              />
              <div class="p-mem__editfoot">
                <span class="p-mem__counter">{{ trimmedEdit.length }} / 60</span>
                <div class="p-mem__actions">
                  <button
                    type="button"
                    class="p-mem__act p-mem__act--primary"
                    :disabled="!canSaveEdit"
                    @click="saveEdit(item)"
                  >{{ busy ? '保存中…' : '保存' }}</button>
                  <button type="button" class="p-mem__act" :disabled="busy" @click="cancelEdit">取消</button>
                </div>
              </div>
            </div>

            <template v-else>
              <p class="p-mem__kv">
                <span class="p-mem__key">{{ item.key }}</span>：<span class="p-mem__val">{{ item.value }}</span>
                <span v-if="!memoryEnabled" class="p-mem__tag">已暂停 · 不再影响回答</span>
              </p>
              <p v-if="item.sourceQuote" class="p-mem__quote">{{ item.sourceQuote }}</p>
              <p class="p-mem__time">{{ formatTime(item.observedAt) }}</p>

              <!-- 删除二次确认做在行内（两段式），不弹原生 confirm：
                   原生弹窗与本方案的浮层语言是两套东西，且会遮住上下文。 -->
              <div v-if="confirmDeleteId === item.id" class="p-mem__confirm">
                <p class="p-mem__confirm-text">删除后这条不再影响回答。</p>
                <div class="p-mem__actions">
                  <button
                    type="button"
                    class="p-mem__act p-mem__act--danger"
                    :disabled="busy"
                    @click="confirmDelete(item)"
                  >{{ busy ? '删除中…' : '确认删除' }}</button>
                  <button type="button" class="p-mem__act" :disabled="busy" @click="cancelDelete">取消</button>
                </div>
              </div>

              <div v-else class="p-mem__actions">
                <button type="button" class="p-mem__act" :disabled="busy" @click="startEdit(item)">修改</button>
                <button
                  type="button"
                  class="p-mem__act p-mem__act--danger"
                  :disabled="busy"
                  @click="askDelete(item)"
                >删除</button>
              </div>
            </template>
          </article>
        </div>

        <div class="p-mem__foot">
          <template v-if="confirmClear">
            <div class="p-mem__confirm p-mem__confirm--clear">
              <p class="p-mem__confirm-text">
                清空后这 {{ totalCount }} 条记忆都不再影响回答；您的健康档案不受影响。
              </p>
              <div class="p-mem__actions">
                <button
                  type="button"
                  class="p-mem__act p-mem__act--danger"
                  :disabled="busy"
                  @click="confirmClearAll"
                >{{ busy ? '清空中…' : '确定清空全部' }}</button>
                <button type="button" class="p-mem__act" :disabled="busy" @click="confirmClear = false">取消</button>
              </div>
            </div>
          </template>
          <button v-else type="button" class="p-mem__clear" :disabled="busy" @click="askClear">
            清空全部记忆
          </button>
        </div>
      </template>
    </template>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  clearMemories,
  deleteMemory,
  getMemory,
  setMemoryEnabled,
  updateMemory
} from '../../api/memory'

/**
 * 「我记住的」：长期记忆控制面（偏好 + 长期背景）。
 *
 * 患者对「系统记住了什么」有最终决定权：改值、删单条、一键清空、关自动记忆。
 * - 编辑只改值；出处与观察时间由后端保留（页面不提供改它们，因为那是审计事实）。
 * - 删除与清空都走行内两段式确认，文案说清「不再影响回答」与「档案不受影响」。
 * - 任何写操作成功后都重拉 `GET /api/memory`，不缓存——记忆可能刚被抽取写入。
 * - 关闭开关后列表照常显示（每条标「已暂停」），因为关的是抽取与注入，不是删除。
 *
 * 三态是硬要求：loading / ready / failed。失败给「重试」，绝不把「取不到」渲染成空态
 * ——那会让患者以为系统把他的记忆弄丢了。
 */
const state = ref('loading')
const memoryEnabled = ref(true)
const preferences = ref([])
const backgrounds = ref([])
// 上限来自后端受管参数；这里的默认值只在响应到达前兜底
const limits = ref({ preferenceMax: 5, backgroundMax: 10 })

const toggling = ref(false)
// 任一写操作进行中：串行执行，避免两次点击互相覆盖
const busy = ref(false)
const actionError = ref('')

const editingId = ref('')
const editingValue = ref('')
const editInput = ref(null)
const confirmDeleteId = ref('')
const confirmClear = ref(false)

const isEmpty = computed(() => preferences.value.length === 0 && backgrounds.value.length === 0)
const totalCount = computed(() => preferences.value.length + backgrounds.value.length)
const trimmedEdit = computed(() => editingValue.value.trim())
const canSaveEdit = computed(() => trimmedEdit.value.length > 0 && !busy.value)

const groups = computed(() => {
  const list = []
  if (preferences.value.length) {
    list.push({
      key: 'preference',
      label: '偏好',
      quotaMax: limits.value.preferenceMax,
      items: preferences.value
    })
  }
  if (backgrounds.value.length) {
    list.push({
      key: 'background',
      label: '长期背景',
      quotaMax: limits.value.backgroundMax,
      items: backgrounds.value
    })
  }
  return list
})

onMounted(load)

async function load() {
  state.value = 'loading'
  actionError.value = ''
  try {
    await fetchMemory()
    state.value = 'ready'
  } catch (e) {
    console.error('[memory] 记忆加载失败', e)
    state.value = 'failed'
  }
}

async function fetchMemory() {
  const data = await getMemory()
  memoryEnabled.value = data.memoryEnabled !== false
  preferences.value = data.preferences || []
  backgrounds.value = data.backgrounds || []
  limits.value = {
    preferenceMax: data.limits?.preferenceMax ?? 5,
    backgroundMax: data.limits?.backgroundMax ?? 10
  }
}

/**
 * 写操作成功后的重拉。**与写操作分开 catch**：写已经生效，刷新失败不能报成
 * 「没保存成功」（患者会重复操作一次）。刷新失败就退回失败态，让患者点「重试」。
 */
async function reloadAfterWrite() {
  try {
    await fetchMemory()
  } catch (e) {
    console.error('[memory] 写入后刷新失败', e)
    state.value = 'failed'
  }
}

function messageOf(e, fallback) {
  return e?.message || fallback
}

async function toggleEnabled() {
  if (toggling.value || busy.value) return
  const next = !memoryEnabled.value
  toggling.value = true
  actionError.value = ''
  // 先给出即时反馈；接口失败再回滚——开关是二值，回滚不会让人误解成第三个状态
  memoryEnabled.value = next
  try {
    await setMemoryEnabled(next)
    await reloadAfterWrite()
  } catch (e) {
    memoryEnabled.value = !next
    actionError.value = messageOf(e, '自动记忆开关没保存成功，请重试')
  } finally {
    toggling.value = false
  }
}

function startEdit(item) {
  if (busy.value) return
  confirmDeleteId.value = ''
  confirmClear.value = false
  actionError.value = ''
  editingId.value = item.id
  editingValue.value = item.value || ''
  nextTick(() => editInput.value?.focus())
}

function cancelEdit() {
  editingId.value = ''
  editingValue.value = ''
}

async function saveEdit(item) {
  if (!canSaveEdit.value) return
  busy.value = true
  actionError.value = ''
  try {
    await updateMemory(item.id, trimmedEdit.value)
    editingId.value = ''
    editingValue.value = ''
    await reloadAfterWrite()
  } catch (e) {
    actionError.value = messageOf(e, '修改没保存成功，请重试')
  } finally {
    busy.value = false
  }
}

function askDelete(item) {
  if (busy.value) return
  cancelEdit()
  confirmClear.value = false
  actionError.value = ''
  confirmDeleteId.value = item.id
}

function cancelDelete() {
  confirmDeleteId.value = ''
}

async function confirmDelete(item) {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    await deleteMemory(item.id)
    confirmDeleteId.value = ''
    await reloadAfterWrite()
  } catch (e) {
    actionError.value = messageOf(e, '删除没成功，请重试')
  } finally {
    busy.value = false
  }
}

function askClear() {
  if (busy.value) return
  cancelEdit()
  confirmDeleteId.value = ''
  actionError.value = ''
  confirmClear.value = true
}

async function confirmClearAll() {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    await clearMemories()
    confirmClear.value = false
    await reloadAfterWrite()
  } catch (e) {
    actionError.value = messageOf(e, '清空没成功，请重试')
  } finally {
    busy.value = false
  }
}

/**
 * 观察时间本地化：后端给 ISO 本地时间（无时区后缀），按本地时间解析即可。
 * 空值 / 坏值不显示「Invalid Date」，返回空串让整行消失。
 */
function formatTime(raw) {
  if (!raw) return ''
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<style scoped>
/* 记忆段：和「您填写的」同一张纸上的第二块，不另起一张卡。
   分隔靠一条 1px 线与 16px 节奏，不靠描边盒子（「导诊伙伴」§1 的暖沙语言）。 */
.p-mem {
  padding: 20px 0 4px;
  border-top: 1px solid var(--line);
}
.p-mem__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.p-mem__headtext {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 12px;
  min-width: 0;
}
.p-mem__title {
  font-family: var(--sans);
  font-size: 13.5px;
  letter-spacing: .08em;
  color: var(--ink);
}
.p-mem__hint {
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}

/* ---------- 自动记忆开关：手写控件，触控目标 44px ---------- */
.p-mem__switch {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 0 4px;
  border: 0;
  background: transparent;
  font-family: var(--sans);
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
}
.p-mem__switch:disabled {
  cursor: default;
  opacity: .6;
}
.p-mem__switch-track {
  position: relative;
  flex: none;
  width: 40px;
  height: 22px;
  border-radius: var(--r-full);
  background: var(--line-2);
  transition: background .18s ease;
}
.p-mem__switch[aria-checked="true"] .p-mem__switch-track {
  background: var(--leaf);
}
.p-mem__switch-knob {
  position: absolute;
  top: 4px;
  left: 4px;
  width: 14px;
  height: 14px;
  border-radius: var(--r-full);
  background: var(--surface);
  box-shadow: var(--sh-raised);
  transition: transform .18s ease;
}
.p-mem__switch[aria-checked="true"] .p-mem__switch-knob {
  transform: translateX(18px);
}

/* ---------- 三态 ---------- */
.p-mem__state {
  margin: 0;
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
}
.p-mem__failure {
  display: flex;
  align-items: center;
  gap: 12px;
}
.p-mem__retry {
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12px;
  color: var(--leaf-deep);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease;
}
.p-mem__retry:hover {
  border-color: var(--leaf);
  background: var(--leaf-soft);
}
.p-mem__paused {
  margin: 0 0 12px;
  padding: 12px 16px;
  border-left: 4px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--rail);
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}
.p-mem__err {
  margin: 0 0 12px;
  padding: 12px 16px;
  border-left: 4px solid var(--alert);
  border-radius: var(--r-sm);
  background: var(--alert-soft);
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.85;
  color: var(--alert);
}
.p-mem__empty {
  margin: 0;
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
}

/* ---------- 列表 ---------- */
.p-mem__group { padding: 12px 0 4px; }
.p-mem__ghead {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 11px;
}
.p-mem__glabel {
  font-family: var(--sans);
  font-size: 12px;
  letter-spacing: .08em;
  color: var(--ink);
}
.p-mem__quota {
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--ink-3);
}
.p-mem__item { margin-bottom: 11px; }
.p-mem__item:last-child { margin-bottom: 0; }
.p-mem__kv {
  margin: 0 0 4px;
  font-family: var(--sans);
  font-size: 13.5px;
  line-height: 1.85;
  color: var(--ink);
}
.p-mem__key { color: var(--ink-2); }
.p-mem__val { font-weight: 600; }
/* 「已暂停」：安静的中性药丸，不占杏色（杏色专属读数与推荐） */
.p-mem__tag {
  display: inline-flex;
  align-items: center;
  margin-left: 8px;
  padding: 4px 8px;
  border-radius: var(--r-full);
  background: var(--rail);
  font-size: 10.5px;
  line-height: 1.3;
  color: var(--ink-2);
}
/* 原话出处：与追问 / 依据同一套「引用」语言（左侧 2px 浅青竖线 + 缩进） */
.p-mem__quote {
  margin: 0 0 4px;
  padding-left: 11px;
  border-left: 2px solid var(--leaf-soft);
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-2);
}
.p-mem__time {
  margin: 0;
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}

/* ---------- 行内编辑 ---------- */
.p-mem__edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.p-mem__input {
  width: 100%;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 13.5px;
  color: var(--ink);
  box-sizing: border-box;
}
.p-mem__input:focus { outline: none; border-color: var(--leaf); }
.p-mem__editfoot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.p-mem__counter {
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}

/* ---------- 动作与确认 ---------- */
.p-mem__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
/* 文字型次操作：视觉安静，热区补到 44px（§5 触控底线） */
.p-mem__act {
  min-height: 44px;
  padding: 0 8px;
  border: 0;
  background: transparent;
  font-family: var(--sans);
  font-size: 12px;
  color: var(--leaf-deep);
  cursor: pointer;
  transition: color .18s ease;
}
.p-mem__act:hover { text-decoration: underline; }
.p-mem__act:disabled {
  color: var(--ink-3);
  cursor: default;
  text-decoration: none;
}
.p-mem__act--danger { color: var(--alert); }
.p-mem__act--primary {
  padding: 0 16px;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
}
.p-mem__act--primary:hover {
  background: var(--leaf-deep);
  text-decoration: none;
}
.p-mem__act--primary:disabled {
  background: var(--line-2);
  color: var(--ink-3);
}
.p-mem__confirm {
  margin-top: 4px;
  padding: 12px 16px;
  border-radius: var(--r-sm);
  background: var(--rail);
}
.p-mem__confirm--clear {
  margin-bottom: 8px;
  background: var(--alert-soft);
}
.p-mem__confirm-text {
  margin: 0 0 4px;
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}
.p-mem__confirm--clear .p-mem__confirm-text { color: var(--alert); }
.p-mem__foot {
  padding-top: 16px;
  margin-top: 4px;
  border-top: 1px solid var(--line);
}
.p-mem__clear {
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 8px;
  border: 0;
  background: transparent;
  font-family: var(--sans);
  font-size: 12px;
  color: var(--alert);
  cursor: pointer;
}
.p-mem__clear:hover { text-decoration: underline; }
.p-mem__clear:disabled {
  color: var(--ink-3);
  cursor: default;
  text-decoration: none;
}
</style>
