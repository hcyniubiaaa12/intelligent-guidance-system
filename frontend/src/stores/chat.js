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

  /**
   * 游客草稿：输入框文字 + 人体图声明，**跨「去登录 → 登录 → 回来」存活**。
   *
   * 它单独一组、不并进 entries：`draft` / `parts` / `partStage` 在游客态下是对话页的局部状态，
   * 跳登录那一刻对话页卸载，三样全没了——**而这是我们要求他去登录的，不是他改了主意**。
   *
   * 三条硬约束（各有它的失败形态）：
   *   ① **`reset()` 一行都不许碰它**。`reset()` 的语义是"清空这次会话"（开新咨询 / 挂号成功 /
   *      登出三处调用），草稿不是会话的一部分；塞进去的后果就是"游客点了去登录、回来一看
   *      输入框空了"，正是这组字段要修的那个缺陷。
   *   ② **清除点显式写，不靠"反正会被覆盖"**：`consumeGuestDraft()` 那一次（对话页挂载时取走），
   *      以及登出时调 `clearGuestDraft()`——登出根本不经过对话页挂载，取走那条兜不到它。
   *   ③ **不落 localStorage**：共用设备上会给下一个人看到上一位患者写的主诉。
   *
   * 形状 `{ draft, parts, partStage }`，读写方式照搬下面的 `consumeReplay()`。
   */
  const guestDraft = ref(null)

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

  /**
   * 清空"这次会话"。**`guestDraft` 不在这里清、也不许加进来**（见上方那一组字段的注释：
   * 塞进来会把"游客去登录、回来输入框空了"这个缺陷造回来）。
   */
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

  /**
   * 存游客草稿。**空草稿不存**（无文字、无部位 ⇒ 存成 null）：否则登录回来会拿一个空值
   * 覆写输入框与声明，比"没恢复"更糟。判空看 `trim`，但**存的是原文**——不替患者改写他写的字
   * （首尾空格在拼句时自有它的位置）。
   */
  function saveGuestDraft(next) {
    const draft = next?.draft || ''
    const parts = next?.parts || []
    if (!draft.trim() && parts.length === 0) {
      guestDraft.value = null
      return
    }
    guestDraft.value = { draft, parts: [...parts], partStage: next.partStage || 'awaiting' }
  }

  /** 取走游客草稿并清空（一次性：对话页挂载时调那一次。取走即清让时序单向，不赌登录跳转的先后） */
  function consumeGuestDraft() {
    const next = guestDraft.value
    guestDraft.value = null
    return next
  }

  /**
   * 显式清空游客草稿——登出路径专用（那里不经过对话页挂载，`consumeGuestDraft()` 兜不到它）。
   * 与 `reset()` 分开两个动作，正是为了"谁清草稿"永远能一眼看见：不是 reset 顺带干的。
   */
  function clearGuestDraft() {
    guestDraft.value = null
  }

  return {
    sessionId, entries, streaming, pendingReplay, guestDraft,
    pushEntry, removeEntry, setSession, reset, requestReplay, consumeReplay,
    saveGuestDraft, consumeGuestDraft, clearGuestDraft
  }
})
