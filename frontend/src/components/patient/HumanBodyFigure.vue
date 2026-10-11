<template>
  <svg ref="figureEl" class="p-body" :class="{ 'is-readonly': readonly }" viewBox="0 0 240 608" role="group" :aria-label="`${faceName}人体图`">
    <defs>
      <pattern :id="patternId" width="7" height="7" patternUnits="userSpaceOnUse">
        <rect width="7" height="7" class="p-body__patternbase" />
        <path d="M-2 2L2 -2M0 7L7 0M5 9L9 5" class="p-body__patternline" />
      </pattern>
    </defs>
    <g class="p-body__pieces">
      <g
        v-for="area in areas"
        :key="area.key"
        :ref="(el) => setAreaRef(area.key, el)"
        class="p-body__area"
        :class="areaClasses(area)"
        :data-region="area.id"
        :data-part="area.part"
        :data-piece="area.key"
        :role="readonly ? undefined : 'button'"
        :tabindex="readonly ? undefined : enabled(area.id) && tabKey === area.key ? 0 : -1"
        :aria-label="`${faceName} ${area.label}`"
        :aria-pressed="readonly ? undefined : selected(area.id)"
        :aria-disabled="readonly ? undefined : !enabled(area.id)"
        @click="choose(area)"
        @keydown="onAreaKey($event, area)"
        @focus="onFocus($event, area)"
        @blur="focusedKey = null"
        @pointerenter="onPointerEnter($event, area)"
        @pointerleave="hoveredKey = null; touchedKey = null"
        @pointerdown="onPointerDown($event, area)"
        @pointerup="touchedKey = null"
        @pointercancel="touchedKey = null"
      >
        <title>{{ area.label }}</title>
        <path v-if="!readonly && enabled(area.id)" class="p-body__hit" :d="area.path" />
        <path class="p-body__region" :d="area.path" :style="!enabled(area.id) ? { fill: `url(#${patternId})` } : null" />
      </g>
    </g>
    <g class="p-body__detail" aria-hidden="true">
      <path :d="DETAIL[face]" />
      <circle v-if="face === 'front'" cx="120" cy="270" r="1.8" />
    </g>
    <g class="p-body__badges" aria-hidden="true">
      <g v-for="area in solidSelectedAreas" :key="area.key" :transform="`translate(${area.x} ${area.y})`">
        <circle r="8" />
        <path d="M-3.5 0L-1 2.5L3.8 -3" />
      </g>
    </g>
    <!-- 描边最后绘制，避免相邻色块把当前编辑或键盘焦点盖住。 -->
    <g v-if="!readonly" class="p-body__rings" aria-hidden="true">
      <path v-for="area in activeAreas" :key="area.key" :d="area.path" class="p-body__ring p-body__ring--active" />
      <path v-if="focusedArea" :d="focusedArea.path" class="p-body__ring p-body__ring--focus" />
    </g>
    <g v-if="!readonly && tooltip" class="p-body__tooltip" :transform="`translate(${tooltip.x} ${tooltip.y})`" aria-hidden="true">
      <rect :x="-tooltip.width / 2" :y="-tooltip.height / 2" :width="tooltip.width" :height="tooltip.height" :rx="6 * textScale" />
      <text text-anchor="middle" dominant-baseline="central" :style="{ fontSize: `${13 * textScale}px` }">{{ tooltip.label }}</text>
    </g>
    <g v-if="!readonly" class="p-body__sides" aria-hidden="true" :style="{ fontSize: `${12 * textScale}px` }">
      <text x="8" y="600">{{ face === 'front' ? '右' : '左' }}</text>
      <text x="232" y="600" text-anchor="end">{{ face === 'front' ? '左' : '右' }}</text>
    </g>
  </svg>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'

const props = defineProps({
  face: { type: String, default: 'front' },
  active: { type: String, default: null },
  selected: { type: Function, required: true },
  enabled: { type: Function, required: true },
  side: { type: String, default: null },
  readonly: { type: Boolean, default: false }
})
const emit = defineEmits(['select'])
const patternId = `body-disabled-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
const figureEl = ref(null)
const areaRefs = new Map()
const hoveredKey = ref(null)
const focusedKey = ref(null)
const touchedKey = ref(null)
const rovingKey = ref(null)
const textScale = ref(1)
const faceName = computed(() => props.face === 'front' ? '正面' : '背面')

const CENTER = {
  head: 'M120 14 C139 14 151 29 151 50 C151 68 146 82 136 90 C131 94 126 96 120 96 C114 96 109 94 104 90 C94 82 89 68 89 50 C89 29 101 14 120 14 Z',
  neck: 'M106 89 C111 94 115 96 120 96 C125 96 129 94 134 89 L135 113 C130 116 125 117 120 117 C115 117 110 116 105 113 Z',
  hip: 'M78 296 C95 302 107 304 120 304 C133 304 145 302 162 296 C166 306 168 316 168 326 C152 330 136 338 123 348 L117 348 C104 338 88 330 72 326 C72 316 74 306 78 296 Z'
}
const TRUNK = {
  front: {
    chest: 'M105 113 C110 116 115 117 120 117 C125 117 130 116 135 113 C137 120 142 126 148 132 C158 141 164 154 166 168 C163 186 161 204 160 222 C147 226 133 228 120 228 C107 228 93 226 80 222 C79 204 77 186 74 168 C76 154 82 141 92 132 C98 126 103 120 105 113 Z',
    abd: 'M80 222 C93 226 107 228 120 228 C133 228 147 226 160 222 C160 240 158 256 158 268 C159 278 161 288 162 296 C145 302 133 304 120 304 C107 304 95 302 78 296 C79 288 81 278 82 268 C82 256 80 240 80 222 Z'
  },
  back: {
    // 腰部从肩胛下缘起，与正面腹部保留不同的分区高度。
    back: 'M105 113 C110 116 115 117 120 117 C125 117 130 116 135 113 C137 120 142 126 148 132 C158 141 164 154 166 168 C163 190 161 214 160 238 C147 242 133 244 120 244 C107 244 93 242 80 238 C79 214 77 190 74 168 C76 154 82 141 92 132 C98 126 103 120 105 113 Z',
    waist: 'M80 238 C93 242 107 244 120 244 C133 244 147 242 160 238 C159 252 158 262 158 268 C159 278 161 288 162 296 C145 302 133 304 120 304 C107 304 95 302 78 296 C79 288 81 278 82 268 C82 262 81 252 80 238 Z'
  }
}
const PAIRED = {
  shoulder: {
    vl: 'M105 113 C95 117 85 119 75 122 C62 126 54 137 52 152 L51 164 L74 168 C76 154 82 141 92 132 C98 126 103 120 105 113 Z',
    vr: 'M135 113 C145 117 155 119 165 122 C178 126 186 137 188 152 L189 164 L166 168 C164 154 158 141 148 132 C142 126 137 120 135 113 Z'
  },
  arm: {
    vl: 'M51 164 L74 168 C70 192 64 214 58 236 C53 262 47 290 42 318 L22 316 C27 290 33 262 37 234 C40 214 46 190 51 164 Z',
    vr: 'M189 164 L166 168 C170 192 176 214 182 236 C187 262 193 290 198 318 L218 316 C213 290 207 262 203 234 C200 214 194 190 189 164 Z'
  },
  hand: {
    vl: 'M22 316 L42 318 C45 330 46 342 44 354 C42 366 37 374 30 375 C24 376 19 371 18 363 C17 356 17 350 17 344 C12 342 8 338 8 332 C8 326 13 323 19 325 Z',
    vr: 'M218 316 L198 318 C195 330 194 342 196 354 C198 366 203 374 210 375 C216 376 221 371 222 363 C223 356 223 350 223 344 C228 342 232 338 232 332 C232 326 227 323 221 325 Z'
  },
  thigh: {
    vl: 'M72 326 C88 330 104 338 117 348 C116 376 112 404 108 432 C99 435 89 435 80 432 C75 404 70 376 70 352 C70 343 71 334 72 326 Z',
    vr: 'M168 326 C152 330 136 338 123 348 C124 376 128 404 132 432 C141 435 151 435 160 432 C165 404 170 376 170 352 C170 343 169 334 168 326 Z'
  },
  knee: {
    vl: 'M80 432 C89 435 99 435 108 432 C108 444 107 456 106 466 C97 469 89 469 81 466 C80 456 79 444 80 432 Z',
    vr: 'M160 432 C151 435 141 435 132 432 C132 444 133 456 134 466 C143 469 151 469 159 466 C160 456 161 444 160 432 Z'
  },
  calf: {
    vl: 'M81 466 C89 469 97 469 106 466 C110 486 110 506 106 526 C104 536 103 546 103 556 L87 556 C86 546 84 536 81 524 C77 504 76 484 81 466 Z',
    vr: 'M159 466 C151 469 143 469 134 466 C130 486 130 506 134 526 C136 536 137 546 137 556 L153 556 C154 546 156 536 159 524 C163 504 164 484 159 466 Z'
  },
  foot: {
    vl: 'M87 556 L103 556 C105 566 108 576 110 584 C111 591 107 596 100 596 L84 596 C78 596 75 592 77 586 C80 578 84 568 87 556 Z',
    vr: 'M153 556 L137 556 C135 566 132 576 130 584 C129 591 133 596 140 596 L156 596 C162 596 165 592 163 586 C160 578 156 568 153 556 Z'
  }
}
const ANCHORS = {
  head: [120, 54], neck: [120, 106], hip: [120, 318],
  chest: [120, 176], abd: [120, 264], back: [120, 182], waist: [120, 272],
  shoulder: [[72, 142], [168, 142]], arm: [[47, 240], [193, 240]],
  hand: [[30, 348], [210, 348]], thigh: [[93, 388], [147, 388]],
  knee: [[94, 450], [146, 450]], calf: [[94, 510], [146, 510]],
  foot: [[93, 580], [147, 580]]
}
const LABELS = {
  head: '头部', neck: '颈部', hip: '髋部', chest: '胸部', abd: '腹部',
  back: '背部', waist: '腰部', shoulder: '肩部', arm: '上肢', hand: '手部',
  thigh: '大腿', knee: '膝部', calf: '小腿', foot: '足部'
}
const DETAIL = {
  front: 'M98 124 Q108 128 116 127 M124 127 Q132 128 142 124 M120 236 L120 286 M100 300 Q120 312 140 300',
  back: 'M120 120 L120 300 M90 150 Q96 176 110 186 M150 150 Q144 176 130 186 M120 322 L120 346'
}

// 图上的左右是观者视角，提交的方位必须换算成患者身体的左右。
function bodySide(face, part) {
  if (part === 'c') return null
  if (face === 'front') return part === 'vl' ? '右' : '左'
  return part === 'vl' ? '左' : '右'
}
const areas = computed(() => {
  const center = Object.entries({ ...CENTER, ...TRUNK[props.face] }).map(([id, path]) => ({
    key: `${id}:c`, shape: id, id, part: 'c', label: LABELS[id], path,
    x: ANCHORS[id][0], y: ANCHORS[id][1]
  }))
  const paired = Object.entries(PAIRED).flatMap(([shape, paths]) =>
    ['vl', 'vr'].map((part, i) => ({
      key: `${shape}:${part}`, shape, id: ['thigh', 'calf'].includes(shape) ? 'leg' : shape,
      part, label: bodySide(props.face, part) + LABELS[shape], path: paths[part],
      x: ANCHORS[shape][i][0], y: ANCHORS[shape][i][1]
    }))
  )
  return [...center, ...paired].sort((a, b) => a.y - b.y || a.x - b.x)
})
const enabledAreas = computed(() => areas.value.filter((area) => props.enabled(area.id)))
const tabKey = computed(() => enabledAreas.value.find((area) => area.key === rovingKey.value)?.key || enabledAreas.value[0]?.key)
const focusedArea = computed(() => areas.value.find((area) => area.key === focusedKey.value && props.enabled(area.id)))
function matchesSide(area) {
  return area.part === 'c' || !['左', '右'].includes(props.side) || bodySide(props.face, area.part) === props.side
}
const solidSelectedAreas = computed(() => areas.value.filter((area) => props.selected(area.id) && matchesSide(area) && props.enabled(area.id)))
const activeAreas = computed(() => areas.value.filter((area) => area.id === props.active && matchesSide(area) && props.enabled(area.id)))
const tooltip = computed(() => {
  const key = touchedKey.value || hoveredKey.value || focusedKey.value
  const area = areas.value.find((item) => item.key === key && props.enabled(item.id))
  if (!area) return null
  const width = (area.label.length * 13 + 20) * textScale.value
  const edge = Math.max(30, width / 2 + 2)
  return {
    label: area.label, width, height: 26 * textScale.value,
    x: Math.max(edge, Math.min(240 - edge, area.x)),
    y: Math.max(13 * textScale.value + 2, area.y - 22)
  }
})
function areaClasses(area) {
  return {
    'is-selected': props.selected(area.id) && matchesSide(area),
    'is-soft': props.selected(area.id) && !matchesSide(area),
    'is-active': props.active === area.id && matchesSide(area),
    'is-disabled': !props.enabled(area.id),
    'is-hovered': hoveredKey.value === area.key
  }
}
function setAreaRef(key, el) {
  if (el) areaRefs.set(key, el)
  else areaRefs.delete(key)
}
function choose(area) {
  if (!props.readonly && props.enabled(area.id)) emit('select', area.id, { part: area.part, side: bodySide(props.face, area.part) })
}
function onFocus(e, area) {
  if (props.readonly) return
  focusedKey.value = e.currentTarget.matches(':focus-visible') ? area.key : null
  rovingKey.value = area.key
}
function onPointerEnter(e, area) {
  if (props.readonly) return
  if (e.pointerType === 'mouse' && window.matchMedia('(hover: hover)').matches) hoveredKey.value = area.key
}
function onPointerDown(e, area) {
  if (!props.readonly && e.pointerType !== 'mouse' && props.enabled(area.id)) touchedKey.value = area.key
}
function onAreaKey(e, area) {
  if (props.readonly || !props.enabled(area.id)) return
  focusedKey.value = area.key
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    choose(area)
    return
  }
  if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return
  e.preventDefault()
  let target
  if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
    const list = enabledAreas.value
    const index = list.findIndex((item) => item.key === area.key)
    target = list[Math.max(0, Math.min(list.length - 1, index + (e.key === 'ArrowDown' ? 1 : -1)))]
  } else {
    const part = e.key === 'ArrowLeft' ? 'vl' : 'vr'
    target = enabledAreas.value.find((item) => item.shape === area.shape && item.part === part)
  }
  areaRefs.get(target?.key)?.focus()
}
let figureObserver = null
onMounted(() => {
  // SVG 会随画布缩小，反向补偿文字尺寸，避免气泡和左右提示也缩成难读的小字。
  figureObserver = new ResizeObserver(() => {
    const rect = figureEl.value?.getBoundingClientRect()
    if (rect?.width && rect.height) textScale.value = 1 / Math.min(rect.width / 240, rect.height / 608)
  })
  figureObserver.observe(figureEl.value)
})
onBeforeUnmount(() => figureObserver?.disconnect())
</script>

<style scoped>
.p-body { display: block; width: 100%; height: 100%; min-height: 0; overflow: visible; }
.p-body__pieces { filter: drop-shadow(var(--sh-rest)); }
.p-body__area { cursor: pointer; outline: none !important; }
.p-body.is-readonly .p-body__area { cursor: default; }
.p-body__region { fill: var(--body-skin); stroke: var(--body-gap); stroke-width: 2.4; stroke-linejoin: round; transition: fill .18s ease, opacity .18s ease; }
.p-body__hit { fill: transparent; stroke: transparent; stroke-width: 10; pointer-events: all; }
.p-body__area.is-selected .p-body__region { fill: var(--leaf); }
.p-body__area.is-soft .p-body__region { fill: var(--leaf-soft); stroke: var(--leaf); stroke-dasharray: 4 3; }
.p-body__area.is-disabled { cursor: default; }
.p-body__area.is-disabled .p-body__region { opacity: .55; }
.p-body__patternbase { fill: var(--body-skin); }
.p-body__patternline { fill: none; stroke: var(--line-2); stroke-width: 1.2; }
.p-body__detail { fill: none; stroke: var(--leaf-deep); stroke-width: 1; opacity: .28; pointer-events: none; }
.p-body__badges { pointer-events: none; }
.p-body__badges circle { fill: var(--surface); }
.p-body__badges path { fill: none; stroke: var(--leaf-deep); stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.p-body__rings { pointer-events: none; }
.p-body__ring { fill: none; stroke-width: 2.5; stroke-linejoin: round; transition: stroke .18s ease; }
/* 「正在编辑」环：只靠描边颜色与投影区分，不做任何几何动效——
   点一下就缩一圈（1.025→1）会让患者以为图在动、以为自己点错了。 */
.p-body__ring--active { stroke: var(--leaf-deep); filter: drop-shadow(0 0 4px var(--leaf-glow)); }
.p-body__ring--focus { stroke: var(--ink); }
.p-body__tooltip { pointer-events: none; filter: drop-shadow(var(--sh-raised)); }
.p-body__tooltip rect { fill: var(--surface); }
.p-body__tooltip text { fill: var(--ink); font-family: var(--sans); letter-spacing: 0; }
.p-body__sides { fill: var(--ink-2); font-family: var(--sans); pointer-events: none; letter-spacing: 0; }
@media (hover: hover) {
  .p-body__area.is-hovered:not(.is-disabled) .p-body__region { fill: var(--body-skin-hover); }
  .p-body__area.is-selected.is-hovered:not(.is-disabled) .p-body__region { fill: var(--leaf-deep); }
  .p-body__area.is-soft.is-hovered:not(.is-disabled) .p-body__region { fill: var(--leaf-soft); }
}
.p-body.is-loading .p-body__region { fill: var(--line) !important; opacity: 1; animation: p-body-breathe 1.6s ease-in-out infinite alternate; }
.p-body.is-loading .p-body__detail, .p-body.is-loading .p-body__sides { opacity: 0; }
@keyframes p-body-breathe { from { opacity: .45; } to { opacity: .9; } }
@media (prefers-reduced-motion: reduce) {
  .p-body__region { transition: none; animation: none !important; }
}
</style>
