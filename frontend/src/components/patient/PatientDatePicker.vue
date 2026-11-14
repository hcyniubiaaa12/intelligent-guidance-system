<template>
  <div class="p-dp" ref="root">
    <button
      :id="id"
      ref="trigger"
      type="button"
      class="p-dp__btn"
      :class="{ 'is-open': open, 'is-empty': !modelValue }"
      :aria-labelledby="labelId"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click="toggle"
      @keydown.esc="close"
    >
      <svg class="p-dp__icon" width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
        <rect x="1.6" y="2.8" width="12.8" height="11.6" rx="1.6" fill="none" stroke="currentColor" stroke-width="1.3" />
        <path d="M1.6 6.2h12.8M5 1.2v3M11 1.2v3" stroke="currentColor" stroke-width="1.3" />
      </svg>
      <span class="p-dp__val">{{ displayText || placeholder }}</span>
      <svg class="p-dp__caret" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true">
        <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4" />
      </svg>
    </button>

    <div
      v-if="open"
      class="p-dp__panel"
      :class="{ 'is-up': openUp }"
      role="dialog"
      aria-label="选择出生日期"
      @keydown.esc="close"
    >
      <div class="p-dp__hd">
        <button type="button" class="p-dp__nav" title="上一年" aria-label="上一年" @click="shiftView(-12)">«</button>
        <button type="button" class="p-dp__nav" title="上个月" aria-label="上个月" @click="shiftView(-1)">‹</button>
        <button type="button" class="p-dp__title" @click="toggleYear">
          {{ viewYear }} 年
        </button>
        <button type="button" class="p-dp__title" @click="mode = mode === 'month' ? 'day' : 'month'">
          {{ viewMonth }} 月
        </button>
        <button type="button" class="p-dp__nav" title="下个月" aria-label="下个月" @click="shiftView(1)">›</button>
        <button type="button" class="p-dp__nav" title="下一年" aria-label="下一年" @click="shiftView(12)">»</button>
      </div>

      <div v-if="mode === 'year'" class="p-dp__sub">
        <button type="button" class="p-dp__nav" aria-label="上一页年份" @click="yearPage -= YEAR_PAGE_SIZE">‹</button>
        <span class="p-dp__subtitle">{{ yearPage }}–{{ yearPage + YEAR_PAGE_SIZE - 1 }}</span>
        <button type="button" class="p-dp__nav" aria-label="下一页年份" @click="yearPage += YEAR_PAGE_SIZE">›</button>
      </div>

      <div v-if="mode === 'year'" class="p-dp__grid p-dp__grid--year">
        <button
          v-for="y in yearsOnPage"
          :key="y"
          type="button"
          class="p-dp__cell"
          :class="{ 'is-on': y === viewYear }"
          :disabled="y > currentYear"
          @click="pickYear(y)"
        >{{ y }}</button>
      </div>

      <div v-else-if="mode === 'month'" class="p-dp__grid p-dp__grid--month">
        <button
          v-for="m in 12"
          :key="m"
          type="button"
          class="p-dp__cell"
          :class="{ 'is-on': m === viewMonth }"
          :disabled="viewYear === currentYear && m > Number(today.slice(5, 7))"
          @click="pickMonth(m)"
        >{{ m }} 月</button>
      </div>

      <template v-else>
        <div class="p-dp__week" aria-hidden="true">
          <span v-for="w in WEEKDAY_LABELS" :key="w">{{ w }}</span>
        </div>
        <div class="p-dp__grid p-dp__grid--day">
          <button
            v-for="(c, i) in cells"
            :key="i"
            type="button"
            class="p-dp__cell p-dp__day"
            :class="{ 'is-on': c.date && c.date === modelValue, 'is-today': c.date === today }"
            :disabled="!c.inMonth || c.date > today"
            :aria-label="c.date || undefined"
            @click="pickDay(c.date)"
          >{{ c.day }}</button>
        </div>
      </template>

      <div class="p-dp__foot">
        <button type="button" class="p-dp__clear" :disabled="!modelValue" @click="clear">清除</button>
        <span class="p-dp__hint">只保存年份，用于计算年龄</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import {
  WEEKDAY_LABELS,
  YEAR_PAGE_SIZE,
  buildMonthGrid,
  formatDisplay,
  parseDate,
  shiftMonth,
  todayDate,
  yearPageStart
} from '../../utils/datePicker'

/**
 * 出生日期选择器（患者端自绘，无组件库）。
 *
 * 为什么用完整日历而不是只填年份：业内生日输入就是日历（选起来比手打四位数字快、
 * 也不会输成 198 或 19800）。**服务端仍只保存年份**（`birth_year`），
 * 所以 `yearOnly` 为真时按钮只显示「1980 年」，不假装知道月日；
 * 选完整日期后本次会话显示到日，保存时只取年份。
 *
 * 交互与 `PatientSelect` 同一套语言：焦点留在触发按钮、Esc 关闭、点面板外收起、
 * 面板按可用空间向上翻。月/年也能直接点标题切换网格，避免从今天一路翻几十年。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 只展示年份（服务端只存年份时用）；日历仍可按完整日期选择 */
  yearOnly: { type: Boolean, default: false },
  id: { type: String, default: undefined },
  labelId: { type: String, default: undefined },
  placeholder: { type: String, default: '选择出生日期（选填）' }
})
const emit = defineEmits(['update:modelValue'])

const root = ref(null)
const trigger = ref(null)
const open = ref(false)
const openUp = ref(false)
const mode = ref('day') // day / month / year
const today = todayDate()
const todayYear = Number(today.slice(0, 4))
const todayMonth = Number(today.slice(5, 7))
const viewYear = ref(todayYear)
const viewMonth = ref(todayMonth)
const yearPage = ref(yearPageStart(todayYear))
const currentYear = Number(today.slice(0, 4))

const yearsOnPage = computed(() =>
  Array.from({ length: YEAR_PAGE_SIZE }, (_, i) => yearPage.value + i)
)
const cells = computed(() => buildMonthGrid(viewYear.value, viewMonth.value))
const displayText = computed(() => formatDisplay(props.modelValue, props.yearOnly))

watch(open, async (v) => {
  if (!v) return
  const parsed = parseDate(props.modelValue)
  // 未选过时从**当前年/当前月**打开（业内日期选择器的默认位），不从 1990 之类的猜测年份开始
  viewYear.value = parsed?.year ?? todayYear
  viewMonth.value = parsed?.month ?? todayMonth
  yearPage.value = yearPageStart(viewYear.value)
  mode.value = 'day'
  await nextTick()
  measureDirection()
})

function toggle() {
  open.value ? close() : openPanel()
}

function openPanel() {
  open.value = true
  document.addEventListener('pointerdown', onOutside, true)
}

function close() {
  open.value = false
  document.removeEventListener('pointerdown', onOutside, true)
}

function onOutside(event) {
  if (root.value && !root.value.contains(event.target)) close()
}

/** 面板高度约 330px；下方装不下且上方更宽裕时向上翻，避免被浮层底边裁掉 */
function measureDirection() {
  const rect = trigger.value?.getBoundingClientRect()
  if (!rect) return
  openUp.value = rect.bottom + 340 > window.innerHeight && rect.top > 340
}

function toggleYear() {
  if (mode.value === 'year') {
    mode.value = 'day'
    return
  }
  yearPage.value = yearPageStart(viewYear.value)
  mode.value = 'year'
}

function shiftView(delta) {
  if (mode.value === 'year') {
    yearPage.value = Math.max(1900, Math.min(yearPage.value + delta, currentYear))
    return
  }
  const next = shiftMonth(viewYear.value, viewMonth.value, delta)
  // 不翻到未来：生日选择器翻到今年以后没有意义
  if (next.year > currentYear || (next.year === currentYear && next.month > Number(today.slice(5, 7)))) {
    return
  }
  viewYear.value = Math.max(1900, next.year)
  viewMonth.value = next.month
  mode.value = 'day'
}

function pickYear(y) {
  viewYear.value = y
  yearPage.value = yearPageStart(y)
  mode.value = 'month'
}

function pickMonth(m) {
  viewMonth.value = m
  mode.value = 'day'
}

function pickDay(date) {
  if (!date || date > today) return
  emit('update:modelValue', date)
  close()
  nextTick(() => trigger.value?.focus())
}

function clear() {
  emit('update:modelValue', '')
  close()
  nextTick(() => trigger.value?.focus())
}

onBeforeUnmount(() => document.removeEventListener('pointerdown', onOutside, true))
</script>
