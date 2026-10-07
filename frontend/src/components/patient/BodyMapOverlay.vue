<template>
  <Transition name="p-fade">
    <div v-if="open" class="p-overlay p-overlay--xl p-overlay--tall" @click.self="close">
      <div class="p-overlay__box p-map__box" role="dialog" aria-label="人体图选部位">
        <header class="p-overlay__head">
          <div>
            <div class="p-overlay__title">身 上 哪 里 不 舒 服</div>
            <p class="p-overlay__sub">
              点一下图上不舒服的位置，可以选多个；再顺手点一下「哪一侧」和「什么感觉」。
              <b>心 / 肺 / 胃 / 肠不在图上</b>——它们是器官，不是体表位置，等下直接打字说也一样。
            </p>
          </div>
          <button class="p-overlay__close" aria-label="关闭人体图" @click="close">关 闭</button>
        </header>

        <div class="p-map__bd">
          <!-- 左：正反两面人体图 -->
          <div class="p-map__fig">
            <div class="p-map__canvas">
              <svg
                v-for="face in FACES"
                v-show="state.view === face.id"
                :key="face.id"
                class="p-map__svg"
                viewBox="0 0 220 360"
                role="group"
                :aria-label="face.label"
              >
                <g v-for="shape in shapesFor(face.id)" :key="shape.key">
                  <component
                    :is="shape.tag"
                    v-if="regionHasWords(regionById(shape.id), vocabulary)"
                    class="p-map__rg"
                    :class="{ 'is-sel': isRegionSelected(shape.id), 'is-act': state.act === shape.id }"
                    v-bind="shape.attrs"
                    role="button"
                    tabindex="0"
                    :aria-label="regionById(shape.id)?.name"
                    :aria-pressed="isRegionSelected(shape.id) ? 'true' : 'false'"
                    @click="activate(shape.id)"
                    @keydown.enter.prevent="activate(shape.id)"
                    @keydown.space.prevent="activate(shape.id)"
                  />
                </g>
              </svg>
            </div>
            <div class="p-map__views" role="group" aria-label="切换正反面">
              <button
                v-for="face in FACES"
                :key="face.id"
                type="button"
                class="p-map__viewbtn"
                :class="{ 'is-on': state.view === face.id }"
                @click="state = { ...state, view: face.id }"
              >{{ face.label }}</button>
            </div>
            <p class="p-map__tip">背痛、腰痛在正面图上指不出来，看背面 →</p>
          </div>

          <!-- 右：细分词 / 方位 / 感觉 / 已选 -->
          <div class="p-map__pick">
            <section class="p-map__sec">
              <h3 class="p-map__lb">
                这 一 片 具 体 是 哪 儿
                <em>{{ activeRegion ? activeRegion.name : '先点一下图' }}</em>
              </h3>
              <div v-if="!activeRegion" class="p-map__hint">
                点图上任意一块，这里会列出它包含的部位词。
              </div>
              <div v-else-if="!subWords.length" class="p-map__hint">
                这块的部位词在管理端被停用了，换一块试试，或直接打字说。
              </div>
              <div v-else class="p-map__chips">
                <button
                  v-for="w in subWords"
                  :key="w"
                  type="button"
                  class="p-map__chip"
                  :class="{ 'is-on': state.picked.includes(w) }"
                  @click="pick(w)"
                >{{ w }}</button>
              </div>
            </section>

            <section class="p-map__sec">
              <h3 class="p-map__lb">哪 一 侧<em>选填 · 单选</em></h3>
              <div class="p-map__chips">
                <button
                  v-for="s in SIDES"
                  :key="s"
                  type="button"
                  class="p-map__chip"
                  :class="{ 'is-on': state.side === s }"
                  @click="state = selectSide(state, s)"
                >{{ sideLabel(s) }}</button>
              </div>
              <p class="p-map__hint">左右在分诊里是有效信息：左上腹多在胃，右下腹要留意阑尾。</p>
            </section>

            <section class="p-map__sec">
              <h3 class="p-map__lb">再 点 一 下 什 么 感 觉<em>选填 · 可多选</em></h3>
              <div class="p-map__chips">
                <button
                  v-for="f in FEELINGS"
                  :key="f"
                  type="button"
                  class="p-map__chip"
                  :class="{ 'is-on': state.feelings.includes(f) }"
                  @click="state = selectFeeling(state, f)"
                >{{ f }}</button>
              </div>
              <p class="p-map__hint">没有合适的？先随便点一个，回头在输入框里补。</p>
            </section>

            <section class="p-map__sec">
              <h3 class="p-map__lb">已 选<em>可逐条删</em></h3>
              <div v-if="!pickedItems.length" class="p-map__hint">还没选</div>
              <div v-else class="p-map__picked">
                <span
                  v-for="item in pickedItems"
                  :key="item.key"
                  class="p-map__pill"
                  :class="item.kind === 'feel' ? 'p-map__pill--feel' : ''"
                >
                  {{ item.text }}
                  <button
                    type="button"
                    class="p-map__x"
                    :aria-label="`移除 ${item.text}`"
                    @click="item.remove()"
                  >×</button>
                </span>
                <button type="button" class="p-map__clear" @click="state = clearAll()">全部清空</button>
              </div>
            </section>

            <section class="p-map__sec p-map__sec--last">
              <h3 class="p-map__lb">全 身 性 的</h3>
              <div class="p-map__chips">
                <button
                  v-for="w in globalWords"
                  :key="w"
                  type="button"
                  class="p-map__chip"
                  :class="{ 'is-on': state.picked.includes(w) }"
                  @click="pick(w)"
                >{{ w }}</button>
              </div>
            </section>
          </div>
        </div>

        <footer class="p-overlay__foot p-map__ft">
          <p class="p-map__preview">
            <span class="p-map__previewk">会填进输入框</span>
            <b>{{ previewText }}</b>
          </p>
          <div class="p-map__acts">
            <button type="button" class="p-btn p-btn--ghost" @click="giveUp">说不清在哪儿 · 直接描述</button>
            <small>选完只填进输入框、不自动发送。</small>
            <button type="button" class="p-btn" :disabled="!canFill" @click="fill">填 入</button>
          </div>
        </footer>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { listBodyParts } from '../../api/chat'
import {
  GLOBAL_WORDS, FEELINGS, SIDES, NO_LOCATION_HINT,
  regionById, isGlobalWord, sentence, sideLabel,
  visibleWords, regionHasWords,
  emptyState, selectWord, selectFeeling, selectSide, removeWord, removeFeeling, clearAll
} from '../../utils/bodyMap'

/**
 * 人体图覆盖层（部位声明的选图界面）。
 *
 * 浮层**外壳**（遮罩 / 容器 / 抬头 / 关闭 / 进出节奏）全吃共享件 `.p-overlay*` 与 `.p-fade-*`
 * ——见设计文档 §3.8，**不新造一种浮层**；变体只用 `--xl`（左右分栏要宽）与 `--tall`（图要高）。
 *
 * **图上"形"是本组件的资产，"词"是后端的**：`GET /api/chat/parts` 给部位词表，组件只提供
 * 区域怎么画、每个区域装哪些词。所以管理员停用一个词，这里立刻就不给了（不需要改前端代码），
 * 反而**不新增**部位词时图上不会出现新区域——这是刻意的取舍，不是漏做。
 *
 * 所有拼句/声明规则都在 `utils/bodyMap.js`，这里只管画与点。
 *
 * 覆盖层**自管数据**（打开时拉词表），调用方只管 `open`；选定结果经 `submit` 单出口交出。
 */
const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'submit'])

/** 正反两面。背面是硬需求：背痛、腰痛在正面图上根本指不出来 */
const FACES = [{ id: 'front', label: '正面' }, { id: 'back', label: '背面' }]

const state = ref(emptyState())
const vocabulary = ref([])
const loading = ref(false)
const loadError = ref('')

const activeRegion = computed(() => (state.value.act ? regionById(state.value.act) : null))
const subWords = computed(() => visibleWords(activeRegion.value, vocabulary.value))
const globalWords = computed(() => visibleWords({ words: [...GLOBAL_WORDS] }, vocabulary.value))
const previewText = computed(() => sentence(state.value.picked, state.value.feelings, state.value.side) || NO_LOCATION_HINT)
const canFill = computed(() => state.value.picked.length > 0)

/** 已选区：部位（含方位前缀的显示形态）、方位、感觉三类，各自带一个删除口 */
const pickedItems = computed(() => {
  const s = state.value
  const out = []
  if (s.side) {
    out.push({ key: `side:${s.side}`, text: sideLabel(s.side), kind: 'side', remove: () => { state.value = { ...s, side: null } } })
  }
  const pre = s.side ? s.side : ''
  s.picked.forEach((w) => {
    out.push({
      key: `part:${w}`,
      text: isGlobalWord(w) ? w : pre + w,
      kind: 'part',
      remove: () => { state.value = removeWord(s, w) }
    })
  })
  s.feelings.forEach((f) => {
    out.push({ key: `feel:${f}`, text: f, kind: 'feel', remove: () => { state.value = removeFeeling(s, f) } })
  })
  return out
})

/** 该区域是否有已选中的词（图上要看得见选中了） */
function isRegionSelected(regionId) {
  const region = regionById(regionId)
  if (!region) return false
  return region.words.some((w) => state.value.picked.includes(w))
}

function activate(id) {
  state.value = { ...state.value, act: state.value.act === id ? null : id }
}

function pick(word) {
  state.value = selectWord(state.value, word)
}

/**
 * 图上的"形"（本组件的资产）：每块区域画成什么图元、坐标在哪。
 * 键是"面:区域:序号"——同一区域在一张图上可能有多块（上肢左右各一），序号只为 v-for 稳定。
 *
 * 坐标是 220×360 视图下的手绘比例，不追求解剖学准确：**目标是"患者能指准"**——
 * 头/颈/肩/胸/腹/髋/上肢/手/下肢/膝/足的落位与日常说这几个部位时的手势一致。
 * 正面与背面刻意画成同一个轮廓、只换躯干中间那块（正面胸腹、背面背腰），
 * 免得患者在两张图之间找不到"我的头"在哪儿。
 */
const FIGURES = Object.freeze({
  front: [
    { id: 'head', tag: 'ellipse', attrs: { cx: 110, cy: 36, rx: 26, ry: 31 } },
    { id: 'neck', tag: 'rect', attrs: { x: 100, y: 69, width: 20, height: 14, rx: 4 } },
    { id: 'shoulder', tag: 'rect', attrs: { x: 46, y: 84, width: 30, height: 24, rx: 7 } },
    { id: 'shoulder', tag: 'rect', attrs: { x: 144, y: 84, width: 30, height: 24, rx: 7 } },
    { id: 'chest', tag: 'rect', attrs: { x: 77, y: 84, width: 66, height: 48, rx: 7 } },
    { id: 'arm', tag: 'rect', attrs: { x: 48, y: 110, width: 24, height: 96, rx: 10 } },
    { id: 'arm', tag: 'rect', attrs: { x: 148, y: 110, width: 24, height: 96, rx: 10 } },
    { id: 'hand', tag: 'circle', attrs: { cx: 60, cy: 220, r: 12 } },
    { id: 'hand', tag: 'circle', attrs: { cx: 160, cy: 220, r: 12 } },
    { id: 'abd', tag: 'rect', attrs: { x: 80, y: 134, width: 60, height: 62, rx: 7 } },
    { id: 'hip', tag: 'rect', attrs: { x: 76, y: 198, width: 68, height: 26, rx: 7 } },
    { id: 'leg', tag: 'rect', attrs: { x: 82, y: 226, width: 24, height: 44, rx: 8 } },
    { id: 'leg', tag: 'rect', attrs: { x: 114, y: 226, width: 24, height: 44, rx: 8 } },
    { id: 'knee', tag: 'circle', attrs: { cx: 94, cy: 286, r: 14 } },
    { id: 'knee', tag: 'circle', attrs: { cx: 126, cy: 286, r: 14 } },
    { id: 'leg', tag: 'rect', attrs: { x: 82, y: 302, width: 24, height: 34, rx: 8 } },
    { id: 'leg', tag: 'rect', attrs: { x: 114, y: 302, width: 24, height: 34, rx: 8 } },
    { id: 'foot', tag: 'rect', attrs: { x: 78, y: 338, width: 30, height: 15, rx: 6 } },
    { id: 'foot', tag: 'rect', attrs: { x: 112, y: 338, width: 30, height: 15, rx: 6 } }
  ],
  back: [
    { id: 'head', tag: 'ellipse', attrs: { cx: 110, cy: 36, rx: 26, ry: 31 } },
    { id: 'neck', tag: 'rect', attrs: { x: 100, y: 69, width: 20, height: 14, rx: 4 } },
    { id: 'shoulder', tag: 'rect', attrs: { x: 46, y: 84, width: 30, height: 24, rx: 7 } },
    { id: 'shoulder', tag: 'rect', attrs: { x: 144, y: 84, width: 30, height: 24, rx: 7 } },
    // 背面没有胸腹，取而代之是背 + 腰：这两块只在背面画
    { id: 'back', tag: 'rect', attrs: { x: 77, y: 84, width: 66, height: 70, rx: 7 } },
    { id: 'arm', tag: 'rect', attrs: { x: 48, y: 110, width: 24, height: 96, rx: 10 } },
    { id: 'arm', tag: 'rect', attrs: { x: 148, y: 110, width: 24, height: 96, rx: 10 } },
    { id: 'hand', tag: 'circle', attrs: { cx: 60, cy: 220, r: 12 } },
    { id: 'hand', tag: 'circle', attrs: { cx: 160, cy: 220, r: 12 } },
    { id: 'waist', tag: 'rect', attrs: { x: 80, y: 156, width: 60, height: 68, rx: 7 } },
    { id: 'leg', tag: 'rect', attrs: { x: 82, y: 226, width: 24, height: 44, rx: 8 } },
    { id: 'leg', tag: 'rect', attrs: { x: 114, y: 226, width: 24, height: 44, rx: 8 } },
    { id: 'knee', tag: 'circle', attrs: { cx: 94, cy: 286, r: 14 } },
    { id: 'knee', tag: 'circle', attrs: { cx: 126, cy: 286, r: 14 } },
    { id: 'leg', tag: 'rect', attrs: { x: 82, y: 302, width: 24, height: 34, rx: 8 } },
    { id: 'leg', tag: 'rect', attrs: { x: 114, y: 302, width: 24, height: 34, rx: 8 } },
    { id: 'foot', tag: 'rect', attrs: { x: 78, y: 338, width: 30, height: 15, rx: 6 } },
    { id: 'foot', tag: 'rect', attrs: { x: 112, y: 338, width: 30, height: 15, rx: 6 } }
  ]
})

function shapesFor(faceId) {
  return (FIGURES[faceId] || []).map((shape, i) => ({ ...shape, key: `${faceId}:${shape.id}:${i}` }))
}

/** 「填 入」：把句子交给调用方落进输入框（**不自动发送**，患者还要补话） */
function fill() {
  if (!canFill.value) return
  emit('submit', {
    locations: state.value.picked.map((w) => (isGlobalWord(w) ? w : (state.value.side || '') + w)),
    sentence: sentence(state.value.picked, state.value.feelings, state.value.side)
  })
  close()
}

/**
 * 「说不清在哪儿 · 直接描述」：必填的**出口**。
 * 发烧、乏力、浑身没劲、头晕这类主诉根本指不出位置，点了它声明为空 ⇒ 链路回落到今天的行为。
 * **这是接受的降级，不是待修的缺陷**（见 CONTEXT.md「部位声明」）。
 */
function giveUp() {
  emit('submit', { locations: [], sentence: '' })
  close()
}

function close() {
  emit('update:open', false)
}

/** 每次打开都从零开始：上一次选过的部位不该在这一次里幽灵般地预选着 */
watch(() => props.open, async (open) => {
  if (!open) return
  state.value = emptyState()
  await loadVocabulary()
})

async function loadVocabulary() {
  if (vocabulary.value.length) return
  loading.value = true
  loadError.value = ''
  try {
    vocabulary.value = await listBodyParts()
  } catch (e) {
    // 取不到词表时**照常可用**：bodyMap 的 visibleWords 在空表时回落到全量词，
    // 否则一次网络抖动就把"选部位"这个功能整个锁死
    loadError.value = '部位词表没取到，暂时按本地点位显示'
  } finally {
    loading.value = false
  }
}

/** ESC 关闭：既有的浮层（健康档案）没做这条，但这是个长流程的中途退出，值得给 */
function onKeydown(e) {
  if (e.key === 'Escape' && props.open) close()
}
onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
/* 组件私有样式：外壳全吃共享件，这里只有图与选栏。
   同名类不许两处实现——所以下面凡带 p-map__ 前缀的都是本组件独有的部件。*/
.p-map__box { max-width: 760px; }

.p-map__bd {
  flex: 1;
  min-height: 0;
  display: flex;
}

/* 左图 */
.p-map__fig {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 14px 10px 10px 20px;
  overflow: hidden;
}
.p-map__canvas {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.p-map__svg {
  height: 100%;
  max-height: 340px;
  width: auto;
  overflow: visible;
}
.p-map__rg {
  fill: #E9F1EE;
  stroke: #B9D2CB;
  stroke-width: 1.5;
  cursor: pointer;
  transition: fill .16s ease, stroke .16s ease;
}
.p-map__rg:hover { fill: #CBE6DE; stroke: var(--leaf); }
.p-map__rg.is-act { fill: #CBE6DE; stroke: var(--leaf); stroke-width: 2.5; }
/* 选中态必须"看得出"：填实 + 深描边，两条一起变（只变色在灰底图上读不出来） */
.p-map__rg.is-sel { fill: var(--leaf); stroke: var(--leaf-deep); }
.p-map__rg.is-sel.is-act { stroke: var(--apricot-deep); stroke-width: 2.5; }

.p-map__views {
  display: flex;
  flex: none;
  background: var(--rail);
  border-radius: var(--r-sm);
  padding: 3px;
}
.p-map__viewbtn {
  min-height: 44px;
  padding: 0 20px;
  border-radius: var(--r-xs);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink-2);
  cursor: pointer;
  transition: background .18s ease, color .18s ease;
}
.p-map__viewbtn.is-on {
  background: var(--surface);
  color: var(--leaf-deep);
  font-weight: 600;
  box-shadow: var(--sh-raised);
}
.p-map__tip { flex: none; font-size: 10.5px; color: var(--ink-3); }

/* 右选栏 */
.p-map__pick {
  width: 272px;
  flex: none;
  border-left: 1px solid var(--line);
  background: var(--stage);
  overflow-y: auto;
}
.p-map__sec {
  padding: 13px 16px;
  border-bottom: 1px solid var(--line);
}
.p-map__sec--last { border-bottom: none; }
.p-map__lb {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .16em;
  color: var(--ink-2);
}
.p-map__lb em {
  font-style: normal;
  font-weight: 400;
  letter-spacing: 0;
  color: var(--leaf-deep);
}
.p-map__hint { font-size: 10.5px; line-height: 1.7; color: var(--ink-3); }
.p-map__chips { display: flex; flex-wrap: wrap; gap: 7px; }

.p-map__chip {
  min-height: 44px;
  padding: 0 14px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-full);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 12.5px;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color .18s ease, color .18s ease, background .18s ease;
}
.p-map__chip:hover { border-color: var(--leaf); color: var(--leaf-deep); background: var(--leaf-soft); }
.p-map__chip.is-on {
  background: var(--leaf);
  border-color: var(--leaf);
  color: var(--on-accent);
  font-weight: 600;
}

.p-map__picked { display: flex; flex-wrap: wrap; gap: 7px; align-items: center; }
.p-map__pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px 5px 12px;
  border-radius: var(--r-full);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
  font-size: 12px;
  font-weight: 600;
}
.p-map__pill--feel { background: var(--apricot-soft); color: var(--apricot-deep); }
.p-map__x {
  width: 22px;
  height: 22px;
  border-radius: var(--r-full);
  background: rgba(31, 138, 112, .2);
  color: inherit;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  transition: background .18s ease, color .18s ease;
}
.p-map__x:hover { background: var(--leaf); color: var(--on-accent); }
.p-map__pill--feel .p-map__x { background: rgba(232, 151, 74, .3); }
.p-map__pill--feel .p-map__x:hover { background: var(--apricot); color: var(--on-accent); }
.p-map__clear {
  min-height: 44px;
  padding: 0 6px;
  background: none;
  border: 0;
  font-family: var(--sans);
  font-size: 11px;
  color: var(--ink-3);
  cursor: pointer;
}
.p-map__clear:hover { color: var(--alert); }

/* 底部：预览 + 动作 */
.p-map__ft { display: flex; flex-direction: column; gap: 11px; }
.p-map__preview {
  display: flex;
  align-items: baseline;
  gap: 9px;
  padding: 9px 13px;
  border-radius: var(--r-sm);
  background: var(--apricot-soft);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--apricot-deep);
}
.p-map__previewk { flex: none; font-weight: 600; }
.p-map__preview b { font-weight: 600; }
.p-map__acts { display: flex; align-items: center; gap: 10px; }
.p-map__acts small { flex: 1; font-size: 10.5px; line-height: 1.6; color: var(--ink-3); }

/* 窄屏：图与选栏改成上下（各占一半高度），避免选栏被压成一条 */
@media (max-width: 640px) {
  .p-map__bd { flex-direction: column; }
  .p-map__fig { padding: 12px 16px 6px; }
  .p-map__pick { width: auto; border-left: none; border-top: 1px solid var(--line); }
  .p-map__acts { flex-wrap: wrap; }
  .p-map__acts small { order: 3; flex-basis: 100%; }
}
</style>