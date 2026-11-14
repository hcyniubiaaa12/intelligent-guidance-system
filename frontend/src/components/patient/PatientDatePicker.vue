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
      ref="panelEl"
      class="p-dp__panel"
      :class="{ 'is-up': openUp, 'p-dp__panel--month': isMonthMode }"
      role="dialog"
      :aria-label="isMonthMode ? '选择年月' : '选择出生日期'"
      @keydown.esc="close"
    >
      <div class="p-dp__hd">
        <!-- 年月模式只翻年：月份本身就在下面那张网格里点 -->
        <template v-if="isMonthMode">
          <button type="button" class="p-dp__nav" title="上一年" aria-label="上一年" @click="shiftView(-12)">«</button>
          <button type="button" class="p-dp__title" @click="toggleYear">{{ viewYear }} 年</button>
          <button type="button" class="p-dp__nav" title="下一年" aria-label="下一年" @click="shiftView(12)">»</button>
        </template>
        <template v-else>
          <button type="button" class="p-dp__nav" title="上一年" aria-label="上一年" @click="shiftView(-12)">«</button>
          <button type="button" class="p-dp__nav" title="上个月" aria-label="上个月" @click="shiftView(-1)">‹</button>
          <button type="button" class="p-dp__title" @click="toggleYear">{{ viewYear }} 年</button>
          <button type="button" class="p-dp__title" @click="mode = mode === 'month' ? 'day' : 'month'">
            {{ viewMonth }} 月
          </button>
          <button type="button" class="p-dp__nav" title="下个月" aria-label="下个月" @click="shiftView(1)">›</button>
          <button type="button" class="p-dp__nav" title="下一年" aria-label="下一年" @click="shiftView(12)">»</button>
        </template>
      </div>

      <div v-if="mode === 'year'" class="p-dp__sub">
        <button type="button" class="p-dp__nav" aria-label="上一页年份" @click="shiftView(-YEAR_PAGE_SIZE)">‹</button>
        <span class="p-dp__subtitle">{{ yearPage }}–{{ yearPage + YEAR_PAGE_SIZE - 1 }}</span>
        <button type="button" class="p-dp__nav" aria-label="下一页年份" @click="shiftView(YEAR_PAGE_SIZE)">›</button>
      </div>

      <div v-if="mode === 'year'" class="p-dp__grid p-dp__grid--year">
        <button
          v-for="y in yearsOnPage"
          :key="y"
          type="button"
          class="p-dp__cell"
          :class="{ 'is-on': y === viewYear }"
          :disabled="yearOutOfRange(y)"
          @click="pickYear(y)"
        >{{ y }}</button>
      </div>

      <div v-else-if="mode === 'month'" class="p-dp__grid p-dp__grid--month">
        <button
          v-for="m in 12"
          :key="m"
          type="button"
          class="p-dp__cell"
          :class="{ 'is-on': m === selectedMonth, 'is-today': showThisMonth && m === thisMonth }"
          :disabled="monthOutOfRange(viewYear, m)"
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
        <span v-if="hintText" class="p-dp__hint">{{ hintText }}</span>
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
  formatMonth,
  formatMonthDisplay,
  monthInRange,
  parseDate,
  parseMonth,
  shiftMonth,
  todayDate,
  todayMonth,
  yearPageStart
} from '../../utils/datePicker'

/**
 * 日期 / 年月选择器（患者端自绘，无组件库）。
 *
 * 两种粒度（`granularity`）：
 * - `day`（默认）：完整日历，服务端只保存年份时配合 `yearOnly` 只显示「1980 年」；
 * - `month`：只到年月（`YYYY-MM`），给用药开始/结束、手术发生时间用——档案里这些字段
 *   本来就只到月，给患者一个日历去点「日」等于让他做一次没有落点的选择。
 *
 * 交互与 `PatientSelect` 同一套语言：焦点留在触发按钮、Esc 关闭、点面板外收起、
 * 面板按可用空间向上翻（水平方向也会夹回视口内——条目行里的触发按钮不一定贴着表单左缘）。
 * 月/年也能直接点标题切换网格，避免从今天一路翻几十年。
 *
 * **不做"至今"这种业务语义**：值只有一个出口（`update:modelValue`，空串 = 未填）。
 * 「仍在使用」由调用方用 `endedOn` 为空表达——后端本来就按空值算至今。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  /** day = 完整日期（YYYY-MM-DD）；month = 只到年月（YYYY-MM） */
  granularity: { type: String, default: 'day' },
  /** 只展示年份（服务端只存年份时用）；仅 day 粒度生效 */
  yearOnly: { type: Boolean, default: false },
  /** 可选范围（含），`YYYY-MM`；空 = 不设下限 / 上限默认为本月 */
  min: { type: String, default: '' },
  max: { type: String, default: '' },
  /** 面板脚注；不传时 day 粒度给出生日期那句，month 粒度不显示 */
  hint: { type: String, default: '' },
  id: { type: String, default: undefined },
  labelId: { type: String, default: undefined },
  placeholder: { type: String, default: '选择出生日期（选填）' }
})
const emit = defineEmits(['update:modelValue'])

const root = ref(null)
const trigger = ref(null)
const panelEl = ref(null)
const open = ref(false)
const openUp = ref(false)
const mode = ref('day') // day / month / year
const today = todayDate()
const todayYear = Number(today.slice(0, 4))
const thisMonth = Number(today.slice(5, 7))
const viewYear = ref(todayYear)
const viewMonth = ref(thisMonth)
const yearPage = ref(yearPageStart(todayYear))

const isMonthMode = computed(() => props.granularity === 'month')
/** 上限默认是本月：用药/手术都不可能发生在未来 */
const maxMonth = computed(() => props.max || todayMonth())
const minMonth = computed(() => props.min || '')
const minYear = computed(() => Number(minMonth.value.slice(0, 4)) || 1900)
const maxYear = computed(() => Number(maxMonth.value.slice(0, 4)))
const hintText = computed(
  () => props.hint || (isMonthMode.value ? '' : '只保存年份，用于计算年龄')
)

const yearsOnPage = computed(() =>
  Array.from({ length: YEAR_PAGE_SIZE }, (_, i) => yearPage.value + i)
)
const cells = computed(() => buildMonthGrid(viewYear.value, viewMonth.value))
const displayText = computed(() =>
  isMonthMode.value
    ? formatMonthDisplay(props.modelValue)
    : formatDisplay(props.modelValue, props.yearOnly)
)

const selectedMonth = computed(() => {
  if (isMonthMode.value) {
    const parsed = parseMonth(props.modelValue)
    // 没选过就一个都不高亮：把「当前月」画成选中会像已经填了值
    return parsed && parsed.year === viewYear.value ? parsed.month : -1
  }
  // day 粒度里，月份网格是"选完月再选日"的中转站，高亮当前视图月
  return viewMonth.value
})
const showThisMonth = computed(() => viewYear.value === todayYear)

watch(open, async (v) => {
  if (!v) return
  const parsedMonth = isMonthMode.value ? parseMonth(props.modelValue) : null
  const parsedDay = isMonthMode.value ? null : parseDate(props.modelValue)
  // 未选过时从**当前年/当前月**打开（业内日期选择器的默认位），不从 1990 之类的猜测年份开始
  let year = parsedMonth?.year ?? parsedDay?.year ?? todayYear
  let month = parsedMonth?.month ?? parsedDay?.month ?? thisMonth
  // 当前年月落在范围外时（如开始时间被结束时间封顶在去年），夹到最近的可选端，别让整屏都是禁用格
  const upper = parseMonth(maxMonth.value)
  const lower = parseMonth(minMonth.value)
  if (upper && (year > upper.year || (year === upper.year && month > upper.month))) {
    year = upper.year
    month = upper.month
  }
  if (lower && (year < lower.year || (year === lower.year && month < lower.month))) {
    year = lower.year
    month = lower.month
  }
  viewYear.value = year
  viewMonth.value = month
  yearPage.value = yearPageStart(viewYear.value)
  mode.value = isMonthMode.value ? 'month' : 'day'
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

/**
 * 面板高度约 330px（年月模式更低，仍按同一值算，宁可多留余量）；下方装不下且上方更宽裕时向上翻。
 * 水平方向把面板夹回视口：触发按钮在用药条目里只有一百来像素宽，居中弹出会顶到浮层边缘。
 */
function measureDirection() {
  const rect = trigger.value?.getBoundingClientRect()
  if (!rect) return
  openUp.value = rect.bottom + 360 > window.innerHeight && rect.top > 360
  const panel = panelEl.value
  if (!panel) return
  const width = panel.offsetWidth
  if (!width) return
  const center = rect.left + rect.width / 2
  const left = Math.min(
    Math.max(center - width / 2, 8),
    Math.max(8, window.innerWidth - width - 8)
  )
  panel.style.left = `${left - rect.left}px`
  panel.style.transform = 'none'
}

/** 该年月是否落在可选范围外（上限默认本月） */
function monthOutOfRange(year, month) {
  return !monthInRange(formatMonth(year, month), minMonth.value, maxMonth.value)
}

function yearOutOfRange(year) {
  return year > maxYear.value || year < minYear.value
}

function toggleYear() {
  if (mode.value === 'year') {
    mode.value = isMonthMode.value ? 'month' : 'day'
    return
  }
  yearPage.value = yearPageStart(viewYear.value)
  mode.value = 'year'
}

function shiftView(delta) {
  if (mode.value === 'year') {
    const lastPage = yearPageStart(maxYear.value)
    yearPage.value = Math.max(
      yearPageStart(minYear.value),
      Math.min(yearPage.value + delta, lastPage)
    )
    return
  }
  const next = shiftMonth(viewYear.value, viewMonth.value, delta)
  // 不翻到范围外：生日/治疗时间翻到未来没有意义，也不往回翻到 1900 之前
  if (next.year < minYear.value || monthOutOfRange(next.year, next.month)) return
  viewYear.value = next.year
  viewMonth.value = next.month
  mode.value = isMonthMode.value ? 'month' : 'day'
}

function pickYear(y) {
  viewYear.value = y
  yearPage.value = yearPageStart(y)
  mode.value = 'month'
}

function pickMonth(m) {
  if (monthOutOfRange(viewYear.value, m)) return
  if (isMonthMode.value) {
    emit('update:modelValue', formatMonth(viewYear.value, m))
    close()
    nextTick(() => trigger.value?.focus())
    return
  }
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
