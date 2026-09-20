import { defineStore } from 'pinia'
import { ref } from 'vue'

// 会话状态 store：当前会话、对话条目、SSE 流式状态
// entries 为对话条目数组（一个聊天页可先后承载多个会话与多张结论卡），形态：
//   { type: 'user' | 'ai' | 'question', content }
//   { type: 'card', recordId, card }   结论卡（挂号与埋点都要 recordId）
//   { type: 'notice', content }        处置提示（敏感词累计触发的警告），独立一泡
//   { type: 'error', message, text }   error 态（text = 待重发的输入）
export const useChatStore = defineStore('chat', () => {
  // 当前会话 id：续聊时随每次请求回传（后端据此判定新主诉 / 追问）
  const sessionId = ref('')
  const entries = ref([])
  const streaming = ref(false)

  /** 追加条目并返回其响应式引用（流式增量直接改引用即可触发渲染，避免整数组替换） */
  function pushEntry(entry) {
    entries.value.push(entry)
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

  return { sessionId, entries, streaming, pushEntry, removeEntry, setSession, reset }
})
