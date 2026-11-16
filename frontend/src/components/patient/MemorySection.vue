<template>
  <div class="p-hp__panel" role="region" aria-label="它记住的">
    <header class="p-hp__phd">
      <div class="p-hp__phdtext">
        <span class="p-hp__eb">它 记 住 的</span>
        <h2 class="p-hp__h">系统从聊天里记下的笔记</h2>
        <p class="p-hp__lead">每条都带着您的原话；您可以改、可以删。</p>
      </div>
    </header>

    <div class="p-hp__scroll">
      <p v-if="state === 'loading'" class="p-hp__state">正在读取系统记住的内容…</p>

      <div v-else-if="state === 'failed'" class="p-hp__failed">
        <p class="p-hp__state">记忆加载失败，请重试</p>
        <button type="button" class="p-hp__retry" @click="$emit('retry')">重试</button>
      </div>

      <template v-else>
        <!-- 自动记忆：做成设置行（参考控制台的"标签在左、开关在右"），
             不占用页头——页头右端留给浮层的「关闭」。 -->
        <section class="p-hp__block">
          <div class="p-mem__masterrow">
            <div class="p-mem__mastertext">
              <h3 class="p-hp__bt">自动记忆</h3>
              <p class="p-hp__bnote">关掉后不再从聊天里记新内容；已记住的仍在这里，您的健康档案不受影响。</p>
            </div>
            <!-- 手写开关（患者端无组件库）：role=switch + aria-checked 是读屏与键盘的契约。
                 关闭只停抽取与注入，不删任何一条记忆。 -->
            <button
              type="button"
              class="p-mem__switch"
              role="switch"
              :aria-checked="memoryEnabled"
              :disabled="toggling || busy"
              @click="toggleEnabled"
            >
              <span class="p-mem__switch-track" aria-hidden="true">
                <span class="p-mem__switch-knob"></span>
              </span>
              <span class="p-mem__switch-label">{{ memoryEnabled ? '开' : '关' }}</span>
            </button>
          </div>
          <p v-if="!memoryEnabled" class="p-mem__paused">
            自动记忆已暂停——旧记忆还留着，只是暂时不参与回答；您的健康档案不受影响。
          </p>
        </section>

        <p v-if="actionError" class="p-mem__err" role="alert">{{ actionError }}</p>

        <p v-if="needsCleanup" class="p-mem__cleanup">
          长期背景已经有 {{ limits.backgroundMax }} 条，满了。删掉一条不再适用的，给新的留位置——系统不会自己替您淘汰。
        </p>

        <p v-if="isEmpty" class="p-mem__empty">
          还没有记住什么。聊几句之后，我会把您的偏好和长期情况记在这里。
        </p>

        <template v-else>
          <section v-for="group in groups" :key="group.key" class="p-hp__block">
            <div class="p-hp__blockhd">
              <div>
                <h3 class="p-hp__bt">{{ group.title }}</h3>
                <p class="p-hp__bnote">{{ group.desc }}</p>
              </div>
              <span class="p-mem__quota">{{ group.items.length }} / {{ group.quotaMax }}</span>
            </div>

            <article v-for="item in group.items" :key="item.id" class="p-mem__item">
              <!-- 编辑态：只改值。键、原话出处、观察时间都由后端保留，页面照旧显示 -->
              <div v-if="editingId === item.id" class="p-mem__edit">
                <label class="p-mem__editlb" :for="`mem-edit-${item.id}`">修改这条「{{ item.key }}」</label>
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
                  <div class="p-mem__acts-inline">
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
                <div class="p-mem__row">
                  <span class="p-mem__key">{{ item.key }}</span>
                  <span class="p-mem__val">{{ item.value }}</span>
                  <span v-if="!memoryEnabled" class="p-mem__tag">已暂停</span>
                  <span class="p-mem__time">{{ formatTime(item.observedAt) }}</span>
                </div>
                <p v-if="item.sourceQuote" class="p-mem__quote">「{{ item.sourceQuote }}」</p>

                <!-- 删除二次确认做在行内（两段式），不弹原生 confirm：
                     原生弹窗与本方案的浮层语言是两套东西，且会遮住上下文 -->
                <div v-if="confirmDeleteId === item.id" class="p-mem__confirm">
                  <p class="p-mem__confirm-text">删除后这条不再影响回答。</p>
                  <div class="p-mem__acts-inline">
                    <button
                      type="button"
                      class="p-mem__act p-mem__act--danger"
                      :disabled="busy"
                      @click="confirmDelete(item)"
                    >{{ busy ? '删除中…' : '确认删除' }}</button>
                    <button type="button" class="p-mem__act" :disabled="busy" @click="confirmDeleteId = ''">取消</button>
                  </div>
                </div>

                <!-- 桌面 hover 才浮现、触屏常显（与 §4.1 复制按钮同一套口径）；
                     hover 时右侧时间让位——否则动作会压在上面 -->
                <div v-else class="p-mem__acts">
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
          </section>

          <div class="p-mem__foot">
            <template v-if="confirmClear">
              <div class="p-mem__confirm p-mem__confirm--clear">
                <p class="p-mem__confirm-text">
                  清空后这 {{ totalCount }} 条记忆都不再影响回答；您的健康档案不受影响。
                </p>
                <div class="p-mem__acts-inline">
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
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import {
  clearMemories,
  deleteMemory,
  setMemoryEnabled,
  updateMemory
} from '../../api/memory'

/**
 * 「它记住的」：长期记忆控制面（偏好 + 稳定背景）。
 *
 * <p>**受控展示组件**：数据由 `HealthProfileOverlay` 统一读取后经 `data` 下发，
 * 本组件不自己发 `GET /memory`——三页共用同一份读回体，左栏徽标与这一页才不会数字对不上。
 * 写操作仍在这里发起（就近处理编辑/确认态），成功后只 `emit('refresh')` 让父组件重拉。
 *
 * <p>患者对「系统记住了什么」有最终决定权：改值、删单条、一键清空、关自动记忆。
 * - 编辑只改值；出处与观察时间由后端保留（那是审计事实，页面不提供改它们）。
 * - 删除与清空都走行内两段式确认，文案说清「不再影响回答」与「档案不受影响」。
 * - 关闭开关后列表照常显示（每条标「已暂停」），因为关的是抽取与注入，不是删除。
 *
 * <p>三态是硬要求（由 `state` 传入）：loading / ready / failed。失败给「重试」，
 * 绝不把「取不到」渲染成空态——那会让患者以为系统把他的记忆弄丢了。
 */
const props = defineProps({
  /** `GET /memory` 的读回体；加载失败时为 null */
  data: { type: Object, default: null },
  /** loading / ready / failed */
  state: { type: String, default: 'loading' }
})
const emit = defineEmits(['refresh', 'retry'])

const toggling = ref(false)
// 任一写操作进行中：串行执行，避免两次点击互相覆盖
const busy = ref(false)
const actionError = ref('')

const editingId = ref('')
const editingValue = ref('')
const editInput = ref(null)
const confirmDeleteId = ref('')
const confirmClear = ref(false)

/**
 * 开关的乐观值：点击后立刻翻过去，别等一次网络往返。
 * 接口失败时回滚——开关是二值，回滚不会让人误解成第三个状态。
 * 写成功后就地保留这个值，直到下一次点击（它与服务端值相同，留着无害）。
 */
const optimisticEnabled = ref(null)

const memoryEnabled = computed(() => {
  if (optimisticEnabled.value !== null) return optimisticEnabled.value
  return props.data?.memoryEnabled !== false
})
const preferences = computed(() => props.data?.preferences || [])
const backgrounds = computed(() => props.data?.backgrounds || [])
// 上限来自后端受管参数；这里的默认值只在响应到达前兜底
const limits = computed(() => ({
  preferenceMax: props.data?.limits?.preferenceMax ?? 5,
  backgroundMax: props.data?.limits?.backgroundMax ?? 10
}))
// 背景满额时后端下发 true：这里必须消费它，否则「满了请整理」只存在于接口里
const needsCleanup = computed(() => props.data?.needsCleanup === true)

const isEmpty = computed(() => preferences.value.length === 0 && backgrounds.value.length === 0)
const totalCount = computed(() => preferences.value.length + backgrounds.value.length)
const trimmedEdit = computed(() => editingValue.value.trim())
const canSaveEdit = computed(() => trimmedEdit.value.length > 0 && !busy.value)

const groups = computed(() => {
  const list = []
  if (preferences.value.length) {
    list.push({
      key: 'preference',
      title: '偏好',
      desc: '希望我怎么回答您',
      quotaMax: limits.value.preferenceMax,
      items: preferences.value
    })
  }
  if (backgrounds.value.length) {
    list.push({
      key: 'background',
      title: '长期背景',
      desc: '跨会话仍然成立的情况',
      quotaMax: limits.value.backgroundMax,
      items: backgrounds.value
    })
  }
  return list
})

function messageOf(e, fallback) {
  return e?.message || fallback
}

async function toggleEnabled() {
  if (toggling.value || busy.value) return
  const next = !memoryEnabled.value
  toggling.value = true
  actionError.value = ''
  optimisticEnabled.value = next
  try {
    await setMemoryEnabled(next)
    emit('refresh')
  } catch (e) {
    optimisticEnabled.value = !next
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
  // 模板 ref 在 v-for 内是数组（Vue 3）：直接 `.focus()` 会静默失效，这里取第一个
  nextTick(() => {
    const el = Array.isArray(editInput.value) ? editInput.value[0] : editInput.value
    el?.focus()
  })
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
    emit('refresh')
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

async function confirmDelete(item) {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    await deleteMemory(item.id)
    confirmDeleteId.value = ''
    emit('refresh')
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
    emit('refresh')
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
/* 记忆页：组头 + 两行条目 + 行内动作。页头与滚动区走 patient.css §3.11 的共享件。 */

/* ---------- 自动记忆：设置行（左说明、右开关）---------- */
.p-mem__masterrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.p-mem__mastertext { min-width: 0; }

/* 手写开关：触控目标 44px */
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
  white-space: nowrap;
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
.p-mem__switch[aria-checked="true"] .p-mem__switch-track { background: var(--leaf); }
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
.p-mem__switch[aria-checked="true"] .p-mem__switch-knob { transform: translateX(18px); }

/* ---------- 提示条 ---------- */
/* 暂停提示在「自动记忆」卡内、开关行下方 */
.p-mem__paused {
  margin: 12px 0 0;
  padding: 10px 14px;
  border-left: 4px solid var(--line-2);
  border-radius: var(--r-xs);
  background: var(--rail);
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-2);
}
/* 背景满额：满不是错误，是「该您整理了」——用中性浅底，不占杏色（杏色专属读数/推荐） */
.p-mem__cleanup {
  margin: 0 0 16px;
  padding: 12px 16px;
  border-left: 4px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}
.p-mem__err {
  margin: 0 0 16px;
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
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-3);
}

/* ---------- 分组：区块卡走共享件 `.p-hp__block*`（patient.css §3.11）---------- */
.p-mem__quota {
  flex: none;
  font-family: var(--sans);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--ink-3);
}

/* ---------- 一条记忆：两行（键 值 时间 / 原话）---------- */
.p-mem__item {
  position: relative;
  margin: 0 -8px;
  padding: 10px 8px;
  border-radius: var(--r-xs);
  transition: background .18s ease;
}
.p-mem__item + .p-mem__item { border-top: 1px solid var(--line); }
.p-mem__item:hover,
.p-mem__item:focus-within { background: var(--stage); }
.p-mem__row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.p-mem__key {
  flex: none;
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .06em;
  color: var(--ink-3);
}
.p-mem__val {
  font-family: var(--sans);
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: .01em;
  color: var(--ink);
  overflow-wrap: anywhere;
}
/* 「已暂停」：安静的中性药丸，不占杏色（杏色专属读数与推荐） */
.p-mem__tag {
  flex: none;
  padding: 2px 8px;
  border-radius: var(--r-full);
  background: var(--rail);
  font-family: var(--sans);
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--ink-2);
}
/* 时间右端对齐；hover 时让位给动作（与会话条目同一套口径，§4.1） */
.p-mem__time {
  margin-left: auto;
  flex: none;
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
  transition: opacity .18s ease;
}
/* 原话出处：与追问 / 依据同一套「引用」语言（左侧 2px 浅青竖线 + 缩进） */
.p-mem__quote {
  margin: 4px 0 0;
  padding-left: 11px;
  border-left: 2px solid var(--leaf-soft);
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-3);
}

/* 动作贴在行内右上角，**桌面 hover 才浮现、触屏常显** */
.p-mem__acts {
  position: absolute;
  top: 2px;
  right: 10px;
  display: flex;
  gap: 2px;
  opacity: 0;
  pointer-events: none;
  transition: opacity .18s ease;
}
.p-mem__item:hover .p-mem__acts,
.p-mem__item:focus-within .p-mem__acts {
  opacity: 1;
  pointer-events: auto;
}
@media (hover: hover) {
  .p-mem__item:hover .p-mem__time,
  .p-mem__item:focus-within .p-mem__time { opacity: 0; }
}
@media (hover: none) {
  .p-mem__acts { opacity: 1; pointer-events: auto; }
}

/* ---------- 行内编辑 ---------- */
.p-mem__edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.p-mem__editlb {
  font-family: var(--sans);
  font-size: 11px;
  color: var(--ink-2);
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
.p-mem__acts-inline {
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
  margin-top: 8px;
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
  padding-top: 20px;
  margin-top: 12px;
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
