<template>
  <div class="p-sel" ref="rootEl">
    <button
      :id="id"
      type="button"
      role="combobox"
      class="p-sel__btn"
      :class="{ 'is-open': open, 'is-empty': !hasValue }"
      :disabled="disabled"
      aria-haspopup="listbox"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-controls="panelId"
      :aria-activedescendant="open && active >= 0 ? optionId(active) : null"
      :aria-labelledby="labelId ? labelId + ' ' + id : null"
      @click="toggle"
      @keydown="onKeydown"
    >
      <span class="p-sel__val">{{ displayText }}</span>
      <svg class="p-sel__caret" width="11" height="11" viewBox="0 0 14 14" aria-hidden="true">
        <path d="M3.2 5.4 7 9.2l3.8-3.8" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
      </svg>
    </button>

    <Transition name="p-sel-rise">
      <ul
        v-if="open"
        :id="panelId"
        ref="panelEl"
        class="p-sel__panel"
        :class="{ 'p-sel__panel--up': dropUp }"
        role="listbox"
        :aria-labelledby="labelId || null"
      >
        <li
          v-for="(o, i) in items"
          :id="optionId(i)"
          :key="o.value"
          role="option"
          class="p-sel__opt"
          :class="{ on: i === active, sel: o.value === modelValue }"
          :aria-selected="o.value === modelValue ? 'true' : 'false'"
          @click="pick(o.value)"
          @mousemove="active = i"
        >
          <span class="p-sel__tick" aria-hidden="true">
            <svg v-if="o.value === modelValue" width="11" height="11" viewBox="0 0 12 12"><path d="M2 6.4 4.9 9.3 10 3.4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" /></svg>
          </span>
          <span class="p-sel__optx">{{ o.label }}</span>
        </li>
        <li v-if="!items.length" class="p-sel__none">暂无可选项</li>
      </ul>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 自绘下拉（**不用原生 `<select>`**）。
 *
 * 为什么不用原生：原生控件的外观完全由浏览器决定——Windows 是一块灰色凹面、macOS 是
 * 圆角胶囊，与本方案「白底 + 1px 细线 + --r-sm 圆角」的控件语言对不上，而且**同一套
 * 截图换台机器就变了**。自绘换来的是可控。
 *
 * 交互按 WAI-ARIA 的 combobox + listbox 写：焦点**始终留在触发按钮上**，面板里用
 * `aria-activedescendant` 指向当前项（而不是把焦点搬进 listbox）——这样 Tab 键的走向
 * 不会被面板劫持，Esc 也能就地收起。触控目标 ≥44px。
 *
 * **面板可能被祖先的滚动容器裁掉**（健康档案浮层的表单区是 `overflow-y:auto`），
 * 所以面板高度按"到最近滚动容器的可用空间"算，装不下就**向上翻**。不这么做就会
 * 出现"打开列表只露出一条、其余点不到"。
 *
 * 值只有一个出口：`update:modelValue`。选项由调用方下发（健康档案的性别/年龄段
 * 来自后端读回体的 `options`，前端不写死）。
 */
const props = defineProps({
  /** 当前值。空串表示"没填" */
  modelValue: { type: String, default: '' },
  /** [{ value, label }] */
  options: { type: Array, default: () => [] },
  /** 非空时在列表最前面插一项"清空"（value 为空串），用于性别/年龄段这种选填字段 */
  noneLabel: { type: String, default: '' },
  /** 没有有效选中项时显示的占位文案 */
  placeholder: { type: String, default: '请选择' },
  /** 触发按钮的 id */
  id: { type: String, default: '' },
  /** 可见 label 元素的 id（button 不能用 label 的 for，改 aria-labelledby） */
  labelId: { type: String, default: '' },
  /**
   * 禁用态。**当前唯一的调用方（健康档案）不使用它**——但一个表单控件不能没有这个状态：
   * 去掉它，下次遇到"这段表单暂时不可改"只能把组件整个换掉。样式已备（`.p-sel__btn:disabled`）。
   */
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

/** 面板与触发器之间的缝。与 §2.4 的 4px 同值，别单独造一个数 */
const GAP = 4

const rootEl = ref(null)
const panelEl = ref(null)
const open = ref(false)
const dropUp = ref(false)
/** 面板里"当前高亮"的序号，与选中值是两回事：键盘在移动高亮，回车才落到值上 */
const active = ref(-1)

const panelId = computed(() => `${props.id || 'p-sel'}-panel`)
const items = computed(() =>
  props.noneLabel ? [{ value: '', label: props.noneLabel }, ...props.options] : props.options
)
const hasValue = computed(() => items.value.some((o) => o.value === props.modelValue))
const displayText = computed(() => {
  const hit = items.value.find((o) => o.value === props.modelValue)
  return hit ? hit.label : props.placeholder
})
const optionId = (i) => `${panelId.value}-opt-${i}`

function openPanel(toIndex) {
  if (props.disabled || open.value) return
  open.value = true
  // 打开时高亮当前选中项；没选中就落到第一项（键盘按下方向键则落到最后一项）
  const cur = items.value.findIndex((o) => o.value === props.modelValue)
  active.value = toIndex === 'last' ? items.value.length - 1 : Math.max(cur, 0)
  // 要等面板真的挂上来才能量它——requestAnimationFrame 排在 Vue 的 DOM 更新之后
  nextFrame(layout)
}

function closePanel() {
  open.value = false
  active.value = -1
  // 行内 max-height 是上次量出来的，留着会在下次打开时先按旧高度渲染一帧
  if (panelEl.value) panelEl.value.style.maxHeight = ''
}

function toggle() {
  if (open.value) closePanel()
  else openPanel()
}

function move(step) {
  if (!open.value) {
    openPanel(step < 0 ? 'last' : undefined)
    return
  }
  const n = items.value.length
  if (!n) return
  active.value = (active.value + step + n) % n
  scrollActiveIntoView()
}

function pick(value) {
  emit('update:modelValue', value)
  closePanel()
}

/** 最近的会裁掉溢出内容的祖先——面板的可视空间要按它算，不是按视口 */
function clipParent(el) {
  let p = el?.parentElement
  while (p && p !== document.body) {
    const s = getComputedStyle(p)
    if (/(auto|scroll|hidden|clip)/.test(s.overflowY + s.overflowX)) return p
    p = p.parentElement
  }
  return null
}

/**
 * 决定面板朝哪边开、限多高。
 * 先把 max-height 放开量一次真实高度，再拿它和上下可用空间比——直接算会算不准，
 * 因为放开的 max-height 才是内容本来想占的高度。
 *
 * 可用空间取「裁溢出的祖先」与「视口」**两者的交集**：万一链上找不到祖先（退化到 body），
 * body 的 rect 是整篇文档的高度而不是视口，直接拿它算会把面板放到屏幕外面去。
 * 高度**不给下限**——宁可面板内部自己滚，也不要为了凑一个"至少 Npx"而超出可用空间，
 * 那正是"只露出一条、其余点不到"的成因。
 */
function layout() {
  const panel = panelEl.value
  const box = rootEl.value?.getBoundingClientRect()
  if (!panel || !box) return

  panel.style.maxHeight = 'none'
  panel.classList.remove('p-sel__panel--up')
  const wanted = panel.offsetHeight
  if (!wanted) return

  const cp = clipParent(rootEl.value)
  const cRect = cp ? cp.getBoundingClientRect() : null
  const top = Math.max(cRect ? cRect.top : 0, 0)
  const bottom = Math.min(cRect ? cRect.bottom : window.innerHeight, window.innerHeight)
  const below = bottom - box.bottom - GAP
  const above = box.top - top - GAP

  if (below >= wanted || below >= above) {
    dropUp.value = false
    panel.style.maxHeight = Math.max(0, below) + 'px'
  } else {
    dropUp.value = true
    panel.classList.add('p-sel__panel--up')
    panel.style.maxHeight = Math.max(0, above) + 'px'
  }
  scrollActiveIntoView()
}

function scrollActiveIntoView() {
  panelEl.value?.querySelector('.p-sel__opt.on')?.scrollIntoView({ block: 'nearest' })
}

function onKeydown(e) {
  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      move(1)
      break
    case 'ArrowUp':
      e.preventDefault()
      move(-1)
      break
    case 'Enter':
    case ' ':
      e.preventDefault()
      // 开着就落值；关着就打开——两种意图都在同一个键上，别让患者按两次
      if (open.value && active.value >= 0) pick(items.value[active.value].value)
      else openPanel()
      break
    case 'Escape':
      if (open.value) {
        e.preventDefault()
        closePanel()
      }
      break
    case 'Tab':
      // 不拦 Tab：让它正常走，面板随之收起
      closePanel()
      break
    default:
      break
  }
}

/** 点面板外面收起。绑 pointerdown 而不是 click——click 晚一帧，会先触发外面的跳转 */
function onDocPointerDown(e) {
  if (!open.value) return
  if (rootEl.value && !rootEl.value.contains(e.target)) closePanel()
}

/** 开着的时候容器可能滚动了、窗口可能缩放了，可视空间随之变化，要重算 */
function onViewportChange() {
  if (open.value) layout()
}

watch(
  () => props.options,
  () => {
    // 词表常常是打开时从接口拿的，拿到后面板内容变了，高度与朝向都要重算
    if (open.value) nextFrame(layout)
  }
)

function nextFrame(fn) {
  if (typeof requestAnimationFrame === 'function') requestAnimationFrame(fn)
  else fn()
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown)
  window.addEventListener('resize', onViewportChange)
  window.addEventListener('scroll', onViewportChange, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown)
  window.removeEventListener('resize', onViewportChange)
  window.removeEventListener('scroll', onViewportChange, true)
})
</script>