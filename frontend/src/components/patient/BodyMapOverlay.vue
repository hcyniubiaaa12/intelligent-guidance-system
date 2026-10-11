<template>
  <Transition name="p-fade">
    <div v-if="open" class="p-overlay p-overlay--xl p-overlay--tall" @click.self="close">
      <div
        ref="dialogEl"
        class="p-overlay__box p-map__box"
        role="dialog"
        aria-modal="true"
        aria-labelledby="body-map-title"
        aria-describedby="body-map-sub"
        @keydown="onDialogKeydown"
      >
        <header class="p-overlay__head">
          <div>
            <div id="body-map-title" ref="titleEl" class="p-overlay__title" tabindex="-1">选择不适部位 <span class="p-map__must">必填</span></div>
            <p id="body-map-sub" class="p-overlay__sub">点图上的位置，可以选多处。选完还能在对话里补充。</p>
          </div>
          <button type="button" class="p-overlay__close" aria-label="关闭人体图" @click="close">×</button>
        </header>

        <div class="p-map__bd" :aria-busy="loading">
          <div v-if="allDisabled" class="p-map__dead">
            <p class="p-map__deadt">暂时没有可选部位</p>
            <p class="p-map__deads">您可以选择<b>「说不清在哪儿 · 直接描述」</b>，继续描述您的不适。</p>
            <button type="button" class="p-map__prime" @click="giveUp">直接描述不适</button>
          </div>

          <div v-else class="p-map__fig">
            <div class="p-map__figurehead">
              <!-- 悬停/聚焦/按触某一块时，这里显示**这块的命名**（图上写什么，这里就报什么）；
                   没指向时回落引导语。刻意**不加 aria-live**：区域按钮本身已经播报了区域名，
                   加上会让聚焦某块时被重复播报一遍。 -->
              <span class="p-map__pointer" :class="{ 'is-on': !!pointedLabel }">{{ pointedLabel || '点一下不舒服的位置' }}</span>
              <span class="p-map__count" aria-live="polite">{{ state.picked.length ? `已选 ${state.picked.length} 个部位` : '可多选' }}</span>
            </div>
            <p v-if="loadState === 'failed'" class="p-map__loadhint">部位清单没加载成功，先按默认清单选，不影响使用 <button type="button" @click="loadVocabulary">重试</button></p>

            <div v-if="isMobile" class="p-map__tabs" role="tablist" aria-label="人体图正反面">
              <button v-for="face in FACES" :key="face.id" type="button" role="tab" :aria-selected="state.view === face.id" :tabindex="state.view === face.id ? 0 : -1" :class="{ 'is-on': state.view === face.id }" @click="state = { ...state, view: face.id }" @keydown="onFaceTabKey($event, face.id)">{{ face.label }}</button>
            </div>
            <div class="p-map__canvas">
              <figure v-for="face in visibleFaces" :key="face.id" class="p-map__face">
                <HumanBodyFigure
                  :face="face.id"
                  :active="state.view === face.id ? state.act : null"
                  :selected="isRegionSelected"
                  :enabled="isRegionEnabled"
                  :side="state.side"
                  :readonly="loading"
                  :class="{ 'is-loading': loading }"
                  @select="(id, meta) => activate(id, face.id, meta)"
                  @point="onPoint"
                />
                <figcaption :class="{ 'is-on': state.view === face.id && state.act }">{{ face.label }}</figcaption>
              </figure>
            </div>
            <div class="p-map__legend" aria-hidden="true">
              <span><i />未选</span><span><i class="is-selected" />已选</span><span><i class="is-active" />正在编辑</span><span><i class="is-disabled" />暂不可选</span>
            </div>
            <div class="p-map__global">
              <span>全身性不适（不分部位）</span>
              <div class="p-map__chips">
                <button v-for="w in globalWords" :key="w" type="button" class="p-map__chip" :class="{ 'is-on': state.picked.includes(w) }" :aria-pressed="state.picked.includes(w)" :disabled="loading" @click="pick(w)">{{ w }}</button>
              </div>
              <p class="p-map__hint">胃、心、肺、肠等内脏不适，可以在咨询时直接描述。</p>
            </div>
          </div>

          <div v-if="!allDisabled" class="p-map__pick">
            <div v-if="pickedItems.length" class="p-map__pickedbox" aria-label="已选部位与感觉">
              <h3 class="p-map__lb">已选 <em>{{ pickedItems.length }} 项 · 点 × 逐条删</em></h3>
              <div class="p-map__picked">
                <span v-for="item in pickedItems" :key="item.key" class="p-map__pill" :class="`p-map__pill--${item.kind}`">
                  {{ item.text }}<button type="button" class="p-map__x" :aria-label="`移除 ${item.text}`" @click="item.remove()">×</button>
                </span>
                <button type="button" class="p-map__clear" @click="clearState">全部清空</button>
              </div>
            </div>
            <section ref="locationEl" class="p-map__sec" :class="{ 'is-pulse': locationPulse }">
              <button class="p-map__mobilehead" type="button" :aria-expanded="mobileOpen.location" aria-controls="body-map-location" @click="toggleSection('location')">
                <span>具体位置 <em>{{ recordedLabel }}</em></span><span aria-hidden="true">{{ mobileOpen.location ? '−' : '+' }}</span>
              </button>
              <!-- 标题写的是**这块实际会记下的词**（不是区域展示名）：图上写「腿」、这里就报「腿」，
                   患者不必去猜"下肢"和"腿"是不是一回事。 -->
              <h3 class="p-map__lb">具体位置 <em>{{ recordedLabel }}</em></h3>
              <div id="body-map-location" v-show="!isMobile || mobileOpen.location" class="p-map__sectionbody">
                <div v-if="loading" class="p-map__skeleton" aria-hidden="true"><i v-for="i in 3" :key="i" /></div>
                <div v-else-if="!activeRegion" class="p-map__hintbox">
                  <svg width="24" height="28" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><ellipse cx="12" cy="4.6" rx="2.6" ry="2.9"/><path d="M8.2 8.6c1.1-.6 2.4-.9 3.8-.9s2.7.3 3.8.9c1 .5 1.5 1.5 1.5 2.6v4.2c0 .6-.5 1-1 1h-.6l-.5 5.6c-.1.6-.6 1-1.2 1h-4c-.6 0-1.1-.4-1.2-1l-.5-5.6h-.6c-.5 0-1-.4-1-1v-4.2c0-1.1.5-2.1 1.5-2.6z"/></svg>
                  <span>{{ isMobile ? '先在上面的图上点一下' : '先在左边的图上点一下' }}</span>
                </div>
                <div v-else-if="!subWords.length" class="p-map__hint">这个部位暂时不可选，您可以直接描述不适。</div>
                <div v-else class="p-map__chips"><button v-for="w in subWords" :key="w" type="button" class="p-map__chip" :class="{ 'is-on': state.picked.includes(w), 'is-current': recordedWords.includes(w) }" :aria-pressed="state.picked.includes(w)" @click="pick(w)">{{ w }}</button></div>
              </div>
            </section>
            <section class="p-map__sec">
              <button class="p-map__mobilehead" type="button" :aria-expanded="mobileOpen.side" aria-controls="body-map-side" @click="toggleSection('side')"><span>哪一侧 <em>选填</em></span><span aria-hidden="true">{{ mobileOpen.side ? '−' : '+' }}</span></button>
              <h3 class="p-map__lb">哪一侧 <em>选填</em></h3>
              <div id="body-map-side" v-show="!isMobile || mobileOpen.side" class="p-map__sectionbody">
                <div class="p-map__chips"><button v-for="s in SIDES" :key="s" type="button" class="p-map__chip" :class="{ 'is-on': state.side === s }" :aria-pressed="state.side === s" :disabled="loading" @click="setSide(s)">{{ sideLabel(s) }}</button></div>
                <button v-if="suggestSide && !state.side" type="button" class="p-map__suggest" @click="applySuggest">您点的是图上的{{ suggestSide.side }}侧 · 标为{{ suggestSide.side }}侧</button>
                <p class="p-map__hint">以您自己身体的左右为准，图上已标出。</p>
              </div>
            </section>
            <section class="p-map__sec">
              <button class="p-map__mobilehead" type="button" :aria-expanded="mobileOpen.feeling" aria-controls="body-map-feeling" @click="toggleSection('feeling')"><span>什么感觉 <em>选填 · 可多选</em></span><span aria-hidden="true">{{ mobileOpen.feeling ? '−' : '+' }}</span></button>
              <h3 class="p-map__lb">什么感觉 <em>选填 · 可多选</em></h3>
              <div id="body-map-feeling" v-show="!isMobile || mobileOpen.feeling" class="p-map__sectionbody">
                <div class="p-map__chips"><button v-for="f in FEELINGS" :key="f" type="button" class="p-map__chip" :class="{ 'is-on': state.feelings.includes(f), 'is-unsure': f === '说不上来' }" :aria-pressed="state.feelings.includes(f)" :disabled="loading" @click="state = selectFeeling(state, f)">{{ f }}</button></div>
                <p class="p-map__hint">没有合适的感觉，可以稍后补充。</p>
              </div>
            </section>
          </div>
        </div>

        <footer class="p-overlay__foot p-map__ft">
          <p class="p-map__preview" aria-live="polite"><span class="p-map__previewk">您的描述</span><b>{{ allDisabled ? '您可以直接描述不适' : previewText }}</b></p>
          <div class="p-map__acts">
            <button type="button" class="p-map__ghost" @click="giveUp">说不清在哪儿 · 直接描述</button>
            <span class="p-map__reason" :class="{ 'is-off': canFill }" :aria-hidden="canFill ? 'true' : 'false'">先选一个部位，或点「说不清在哪儿」</span>
            <button type="button" class="p-map__prime" :disabled="!canFill" @click="fill">确认部位 <span aria-hidden="true">→</span></button>
          </div>
        </footer>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { listBodyParts } from '../../api/chat'
import HumanBodyFigure from './HumanBodyFigure.vue'
import { REGIONS, GLOBAL_WORDS, FEELINGS, SIDES, NO_LOCATION_HINT, regionById, sentence, sideLabel, declarationLocations, visibleWords, regionHasWords, landingWord, faceOfRegion, pickedWordsOf, emptyState, selectWord, selectFeeling, selectSide, removeWord, removeFeeling, clearAll } from '../../utils/bodyMap'

const props = defineProps({ open: { type: Boolean, default: false }, preset: { type: Object, default: null } })
const emit = defineEmits(['update:open', 'submit'])
const FACES = [{ id: 'front', label: '正面' }, { id: 'back', label: '背面' }]
const PAIRED_IDS = ['shoulder', 'arm', 'hand', 'leg', 'knee', 'foot']
const state = ref(emptyState())
const vocabulary = ref([])
const loading = ref(false)
const loadState = ref('loading')
const isMobile = ref(false)
const suggestSide = ref(null)
const locationPulse = ref(false)
const pointedLabel = ref(null)
const locationEl = ref(null)
const mobileOpen = ref({ location: true, side: false, feeling: false })
const dialogEl = ref(null)
const titleEl = ref(null)
const restoreFocus = ref(null)
let previousOverflow = ''
let locked = false
let returnRoot = null
let openSeq = 0
let requestSeq = 0
let resizeObserver = null
let pulseTimer = null
const activeRegion = computed(() => state.value.act ? regionById(state.value.act) : null)
const subWords = computed(() => visibleWords(activeRegion.value, vocabulary.value))
// 「具体位置」栏的标题要报**这块实际记下的词**，而不是一个书面区域名（书上叫"下肢"、图上是"腿"）。
// 注意与 landingWord 的区别：landingWord 是"点了会**将要**落下的词"，recorded 是"**已经**记下的词"。
const recordedWords = computed(() => pickedWordsOf(state.value.act, state.value.picked))
const recordedLabel = computed(() => {
  const recorded = recordedWords.value
  if (!recorded.length) return '尚未选择'
  const alt = subWords.value.filter(w => !recorded.includes(w))
  return `已记录：${recorded.join('、')}${alt.length ? ` · 可改成${alt.join(' / ')}` : ''}`
})
const globalWords = computed(() => visibleWords({ words: [...GLOBAL_WORDS] }, vocabulary.value))
const previewText = computed(() => sentence(state.value.picked, state.value.feelings, state.value.side) || NO_LOCATION_HINT)
const allDisabled = computed(() => loadState.value === 'ready' && !vocabulary.value.length)
const canFill = computed(() => !loading.value && !allDisabled.value && state.value.picked.length > 0)
const visibleFaces = computed(() => isMobile.value ? FACES.filter(face => face.id === state.value.view) : FACES)
function isRegionEnabled(id) { return !loading.value && !allDisabled.value && regionHasWords(regionById(id), vocabulary.value) }
function isRegionSelected(id) { return pickedWordsOf(id, state.value.picked).length > 0 }
const pickedItems = computed(() => {
  const s = state.value
  const locations = declarationLocations(s.picked, s.side)
  return [
    ...s.picked.map((word, i) => ({ key: `part:${word}`, kind: 'part', text: locations[i], remove: () => { state.value = removeWord(state.value, word) } })),
    ...(s.side ? [{ key: 'side', kind: 'side', text: sideLabel(s.side), remove: () => { state.value = { ...state.value, side: null }; suggestSide.value = null } }] : []),
    ...s.feelings.map(f => ({ key: `feel:${f}`, kind: 'feel', text: f, remove: () => { state.value = removeFeeling(state.value, f) } }))
  ]
})
function activate(id, face = faceOfRegion(id), meta = {}) {
  if (!isRegionEnabled(id)) return
  const region = regionById(id)
  const word = landingWord(id, vocabulary.value)
  const wasSelected = isRegionSelected(id)
  const alreadyPaired = REGIONS.some(r => PAIRED_IDS.includes(r.id) && r.words.some(w => state.value.picked.includes(w)))
  state.value = { ...state.value, view: face, act: id, picked: wasSelected ? state.value.picked.filter(w => !region.words.includes(w)) : [...state.value.picked, word] }
  mobileOpen.value = { ...mobileOpen.value, location: true }
  locationPulse.value = false
  nextTick(() => {
    locationPulse.value = true
    clearTimeout(pulseTimer)
    pulseTimer = setTimeout(() => { locationPulse.value = false }, 1200)
    // 窄屏：细分词那一栏在图**下方**，点完常常根本不在视野里，患者会以为"点了没反应"。
    // 桌面不滚——那一栏就在图右边、本来就在视野里，凭空滚一次反而像页面在跳。
    if (isMobile.value) locationEl.value?.scrollIntoView({ block: 'nearest' })
  })
  // 方位对所有部位统一加前缀，只建议第一次点击的成对部位，不能静默替患者决定。
  if (!state.value.side && !wasSelected && meta.side && !alreadyPaired) suggestSide.value = { side: meta.side, id }
}
function pick(word) {
  const region = activeRegion.value
  const regionPicks = pickedWordsOf(region?.id, state.value.picked)
  const preferred = landingWord(region?.id, vocabulary.value)
  const base = !state.value.picked.includes(word) && regionPicks.length === 1 && regionPicks[0] === preferred && region?.words.includes(word) ? { ...state.value, picked: state.value.picked.filter(w => w !== preferred) } : state.value
  state.value = selectWord(base, word)
}
// 同一个区域可从图和细分词两处取消，建议对应的区域消失时一起撤回。
watch(() => state.value.picked, () => {
  if (suggestSide.value && !isRegionSelected(suggestSide.value.id)) suggestSide.value = null
})
function setSide(s) { state.value = selectSide(state.value, s); suggestSide.value = null }
function onPoint(label) { pointedLabel.value = label || null }
function applySuggest() { state.value = selectSide(state.value, suggestSide.value.side); suggestSide.value = null }
function toggleSection(key) { mobileOpen.value = { ...mobileOpen.value, [key]: !mobileOpen.value[key] } }
function clearState() { state.value = clearAll(); suggestSide.value = null; mobileOpen.value = { location: true, side: false, feeling: false } }
function fill() {
  if (!canFill.value) return
  emit('submit', { locations: declarationLocations(state.value.picked, state.value.side), sentence: sentence(state.value.picked, state.value.feelings, state.value.side) })
  close()
}
function giveUp() { emit('submit', { locations: [], sentence: '' }); close() }
function close() { emit('update:open', false) }
async function loadVocabulary() {
  // 关闭、重开和重试都可能交错，旧响应不能改写患者正在操作的新一轮选择。
  const seq = ++requestSeq
  loading.value = true
  loadState.value = 'loading'
  try {
    const list = await listBodyParts()
    if (seq !== requestSeq || !props.open) return
    vocabulary.value = Array.isArray(list) ? list : []
    state.value = { ...state.value, picked: state.value.picked.filter(w => vocabulary.value.includes(w)) }
    loadState.value = 'ready'
  } catch {
    if (seq !== requestSeq || !props.open) return
    // 请求失败才回落全量；成功空表代表管理员全停用，不能与失败合并。
    vocabulary.value = []
    loadState.value = 'failed'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}
function focusables() {
  return [...dialogEl.value?.querySelectorAll('button:not([disabled]), [tabindex="0"], input:not([disabled]), textarea:not([disabled]), select:not([disabled])') || []]
    .filter(el => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden' && el.tabIndex >= 0)
}
function onDialogKeydown(e) {
  if (e.key === 'Escape') { e.preventDefault(); close(); return }
  if (e.key !== 'Tab') return
  const list = focusables()
  if (!list.length) return
  const first = list[0]
  const last = list[list.length - 1]
  if (!list.includes(document.activeElement)) { e.preventDefault(); (e.shiftKey ? last : first).focus() }
  else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
}
function onFaceTabKey(e, id) {
  if (!['ArrowLeft', 'ArrowRight'].includes(e.key)) return
  e.preventDefault()
  const next = id === 'front' ? 'back' : 'front'
  state.value = { ...state.value, view: next }
  nextTick(() => dialogEl.value?.querySelector(`[role="tab"][aria-selected="true"]`)?.focus())
}
watch(dialogEl, el => {
  resizeObserver?.disconnect()
  if (!el) return
  returnRoot = el.closest('.patient-root')
  isMobile.value = el.clientWidth <= 700
  resizeObserver = new ResizeObserver(entries => { isMobile.value = entries[0].contentRect.width <= 700 })
  resizeObserver.observe(el)
})
function releaseScroll() {
  if (!locked) return
  document.body.style.overflow = previousOverflow
  locked = false
}
// 入口可能随锁定回执一起卸载，回退到仍在页面里的选图按钮。
function returnFocus() {
  const opener = restoreFocus.value
  const target = opener?.isConnected && opener.getClientRects().length && opener !== document.body ? opener
    : returnRoot?.querySelector('.p-composer__map, .body-map-preview__content .p-btn')
  target?.focus({ preventScroll: true })
  restoreFocus.value = null
}
watch(() => props.open, async open => {
  const seq = ++openSeq
  if (open) {
    restoreFocus.value = document.activeElement
    previousOverflow = document.body.style.overflow
    locked = true
    document.body.style.overflow = 'hidden'
    state.value = emptyState()
    suggestSide.value = null
    mobileOpen.value = { location: true, side: false, feeling: false }
    const preset = props.preset
    if (preset?.word) state.value = { ...state.value, view: faceOfRegion(preset.region), act: preset.region || null, picked: [preset.word] }
    const request = loadVocabulary()
    await nextTick()
    if (seq === openSeq && props.open) titleEl.value?.focus({ preventScroll: true })
    await request
  } else {
    requestSeq += 1
    releaseScroll()
    await nextTick()
    if (seq === openSeq) returnFocus()
  }
}, { immediate: true })
onBeforeUnmount(() => {
  openSeq += 1
  requestSeq += 1
  resizeObserver?.disconnect()
  clearTimeout(pulseTimer)
  releaseScroll()
})
</script>

<style scoped>
.p-map__box { container-type: inline-size; overflow: hidden; }
.p-overlay--tall .p-map__box { height: calc(100vh - 40px); height: calc(100dvh - 40px); }
.p-map__box .p-overlay__head { align-items: center; flex: none; padding: 16px 22px; }
.p-map__box .p-overlay__title { font-size: 19px; font-weight: 600; color: var(--ink); letter-spacing: 0; }
.p-map__box .p-overlay__sub { font-size: 12.5px; color: var(--ink-2); }
.p-map__box .p-overlay__close { justify-content: center; width: 44px; padding: 0; font-size: 28px; line-height: 1; letter-spacing: 0; }
.p-map__must { display: inline-block; margin-left: 8px; padding: 2px 8px; border-radius: var(--r-full); background: var(--leaf-soft); color: var(--leaf-deep); font-size: 12px; font-weight: 600; vertical-align: 1px; }
.p-map__bd { flex: 1; min-height: 0; display: flex; overflow: hidden; }
.p-map__fig { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 12px; padding: 16px 22px; overflow-y: auto; }
.p-map__figurehead { display: flex; justify-content: space-between; align-items: center; flex: none; font-size: 15px; font-weight: 600; color: var(--ink-2); }
/* 图外那一行：悬停 / 聚焦 / 按触某块时显示它的命名——提示永远不压在人身上，也不必推算尺寸。 */
.p-map__pointer { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; transition: color .15s ease; }
.p-map__pointer.is-on { color: var(--leaf-deep); }
.p-map__count { font-size: 12px; font-weight: 600; color: var(--leaf-deep); background: var(--leaf-soft); padding: 2px 10px; border-radius: var(--r-full); border: 1px solid var(--leaf-line); }
.p-map__loadhint { margin: 0; color: var(--ink-2); font-size: 12px; }
.p-map__loadhint button { padding: 0; border: 0; background: none; color: var(--leaf-deep); text-decoration: underline; cursor: pointer; }
/* 图的尺寸只由**视口**决定（定高 + 固定宽高比），**不占 flex 剩余空间**——
   否则页脚一变高（点选后多一行「已选」）就会逐级把图挤矮，患者看到"点一下就缩了一圈"。
   空间不够时由 .p-map__fig 自身滚动，而不是把图压小。这套写法先在窄屏验证过，现上移到桌面。
   三个约束各管一件事：`62vh` 是窄屏验证过的比例；`460px` 是上限；
   `100dvh - 510px` 是"浮层里除了图还有固定开销"——头 + 页脚 + 图例 +「全身性不适」那一排
   + 三段间距 + 画布与图区自己的内边距 ≈ 500px（1440×900 与 1280×800 实测溢出反推），
   减掉它才不至于在常见桌面尺寸上**默认就要滚一下**才看得见"全身性不适"；220px 是地板，防极矮视口把图压没。
   注意：窄屏与桌面共用这一条（同一份事实只允许一处拼装），不再各写一份。 */
.p-map__canvas { flex: none; width: 100%; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 16px; background: var(--stage); border-radius: var(--r-md); border: 1px solid var(--line); }
.p-map__face { display: flex; flex-direction: column; align-items: center; min-width: 0; min-height: 0; gap: 8px; }
.p-map__face .p-body { flex: none; width: auto; max-width: 100%; height: clamp(220px, min(62vh, 100dvh - 510px), 460px); aspect-ratio: 240 / 608; margin: auto; }
.p-map__fig > :not(.p-map__canvas) { flex-shrink: 0; }
.p-map__canvas { overflow: hidden; }
.p-map__box .p-overlay__title:focus { outline: none; }
.p-map__pick .p-map__sec:last-child { border-bottom: 0; }
.p-map__loadhint button { font: inherit; }
.p-map__face figcaption { flex: none; padding: 4px 24px; border-radius: var(--r-full); background: var(--leaf-soft); border: 1.5px solid var(--leaf-line); color: var(--leaf-deep); font-size: 12px; font-weight: 600; transition: background .2s ease, color .2s ease; }
.p-map__face figcaption.is-on { background: var(--leaf); border-color: var(--leaf-deep); color: var(--on-accent); box-shadow: 0 2px 8px var(--leaf-glow); }
.p-map__legend { display: flex; justify-content: center; flex-wrap: wrap; gap: 12px 20px; color: var(--ink-2); font-size: 12px; }
.p-map__legend span { display: inline-flex; align-items: center; gap: 8px; }
.p-map__legend i { width: 10px; height: 10px; border-radius: var(--r-full); background: var(--body-skin); border: 1.5px solid var(--body-gap); }
.p-map__legend i.is-selected { background: var(--leaf); border-color: var(--leaf); }
.p-map__legend i.is-active { background: none; border: 2px solid var(--leaf-deep); }
.p-map__legend i.is-disabled { border-radius: 2px; background: repeating-linear-gradient(135deg, var(--body-skin) 0 3px, var(--line-2) 3px 4px); }
.p-map__global { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; padding-top: 10px; border-top: 1px solid var(--line); font-size: 12px; color: var(--ink-2); }
.p-map__global .p-map__hint { width: 100%; margin: 0; }
.p-map__pick { width: 300px; flex: none; border-left: 1px solid var(--line); background: var(--surface); overflow-y: auto; }
.p-map__sec { padding: 10px 16px; border-bottom: 1px solid var(--line); scroll-margin-top: 88px; }
/* 点选后把视线引到这一段：**只用颜色与左侧色条**，不碰 transform——
   点一下图就位移/缩放，会让人以为图在动、以为自己点错了。 */
.p-map__sec.is-pulse { background: var(--leaf-soft); box-shadow: inset 3px 0 0 var(--leaf); animation: p-map-pulse 1.2s ease; }
.p-map__lb { margin-bottom: 8px; font-size: 15px; font-weight: 600; color: var(--ink); padding-left: 10px; border-left: 2.5px solid var(--leaf); }
.p-map__lb em, .p-map__mobilehead em { font-style: normal; font-weight: 400; margin-left: 8px; font-size: 12px; color: var(--ink-2); }
.p-map__mobilehead { display: none; }
.p-map__hint { margin-top: 8px; font-size: 12px; line-height: 1.7; color: var(--ink-2); }
.p-map__hintbox { display: flex; align-items: center; gap: 10px; padding: 12px; border: 1px solid var(--line); border-radius: var(--r-sm); color: var(--ink-2); font-size: 12px; }
.p-map__hintbox svg { flex: none; color: var(--leaf); }
.p-map__skeleton { display: flex; gap: 8px; }
.p-map__skeleton i { width: 56px; height: 36px; border-radius: var(--r-full); background: var(--line); animation: p-map-breathe 1.6s ease-in-out infinite alternate; }
.p-map__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.p-map__chip { min-height: 36px; padding: 4px 14px; border: 1px solid var(--leaf-line); border-radius: var(--r-full); background: var(--surface); font-family: var(--sans); font-size: 13px; color: var(--ink-2); cursor: pointer; transition: border-color .18s ease, color .18s ease, background .18s ease; }
.p-map__chip:hover, .p-map__chip:focus-visible { border-color: var(--leaf); color: var(--leaf-deep); background: var(--leaf-soft); }
.p-map__chip.is-on { background: var(--leaf); border-color: var(--leaf-deep); color: var(--on-accent); font-weight: 600; }
/* 「当前生效的那一颗」——它同时也是"已选"，所以标记必须是**追加**的，不能只靠反色：
   ✓ 前缀把"系统实际记下的就是这个词"与"另外还选了别的"分开。 */
.p-map__chip.is-current { outline: 2px solid var(--leaf-deep); outline-offset: 1px; }
.p-map__chip.is-current::before { content: '✓ '; font-weight: 700; }
.p-map__chip.is-unsure { border-style: dashed; }
.p-map__chip:disabled { cursor: default; opacity: .55; }
.p-map__suggest { display: block; margin-top: 10px; padding: 8px 10px; border: 1px dashed var(--leaf-line); border-radius: var(--r-xs); background: var(--leaf-soft); color: var(--leaf-deep); font: inherit; font-size: 12px; cursor: pointer; }
.p-map__dead { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 28px 32px; text-align: center; }
.p-map__deadt { font-size: 15px; font-weight: 600; color: var(--ink); }
.p-map__deads { font-size: 12.5px; line-height: 1.9; color: var(--ink-2); }
.p-map__deads b { color: var(--leaf-deep); font-weight: 600; }
.p-map__ft { flex: none; display: flex; flex-direction: column; gap: 10px; }
/* 「已选」收编进侧栏——原来这行在页脚里，点选后页脚一变高就把图挤矮（"点一下就缩一圈"的根源）。
   贴顶吸住：滚侧栏时"我选了哪些"也一直看得见，不必额外滚动去找。 */
.p-map__pickedbox { position: sticky; top: 0; z-index: 1; padding: 10px 16px; border-bottom: 1px solid var(--line); background: var(--surface); }
.p-map__picked { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: var(--leaf-deep); font-size: 12px; }
.p-map__pill { flex: none; display: inline-flex; align-items: center; gap: 6px; padding: 2px 4px 2px 10px; border: 1px solid var(--leaf-line); border-radius: var(--r-full); background: var(--leaf-soft); color: var(--leaf-deep); }
.p-map__pill--side { background: var(--rail); color: var(--ink-2); border-color: var(--line-2); }
.p-map__pill--feel { background: var(--apricot-soft); color: var(--apricot-deep); border-color: var(--line-2); }
.p-map__x { width: 28px; height: 28px; padding: 0; border: 0; border-radius: var(--r-full); background: none; color: inherit; font-size: 18px; cursor: pointer; }
.p-map__x:hover { background: var(--hover-soft); }
.p-map__clear { flex: none; padding: 4px 8px; border: 0; background: none; color: var(--ink-2); font: inherit; cursor: pointer; }
/* 描述预览的高度**钉死**（正文里 1 行、窄屏 2 行），不跟着文字长短变——
   否则"还没选…"的引导语与选完后的句子行数不同，页脚仍会在点选那一下变高。 */
.p-map__preview { display: flex; align-items: baseline; gap: 12px; font-size: 13px; line-height: 1.7; color: var(--ink-2); }
.p-map__previewk { flex: none; font-weight: 600; }
.p-map__preview b { height: 23px; overflow-y: auto; font-weight: 500; color: var(--leaf-deep); overflow-wrap: anywhere; }
.p-map__acts { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.p-map__acts > .p-map__prime { margin-left: auto; }
.p-map__ghost { min-height: 44px; padding: 8px 12px; border: 1px solid var(--line); border-radius: var(--r-xs); background: none; font-family: var(--sans); font-size: 13px; color: var(--ink-2); cursor: pointer; }
.p-map__prime { display: inline-flex; justify-content: center; align-items: center; gap: 16px; min-height: 44px; padding: 8px 28px; border: 0; border-radius: var(--r-xs); background: var(--leaf); color: var(--on-accent); font-family: var(--sans); font-size: 13.5px; font-weight: 600; cursor: pointer; box-shadow: var(--sh-primary); }
.p-map__prime:not(:disabled):hover { background: var(--leaf-deep); }
.p-map__prime:disabled { background: var(--rail); color: var(--ink-3); box-shadow: none; cursor: not-allowed; }
.p-map__reason { flex: 1; min-width: 180px; color: var(--ink-2); font-size: 12px; }
/* 页脚要恒定高度：这句提示在"能确认"时只是看不见，**位置照旧占着**——
   用 v-if 摘掉它，窄屏下 acts 会少一整行，页脚仍然会变矮。 */
.p-map__reason.is-off { visibility: hidden; }
@keyframes p-map-pulse { 0% { background: var(--surface); } 16% { background: var(--leaf-soft); } 100% { background: var(--surface); } }
@keyframes p-map-breathe { from { opacity: .45; } to { opacity: .9; } }
@container (max-width: 700px) {
  .p-map__bd { flex-direction: column; overflow-y: auto; }
  .p-map__fig { flex: 0 0 auto; padding: 12px 16px; overflow: visible; }
  .p-map__canvas { overflow: visible; }
  .p-map__tabs { display: flex; align-self: center; padding: 3px; border-radius: var(--r-full); background: var(--rail); }
  .p-map__tabs button { min-width: 92px; min-height: 40px; border: 0; border-radius: var(--r-full); background: none; color: var(--ink-2); font: inherit; font-size: 13px; cursor: pointer; }
  .p-map__tabs button.is-on { background: var(--leaf); color: var(--on-accent); }
  .p-map__canvas { display: block; min-height: 0; padding: 12px; }
  .p-map__face { animation: p-map-face .18s ease; }
  .p-map__face figcaption { display: none; }
  .p-map__pick { width: auto; min-height: 0; border-left: 0; border-top: 1px solid var(--line); overflow: visible; }
  .p-map__mobilehead { display: flex; justify-content: space-between; align-items: center; width: 100%; padding: 0; border: 0; background: none; font: inherit; font-size: 15px; color: var(--ink); text-align: left; cursor: pointer; }
  .p-map__mobilehead + .p-map__lb { display: none; }
  .p-map__chip { min-height: 44px; }
  .p-map__sectionbody { margin-top: 10px; }
  .p-map__x { width: 40px; height: 40px; }
  .p-map__clear { min-height: 44px; }
  .p-map__ft { padding-bottom: max(16px, env(safe-area-inset-bottom)); }
  .p-map__acts { display: grid; grid-template-columns: 1fr; gap: 8px; }
  .p-map__prime { grid-row: 1; width: 100%; }
  .p-map__reason { min-width: 0; text-align: center; }
  /* 描述预览钉死两行高：否则"还没选…"的引导语与选完后的句子行数不同，页脚仍会跳。 */
  .p-map__preview b { height: 44px; overflow-y: auto; }
}
@keyframes p-map-face { from { opacity: 0; } to { opacity: 1; } }
@media (max-width: 600px) {
  .p-overlay { padding: 8px; }
  .p-overlay.p-overlay--tall .p-map__box { height: calc(100vh - 16px); height: calc(100dvh - 16px); max-height: calc(100vh - 16px); max-height: calc(100dvh - 16px); }
  .p-map__box .p-overlay__head { padding: 12px 16px; }
  .p-map__box .p-overlay__title { font-size: 17px; }
  .p-map__figurehead { align-items: flex-start; gap: 8px; font-size: 13px; }
  .p-map__count { flex: none; }
  .p-map__ghost { min-height: 36px; padding: 0; border: 0; }
  .p-map__ft { padding: 10px 16px max(12px, env(safe-area-inset-bottom)); gap: 6px; }
}
@media (prefers-reduced-motion: reduce) {
  .p-map__skeleton i, .p-map__face { animation: none; }
  .p-map__chip, .p-map__prime, .p-map__ghost { transition: none; }
  /* 高亮退化成**静态色块**、而不是消失——运动偏好不该把"点完该看哪儿"这条信息一起拿走。 */
  .p-map__sec.is-pulse { animation: none; background: var(--leaf-soft); box-shadow: inset 3px 0 0 var(--leaf); }
}
</style>
