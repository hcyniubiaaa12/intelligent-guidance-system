<template>
  <div class="patient-root p-chat">
    <!-- 顶栏：病历抬头式 -->
    <header class="p-topbar">
      <div class="p-topbar__title">智能导诊</div>
      <div class="p-topbar__ops">
        <span class="p-topbar__sub">{{ user.nickname || '分 诊 台 · 在 线' }}</span>
        <button class="p-topbar__logout" @click="onLogout">退 出</button>
      </div>
    </header>

    <!-- 对话区：条目流（用户气泡 / AI 直排 / 追问 / 结论卡 / error） -->
    <main ref="threadEl" class="p-thread p-chat__thread">
      <p v-if="!chat.entries.length" class="p-empty">说说哪里不舒服，我来帮您分诊</p>

      <template v-for="(m, i) in chat.entries" :key="i">
        <!-- 用户消息：实心 teal 气泡 -->
        <div v-if="m.type === 'user'" class="p-user">{{ m.content }}</div>

        <!-- 追问消息：与普通回复同款 -->
        <div v-else-if="m.type === 'question'" class="p-ai">
          <div class="p-ai__tag">分 诊 助 理 · 追 问</div>
          <div class="p-ai__text p-ask">{{ m.content }}</div>
        </div>

        <!-- AI 回复：直排文字 + moss 小标签（SSE delta 逐字填充同一文本节点）
             本块必须单行书写：.p-ai__text 是 pre-wrap，换行缩进会被原样渲染 -->
        <div v-else-if="m.type === 'ai'" class="p-ai">
          <div class="p-ai__tag">分 诊 助 理</div>
          <div class="p-ai__text">{{ m.content }}<span v-if="chat.streaming && i === chat.entries.length - 1 && !m.content" class="p-ai__wait">正在整理…</span></div>
        </div>

        <!-- 处置提示（敏感词累计触发的警告）：单独成泡，视觉上与诊断结论区分开 -->
        <div v-else-if="m.type === 'notice'" class="p-notice">{{ m.content }}</div>

        <!-- 推荐卡（签名元素 · 链路 A 结论单）：只有最新一张能继续挂号 -->
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

          <button
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
    </main>

    <!-- 步进流程条：① 导诊结论 → ② 模拟挂号 → ③ 确认完成 -->
    <nav class="p-steps">
      <span class="p-steps__item p-steps__item--now"><span class="p-steps__no">1</span>导诊结论</span>
      <span class="p-steps__link" />
      <span class="p-steps__item"><span class="p-steps__no">2</span>模拟挂号</span>
      <span class="p-steps__link" />
      <span class="p-steps__item"><span class="p-steps__no">3</span>确认完成</span>
    </nav>

    <!-- 输入区 -->
    <footer class="p-composer">
      <textarea
        v-model="draft"
        class="p-composer__input"
        rows="1"
        placeholder="说说哪里不舒服，我来帮您分诊"
        @keydown.enter.exact.prevent="send()"
      />
      <button class="p-composer__send" :disabled="chat.streaming" @click="send()">发送</button>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { useChatStore } from '../../stores/chat'
import { logout as apiLogout } from '../../api/auth'
import { streamChat } from '../../utils/sse'
import { track } from '../../utils/track'
import '../../styles/patient.css'

const router = useRouter()
const user = useUserStore()
const chat = useChatStore()

const draft = ref('')
const threadEl = ref(null)
// 当前流的终止句柄：离开页面或退出登录时中断，避免回调写已卸载的组件
let turnAbort = null

// 只有最新一张结论卡可继续挂号（一个聊天页可先后承载多个会话与多张卡）
const lastCardIndex = computed(() => {
  for (let i = chat.entries.length - 1; i >= 0; i--) {
    if (chat.entries[i].type === 'card') return i
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
  chat.pushEntry({ type: 'user', content })
  runTurn(content)
}

// 一轮导诊：占位 AI 气泡 → SSE 四态（delta / question / result / done / error）
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

onBeforeUnmount(() => turnAbort?.abort())
</script>

<style scoped>
.p-chat {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.p-chat__thread {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 18px;
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
</style>
