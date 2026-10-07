<template>
  <div class="patient-root p-chat">
    <div class="p-chat__body">
      <!-- ---------- 左：我的就诊（会话目录，桌面常驻 / 手机覆盖层） ----------
           侧栏自管会话列表状态，页面只告诉它"正在看哪一条"，其余靠 reload / showBooked -->
      <SideRail
        ref="rail"
        :active-session-id="activeSessionId"
        :nickname="user.nickname"
        :mobile-open="sideOpen"
        @select="openSession"
        @new="startNew"
        @close-mobile="sideOpen = false"
        @open-booked="openBooked"
        @open-profile="openProfile"
        @logout="onLogout"
        @archive-change="onArchiveChange"
      />

      <!-- ---------- 中：对话舞台 ---------- -->
      <div class="p-chat__stage">
        <!-- 顶栏只剩手机端：桌面端整条移除——标题、用户区都归左侧栏，对话区顶上不再有一条横线。
             （手机没有侧栏可挂，只能留在顶栏） -->
        <header class="p-topbar p-chat__topbar">
          <button class="p-chat__menubtn" @click="sideOpen = true">会 话</button>
          <div class="p-topbar__title">智能导诊</div>
          <div class="p-topbar__ops">
            <button class="p-topbar__nick" title="看我的挂号历史" @click="openBooked">{{ user.nickname || '我 的 就 诊' }}</button>
            <button class="p-topbar__logout" @click="onLogout">退 出</button>
          </div>
        </header>

        <!-- 对话区：有消息时＝对话流＋流程条＋底部输入；空状态时＝欢迎块＋输入框＋免责 一起居中 -->
        <div class="p-chat__main" :class="{ 'is-empty': isEmpty }">
          <main ref="threadEl" class="p-thread p-chat__thread">
            <!-- 空状态：一张还没填的导诊卡 -->
            <EmptyState
              v-if="isEmpty"
              :stage="partStage"
              :part-label="partLabel"
              @pick-part="mapOpen = true"
              @quick="onQuickPick"
              @rechoose="rechooseParts"
            />

            <!-- 回放与实时共用同一套条目渲染：shown = 回放条目 或 本次对话条目 -->
            <template v-else>
              <template v-for="(m, i) in shown" :key="i">
                <!-- 用户消息：实心主色气泡（回放时带问题编号，书签靠它定位） -->
                <div
                  v-if="m.type === 'user'"
                  :ref="(el) => setQRef(el, m.qNo)"
                  class="p-q"
                  :class="{ 'is-active': m.qNo && m.qNo === activeQ }"
                >
                  <div class="p-q__col">
                    <div class="p-user">{{ m.content }}</div>
                    <CopyButton
                      :copied="copiedKey === 'u' + i"
                      aria-label="复制这条主诉"
                      @copy="copyText('u' + i, m.content)"
                    />
                  </div>
                </div>

                <!-- 追问消息：与普通回复同款 -->
                <div v-else-if="m.type === 'question'" class="p-ai">
                  <span class="p-ai__ava" aria-hidden="true">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
                  </span>
                  <div class="p-ai__bd">
                  <div class="p-ai__tag">陪 诊 助 手 · 追 问</div>
                  <div class="p-ai__text p-ask">{{ m.content }}</div>
                  <CopyButton
                    v-if="m.content"
                    :copied="copiedKey === 'q' + i"
                    aria-label="复制这条追问"
                    @copy="copyText('q' + i, m.content)"
                  />
                  </div>
                </div>

                <!-- 资料回答：患者问的是知识库内容，系统如实复述片段作答（不推荐科室、不是追问）
                     与追问一样复用 AI 气泡的形状，只有标签不同——患者一眼能分出这不是分诊结论 -->
                <div v-else-if="m.type === 'info'" class="p-ai">
                  <span class="p-ai__ava" aria-hidden="true">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
                  </span>
                  <div class="p-ai__bd">
                  <div class="p-ai__tag">陪 诊 助 手 · 资 料</div>
                  <div class="p-ai__text">{{ m.content }}</div>
                  <CopyButton
                    v-if="m.content"
                    :copied="copiedKey === 'if' + i"
                    aria-label="复制这条资料回答"
                    @copy="copyText('if' + i, m.content)"
                  />
                  </div>
                </div>

                <!-- AI 回复：直排文字 + 主色小标签（SSE delta 逐字填充同一文本节点）
                     本块必须单行书写：.p-ai__text 是 pre-wrap，换行缩进会被原样渲染 -->
                <div v-else-if="m.type === 'ai'" class="p-ai">
                  <span class="p-ai__ava" aria-hidden="true">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16v11H8l-4 4z"/><path d="M12 9v5M9.5 11.5h5"/></svg>
                  </span>
                  <div class="p-ai__bd">
                  <div class="p-ai__tag">陪 诊 助 手</div>
                  <div class="p-ai__text">{{ m.content }}<span v-if="!isReplay && chat.streaming && i === shown.length - 1 && !m.content" class="p-ai__wait">正在整理…</span></div>
                  <CopyButton
                    v-if="m.content"
                    :copied="copiedKey === 'a' + i"
                    aria-label="复制这条回复"
                    @copy="copyText('a' + i, m.content)"
                  />
                  </div>
                </div>

                <!-- 处置提示（敏感词累计触发的警告）：单独成泡，视觉上与诊断结论区分开 -->
                <div v-else-if="m.type === 'notice'" class="p-notice">{{ m.content }}</div>

                <!-- 推荐卡（签名元素 · 链路 A 结论单）：只有最新一张能继续挂号；回放时整张只读 -->
                <ConclusionCard
                  v-else-if="m.type === 'card'"
                  :entry="m"
                  :replay="isReplay"
                  :can-register="i === lastCardIndex"
                  @register="goRegister(m)"
                />

                <!-- SSE error：line 描边块，文案说清原因与下一步 -->
                <div v-else-if="m.type === 'error'" class="p-error">
                  {{ m.message }}
                  <button class="p-error__retry" @click="retry(m)">重新发送</button>
                </div>
              </template>
            </template>
          </main>

          <!-- 步进流程条：① 导诊结论 → ② 模拟挂号 → ③ 确认完成（空状态时还没有任何步骤，不显示） -->
          <nav v-if="!isEmpty" class="p-steps">
            <span class="p-steps__item p-steps__item--now"><span class="p-steps__no">1</span>导诊结论</span>
            <span class="p-steps__link" />
            <span class="p-steps__item"><span class="p-steps__no">2</span>模拟挂号</span>
            <span class="p-steps__link" />
            <span class="p-steps__item"><span class="p-steps__no">3</span>确认完成</span>
          </nav>

<!-- 输入区：一整块「书写区」——上面写字，下面一行小字＋发送。
             看历史时换成只读条——一次会话 = 一次就诊，翻旧账不能往里写字。
             等待态（新会话还没声明部位，草案 06）：输入框只读 + 发送不可用。
             「人体图选部位」小按钮**等待态也不禁**——草案里它禁用是因为引导区有大按钮兜着，
             但结论卡出现后 EmptyState 已不在，禁掉它就是死路（声明就没了入口）。 -->
          <footer v-if="!isReplay" class="p-composer" :class="{ 'is-waiting': partStage === 'awaiting' }">
            <div class="p-composer__box">
              <textarea
                ref="inputEl"
                v-model="draft"
                class="p-composer__input"
                rows="1"
                :readonly="partStage === 'awaiting'"
                :placeholder="partStage === 'awaiting'
                  ? '先在上面选好位置，就能开始说了…'
                  : (isEmpty ? '接着说一句，我边听边判断…' : '还有什么想补充的，慢慢说…')"
                @input="autoGrow"
                @keydown.enter.exact.prevent="send()"
              />
              <div class="p-composer__bar">
                <button
                  type="button"
                  class="p-composer__map"
                  :disabled="chat.streaming"
                  @click="mapOpen = true"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="5" r="2.4"/><path d="M12 7.6v6M12 13.6l-3.2 6M12 13.6l3.2 6M7.6 10h8.8"/></svg>
                  人体图选部位
                </button>
                <span class="p-composer__hint">分诊建议，不能替代医生诊断</span>
                <button
                  class="p-composer__send"
                  :disabled="chat.streaming || partStage === 'awaiting' || !draft.trim()"
                  aria-label="发送"
                  @click="send()"
                >
                  <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
                    <path d="M8 13.5V3M3.2 7.8 8 3l4.8 4.8" fill="none" stroke="currentColor" stroke-width="1.8" />
                  </svg>
                </button>
              </div>
            </div>
          </footer>
          <div v-else class="p-chat__lock">
            <span class="p-chat__locktxt">历 史 记 录 · 只 读</span>
            <button class="p-chat__lockbtn" @click="startNew">开始新的咨询</button>
          </div>
        </div>
      </div>

      <!-- ---------- 右缘：问题导航（只在回放时）——悬浮面板，鼠标移到右缘就展开 ----------
           面板脱离布局流（absolute）：进出回放不会把中间那一列顶来顶去。
           一根 = 这条会话里患者的一个问题，自上而下 = 问题 1 → 最新一问，跟正文同向 -->
      <nav
        v-if="isReplay && marks.length"
        class="p-chat__marks"
        :class="{ 'is-open': marksOpen }"
        aria-label="用户问题"
        @mouseenter="marksOpen = true"
        @mouseleave="marksOpen = false"
      >
        <button class="p-chat__marksgrip" aria-label="展开问题导航" @click="onGripClick" />
        <div class="p-chat__markspanel">
          <div class="p-chat__markshd">
            <span class="p-eyebrow">用 户 问 题</span>
            <span class="p-chat__marksno">{{ activeQ ? `${activeQ} / ${marks.length}` : `${marks.length} 问` }}</span>
          </div>
          <!-- 放不下时面板内部自己滚（滚动条不画），滚到头再滚正文 -->
          <div
            ref="marksEl"
            class="p-chat__markset"
            :class="{ 'is-scroll': marksScroll }"
            @wheel="onMarksWheel"
          >
            <button
              v-for="q in marks"
              :key="q.no"
              :ref="(el) => setMarkRef(el, q.no)"
              class="p-mark"
              :class="{ on: q.no === activeQ }"
              :title="`问题 ${q.no} · ${hhmm(q.at)}`"
              :aria-label="`跳到问题 ${q.no}：${q.content}`"
              @click="jump(q.no)"
            >
              <span class="p-mark__x">{{ q.content }}</span>
              <span class="p-mark__bar" />
            </button>
          </div>
        </div>
      </nav>

      <!-- ---------- 健康档案（选填）：浮层。自带加载与保存，页面只管开关 ---------- -->
      <HealthProfileOverlay v-model:open="profileOpen" />

      <!-- ---------- 人体图选部位：浮层。外壳与档案共用浮层语言，页面只管开关、预选与提交 ---------- -->
      <BodyMapOverlay v-model:open="mapOpen" :preset="mapPreset" @submit="onPartsSubmit" />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { useChatStore } from '../../stores/chat'
import { logout as apiLogout } from '../../api/auth'
import { sessionDetail } from '../../api/records'
import { streamChat } from '../../utils/sse'
import { track } from '../../utils/track'
import { rankedTop3 } from '../../utils/conclusion'
import { copyToClipboard } from '../../utils/clipboard'
import { pickedLabel } from '../../utils/bodyMap'
import SideRail from '../../components/patient/SideRail.vue'
import EmptyState from '../../components/patient/EmptyState.vue'
import ConclusionCard from '../../components/patient/ConclusionCard.vue'
import HealthProfileOverlay from '../../components/patient/HealthProfileOverlay.vue'
import BodyMapOverlay from '../../components/patient/BodyMapOverlay.vue'
import CopyButton from '../../components/patient/CopyButton.vue'
import '../../styles/patient.css'

/**
 * 对话页 —— 现在只是一层**壳**：左栏、空状态、推荐卡、健康档案各自成组件，
 * 这里留的是「消息流 / 书签 / 步进条 / 输入区」这条主线与 SSE 编排。
 * 拆出去的四块与留在本页的判据是「状态归谁」：谁的字段谁管
 * （会话列表归 SideRail、档案表单归 HealthProfileOverlay、结论的纯派生归 utils/conclusion.js）。
 */
const router = useRouter()
const user = useUserStore()
const chat = useChatStore()

const draft = ref('')
const threadEl = ref(null)
const inputEl = ref(null)
const rail = ref(null)
/** 手机端左栏是覆盖层，展开与否归本页管（组件只负责渲染形态） */
const sideOpen = ref(false)
/** 健康档案浮层的开关 */
const profileOpen = ref(false)

/**
 * 部位声明的两样东西与它的**三态机**（草案 06：引导 → 选图 → 已锁定）。
 *
 * `partStage` 是门禁，不是装饰——「必填的是这一步动作，不是必须点中某个区域」：
 *   awaiting：新会话的首条输入还没准备好，输入框是等待态（发送不可用）；
 *   locked  ：声明完成。选了部位、点了「说不清在哪儿」都算——出口是完成声明的一种方式，
 *             不是失败；区别只写在回执文案里（`parts.locations` 是否为空）。
 *
 * 什么时候回到 awaiting（与后端「新主诉判定」同口径，**以后端状态为准**）：
 *   - startNew()：患者主动开新咨询；
 *   - done 事件带回 hasResult=1：已出结论，下一句输入就是新会话（追问轮、归档续聊不在此列——
 *     它们期间 partStage 一直是 locked，不会被拦）。
 * 回放是只读的，根本没有输入框，天然不涉及门禁。
 */
const parts = ref(emptyParts())
const partStage = ref('awaiting') // 'awaiting' | 'locked'
const mapOpen = ref(false)
/** 快捷词带来的预选（{ region, word } | null）：交给覆盖层，只在打开那一刻生效 */
const mapPreset = ref(null)

/** 空声明：开新咨询、重选、登出三处都要用，字面量只写这一处 */
function emptyParts() {
  return { locations: [], sentence: '' }
}

/** 空状态三格里的 ① 部位显示什么（锁定后是回执的一部分） */
const partLabel = computed(() => pickedLabel(parts.value.locations))

/** 覆盖层交回来的结果：句子落进输入框，声明单独存着，门禁随之解除 */
function onPartsSubmit(result) {
  parts.value = { locations: result.locations, sentence: result.sentence }
  partStage.value = 'locked'
  if (result.sentence) {
    const now = draft.value.trim()
    // 输入框里已有患者自己写的话（如从"说不清"出来又重选过）就接在后面，不覆盖——
    // 可编辑文本被静默顶掉属于不可接受的数据丢失
    draft.value = now ? `${result.sentence}${now}` : result.sentence
    nextTick(autoGrow)
  }
  inputEl.value?.focus()
}

/** 「位置选错了？重新选」（草案 06 的 btnReset）：清空重来，回等待态 */
function rechooseParts() {
  parts.value = emptyParts()
  partStage.value = 'awaiting'
  draft.value = ''
  nextTick(() => inputEl.value?.focus())
}

/** 快捷词（草案 06）：带着预选打开图——必填变成省事，而不是一道拦路的关卡 */
function onQuickPick(pick) {
  mapPreset.value = { region: pick.region, word: pick.word }
  mapOpen.value = true
}

/** 输入框随内容长高（120px 封顶后自己滚）——别让一段长主诉挤在一条缝里写 */
function autoGrow() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 120)}px`
}
// 当前流的终止句柄：离开页面或退出登录时中断，避免回调写已卸载的组件
let turnAbort = null

// ---------------------------------------------------------------- 回放

const replay = ref(null)
const activeQ = ref(null)
const qRefs = ref({})

const isReplay = computed(() => !!replay.value)
/** 一句话都还没说过：空状态把欢迎块、输入框、免责当成一整块居中 */
const isEmpty = computed(() => !replay.value && chat.entries.length === 0)
/** 渲染的统一来源：看历史用回放条目，否则用本次对话条目 */
const shown = computed(() => (replay.value ? replay.value.entries : chat.entries))
const marks = computed(() => replay.value?.questions || [])

/** 正在看的是哪一条：看历史就是历史的，实时对话就是本轮的（侧栏据此高亮） */
const activeSessionId = computed(() => replay.value?.session?.id || chat.sessionId || null)

/** 把 RecordService 的详情转成对话区能渲染的条目——与实时 SSE 出来的形状保持一致 */
function toReplayEntries(d) {
  const out = (d.messages || []).map((m) => ({
    // 追问与资料回答在回放里也要保持各自的定性（否则同一条消息实时看是「· 资料」、历史里变成普通回复）
    type: m.role === 'user' ? 'user' : m.role === 'question' ? 'question' : m.role === 'info' ? 'info' : 'ai',
    content: m.content,
    qNo: m.role === 'user' ? m.questionNo : undefined
  }))
  if (d.card) out.push({ type: 'card', recordId: null, card: { ...d.card } })
  return out
}

async function openSession(id) {
  sideOpen.value = false
  try {
    const d = await sessionDetail(id)
    replay.value = { session: d.session, entries: toReplayEntries(d), questions: d.questions || [] }
    activeQ.value = null
    qRefs.value = {}
    markRefs.value = {}
    marksOpen.value = false // 每进一次回放都从收起态开始——面板别自己摊在正文上
    await nextTick()
    if (threadEl.value) threadEl.value.scrollTop = 0
    watchMarks()
  } catch (e) {
    console.error('[chat] 回放失败', e)
  }
}

/** 开一段全新咨询：撤回放、清对话态（下一条消息即新会话），光标直接落进输入框 */
function startNew() {
  turnAbort?.abort()
  replay.value = null
  activeQ.value = null
  sideOpen.value = false
  chat.reset()
  // 声明必须一起清：上一轮选过的部位漏进新会话，会让模型拿到一条与新主诉无关的"已知位置"。
  // 门禁也回等待态——新的一次就诊要重新声明（工单 05）
  parts.value = emptyParts()
  partStage.value = 'awaiting'
  draft.value = ''
  nextTick(() => inputEl.value?.focus())
}

/**
 * 侧栏把一条会话收进了归档 / 取回了。归档的若正是当前正在看（回放）或正在聊的那条，
 * **把视图一起收掉**——它已经不在列表主区了，还留在对话区会让人以为归档没生效；
 * 正在聊的那条则回到"新咨询"状态（新消息不会再回到归档会话）。
 */
function onArchiveChange({ session, archived }) {
  if (!archived) return
  if (replay.value?.session?.id === session.id) replay.value = null
  else if (chat.sessionId === session.id) chat.reset()
}

function openBooked() {
  rail.value?.showBooked()
  sideOpen.value = true
}

function openProfile() {
  profileOpen.value = true
  sideOpen.value = false
}

// ---------------------------------------------------------------- 书签

const marksEl = ref(null)
const markRefs = ref({})
const marksScroll = ref(false)
const marksOpen = ref(false)

function setQRef(el, no) {
  if (el && no) qRefs.value[no] = el
}

function setMarkRef(el, no) {
  if (el && no) markRefs.value[no] = el
}

/** 触屏没有 hover：点一下把手切换面板（桌面端交给 mouseenter） */
function onGripClick() {
  if (window.matchMedia?.('(hover: none)').matches) marksOpen.value = !marksOpen.value
}

/** 面板内是否放不下：放不下才给它画上下渐隐（不溢出时渐隐会把最后一条抹淡） */
function syncMarksScroll() {
  const el = marksEl.value
  marksScroll.value = !!el && el.scrollHeight > el.clientHeight + 1
}

let marksRO = null
function watchMarks() {
  const el = marksEl.value
  if (!el || !marksRO) return
  marksRO.disconnect()
  marksRO.observe(el)
  syncMarksScroll()
}

/** 滚轮落在面板上：面板自己没滚到头就归面板，滚到头（或本来就放得下）转给正文 */
function onMarksWheel(e) {
  const el = marksEl.value
  if (!el) return
  if (marksScroll.value) {
    const atTop = el.scrollTop <= 0 && e.deltaY < 0
    const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 1 && e.deltaY > 0
    if (!atTop && !atBottom) return // 面板自己滚，不拦
  }
  e.preventDefault()
  if (threadEl.value) threadEl.value.scrollTop += e.deltaY
}

/** 把某一根书签带回视野（手动算，不用 scrollIntoView——免得连带滚到别的容器） */
function revealMark(no) {
  const box = marksEl.value
  const el = markRefs.value[no]
  if (!box || !el || !marksScroll.value) return
  const top = el.offsetTop
  const bottom = top + el.offsetHeight
  if (top < box.scrollTop) box.scrollTop = top - 6
  else if (bottom > box.scrollTop + box.clientHeight) box.scrollTop = bottom - box.clientHeight + 6
}

function jump(no) {
  activeQ.value = no
  revealMark(no)
  const el = qRefs.value[no]
  if (!el) return
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'center' })
}

// ---------------------------------------------------------------- 一键复制（气泡与回复）

const copiedKey = ref('')
let copiedTimer = null
async function copyText(key, text) {
  await copyToClipboard(text)
  copiedKey.value = key
  clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => { copiedKey.value = '' }, 1500)
}

// ---------------------------------------------------------------- 对话

// 只有最新一张结论卡可继续挂号（一个聊天页可先后承载多个会话与多张卡）
const lastCardIndex = computed(() => {
  for (let i = shown.value.length - 1; i >= 0; i--) {
    if (shown.value[i].type === 'card') return i
  }
  return -1
})

let scrollScheduled = false
function scrollToBottom() {
  if (scrollScheduled) return
  scrollScheduled = true
  nextTick(() => {
    scrollScheduled = false
    const el = threadEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 用户主动发送：插用户气泡 + 跑一轮导诊。
// 门禁在这里再拦一道（UI 已把等待态的发送禁用，这里兜键盘 Enter 等旁路）：
// partStage === 'awaiting' 意味着这一条会是新会话的首条输入，没声明部位就不该发出去
function send(text) {
  const content = (typeof text === 'string' ? text : draft.value).trim()
  if (!content || chat.streaming || partStage.value === 'awaiting') return
  draft.value = ''
  nextTick(autoGrow) // 清空后缩回单行
  chat.pushEntry({ type: 'user', content })
  runTurn(content)
}

// 一轮导诊：占位 AI 气泡 → SSE 七事件（session / delta / question / info / result / notice / done / error）
// turnSeq 标记「当前这一轮」：终态事件（question/result/error）先于流关闭到达时，
// 收尾只认最新一轮，避免上一轮的收尾把新一轮的流式态关掉
let turnSeq = 0
async function runTurn(content) {
  if (chat.streaming) return
  const seq = ++turnSeq
  chat.streaming = true
  // 占位气泡的响应式引用：delta 直接追加到它，逐字填充同一个文本节点。
  // 用 let 是因为处置提示（notice）会另起一泡，之后答案的 delta 要落到新气泡里
  let bubble = chat.pushEntry({ type: 'ai', content: '' })
  scrollToBottom()

  const endTurn = () => {
    if (seq !== turnSeq) return
    chat.streaming = false
    scrollToBottom()
  }

  turnAbort = new AbortController()
  try {
    await streamChat(
      { sessionId: chat.sessionId, content, parts: parts.value.locations },
      {
        onSession: ({ sessionId }) => chat.setSession(sessionId),

        onDelta: ({ text }) => {
          bubble.content += text
          scrollToBottom()
        },

        // 处置提示（敏感词累计触发的警告）：
        // ① 当前气泡还空着就把它收掉，免得留下一个空气泡；
        // ② 另起一泡显示提示，并把占位气泡换成新的——否则提示后面的答案仍写进同一个气泡，
        //    提示与结论就粘成一段话了（这正是后端用独立 notice 事件而不是 delta 的原因）
        onNotice: ({ content: text }) => {
          if (!bubble.content) chat.removeEntry(bubble)
          chat.pushEntry({ type: 'notice', content: text })
          bubble = chat.pushEntry({ type: 'ai', content: '' })
          scrollToBottom()
        },

        onQuestion: ({ content: full }) => {
          // 两条来源：① 模型已随 delta 流出追问文本——本事件只是定性标记，内容以事件为准，
          // 绝不能重复渲染；② 规则模板兜底（无 delta，占位气泡本就是空的）。
          // 两种情况下都复用当前气泡，视觉上等价于「新建一个追问气泡」。
          bubble.type = 'question'
          bubble.content = full
          endTurn()
        },

        // 资料回答（患者问的是知识库内容而不是描述症状）：内容同样已随 delta 流过，
        // 事件只做定性——复用当前气泡换个标签即可。它既不是追问（系统没在要信息）也不是结论
        // （没有科室推荐），必须让患者看出来，否则会把一段资料读成分诊结论。
        onInfo: ({ content: full }) => {
          bubble.type = 'info'
          bubble.content = full
          endTurn()
        },

        onResult: (data) => {
          // 结论前的自然语言（若有）保留在气泡里；没有则收起占位气泡
          if (!bubble.content) chat.removeEntry(bubble)
          chat.pushEntry({
            type: 'card',
            recordId: data.recordId,
            card: {
              deptId: data.deptId,
              dept: data.dept,
              confidence: data.confidence,
              top3: data.top3 || [],
              note: data.note,
              cites: data.cites || [],
              lowConfidence: data.lowConfidence,
              profileText: data.profileText || ''
            }
          })
          // 推荐卡渲染完成 = result_view 触点（旁路上报，失败静默）
          if (data.recordId) track('result_view', { recordId: data.recordId })
          endTurn()
        },

        // error 之后后端还会补一个 done：此时流式态已结束，重复收尾无害。
        // hasResult=1 ⇒ 本会话已出结论，下一句输入就是**新会话**（后端「新主诉判定」）——
        // 门禁据此回到等待态。这是"以后端状态为准"的落点：前端不自判是不是新主诉。
        onDone: ({ hasResult } = {}) => {
          if (hasResult) {
            parts.value = emptyParts()
            partStage.value = 'awaiting'
          }
          endTurn()
        },

        onError: ({ message }) => {
          if (!bubble.content) chat.removeEntry(bubble)
          chat.pushEntry({
            type: 'error',
            message: message || '网络中断，请检查连接后重试',
            text: content
          })
          endTurn()
        }
      },
      turnAbort.signal
    )
  } catch (e) {
    // streamChat 内部已兜底全部异常路径，这里只兜住意外，避免未处理的 Promise 拒绝
    console.error('[chat] 对话流异常', e)
  } finally {
    if (seq === turnSeq) {
      turnAbort = null
      chat.streaming = false
    }
    // 这一轮可能刚落库：刷新左侧列表（新会话 / 提问数 / 挂号状态都靠它）
    rail.value?.reload()
  }
}

// 重发上一条输入：撤掉错误块再跑一轮（用户气泡已在对话流里，不重复插入）
function retry(entry) {
  if (chat.streaming) return
  const text = entry.text
  chat.removeEntry(entry)
  runTurn(text)
}

function goRegister(cardEntry) {
  // 把 Top3 的科室 id 按推荐次序一并带过去（逗号分隔）：挂号页据此把推荐科室提到列表最前，
  // 否则整份列表只能按创建时间序排，推荐科室夹在中间（患者要自己找哪条是「推 荐」）
  const rec = rankedTop3(cardEntry.card).map((c) => c.deptId).filter(Boolean)
  router.push({
    path: '/register',
    query: {
      recordId: cardEntry.recordId,
      deptId: cardEntry.card.deptId,
      ...(rec.length ? { rec: rec.join(',') } : {})
    }
  })
}

// 退出登录：先调后端删 Redis 登录态（登出即时失效），再清本地态跳登录页；
// 接口失败也照常清本地态，避免卡死
async function onLogout() {
  turnAbort?.abort()
  try {
    await apiLogout()
  } finally {
    chat.reset() // 清对话状态，避免换账号后看到上一账号的会话
    user.logout()
    router.push('/login')
  }
}

function hhmm(at) {
  return at ? String(at).slice(11, 16) : ''
}

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') marksRO = new ResizeObserver(syncMarksScroll)
})

onBeforeUnmount(() => {
  turnAbort?.abort()
  marksRO?.disconnect()
  clearTimeout(copiedTimer)
})
</script>

<style scoped>
/* ============================================================
   对话页 —— 方向 04「导诊伙伴」
   规范：.claude/rules/前端设计方案.md §3 / §4.1

   本页只写「本页私有部件」与「页面布局」。共享部件（气泡 / 推荐卡 / 步进条 /
   输入区 / 浮层 / 标签 / 按钮 / 复制按钮…）一律吃 patient.css 的定义，**同名类
   不在这里重复实现**——两份定义里页面那份会静默覆盖全局，改一处漏一处（2026-10-07 收掉）。

   左栏 / 空状态 / 推荐卡 / 健康档案各自是组件（`components/patient/`），样式随组件走。
   ============================================================ */

.p-chat { height: 100vh; background: var(--bg); }

/* ---------- 两栏：会话目录 + 对话舞台 ---------- */
.p-chat__body { position: relative; display: flex; height: 100%; }
.p-chat__stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--stage);
}
.p-chat__main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.p-chat__thread {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: 20px;
}

/* 助手消息的骨架（**消息流本身在共享件 §3.1 里**，这里只补本页要的头像列）：
   30px 圆头像 + 右侧「标签 + 正文 + 复制」。追问 / 资料 / 普通回复共用这一套，
   三者的区别只在标签文字与那根竖线——换皮不许把它们抹平。 */
.p-ai { display: flex; gap: 12px; }
.p-ai__ava {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  margin-top: 4px;
  border-radius: var(--r-full);
  background: var(--leaf-soft);
  color: var(--leaf-deep);
}
.p-ai__bd { flex: 1; min-width: 0; }

/* 流式等待：首字到达前的轻提示，随 delta 填充自动消失 */
.p-ai__wait {
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .06em;
  color: var(--ink-3);
}

/* 滚动条：显式定宽 12px 并淡化。定宽是为了让问题导航的把手能**精确**停在它左边
   （宽度不定就只能靠猜）。**不能再写 `scrollbar-width`**——Chromium 一旦认了那个
   标准属性，下面这套伪元素就整个失效。 */
.p-chat__thread::-webkit-scrollbar,
.p-chat__main::-webkit-scrollbar { width: 12px; }
.p-chat__thread::-webkit-scrollbar-track,
.p-chat__main::-webkit-scrollbar-track { background: transparent; }
.p-chat__thread::-webkit-scrollbar-thumb,
.p-chat__main::-webkit-scrollbar-thumb {
  background: var(--scroll-thumb);
  border: 3px solid transparent; /* 12px 轨道里只画中间 6px */
  background-clip: content-box;
}
.p-chat__thread::-webkit-scrollbar-thumb:hover,
.p-chat__main::-webkit-scrollbar-thumb:hover {
  background: var(--scroll-thumb-hover);
  background-clip: content-box;
}

/* 空状态：导诊卡与输入区当一整块，在顶栏以下的空白里垂直居中。
   用上下 auto 外边距而不是 justify-content——空间不够时它退化成 0，不会把顶部裁掉 */
.p-chat__main.is-empty { overflow-y: auto; }
.p-chat__main.is-empty .p-chat__thread {
  flex: 0 1 auto;
  margin-top: auto;
  padding-bottom: 0;
}
/* 空状态下输入区不在纸的底边（下方还有居中留白），别在那儿画一条假纸边 */
.p-chat__main.is-empty .p-composer {
  border-top: none;
  background: none;
  padding-top: 20px;
  margin-bottom: auto;
}

/* ---------- 回放时的只读条（替代输入区） ---------- */
.p-chat__lock {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: var(--stage);
  border-top: 1px solid var(--line);
}
.p-chat__locktxt {
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .14em;
  color: var(--ink-3);
}
.p-chat__lockbtn {
  flex: none;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 16px;
  border: 0;
  border-radius: var(--r-sm);
  background: var(--leaf);
  color: var(--on-accent);
  cursor: pointer;
  font-family: var(--sans);
  font-size: 12.5px;
  letter-spacing: .1em;
  transition: background .18s ease;
}
.p-chat__lockbtn:hover { background: var(--leaf-deep); }

/* 用户气泡那一列（右对齐，复制按钮挂在下面）——`.p-user` 本身是共享件 */
.p-q__col {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  max-width: 64%;
}
.p-q__col .p-user { max-width: 100%; }
/* 复制按钮在气泡这一列 hover 才浮现（共享件里 `.p-act__btn` 是常隐的）。
   **这两条不能少**——少了它，追问 / 资料 / AI 回复三处的复制按钮在桌面端永远不出现。 */
.p-q:hover .p-act__btn,
.p-ai:hover .p-act__btn { opacity: 1; }
/* 书签跳到的那一问：左侧一条主色短竖线 */
.p-q { position: relative; display: flex; justify-content: flex-end; }
.p-q.is-active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 2px;
  bottom: 2px;
  width: 2px;
  background: var(--leaf);
}

/* ---------- 右缘：问题导航（悬浮面板） ----------
   absolute 而不是列：面板不占布局位，进出回放时正文那一列一动不动。
   收起时右缘只留一根细把手，鼠标挨到就展开 */
.p-chat__marks {
  position: absolute;
  /* 让开最右那条 12px 滚动条：把手停在它左边，两条竖线各归各位、互不打架 */
  right: 12px;
  top: 0;
  bottom: 0;
  width: 22px;
  z-index: 6;
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 把手：收起态唯一看得见的东西。热区是整条右缘（够大够好按），视觉只有中间那 2px */
.p-chat__marksgrip {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
}
.p-chat__marksgrip::before {
  content: '';
  width: 2px;
  height: 46px;
  background: var(--leaf-line);
  transition: background .18s ease, height .18s ease;
}
.p-chat__marks:hover .p-chat__marksgrip::before { background: var(--leaf); height: 62px; }
.p-chat__marks.is-open .p-chat__marksgrip::before { background: var(--leaf); }

.p-chat__markspanel {
  position: absolute;
  right: 100%; /* 贴在把手左侧 */
  top: 50%;
  width: 220px;
  max-height: min(56vh, 318px);
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  /* 这一块是**浮在纸上**的——没有阴影就和正文糊在一起 */
  box-shadow: var(--sh-overlay);
  /* 收起态：往右挪一点 + 透明，不接收鼠标 */
  transform: translate(12px, -50%);
  opacity: 0;
  pointer-events: none;
  transition: opacity .18s ease, transform .18s ease;
}
.p-chat__marks.is-open .p-chat__markspanel {
  opacity: 1;
  pointer-events: auto;
  transform: translate(0, -50%);
}
.p-chat__markshd {
  flex: none;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--line);
}
.p-chat__marksno {
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .06em;
  color: var(--ink-3);
}
/* 放不下就在面板里自己滚；滚动条不画（220px 的一条卡片，画上滚动条更乱） */
.p-chat__markset {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
  padding: 4px 0;
}
.p-chat__markset::-webkit-scrollbar { display: none; }
/* 溢出时上下渐隐——被截掉的那条不该看起来像正常的一条。
   渐隐用的是**遮罩模板色**（任意不透明色都行），借 `--surface` 当"不透明"，
   免得这里再散写一个 `#000` */
.p-chat__markset.is-scroll {
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, var(--surface) 10px, var(--surface) calc(100% - 10px), transparent 100%);
  mask-image: linear-gradient(to bottom, transparent 0, var(--surface) 10px, var(--surface) calc(100% - 10px), transparent 100%);
}
/* 一行 = 一个问题的正文首句 + 右缘一根横杠（当前那根长而粗） */
.p-mark {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background .18s ease;
}
.p-mark:hover { background: var(--hover-soft); }
.p-mark__x {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ink-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.p-mark__bar {
  flex: none;
  width: 13px;
  height: 2px;
  background: var(--ink-3);
  transition: width .18s ease, height .18s ease, background .18s ease;
}
.p-mark.on .p-mark__x { color: var(--leaf-deep); }
.p-mark.on .p-mark__bar {
  width: 20px;
  height: 4px;
  background: var(--leaf);
}

/* ---------- 桌面 ≥768px：DS 式布局——侧栏贴窗口最左，对话内容在中央收 1000px ----------
   两栏靠底色差分开（--rail vs --stage），中间一条线都不画 */
@media (min-width: 768px) {
  .p-chat__body { max-width: none; margin: 0; border: none; }
  /* 整条顶栏在桌面端移除：标题、用户区都归左侧栏，对话区顶上不再有一条横线 */
  .p-chat .p-chat__topbar { display: none; }
  /* 滚动容器是**整幅宽**的：滚动条因此落在窗口最右缘，而不是缩在"对话框"里。
     内容改由内边距收成 1000px 居中——内边距不会带着滚动条一起走 */
  .p-chat__main { width: 100%; max-width: none; margin: 0; }
  /* .p-chat__main 的**每一个**直接子元素都要在这里列全——容器全宽之后，
     漏掉一个（比如只读条）它就会自己铺满整幅宽，跟旁边的区块错位 */
  .p-chat .p-thread,
  .p-chat .p-steps,
  .p-chat .p-composer,
  .p-chat .p-chat__lock {
    width: 100%;
    max-width: none;
    margin: 0;
    border-left: none;
    border-right: none;
    /* 宽屏收到 1000px 居中，窄屏退化成 32px 内边距 */
    padding-left: max(32px, calc((100% - 1000px) / 2));
    padding-right: max(32px, calc((100% - 1000px) / 2));
  }
  .p-chat__menubtn { display: none; }
}

/* ---------- 手机 <768px：对话区全宽（会话目录的覆盖层形态在 SideRail 里）---------- */
@media (max-width: 767px) {
  /* 触屏没有 hover，右缘那条得够宽才点得中；面板也收窄一点，别盖掉半屏正文。
     手机滚动条是悬浮式不占位，把手可以更贴边 */
  .p-chat__marks { width: 34px; right: 4px; }
  .p-chat__markspanel { width: 186px; }
  .p-chat__menubtn {
    flex: none;
    margin-right: 8px;
    min-height: 44px; /* 触控目标 ≥ 44px */
    border: 1px solid var(--line);
    border-radius: var(--r-xs);
    background: none;
    padding: 0 12px;
    cursor: pointer;
    font-family: var(--sans);
    font-size: 10.5px;
    letter-spacing: .14em;
    color: var(--ink-2);
    transition: border-color .18s ease, color .18s ease;
  }
  .p-chat__menubtn:hover { border-color: var(--leaf); color: var(--leaf-deep); }
  .p-chat .p-topbar__title { flex: 1; }
}
</style>