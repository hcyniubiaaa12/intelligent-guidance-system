<template>
  <Transition name="p-fade">
    <div v-if="open" class="p-overlay p-overlay--xl p-overlay--tall" @click.self="close">
      <div class="p-overlay__box p-map__box" role="dialog" aria-label="人体图选部位">
        <header class="p-overlay__head">
          <div>
            <div class="p-overlay__title">
              身 上 哪 里 不 舒 服
              <span class="p-map__must">必填</span>
            </div>
            <p class="p-overlay__sub">
              先锁住位置，我才能帮您判断科室。点一下图上不舒服的位置（可以选多个），
              再顺手点一下「哪一侧」和「什么感觉」。找不到就点下面的「说不清在哪儿」。
            </p>
          </div>
          <button class="p-overlay__close" aria-label="关闭人体图" @click="close">关 闭</button>
        </header>

        <div class="p-map__bd">
          <!-- 全停用：图上的词一个都不剩。与"取词失败"是两回事，页面必须说得清 -->
          <div v-if="allDisabled" class="p-map__dead">
            <p class="p-map__deadt">部位词暂时都下架了</p>
            <p class="p-map__deads">
              管理员在知识库里停用了全部部位词，所以现在点不出位置。<br>
              您可以点下面的<b>「说不清在哪儿 · 直接描述」</b>直接说话——那样也能正常问诊。
            </p>
          </div>

          <!-- 左：正反两面人体图 -->
          <div v-else class="p-map__fig">
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
          </div>

          <!-- 右：细分词 / 方位 / 感觉 / 已选 -->
          <div v-if="!allDisabled" class="p-map__pick">
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
              <p class="p-map__hint">左右在分诊里是有效信息：左上腹痛多在胃，右下腹痛要留意阑尾。</p>
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
              <p class="p-map__hint">
                <b>心 / 肺 / 胃 / 肠不在图上</b>——它们是器官，不是体表位置。等进了对话直接打字说"胃疼"就行。
              </p>
            </section>
          </div>
        </div>

        <footer class="p-overlay__foot p-map__ft">
          <p class="p-map__preview">
            <span class="p-map__previewk">会填进输入框</span>
            <b>{{ allDisabled ? '词都下架了，用「说不清在哪儿」直接说也行' : previewText }}</b>
          </p>
          <div class="p-map__acts">
            <button type="button" class="p-btn p-btn--ghost" @click="giveUp">说不清在哪儿 · 直接描述</button>
            <small>{{ allDisabled ? '' : '选完只填进输入框、不自动发送。' }}</small>
            <button type="button" class="p-btn" :disabled="!canFill || allDisabled" @click="fill">填 入</button>
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
  regionById, sentence, sideLabel, declarationLocations,
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
 *
 * `preset`（可选）：快捷词入口的**预选**——「肚子疼」这类 chip 点了直接打开图并选中对应区域，
 * 让必填变成省事而不是拦路。只在打开那一刻应用一次（患者随后取消预选是正常操作，
 * 不能每次 watch 都把它按回去）。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  /** { region: 'abd'|null, word: '腹部' } | null —— 打开时预选的区域与部位词 */
  preset: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'submit'])

/** 正反两面。背面是硬需求：背痛、腰痛在正面图上根本指不出来 */
const FACES = [{ id: 'front', label: '正面' }, { id: 'back', label: '背面' }]

const state = ref(emptyState())
const vocabulary = ref([])
const loading = ref(false)
/**
 * 词表状态三态，**不是两个**：
 *   'ready'  取到了（可能为空数组——管理员把部位词全停用了，那也是一种"取到了"）
 *   'failed' 取不到（网络/服务异常）→ 回落到本地点位，功能照常可用
 *   'loading'
 * 合并"失败"与"全停用"会犯一个方向相反的错：全停用时回落到全量，等于把管理员刚做的决定
 * 当成没发生，患者还能点到一个已下架的词。
 */
const loadState = ref('loading')

const activeRegion = computed(() => (state.value.act ? regionById(state.value.act) : null))
const subWords = computed(() => visibleWords(activeRegion.value, vocabulary.value))
const globalWords = computed(() => visibleWords({ words: [...GLOBAL_WORDS] }, vocabulary.value))
const previewText = computed(() => sentence(state.value.picked, state.value.feelings, state.value.side) || NO_LOCATION_HINT)
const canFill = computed(() => state.value.picked.length > 0)
/** 部位词真的一份都没了：告诉患者怎么回事，而不是给一个点不动的空图 */
const allDisabled = computed(() => loadState.value === 'ready' && !vocabulary.value.length)

/** 已选区：部位（含方位前缀的显示形态）、方位、感觉三类，各自带一个删除口 */
const pickedItems = computed(() => {
  const s = state.value
  const out = []
  if (s.side) {
    out.push({ key: `side:${s.side}`, text: sideLabel(s.side), kind: 'side', remove: () => { state.value = { ...s, side: null } } })
  }
  // 逐条对回声明里的同一个位置：声明与"已选"显示的是同一份事实，序号对得上，
  // 患者删第 2 条时不必自己在脑内换算"左腹部"是第几个词
  const locations = declarationLocations(s.picked, s.side)
  s.picked.forEach((word, i) => {
    out.push({
      key: `part:${word}`,
      text: locations[i],
      kind: 'part',
      remove: () => { state.value = removeWord(s, word) }
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
    // 两个字段都取自 bodyMap 的同一组函数，不在这里另拼一遍——前缀规则一改就会漏掉这一处
    locations: declarationLocations(state.value.picked, state.value.side),
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

/**
 * 每次打开都重取，**不做会话内缓存**。
 *
 * 后端那边管理端一停用就refresh()，是即时的；前端缓存一层就把这个即时性抵消了——
 * 患者重开浮层仍看到刚被停用的词，而管理端界面上明明白白写着它已停用。
 * 这个接口很轻（读一份内存词表），不值得为省它而让"停用"看起来没生效。
 *
 * ⚠️ `immediate: true` 不能省：**组件可能挂载时就是打开的**（首屏由父组件直接传 open=true），
 * 那时 `open` 从 true 变 true，watch 一次都不触发 ⇒ 词表永远不加载、界面退回本地点位。
 * 省掉它不会报错，只是"静默降级"——正是这个仓库反复记的那类故障。
 */
watch(() => props.open, async (open) => {
  if (!open) return
  // 每次打开都从零开始：上一次选过的部位不该在这一次里幽灵般地预选着
  state.value = emptyState()
  // 快捷词的预选：只在这一个时机应用一次——患者随后取消是正常操作，
  // 不能在后续的重渲染里把他的取消又按回预选态
  const preset = props.preset
  if (preset && preset.word) {
    state.value = { ...state.value, act: preset.region || null, picked: [preset.word] }
  }
  await loadVocabulary()
}, { immediate: true })

/**
 * **每次打开都重取**，不做会话内缓存。
 *
 * 后端那边管理端一停用就 refresh()，是即时的；前端缓存一层就把这个即时性抵消了——
 * 患者重开浮层仍看到刚被停用的词，而管理端界面上明明白白写着它已停用。
 * 这个接口很轻（读一份内存词表），不值得为省它而让"停用"看起来没生效。
 */
async function loadVocabulary() {
  loading.value = true
  loadState.value = 'loading'
  try {
    const list = await listBodyParts()
    vocabulary.value = Array.isArray(list) ? list : []
    loadState.value = 'ready'
  } catch (e) {
    // 取不到就清空词表：visibleWords 在空表时回落到本地点位，功能不锁死
    vocabulary.value = []
    loadState.value = 'failed'
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
/* container-type: inline-size —— 让下面那条 @container 能按**浮层自身宽度**切布局。
   浮层是 width:100% + max-width:760px，所以它的实际宽度在手机上约 335、桌面 760，
   而视口宽度未必同步（分屏、内嵌宿主容器），只按视口判会漏。*/
.p-map__box { max-width: 760px; container-type: inline-size; }

/* 「必填」徽标（草案 06）：贴在标题右边，10px 小胶囊 */
.p-map__must {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 8px;
  border-radius: var(--r-full);
  background: var(--leaf);
  color: var(--on-accent);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .1em;
  vertical-align: 1px;
}

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

/* 全停用：占满整个身体区的一句话说明 + 那个出口。刻意不给"图"——
   一个点下去什么都不发生的图，比没有图更让人以为系统坏了 */
.p-map__dead {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 24px 32px;
  text-align: center;
}
.p-map__deadt { font-size: 15px; font-weight: 600; color: var(--ink); }
.p-map__deads { font-size: 12.5px; line-height: 1.9; color: var(--ink-2); }
.p-map__deads b { color: var(--leaf-deep); font-weight: 600; }

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
/* 移除按钮：视觉上是 22px 的小圆点，但**可点区域撑到 44px**——
   小 × 是"看着不挤"的常规做法，直接给 44px 圆点会把已选区撑成一串大按钮。
   撑法是 padding + 负 margin（不改变布局），触屏与鼠标都拿到 44px。 */
.p-map__x {
  width: 44px;
  height: 44px;
  margin: -11px -11px -11px 0;
  border-radius: var(--r-full);
  background: none;
  color: inherit;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  transition: background .18s ease, color .18s ease;
}
/* 圆点本体用 ::before 画，尺寸回到 22px：小 × 是"看着不挤"的常规做法，
   直接给 44px 圆点会把已选区撑成一串大按钮 */
.p-map__x::before {
  content: '×';
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin-left: 11px;
  border-radius: var(--r-full);
  background: rgba(31, 138, 112, .2);
}
.p-map__x:hover::before { background: var(--leaf); color: var(--on-accent); }
.p-map__pill--feel .p-map__x::before { background: rgba(232, 151, 74, .3); }
.p-map__pill--feel .p-map__x:hover::before { background: var(--apricot); color: var(--on-accent); }
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
/* 两颗按钮在同一行，中间夹一句说明。
   `.p-btn` 共享件是 **width:100%**（表单页脚那个"保存档案"就该通栏），这里三颗挤在一行，
   必须改回内容宽——**只写 align-self 不够**，100% 是宽度不是拉伸。
   宽度由内容决定后，那句说明才拿得到剩余空间（flex:1 + min-width：窄面板下换行，不被挤成
   "一列一个字"——那是被挤没，不是断行）。 */
.p-map__acts { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.p-map__acts small { flex: 1 1 200px; min-width: 200px; font-size: 10.5px; line-height: 1.6; color: var(--ink-3); }
.p-map__acts .p-btn { width: auto; align-self: flex-start; }

/* 窄屏：图与选栏改成上下。
   断点按**浮层自身宽度**判（`@container`），不按视口——手机视口窄、桌面浮层宽，
   但分屏或内嵌宿主容器时"视口宽、浮层窄"也会发生，只看视口会漏。

   ⚠️ 竖排时**整列作为一个滚动容器**，两块各自按内容取高。走过三段弯路才定成这样：
   ① 分高度（各 flex:1）——横排那套 min-height:0 的分高机制在竖排下失效，图只剩 18px；
   ② 只给图 min-height ——图按比例撑到 493px，把选栏挤成 1px；
   ③ 两块各自 auto 但父级不滚 ——超出容器的部分**压在底部动作条上面**（实测）。
   单一滚动容器避开全部三个：内容多就滚，不互相挤压，也不会有东西溢出到别的区域。 */
@container (max-width: 560px) {
  .p-map__bd { flex-direction: column; overflow-y: auto; }
  .p-map__fig { flex: 0 0 auto; padding: 12px 16px 6px; }
  .p-map__canvas { overflow: hidden; }
  /* 宽度定尺寸、高度按 220:360 的比例自己算。**不能给 height:100%**（按容器高缩，下半截被裁），
     也不给 min-height（顶穿容器回到第③段那个坑）。 */
  .p-map__svg { width: 100%; height: auto; max-height: none; }
  .p-map__pick {
    flex: 0 0 auto;
    width: auto;
    min-height: 0;
    border-left: none;
    border-top: 1px solid var(--line);
    overflow: visible;
  }
  .p-map__acts small { order: 3; flex-basis: 100%; min-width: 0; }
}
</style>