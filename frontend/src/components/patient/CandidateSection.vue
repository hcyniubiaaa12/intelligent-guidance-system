<template>
  <section class="p-cand" aria-label="待确认">
    <div class="p-cand__head">
      <div class="p-cand__headtext">
        <span class="p-cand__title">待确认</span>
        <span class="p-cand__hint">从您的对话里听出来的，确认后才写进健康档案</span>
      </div>
      <!-- 条数是「待处理量」不是数据指标：跟标题同一档小字，不另起大数字 -->
      <span v-if="state === 'ready' && items.length" class="p-cand__count">{{ items.length }} 条</span>
    </div>

    <!-- 三态：加载中 / 取回 / 失败。失败给「重试」，不把「取不到」渲染成空态 -->
    <p v-if="state === 'loading'" class="p-cand__state">正在加载…</p>

    <div v-else-if="state === 'failed'" class="p-cand__failure">
      <p class="p-cand__state">待确认内容加载失败，请重试</p>
      <button type="button" class="p-cand__retry" @click="load">重试</button>
    </div>

    <template v-else>
      <p v-if="actionError" class="p-cand__err" role="alert">{{ actionError }}</p>

      <p v-if="!items.length" class="p-cand__empty">没有待确认的内容。</p>

      <article
        v-for="item in items"
        :key="item.id"
        class="p-cand__item"
        :class="{ 'is-conflict': item.conflict }"
      >
        <div class="p-cand__meta">
          <span class="p-cand__field">{{ item.fieldLabel || fieldLabel(item.field) }}</span>
          <!-- 冲突是「档案与这句话对不上」，用危险色；杏色专属读数与推荐，这里不占 -->
          <span v-if="item.conflict" class="p-cand__badge">待确认 · 冲突</span>
          <span v-if="item.occurredOn" class="p-cand__month">{{ item.occurredOn }}</span>
        </div>

        <!-- 编辑态：先改再收。值可改，月份与原话是抽取事实，不改 -->
        <div v-if="editingId === item.id" class="p-cand__edit">
          <label class="p-cand__sr" :for="`cand-edit-${item.id}`">修改候选内容</label>
          <input
            :id="`cand-edit-${item.id}`"
            ref="editInput"
            v-model="editingValue"
            class="p-cand__input"
            type="text"
            maxlength="60"
            :disabled="busy"
            @keydown.enter.prevent="accept(item, editingValue)"
            @keydown.esc="cancelEdit"
          />
          <div class="p-cand__editfoot">
            <span class="p-cand__counter">{{ trimmedEdit.length }} / 60</span>
            <div class="p-cand__actions">
              <button
                type="button"
                class="p-cand__act p-cand__act--primary"
                :disabled="!canAcceptEdit"
                @click="accept(item, editingValue)"
              >{{ busy ? '保存中…' : '按这个接受' }}</button>
              <button type="button" class="p-cand__act" :disabled="busy" @click="cancelEdit">取消</button>
            </div>
          </div>
        </div>

        <template v-else>
          <p class="p-cand__value">{{ item.value }}</p>
          <p v-if="item.conflict" class="p-cand__conflictline">
            档案里写着相反的表述，核对一下再决定。
          </p>
          <p v-if="item.sourceQuote" class="p-cand__quote">{{ item.sourceQuote }}</p>

          <div class="p-cand__actions">
            <button
              type="button"
              class="p-cand__act p-cand__act--primary"
              :disabled="busy"
              @click="accept(item)"
            >{{ busy ? '处理中…' : '接受' }}</button>
            <button type="button" class="p-cand__act" :disabled="busy" @click="startEdit(item)">改一下</button>
            <button
              type="button"
              class="p-cand__act p-cand__act--danger"
              :disabled="busy"
              @click="reject(item)"
            >拒绝</button>
          </div>
        </template>
      </article>
    </template>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { acceptCandidate, getMemory, rejectCandidate } from '../../api/memory'

/**
 * 「待确认」：档案候选的确认流（工单 09）。
 *
 * 候选是模型从对话里听出来的**二手健康事实**（过敏 / 用药 / 手术）。患者确认之前它不参与
 * 任何回答——接受后才写进「您填写的」那些档案列。三条交互口径：
 * - 接受可以直接按原值，也可以「改一下」再收（听错字时能纠正）；
 * - 拒绝后同一值不再提议（后端落候选时按 rejected 过滤）；
 * - 冲突行（档案同类别自由文本写着相反的表述）置顶并标「待确认 · 冲突」，提醒先核对。
 *
 * 接受成功后**向父组件抛 accepted**：父组件把这条事实并进「您填写的」表单缓存，
 * 否则患者随后点「保存档案」会拿旧表单把刚确认的值覆盖掉。
 *
 * 三态是硬要求：loading / ready / failed。失败给重试，不把「取不到」说成「没有」。
 */
const emit = defineEmits(['accepted'])

const state = ref('loading')
const items = ref([])
// 任一写操作进行中：串行执行，避免两次点击互相覆盖
const busy = ref(false)
const actionError = ref('')

const editingId = ref('')
const editingValue = ref('')
const editInput = ref(null)

const trimmedEdit = computed(() => editingValue.value.trim())
const canAcceptEdit = computed(() => trimmedEdit.value.length > 0 && !busy.value)

onMounted(load)

async function load() {
  state.value = 'loading'
  actionError.value = ''
  try {
    await fetchItems()
    state.value = 'ready'
  } catch (e) {
    console.error('[candidates] 待确认内容加载失败', e)
    state.value = 'failed'
  }
}

async function fetchItems() {
  const data = await getMemory()
  items.value = data.candidates || []
}

/**
 * 写操作成功后的重拉。**与写操作分开 catch**：写已经生效，刷新失败不能报成
 * 「没接受成功」（患者会重复操作一次）。刷新失败退回失败态，让患者点「重试」。
 */
async function reloadAfterWrite() {
  try {
    await fetchItems()
  } catch (e) {
    console.error('[candidates] 写入后刷新失败', e)
    state.value = 'failed'
  }
}

function messageOf(e, fallback) {
  return e?.message || fallback
}

function fieldLabel(field) {
  if (field === 'allergy') return '过敏史'
  if (field === 'medication') return '用药'
  if (field === 'surgery') return '手术'
  return '健康信息'
}

function startEdit(item) {
  if (busy.value) return
  actionError.value = ''
  editingId.value = item.id
  editingValue.value = item.value || ''
  nextTick(() => {
    // ref 在 v-for 里会被收集成数组；两种形态都兼容，焦点落在值输入框上
    const el = Array.isArray(editInput.value) ? editInput.value[0] : editInput.value
    el?.focus?.()
  })
}

function cancelEdit() {
  editingId.value = ''
  editingValue.value = ''
}

/**
 * 接受：edited 省略 = 按原值；给了值 = 先改再收。
 * 成功后才把事实抛给父组件（写档案已在后端完成）。
 */
async function accept(item, edited) {
  if (busy.value) return
  const value = edited === undefined ? null : String(edited || '').trim()
  if (value !== null && !value) return
  busy.value = true
  actionError.value = ''
  try {
    await acceptCandidate(item.id, value)
    editingId.value = ''
    editingValue.value = ''
    emit('accepted', {
      field: item.field,
      value: value === null ? item.value : value,
      occurredOn: item.occurredOn || ''
    })
    await reloadAfterWrite()
  } catch (e) {
    actionError.value = messageOf(e, '接受没成功，请重试')
  } finally {
    busy.value = false
  }
}

async function reject(item) {
  if (busy.value) return
  cancelEdit()
  busy.value = true
  actionError.value = ''
  try {
    await rejectCandidate(item.id)
    await reloadAfterWrite()
  } catch (e) {
    actionError.value = messageOf(e, '拒绝没成功，请重试')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
/* 第三段「待确认」：和「我记住的」同一张纸上的第三块，仍靠 1px 线与节奏分隔，不另起一张卡。 */
.p-cand {
  padding: 20px 0 4px;
  border-top: 1px solid var(--line);
}
.p-cand__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.p-cand__headtext {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 12px;
  min-width: 0;
}
.p-cand__title {
  font-family: var(--sans);
  font-size: 13.5px;
  letter-spacing: .08em;
  color: var(--ink);
}
.p-cand__hint {
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}
.p-cand__count {
  flex: none;
  font-family: var(--sans);
  font-size: 11.5px;
  color: var(--ink-2);
}

/* ---------- 三态 ---------- */
.p-cand__state {
  margin: 0;
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
}
.p-cand__failure {
  display: flex;
  align-items: center;
  gap: 12px;
}
.p-cand__retry {
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
.p-cand__retry:hover {
  border-color: var(--leaf);
  background: var(--leaf-soft);
}
.p-cand__err {
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
.p-cand__empty {
  margin: 0;
  font-family: var(--sans);
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-3);
}

/* ---------- 候选卡片 ---------- */
.p-cand__item {
  padding: 12px 16px;
  margin-bottom: 11px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
}
.p-cand__item:last-child { margin-bottom: 0; }
/* 冲突行：左侧危险色竖条 + 浅底，与「处置提示」同一套警示语言 */
.p-cand__item.is-conflict {
  border-left: 3px solid var(--alert);
  background: var(--alert-soft);
}
.p-cand__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 4px;
}
.p-cand__field {
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .08em;
  color: var(--ink-2);
}
/* 冲突药丸用危险色：它是「对不上」，不是读数，不占杏色 */
.p-cand__badge {
  padding: 4px 8px;
  border-radius: var(--r-full);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 10.5px;
  line-height: 1.3;
  color: var(--alert);
}
.p-cand__month {
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}
.p-cand__value {
  margin: 0 0 4px;
  font-family: var(--sans);
  font-size: 13.5px;
  line-height: 1.85;
  font-weight: 600;
  color: var(--ink);
}
.p-cand__conflictline {
  margin: 0 0 4px;
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--alert);
}
/* 原话出处：与追问 / 依据 / 记忆同一条「引用」语言（左侧 2px 浅青竖线 + 缩进） */
.p-cand__quote {
  margin: 0 0 4px;
  padding-left: 11px;
  border-left: 2px solid var(--leaf-soft);
  font-family: var(--sans);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-2);
}

/* ---------- 编辑 ---------- */
.p-cand__edit {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.p-cand__input {
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
.p-cand__input:focus { outline: none; border-color: var(--leaf); }
.p-cand__editfoot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.p-cand__counter {
  font-family: var(--sans);
  font-size: 10.5px;
  color: var(--ink-3);
}
.p-cand__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

/* ---------- 动作 ---------- */
.p-cand__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.p-cand__act {
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 8px;
  border: 0;
  background: transparent;
  font-family: var(--sans);
  font-size: 12px;
  color: var(--leaf-deep);
  cursor: pointer;
  transition: color .18s ease, background .18s ease;
}
.p-cand__act:hover { text-decoration: underline; }
.p-cand__act:disabled {
  color: var(--ink-3);
  cursor: default;
  text-decoration: none;
}
.p-cand__act--primary {
  padding: 0 16px;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
}
.p-cand__act--primary:hover {
  background: var(--leaf-deep);
  text-decoration: none;
}
.p-cand__act--primary:disabled {
  background: var(--line-2);
  color: var(--ink-3);
}
.p-cand__act--danger { color: var(--alert); }
</style>
