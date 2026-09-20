<template>
  <div class="patient-root p-chat">
    <div class="p-chat__body">
      <!-- ---------- 左：我的就诊（会话目录，桌面常驻 / 手机覆盖层） ---------- -->
      <aside
        class="p-chat__side"
        :class="{ 'is-open': sideOpen, 'is-collapsed': isDesktop && sideHidden }"
        :style="isDesktop && !sideHidden ? { width: sideWidth + 'px' } : null"
        @click.self="sideOpen = false"
      >
        <!-- 精简轨（只在桌面收起时渲染）：收起后只剩标识——它本身就是展开入口，右边再给一个展开图标 -->
        <div class="p-chat__rail">
          <button class="p-chat__railbrand" title="展开侧栏" aria-label="展开侧栏" @click="toggleSide">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="2.6" y="2.6" width="18.8" height="18.8" stroke="currentColor" stroke-width="1.7" />
              <path d="M6.9 8h10.2" stroke="currentColor" stroke-width="2.3" />
              <path d="M6.9 12h6.8" stroke="currentColor" stroke-width="2" opacity=".52" />
              <path d="M6.9 16h3.6" stroke="currentColor" stroke-width="1.7" opacity=".3" />
            </svg>
          </button>
          <span class="p-chat__railsep" />
          <button class="p-chat__iconbtn" title="展开侧栏" aria-label="展开侧栏" @click="toggleSide">
            <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><rect x="1.2" y="2.2" width="11.6" height="9.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M5 2.2v9.6" stroke="currentColor" stroke-width="1.2"/></svg>
          </button>
          <!-- 收起后仍要能开新咨询——否则得先展开侧栏才够得到 -->
          <button class="p-chat__iconbtn" title="新 的 咨 询" aria-label="新 的 咨 询" @click="startNew">
            <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><circle cx="7" cy="7" r="5.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M7 4.8v4.4M4.8 7h4.4" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>
          </button>
        </div>

        <div class="p-chat__sidebox">
          <!-- 侧栏抬头：标识 ＋ 品牌名 ＋ 收起按钮（学 DS 的排布）；「智能导诊」从对话区顶栏挪到这里 -->
          <div class="p-chat__hd">
            <span class="p-chat__logo" aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <rect x="2.6" y="2.6" width="18.8" height="18.8" stroke="currentColor" stroke-width="1.7" />
                <path d="M6.9 8h10.2" stroke="currentColor" stroke-width="2.3" />
                <path d="M6.9 12h6.8" stroke="currentColor" stroke-width="2" opacity=".52" />
                <path d="M6.9 16h3.6" stroke="currentColor" stroke-width="1.7" opacity=".3" />
              </svg>
            </span>
            <span class="p-chat__brand">智能导诊</span>
            <button class="p-chat__iconbtn p-chat__foldbtn" title="收起侧栏" aria-label="收起侧栏" @click="toggleSide">
              <svg width="15" height="15" viewBox="0 0 14 14" aria-hidden="true"><rect x="1.2" y="2.2" width="11.6" height="9.6" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M5 2.2v9.6" stroke="currentColor" stroke-width="1.2"/></svg>
            </button>
          </div>
          <!-- 新咨询放最上头：会话多了要能一眼够到（学 DeepSeek 的「开启新对话」） -->
          <button class="p-chat__new" @click="startNew">+ 新 的 咨 询</button>

          <div class="p-chat__sidehd">
            <div class="p-eyebrow">我 的 就 诊</div>
            <div class="p-chat__count">{{ tab === 'all' ? `${total} 次` : `${booked} 次挂号` }}</div>
            <div class="p-chat__tabs">
              <button :class="{ on: tab === 'all' }" @click="switchTab('all')">全 部 对 话</button>
              <button :class="{ on: tab === 'booked' }" @click="switchTab('booked')">挂 号 历 史</button>
            </div>
          </div>

          <div class="p-chat__list">
            <template v-for="day in shownDays" :key="day.date">
              <div class="p-day">
                <span>{{ shortDate(day.date) }}</span>
                <span class="n">{{ day.sessions.length }} 次{{ isCollapsed(day) ? ' · 已折叠' : '' }}</span>
              </div>
              <button
                v-for="s in visibleSessions(day)"
                :key="s.id"
                class="p-i"
                :class="{ on: s.id === activeSessionId, dim: !s.hasResult }"
                @click="openSession(s.id)"
              >
                <span class="p-i__x">{{ s.firstComplaint || '（无内容）' }}</span>
                <span class="p-i__q">{{ s.questionCount }} 问</span>
                <span class="p-i__d">{{ hhmm(s.startedAt) }}</span>
              </button>
              <button v-if="isCollapsed(day)" class="p-fold" @click="expandDay(day.date)">
                展 开 该 天 全 部 {{ day.sessions.length }} 次
              </button>
              <button v-else-if="expanded.has(day.date)" class="p-fold" @click="collapseDay(day.date)">
                收 起 该 天
              </button>
            </template>
            <p v-if="!shownDays.length" class="p-chat__nores">
              {{ tab === 'booked' ? '还没有挂过号' : '还没有就诊记录' }}
            </p>
          </div>

          <!-- 底部用户区（桌面才有；手机在顶栏） -->
          <div class="p-chat__user">
            <button class="p-chat__unick" title="看我的挂号历史" @click="openBooked">
              {{ user.nickname || '我 的 就 诊' }}
            </button>
            <button class="p-chat__uout" @click="onLogout">退 出</button>
          </div>
        </div>
      </aside>

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
            <!-- 空状态：一张还没填的接诊单——空状态是行动邀请，不是一句客套话 -->
            <div v-if="isEmpty" class="p-hello">
              <div class="p-eyebrow">分 诊 台</div>
              <h2 class="p-hello__t">说说哪里不舒服</h2>
              <p class="p-hello__lead">说清三件事，分诊会更准</p>
              <div class="p-hello__three">
                <span>部 位</span><span>多 久 了</span><span>什 么 感 觉</span>
              </div>
              <p class="p-hello__eg">例：右下腹隐隐作痛两天，一按就疼，还有点恶心</p>
              <div class="p-eyebrow p-hello__eb2">常 见 主 诉</div>
              <div class="p-hello__chips">
                <button v-for="c in COMMON" :key="c" class="p-hello__chip" @click="useChip(c)">{{ c }}</button>
              </div>
            </div>

            <!-- 回放与实时共用同一套条目渲染：shown = 回放条目 或 本次对话条目 -->
            <template v-else>
              <template v-for="(m, i) in shown" :key="i">
                <!-- 用户消息：实心 teal 气泡（回放时带问题编号，书签靠它定位） -->
                <div
                  v-if="m.type === 'user'"
                  :ref="(el) => setQRef(el, m.qNo)"
                  class="p-q"
                  :class="{ 'is-active': m.qNo && m.qNo === activeQ }"
                >
                  <div class="p-q__col">
                    <div class="p-user">{{ m.content }}</div>
                    <button
                      class="p-act__btn"
                      :class="{ 'is-copied': copiedKey === 'u' + i }"
                      :aria-label="copiedKey === 'u' + i ? '已复制' : '复制这条主诉'"
                      @click="copyText('u' + i, m.content)"
                    >
                      <svg v-if="copiedKey !== 'u' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                      <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                      {{ copiedKey === 'u' + i ? '已 复 制' : '复 制' }}
                    </button>
                  </div>
                </div>

                <!-- 追问消息：与普通回复同款 -->
                <div v-else-if="m.type === 'question'" class="p-ai">
                  <div class="p-ai__tag">分 诊 助 理 · 追 问</div>
                  <div class="p-ai__text p-ask">{{ m.content }}</div>
                  <button
                    v-if="m.content"
                    class="p-act__btn"
                    :class="{ 'is-copied': copiedKey === 'q' + i }"
                    :aria-label="copiedKey === 'q' + i ? '已复制' : '复制这条追问'"
                    @click="copyText('q' + i, m.content)"
                  >
                    <svg v-if="copiedKey !== 'q' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                    {{ copiedKey === 'q' + i ? '已 复 制' : '复 制' }}
                  </button>
                </div>

                <!-- AI 回复：直排文字 + moss 小标签（SSE delta 逐字填充同一文本节点）
                     本块必须单行书写：.p-ai__text 是 pre-wrap，换行缩进会被原样渲染 -->
                <div v-else-if="m.type === 'ai'" class="p-ai">
                  <div class="p-ai__tag">分 诊 助 理</div>
                  <div class="p-ai__text">{{ m.content }}<span v-if="!isReplay && chat.streaming && i === shown.length - 1 && !m.content" class="p-ai__wait">正在整理…</span></div>
                  <button
                    v-if="m.content"
                    class="p-act__btn"
                    :class="{ 'is-copied': copiedKey === 'a' + i }"
                    :aria-label="copiedKey === 'a' + i ? '已复制' : '复制这条回复'"
                    @click="copyText('a' + i, m.content)"
                  >
                    <svg v-if="copiedKey !== 'a' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                    {{ copiedKey === 'a' + i ? '已 复 制' : '复 制' }}
                  </button>
                </div>

                <!-- 处置提示（敏感词累计触发的警告）：单独成泡，视觉上与诊断结论区分开 -->
                <div v-else-if="m.type === 'notice'" class="p-notice">{{ m.content }}</div>

                <!-- 推荐卡（签名元素 · 链路 A 结论单）：只有最新一张能继续挂号；回放时整张只读 -->
                <section v-else-if="m.type === 'card'" class="p-card">
                  <div class="p-card__head">
                    <div>
                      <div class="p-eyebrow">分 诊 结 论</div>
                      <div class="p-card__dept">{{ m.card.dept }}</div>
                    </div>
                    <div class="p-card__conf" :class="{ 'p-card__conf--low': isLow(m.card) }">
                      {{ confText(m.card.confidence) }}
                    </div>
                  </div>

                  <!-- 置信度条列表 Top3 -->
                  <div class="p-bars">
                    <div
                      v-for="(c, k) in m.card.top3"
                      :key="c.name"
                      class="p-bar"
                      :class="{ 'p-bar--top': k === 0 }"
                    >
                      <span class="p-bar__name">{{ c.name }}</span>
                      <span class="p-bar__track"><span class="p-bar__fill" :style="{ width: (c.pct ?? 0) + '%' }" /></span>
                      <span class="p-bar__pct">{{ c.pct == null ? '—' : c.pct + '%' }}</span>
                    </div>
                  </div>

                  <p class="p-card__note">{{ m.card.note }}</p>

                  <!-- 脚注区：判断依据（1px dashed 上边框，注号对应证据顺序） -->
                  <div class="p-card__foot">
                    <span class="p-eyebrow">判 断 依 据</span>
                    <p v-for="c in m.card.cites" :key="c.no" class="p-cite">
                      <span class="p-cite__no">注{{ c.no }}</span>　{{ c.text }}
                    </p>
                  </div>

                  <p v-if="isLow(m.card)" class="p-card__lowhint">
                    信息有限，结果仅供参考，建议进一步咨询医生。
                  </p>

                  <!-- 一键复制结论单（科室＋置信度＋依据），学 DS 的块级复制 -->
                  <button
                    class="p-act__btn p-card__copy"
                    :class="{ 'is-copied': copiedKey === 'c' + i }"
                    :aria-label="copiedKey === 'c' + i ? '已复制' : '复制这条分诊结论'"
                    @click="copyText('c' + i, cardText(m.card))"
                  >
                    <svg v-if="copiedKey !== 'c' + i" width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor"/><path d="M8.5 3.5v-2h-7v7h2" fill="none" stroke="currentColor"/></svg>
                    <svg v-else width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 6.5 5 9.5 10 3.5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
                    {{ copiedKey === 'c' + i ? '已 复 制' : '复 制 结 论' }}
                  </button>

                  <!-- 回放时已挂号的，补一行就诊信息；没有的什么都不加 -->
                  <div v-if="isReplay && m.card.booked" class="p-chat__visit">
                    就 诊　{{ m.card.actualDept }}<template v-if="m.card.actualDeptLocation"> · {{ m.card.actualDeptLocation }}</template>
                  </div>

                  <button
                    v-if="!isReplay"
                    class="p-btn"
                    style="margin-top: 14px"
                    :disabled="i !== lastCardIndex"
                    @click="goRegister(m)"
                  >
                    下一步 · 模拟挂号
                  </button>
                </section>

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

          <!-- 输入区：一整块「书写区」——上面写字，下面一行小字＋发送（学 DeepSeek 网页版的排布，
               保持纸感的直角与细线）。看历史时换成只读条——一次会话 = 一次就诊，翻旧账不能往里写字 -->
          <footer v-if="!isReplay" class="p-composer">
            <div class="p-composer__box">
              <textarea
                ref="inputEl"
                v-model="draft"
                class="p-composer__input"
                rows="1"
                placeholder="说说哪里不舒服，我来帮您分诊"
                @input="autoGrow"
                @keydown.enter.exact.prevent="send()"
              />
              <div class="p-composer__bar">
                <span class="p-composer__hint">分诊建议，不能替代医生诊断</span>
                <button class="p-composer__send" :disabled="chat.streaming" aria-label="发送" @click="send()">
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
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { useChatStore } from '../../stores/chat'
import { logout as apiLogout } from '../../api/auth'
import { listSessions, sessionDetail } from '../../api/records'
import { streamChat } from '../../utils/sse'
import { track } from '../../utils/track'
import '../../styles/patient.css'

// 一天内会话超过这个数就折叠；折叠时只露最新这几条
const FOLD_OVER = 10
const FOLD_SHOW = 2

const router = useRouter()
const user = useUserStore()
const chat = useChatStore()

const draft = ref('')
const threadEl = ref(null)
const inputEl = ref(null)

/** 常见主诉：点一下填进输入框——**不直接发**（患者还能补一句"还伴着恶心"，也不至于误触烧掉一次模型调用） */
const COMMON = ['发热咳嗽', '肚子疼', '头疼头晕', '皮肤起疹', '心慌胸闷', '腰背酸痛']

function useChip(text) {
  const now = draft.value.trim()
  draft.value = now ? `${now}，${text}` : text
  inputEl.value?.focus()
  nextTick(autoGrow)
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

// ---------------------------------------------------------------- 左侧会话目录

const days = ref([])
const total = ref(0)
const booked = ref(0)
const tab = ref('all')
const sideOpen = ref(false)
const expanded = ref(new Set())

// ---------- 侧栏收起/展开（学 DS：收起成一条只留「折叠图标＋新建对话」的精简轨） ----------
const SIDE_HIDDEN_KEY = 'p-chat-side-hidden'
const SIDE_W_DEFAULT = 248

const sideHidden = ref(localStorage.getItem(SIDE_HIDDEN_KEY) === '1')
// 只有桌面才收起侧栏；手机侧栏是覆盖层，开关走的是 sideOpen
const isDesktop = ref(window.matchMedia('(min-width: 768px)').matches)
const sideWidth = computed(() => SIDE_W_DEFAULT)

function toggleSide() {
  sideHidden.value = !sideHidden.value
  localStorage.setItem(SIDE_HIDDEN_KEY, sideHidden.value ? '1' : '0')
}

// ---------- 一键复制（学 DS：回复/结论旁的复制按钮，1.5s 后复位） ----------
const copiedKey = ref('')
let copiedTimer = null
async function copyText(key, text) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // 剪贴板 API 不可用（非安全上下文）时的兜底
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy') } catch { /* 复制失败就静默，按钮照样复位 */ }
    ta.remove()
  }
  copiedKey.value = key
  clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => { copiedKey.value = '' }, 1500)
}

/** 结论卡复制成纯文本：科室＋置信度＋Top3＋说明＋依据，方便贴给医生/家人看 */
function cardText(card) {
  const lines = [`分诊结论：${card.dept}（参考置信度 ${confText(card.confidence)}）`]
  if (card.top3?.length) {
    lines.push('候选科室：' + card.top3.map((c) => `${c.name}${c.pct == null ? '' : ' ' + c.pct + '%'}`).join('，'))
  }
  if (card.note) lines.push(card.note)
  if (card.cites?.length) {
    lines.push('判断依据：')
    card.cites.forEach((c) => lines.push(`  注${c.no}　${c.text}`))
  }
  lines.push('（分诊建议，不能替代医生诊断）')
  return lines.join('\n')
}

/** 当前 tab 口径下要展示的天：全部对话 = 原样；挂号历史 = 只留挂过号的会话 */
const shownDays = computed(() => {
  if (tab.value === 'all') return days.value
  return days.value
    .map((d) => ({ ...d, sessions: d.sessions.filter((s) => s.booked) }))
    .filter((d) => d.sessions.length > 0)
})

/** 正在看的是哪一条：看历史就是历史的，实时对话就是本轮的 */
const activeSessionId = computed(() => replay.value?.session?.id || chat.sessionId || null)

async function loadSessions() {
  try {
    const data = await listSessions()
    days.value = data.days || []
    total.value = data.totalSessions || 0
    booked.value = data.totalBooked || 0
  } catch (e) {
    // 侧栏拉不到不该挡住发消息；接口真挂了别处也会报错
    console.error('[chat] 会话列表拉取失败', e)
  }
}

function switchTab(next) {
  tab.value = next
}

function isCollapsed(day) {
  return day.sessions.length > FOLD_OVER && !expanded.value.has(day.date)
}

function visibleSessions(day) {
  return isCollapsed(day) ? day.sessions.slice(0, FOLD_SHOW) : day.sessions
}

function expandDay(date) {
  expanded.value = new Set([...expanded.value, date])
}

function collapseDay(date) {
  const next = new Set(expanded.value)
  next.delete(date)
  expanded.value = next
}

// ---------------------------------------------------------------- 回放

const replay = ref(null)
const activeQ = ref(null)
const qRefs = ref({})

const isReplay = computed(() => !!replay.value)
/** 一句话都还没说过：空状态把欢迎块、输入框、免责当成一整块居中 */
const isEmpty = computed(() => !isReplay.value && chat.entries.length === 0)
/** 渲染的统一来源：看历史用回放条目，否则用本次对话条目 */
const shown = computed(() => (replay.value ? replay.value.entries : chat.entries))
const marks = computed(() => replay.value?.questions || [])

/** 把 RecordService 的详情转成对话区能渲染的条目——与实时 SSE 出来的形状保持一致 */
function toReplayEntries(d) {
  const out = (d.messages || []).map((m) => ({
    type: m.role === 'user' ? 'user' : m.role === 'question' ? 'question' : 'ai',
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
  nextTick(() => inputEl.value?.focus())
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

// ---------------------------------------------------------------- 对话

// 只有最新一张结论卡可继续挂号（一个聊天页可先后承载多个会话与多张卡）
const lastCardIndex = computed(() => {
  for (let i = shown.value.length - 1; i >= 0; i--) {
    if (shown.value[i].type === 'card') return i
  }
  return -1
})

// 模型未给出合法置信度时后端置 null：显示「—」并走低置信度样式，不出现 NaN% / null%
function confText(confidence) {
  return typeof confidence === 'number' && Number.isFinite(confidence)
    ? `${Math.round(confidence * 100)}%`
    : '—'
}

// 低置信度以**后端下发为准**（阈值来自 sys_config，管理端可调）；
// 前端只在后端没给标志时按「置信度缺失」兜底，不自己写死阈值，避免与管理端口径打架
function isLow(card) {
  return card.lowConfidence === true || typeof card.confidence !== 'number'
}

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

// 用户主动发送：插用户气泡 + 跑一轮导诊
function send(text) {
  const content = (typeof text === 'string' ? text : draft.value).trim()
  if (!content || chat.streaming) return
  draft.value = ''
  nextTick(autoGrow) // 清空后缩回单行
  chat.pushEntry({ type: 'user', content })
  runTurn(content)
}

// 一轮导诊：占位 AI 气泡 → SSE 七事件（session / delta / question / result / notice / done / error）
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
      { sessionId: chat.sessionId, content },
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
              lowConfidence: data.lowConfidence
            }
          })
          // 推荐卡渲染完成 = result_view 触点（旁路上报，失败静默）
          if (data.recordId) track('result_view', { recordId: data.recordId })
          endTurn()
        },

        // error 之后后端还会补一个 done：此时流式态已结束，重复收尾无害
        onDone: () => endTurn(),

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
    loadSessions()
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
  router.push({
    path: '/register',
    query: { recordId: cardEntry.recordId, deptId: cardEntry.card.deptId }
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

/** 点昵称 = 看自己的就诊记录：切到「挂号历史」并打开侧栏（桌面侧栏常驻，这个赋值无害） */
function openBooked() {
  tab.value = 'booked'
  sideOpen.value = true
}

// ---------------------------------------------------------------- 展示

function hhmm(at) {
  return at ? String(at).slice(11, 16) : ''
}

function shortDate(date) {
  return String(date).slice(5)
}

let desktopMQ = null
const onDesktopChange = (e) => { isDesktop.value = e.matches }

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') marksRO = new ResizeObserver(syncMarksScroll)
  desktopMQ = window.matchMedia('(min-width: 768px)')
  desktopMQ.addEventListener?.('change', onDesktopChange)
  loadSessions()
})

onBeforeUnmount(() => {
  turnAbort?.abort()
  marksRO?.disconnect()
  desktopMQ?.removeEventListener?.('change', onDesktopChange)
  clearTimeout(copiedTimer)
})
</script>

<style scoped>
.p-chat {
  height: 100vh;
  background: var(--paper);
}
/* 两栏：会话目录 + 对话舞台；书签（回放时）再挂一列 */
.p-chat__body {
  position: relative;
  display: flex;
  height: 100%;
}
.p-chat__stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
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
  padding-bottom: 18px;
}
/* 滚动条：显式定宽 12px，并淡化成纸面上的一条灰线。
   定宽是为了让问题导航的把手能精确停在它左边（宽度不定就只能靠猜）。
   注意不能再用 scrollbar-width——Chromium 一旦认了那个标准属性，下面这套伪元素就整个失效 */
.p-chat__thread::-webkit-scrollbar,
.p-chat__main::-webkit-scrollbar { width: 12px; }
.p-chat__thread::-webkit-scrollbar-track,
.p-chat__main::-webkit-scrollbar-track { background: transparent; }
.p-chat__thread::-webkit-scrollbar-thumb,
.p-chat__main::-webkit-scrollbar-thumb {
  background: rgba(28, 43, 40, .16);
  border: 3px solid transparent; /* 12px 轨道里只画中间 6px */
  background-clip: content-box;
}
.p-chat__thread::-webkit-scrollbar-thumb:hover,
.p-chat__main::-webkit-scrollbar-thumb:hover { background: rgba(28, 43, 40, .32); background-clip: content-box; }
/* 空状态：欢迎块不撑满，输入框紧随其后，两块一起在这段高度里居中。
   用 auto 外边距而不是 justify-content——空间不够时它退化成 0，不会把顶部裁掉 */
.p-chat__main.is-empty { overflow-y: auto; }
.p-chat__main.is-empty .p-chat__thread {
  flex: 0 1 auto;
  margin-top: auto;
  padding-bottom: 0;
}
/* 紧跟在接诊单下面，不再画分隔线——它们本来就是同一张纸。
   margin-bottom: auto 是空状态居中的下半截（配对话区的 margin-top: auto） */
.p-chat__main.is-empty .p-composer {
  border-top: none;
  background: none;
  padding-top: 22px;
  margin-bottom: auto;
}

/* ---------- 输入区：一整块「书写区」----------
   上写字、下排一行小字＋发送（学 DeepSeek 网页版那块输入卡片的排布），
   但保持纸感的直角与细线：不圆角、不加阴影。 */
.p-chat .p-composer {
  display: block;
  padding: 10px 16px 14px;
  background: var(--paper);
}
.p-composer__box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px 8px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--card);
  transition: border-color .18s ease;
}
.p-composer__box:focus-within { border-color: var(--teal); }
.p-chat .p-composer__input {
  width: 100%;
  min-height: 24px;
  max-height: 120px;
  padding: 0;
  border: none;
  border-radius: 0;
  background: none;
  resize: none;
  overflow-y: auto;
  font-family: var(--serif);
  font-size: 14px;
  line-height: 1.65;
  color: var(--ink);
}
.p-chat .p-composer__input:focus { outline: none; border: none; }
.p-chat .p-composer__input::placeholder { color: var(--ink-2); }
.p-composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.p-composer__hint {
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .08em;
  color: var(--ink-2);
  opacity: .85;
}
.p-chat .p-composer__send {
  flex: none;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 0;
  background: var(--teal);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background .18s ease;
}
.p-chat .p-composer__send:hover { background: var(--teal-deep); }

/* ---------- 左：会话目录 ----------
   底色比对话区暗两档（--side）：「我的就诊」与「对话」因此自然分成两块，
   不用在两栏之间画任何一条线（画线会把一张纸隔成两间病房） */
.p-chat__side {
  display: none;
}
.p-chat__sidebox {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--side);
}
/* 侧栏抬头：标识 ＋ 品牌名 ＋ 收起按钮，一行排在「+ 新的咨询」之上（学 DS） */
.p-chat__hd {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 12px 10px;
}
.p-chat__logo {
  flex: none;
  display: flex;
  color: var(--teal);
}
.p-chat__brand {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-family: var(--serif);
  font-size: 15px;
  letter-spacing: .04em;
  color: var(--ink);
}
.p-chat__sidehd {
  padding: 16px 14px 0;
  /* 侧栏底比 --line 深，原来的浅线在它上面等于不存在，改用 ink 的淡透明 */
  border-bottom: 1px solid rgba(28, 43, 40, .09);
}
.p-chat__count {
  font-family: var(--serif);
  font-size: 16px;
  font-weight: 700;
  letter-spacing: .05em;
  margin-top: 6px;
}
.p-chat__tabs {
  display: flex;
  margin-top: 11px;
}
.p-chat__tabs button {
  flex: 1;
  padding: 9px 2px;
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .12em;
  color: var(--ink-2);
}
.p-chat__tabs button:hover { color: var(--teal); }
.p-chat__tabs button.on {
  color: var(--teal);
  font-weight: 700;
  border-bottom-color: var(--teal);
}
.p-chat__list { flex: 1; min-height: 0; overflow-y: auto; }
.p-chat__nores {
  padding: 24px 14px;
  font-family: var(--sans);
  font-size: 11px;
  letter-spacing: .1em;
  color: var(--ink-2);
  text-align: center;
}
.p-chat__new {
  flex: none;
  margin: 4px 14px 14px;
  min-height: 40px;
  border: 1px solid var(--teal);
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 11px;
  letter-spacing: .16em;
  color: var(--teal);
  transition: background .18s ease, color .18s ease;
}
.p-chat__new:hover { background: var(--teal); color: #fff; }

/* 底部用户区（桌面才有；手机在顶栏） */
.p-chat__user {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid rgba(28, 43, 40, .09);
}
.p-chat__unick {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  border: none;
  border-bottom: 1px dashed var(--ink-2);
  background: none;
  padding: 0 0 1px;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 11px;
  letter-spacing: .14em;
  color: var(--ink);
  transition: color .18s ease, border-color .18s ease;
}
.p-chat__unick:hover { color: var(--teal); border-bottom-color: var(--teal); }
.p-chat__uout {
  flex: none;
  border: 1px solid rgba(28, 43, 40, .18);
  background: none;
  padding: 4px 10px;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .16em;
  color: var(--ink-2);
  transition: color .18s ease, border-color .18s ease;
}
.p-chat__uout:hover { color: var(--err); border-color: var(--err); }

/* 目录条目（与就诊记录页同一套）。
   侧栏底是 --side，所以天头用半透明白、选中行用实白卡片——「压在纸面上」的层次才出得来 */
.p-day {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 8px 14px 6px;
  background: rgba(255, 255, 255, .5);
  font-family: var(--sans);
  font-size: 9.5px;
  letter-spacing: .2em;
  color: var(--ink-2);
}
.p-day .n { letter-spacing: .08em; opacity: .7; }
.p-i {
  width: 100%;
  display: flex;
  align-items: baseline;
  gap: 9px;
  padding: 9px 14px;
  border: none;
  border-bottom: 1px solid rgba(28, 43, 40, .07);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background .18s ease;
}
.p-i:hover { background: rgba(255, 255, 255, .62); }
.p-i.on { background: var(--card); box-shadow: inset 3px 0 0 var(--teal); }
.p-i__x {
  flex: 1;
  min-width: 0;
  font-family: var(--serif);
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.p-i.dim .p-i__x { color: var(--ink-2); }
.p-i__q,
.p-i__d {
  flex: none;
  font-family: var(--sans);
  font-size: 9.5px;
  color: var(--ink-2);
}
.p-i__q { opacity: .6; }
.p-fold {
  width: 100%;
  padding: 8px 14px;
  border: none;
  border-bottom: 1px dashed rgba(28, 43, 40, .16);
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .12em;
  color: var(--teal);
}
.p-fold:hover { text-decoration: underline; }

/* ---------- 回放时的只读条（替代输入区） ---------- */
.p-chat__lock {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: var(--paper);
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}
.p-chat__locktxt {
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .16em;
  color: var(--ink-2);
}
.p-chat__lockbtn {
  flex: none;
  min-height: 38px;
  padding: 0 16px;
  border: 0;
  border-radius: 0;
  background: var(--teal);
  color: #fff;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 11.5px;
  letter-spacing: .12em;
  transition: background .18s ease;
}
.p-chat__lockbtn:hover { background: var(--teal-deep); }
.p-chat__visit {
  border-top: 1px dashed var(--line);
  margin-top: 10px;
  padding-top: 9px;
  font-family: var(--sans);
  font-size: 10.5px;
  letter-spacing: .1em;
  color: var(--ink-2);
}

/* 书签跳到的那一问：左侧一条 teal 短竖线 */
.p-q { position: relative; display: flex; justify-content: flex-end; }
.p-q.is-active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 2px;
  bottom: 2px;
  width: 2px;
  background: var(--teal);
}

/* ---------- 右缘：问题导航（悬浮面板） ----------
   absolute 而不是列：面板不占布局位，进出回放时正文那一列一动不动。
   收起时右缘只留一根细把手，鼠标挨到就展开（学 DS 的问题导航） */
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
  background: var(--teal-soft);
  transition: background .18s ease, height .18s ease;
}
.p-chat__marks:hover .p-chat__marksgrip::before { background: var(--teal); height: 62px; }
.p-chat__marks.is-open .p-chat__marksgrip::before { background: var(--teal); }

.p-chat__markspanel {
  position: absolute;
  right: 100%; /* 贴在把手左侧 */
  top: 50%;
  width: 220px;
  max-height: min(56vh, 318px);
  display: flex;
  flex-direction: column;
  background: var(--card);
  border: 1px solid var(--line);
  /* 纸面部件一律不加阴影，但这一块是**浮在纸上**的——没阴影就和正文糊在一起了 */
  box-shadow: 0 4px 18px rgba(28, 43, 40, .1);
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
  padding: 10px 12px 8px;
  border-bottom: 1px solid var(--line);
}
.p-chat__marksno {
  font-family: var(--sans);
  font-size: 9.5px;
  letter-spacing: .1em;
  color: var(--ink-2);
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
/* 溢出时上下渐隐——被截掉的那条不该看起来像正常的一条 */
.p-chat__markset.is-scroll {
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, #000 10px, #000 calc(100% - 10px), transparent 100%);
  mask-image: linear-gradient(to bottom, transparent 0, #000 10px, #000 calc(100% - 10px), transparent 100%);
}
/* 一行 = 一个问题的正文首句 + 右缘一根横杠（当前那根长而粗） */
.p-mark {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background .15s ease;
}
.p-mark:hover { background: rgba(28, 43, 40, .045); }
.p-mark__x {
  flex: 1;
  min-width: 0;
  font-family: var(--serif);
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
  height: 2.5px;
  background: var(--ink-2);
  opacity: .32;
  transition: all .16s ease;
}
.p-mark.on .p-mark__x { color: var(--teal); }
.p-mark.on .p-mark__bar {
  width: 20px;
  height: 3.5px;
  background: var(--teal);
  opacity: 1;
}

/* ---------- 空状态：一张还没填的接诊单 ---------- */
.p-hello { display: flex; flex-direction: column; }
.p-hello__t {
  font-family: var(--serif);
  font-size: 24px; /* 同结论科室名一阶 */
  font-weight: 400;
  letter-spacing: .03em;
  line-height: 1.35;
  margin-top: 9px;
}
.p-hello__lead {
  margin-top: 8px;
  font-size: 13px;
  color: var(--ink-2);
}
/* 三件该说的事：做成待填的空格，不是按钮——别让人以为要点 */
.p-hello__three {
  display: flex;
  gap: 6px;
  margin-top: 15px;
  max-width: 430px; /* 桌面上别把三条虚线拉成一整行 */
}
.p-hello__three span {
  flex: 1;
  padding: 9px 4px 7px;
  border-bottom: 1px dashed var(--line);
  text-align: center;
  font-family: var(--sans);
  font-size: 11px;
  letter-spacing: .14em;
  color: var(--ink-2);
}
.p-hello__eg {
  margin-top: 13px;
  padding-left: 11px;
  border-left: 2px solid var(--teal-soft);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ink-2);
}
.p-hello__eb2 { margin-top: 20px; }
.p-hello__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
}
.p-hello__chip {
  display: inline-flex;
  align-items: center;
  min-height: 44px; /* 触控目标 ≥44px */
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--card);
  font-family: var(--serif);
  font-size: 12.5px;
  color: var(--ink);
  cursor: pointer;
  transition: border-color .18s ease, color .18s ease;
}
.p-hello__chip:hover { border-color: var(--teal); color: var(--teal); }

/* ---------- 复制按钮（学 DS：悬停浮现，触屏常显；只借用交互，配色仍是现有色板） ---------- */
.p-q__col {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  max-width: 82%;
}
.p-q__col .p-user { max-width: 100%; }
.p-act__btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  padding: 2px 0;
  border: none;
  background: none;
  cursor: pointer;
  font-family: var(--sans);
  font-size: 10px;
  letter-spacing: .12em;
  color: var(--ink-2);
  opacity: 0;
  transition: opacity .15s ease, color .15s ease;
}
.p-q:hover .p-act__btn,
.p-ai:hover .p-act__btn,
.p-act__btn.is-copied,
.p-act__btn:focus-visible { opacity: 1; }
.p-act__btn:hover { color: var(--teal); }
.p-act__btn.is-copied { color: var(--teal); }
/* 结论卡的复制常显——卡是单据，hover 才出现会让人找不到 */
.p-card__copy { margin-top: 10px; opacity: 1; }
/* 触屏没有 hover：复制按钮常显 */
@media (hover: none) {
  .p-act__btn { opacity: 1; }
}

/* ---------- 侧栏折叠图标与精简轨（桌面） ---------- */
.p-chat__iconbtn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  color: var(--ink-2);
  transition: color .18s ease, background .18s ease;
}
.p-chat__iconbtn:hover { color: var(--teal); background: var(--card); }
/* 收起按钮在侧栏抬头里（桌面）；精简轨：收起态才出现，与展开态的 sidebox 互换 */
.p-chat__foldbtn { display: none; }
.p-chat__rail { display: none; }
@media (min-width: 768px) {
  .p-chat__foldbtn { display: inline-flex; margin-left: auto; }
  /* 收起后侧栏只剩横向一条：标识（点它展开）＋ 展开图标 ＋ 新建。
     宽度写定值而不是 fit-content——fit-content 不参与 width 插值，收起会「啪」地跳过去，
     0.2s 的过渡等于白写。min-width 兜底：图标有增减时轨不会挤坏。 */
  .p-chat__side.is-collapsed { width: 127px; min-width: fit-content; }
  .p-chat__side.is-collapsed .p-chat__sidebox { display: none; }
  .p-chat__side.is-collapsed .p-chat__rail {
    display: flex;
    flex-direction: row;
    align-items: center;
    gap: 6px;
    padding: 10px 11px;
    color: var(--teal);
    /* 贴在顶部，不要撑满高度居中（DS 的悬浮条就在左上角） */
    height: auto;
  }
  /* 收起态的标识：它本身就是展开入口 */
  .p-chat__railbrand {
    flex: none;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border: none;
    background: none;
    padding: 0;
    cursor: pointer;
    color: var(--teal);
    transition: opacity .18s ease;
  }
  .p-chat__railbrand:hover { opacity: .7; }
  .p-chat__railsep {
    flex: none;
    width: 1px;
    height: 16px;
    background: rgba(28, 43, 40, .16);
  }
}

/* 流式等待：首字到达前的轻提示，随 delta 填充自动消失 */
.p-ai__wait {
  font-family: var(--sans);
  font-size: 12px;
  letter-spacing: .1em;
  color: var(--ink-2);
}
/* 流式中不可重复提交；历史结论卡的按钮同理（只有最新一张可点） */
.p-chat .p-btn:disabled,
.p-chat .p-composer__send:disabled {
  background: var(--line);
  color: var(--ink-2);
  cursor: not-allowed;
}

/* ---------- 桌面 ≥768px：DS 式布局——侧栏贴窗口最左，可整个收起；对话内容在中央收 1000px ----------
   两栏靠底色差分开（--side vs --paper），中间一条线都不画 */
@media (min-width: 768px) {
  .p-chat__body {
    max-width: none;
    margin: 0;
    border: none;
  }
  .p-chat__side {
    display: block;
    flex: none;
    /* 收起/展开平滑过渡；宽度 0 时裁掉侧栏内容 */
    overflow: hidden;
    transition: width .2s ease;
  }
  /* 侧栏与对话区之间不画任何边线——两块纸靠明度差自然衔接 */
  .p-chat__sidebox { border-right: none; }
  /* 整条顶栏在桌面端移除：标题、用户区都归左侧栏，对话区顶上不再有一条横线 */
  .p-chat .p-chat__topbar { display: none; }
  /* 滚动容器是**整幅宽**的：滚动条因此落在窗口最右缘，而不是缩在"对话框"里
     （2026-09-20 四版改。内容改由内边距收成 1000px 居中——内边距不会带着滚动条一起走） */
  .p-chat__main {
    width: 100%;
    max-width: none;
    margin: 0;
  }
  /* .p-chat__main 的**每一个**直接子元素都要在这里列全——容器全宽之后，
     漏掉一个（比如只读条）它就会自己铺满整幅宽，跟旁边的区块错位 */
  .p-chat .p-thread,
  .p-chat .p-steps,
  .p-chat .p-composer,
  .p-chat .p-chat__lock {
    max-width: none;
    margin: 0;
    border-left: none;
    border-right: none;
    /* 宽屏收到 1000px 居中，窄屏退化成 32px 内边距 */
    padding-left: max(32px, calc((100% - 1000px) / 2));
    padding-right: max(32px, calc((100% - 1000px) / 2));
  }
  /* 空状态下输入区不在纸的底边（下方还有居中留白），别在那儿画一条假纸边 */
  .p-chat__main.is-empty .p-composer { border-bottom: none; }
  .p-chat__menubtn { display: none; }
}

/* ---------- 手机 <768px：会话目录是覆盖层，对话区全宽 ---------- */
@media (max-width: 767px) {
  /* 触屏没有 hover，右缘那条得够宽才点得中；面板也收窄一点，别盖掉半屏正文。
     手机滚动条是悬浮式不占位，把手可以更贴边 */
  .p-chat__marks { width: 34px; right: 4px; }
  .p-chat__markspanel { width: 186px; }
  .p-chat__side {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 20;
    background: rgba(28, 43, 40, .28);
    opacity: 0;
    pointer-events: none;
    transition: opacity .18s ease;
  }
  .p-chat__side.is-open { opacity: 1; pointer-events: auto; }
  .p-chat__sidebox {
    width: 82%;
    max-width: 320px;
    box-shadow: 8px 0 22px rgba(28, 43, 40, .16);
  }
  .p-chat__menubtn {
    flex: none;
    margin-right: 10px;
    border: 1px solid var(--line);
    background: none;
    padding: 5px 10px;
    font-family: var(--sans);
    font-size: 10px;
    letter-spacing: .16em;
    color: var(--ink-2);
    cursor: pointer;
  }
  .p-chat__menubtn:hover { border-color: var(--teal); color: var(--teal); }
  .p-chat .p-topbar__title { flex: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .p-chat * { transition: none !important; }
}
</style>
