// SSE 客户端（链路 A 对话流）：fetch + ReadableStream 手写解析
// 为什么不用 EventSource：它无法携带 Authorization 头，也不支持 POST 请求体。
// 事件协议（见后端 SseEvents）：session → delta → (question | result) → done，异常走 error，
// notice（处置提示/警告）可出现在任意位置且不改变主流程；每条的 data 均为 JSON；
// error 之后后端还会补一个 done，前端以 error 收尾即可。
import router from '../router'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'

// SSE 事件名 → handlers 回调名
const HANDLER = {
  session: 'onSession',
  delta: 'onDelta',
  question: 'onQuestion',
  result: 'onResult',
  notice: 'onNotice',
  done: 'onDone',
  error: 'onError'
}

// 与 src/api/http.js 的 redirectToLogin 行为一致：清登录态并跳登录页
// 同时清空对话 store——共用设备上换账号登录后，不能看到上一位患者的主诉与结论卡
function redirectToLogin() {
  useChatStore().reset()
  useUserStore().logout()
  if (router.currentRoute.value.path !== '/login') {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

// 非 2xx：尽力读出可读错误（后端 Result 体 / 纯文本 / 兜底状态码）
async function readFailure(res) {
  let code = null
  let message = ''
  try {
    const body = await res.json()
    code = body?.code ?? null
    message = body?.message || ''
  } catch {
    try {
      message = (await res.text()).trim()
    } catch {
      // 响应体不可读：走下面的状态码兜底文案
    }
  }
  if (!message) message = `请求失败（HTTP ${res.status}）`
  return { code, message }
}

/**
 * 发送消息并消费 SSE 流（链路 A）。
 *
 * @param {{sessionId?: string, content: string}} payload 续聊回传 sessionId；为空则后端开新会话
 * @param {object} handlers 事件回调：onSession/onDelta/onQuestion/onResult/onNotice/onDone/onError
 *   参数为该事件的 data（JSON 已解析）；网络异常与流中断也回落到 onError({ message })
 * @param {AbortSignal} [signal] 可选：离开页面时中断，不写已卸载组件
 * @returns {Promise<void>} 流结束（或异常已回落 onError）后 resolve
 */
export async function streamChat({ sessionId, content }, handlers = {}, signal) {
  // 是否已经收到终态（done / error / 本地失败）：收流时没有终态 = 连接被中断
  let settled = false
  // onError 是唯一出口：回调自身抛错也不能把异常抛出本函数（调用方是 fire-and-forget）
  const fail = (message) => {
    if (settled) return
    settled = true
    try {
      handlers.onError?.({ message })
    } catch (e) {
      console.error('[sse] onError 回调异常', e)
    }
  }

  const token = localStorage.getItem('token')
  let res
  try {
    res = await fetch('/api/chat/message', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // 同时接受 JSON：接口 produces 是 text/event-stream，参数校验失败等错误路径返回的是
        // Result JSON——只声明 text/event-stream 会让 Spring 协商失败（406 空体），错误文案丢失
        Accept: 'text/event-stream, application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ sessionId: sessionId || undefined, content }),
      signal
    })
  } catch (e) {
    if (e?.name === 'AbortError') return
    fail('网络中断，请检查连接后重试')
    return
  }

  if (!res.ok) {
    const { code, message } = await readFailure(res)
    // 401 与业务码 2000 = 未登录/登录已过期，与 http.js 一致
    if (res.status === 401 || code === 2000) redirectToLogin()
    fail(message)
    return
  }
  // 后端全局异常处理会把业务异常/参数校验失败包成 HTTP 200 + Result JSON：
  // 那种响应不是事件流，必须按 Result 解析并报错，否则错误会被当成空流静默吞掉
  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    const { code, message } = await readFailure(res)
    if (res.status === 401 || code === 2000) redirectToLogin()
    fail(message)
    return
  }
  if (!res.body) {
    fail('当前浏览器不支持流式响应，请更换浏览器后重试')
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''
  let dataLines = []

  // 空行 = 一个事件结束：按 event 名分发（data 多行用 \n 拼接）
  function dispatch() {
    const name = eventName
    const raw = dataLines.join('\n')
    eventName = ''
    dataLines = []
    const handler = name ? handlers[HANDLER[name]] : null
    if (!handler || !raw) return
    if (name === 'done' || name === 'error') settled = true
    let payload
    try {
      payload = JSON.parse(raw)
      // 兼容载荷被二次编码成 JSON 字符串（后端把已序列化的 String 交给 JSON 转换器时会多一层引号）
      if (typeof payload === 'string') payload = JSON.parse(payload)
    } catch {
      return // 单条载荷不合法：丢弃该条，不打断整条流
    }
    try {
      handler(payload)
    } catch (e) {
      // 回调自身异常同样不打断流（否则后续事件全部丢失）
      console.error(`[sse] ${name} 事件处理异常`, e)
    }
  }

  function feedLine(line) {
    if (line === '') {
      dispatch()
      return
    }
    if (line.startsWith(':')) return // 注释行（心跳），忽略
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1) // 规范：冒号后仅一个空格不算内容
    if (field === 'event') eventName = value
    else if (field === 'data') dataLines.push(value)
  }

  function feed(text) {
    buffer += text
    let idx
    while ((idx = buffer.indexOf('\n')) !== -1) {
      let line = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 1)
      if (line.endsWith('\r')) line = line.slice(0, -1) // 兼容 CRLF
      feedLine(line)
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      feed(decoder.decode(value, { stream: true }))
    }
    feed(decoder.decode()) // 冲掉解码器余量
    if (buffer) {
      // 末事件没有收尾空行：按最后一行收尾并分发，避免丢结果
      feedLine(buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer)
      buffer = ''
      dispatch()
    }
    // 流结束但没收到任何终态事件（服务端超时/被中断）：必须报错，
    // 否则页面留下一个空白气泡，患者既看不到原因也没有重试入口
    if (!settled) fail('连接中断，请重新发送')
  } catch (e) {
    if (e?.name === 'AbortError') return
    fail('连接中断，请重新发送')
  }
}
