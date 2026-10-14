import { defineStore } from 'pinia'
import { ref } from 'vue'

let nextEntryId = 1

// 会话状态 store：当前会话、对话条目、SSE 流式状态
// entries 为对话条目数组（一个聊天页可先后承载多个会话与多张结论卡），形态：
//   { type: 'user' | 'ai' | 'question' | 'info', content }（info = 资料回答，患者问知识库内容时的复述）
//   { type: 'card', recordId, card }   结论卡（挂号与埋点都要 recordId）
//   { type: 'notice', content }        处置提示（敏感词累计触发的警告），独立一泡
//   { type: 'error', message, text }   error 态（text = 待重发的输入）
export const useChatStore = defineStore('chat', () => {
  // 当前会话 id：续聊时随每次请求回传（后端据此判定新主诉 / 追问）
  const sessionId = ref('')
  const entries = ref([])
  const streaming = ref(false)

  /**
   * 挂号成功那一刻「要回放的那条会话」——**内存态，不落 URL query、不落本地存储**（工单 04）。
   *
   * 为什么必须是内存态：只读态是"这次挂号刚结束"的一次性提示，刷新页面回到普通空状态
   * 才是对的（那确实是新的一次就诊）；走 URL query 会让刷新也恢复只读态，说反了。
   * 走 store 是因为 chat store 跨路由持久，而页面级状态（门禁 / 声明）随 Chat.vue
   * 重新挂载天然归零——不需要跨组件传标记、不需要路由参数。
   */
  const pendingReplay = ref(null) // { sessionId, justBooked: true }

  /** 追加条目并返回其响应式引用（流式增量直接改引用即可触发渲染，避免整数组替换） */
  function pushEntry(entry) {
    // 删除占位气泡会移动下标，稳定 id 才能把复制等组件状态留在原消息上。
    entries.value.push({ ...entry, id: `e-${nextEntryId++}` })
    return entries.value[entries.value.length - 1]
  }

  function removeEntry(entry) {
    const i = entries.value.indexOf(entry)
    if (i !== -1) entries.value.splice(i, 1)
  }

  /** 记下后端 session 事件给的会话 id（空值不覆盖） */
  function setSession(id) {
    if (id) sessionId.value = id
  }

  function reset() {
    sessionId.value = ''
    entries.value = []
    streaming.value = false
  }

  /** 挂号成功：把刚结束的这条会话交给对话页按只读回放打开（调用方须先取走 sessionId） */
  function requestReplay(sessionId) {
    if (sessionId) pendingReplay.value = { sessionId, justBooked: true }
  }

  /** 取走待回放的会话并清空（一次性：刷新或再次进入都不该重复触发） */
  function consumeReplay() {
    const next = pendingReplay.value
    pendingReplay.value = null
    return next
  }

  return {
    sessionId, entries, streaming, pendingReplay,
    pushEntry, removeEntry, setSession, reset, requestReplay, consumeReplay
  }
})
