/**
 * 对话请求体纯规则自检 —— 与 `check-body-map.mjs` 共用一个闸（`npm run check` + pre-commit）。
 *
 * 为什么值得单独一个常驻脚本：这里钉的是一条**真丢过东西**的规则 ——
 * 请求体里带不带部位声明，**不许看 `sessionId`**。
 *
 * 事故原型（2026-10-14 修）：`...(sessionId || !parts?.length ? {} : { parts })` 拿"我手里
 * 有没有会话 id"去猜"这一轮是不是新会话"。猜错的那一刻恰好是**唯一需要声明的那一轮**——
 * 出结论后接着问、或挂号成功后回来问：后端（`ChatSession.continuable()`）会新建会话、
 * `isFirstTurn=true`，正等着这份声明，而前端因为还留着旧 `sessionId` 把它丢了。
 * 后端愿意收、前端没带，**两边都不报错、日志里也没有痕迹**。
 *
 * 所以这条规则只认一个判据：**有声明就带；接不接纳由后端裁决。**
 *
 * 用法：node scripts/check-chat-body.mjs
 */
import { buildChatBody } from '../src/utils/chatBody.js'

let passed = 0
const failures = []

function eq(actual, expected, what) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) { passed++; return }
  failures.push(`${what}\n      期望 ${e}\n      实际 ${a}`)
}

/* ---------- ① 声明：有就带，**不看 sessionId** ---------- */

eq(buildChatBody({ sessionId: '', content: '左腿疼', parts: ['左腿'] }),
  { content: '左腿疼', parts: ['左腿'] },
  '新会话（无 sessionId）：有声明就带上')

eq(buildChatBody({ sessionId: 's1', content: '左腿疼', parts: ['左腿'] }),
  { sessionId: 's1', content: '左腿疼', parts: ['左腿'] },
  '★ 带着 sessionId 也不许把声明吞掉——后端开新会话时正需要它（这条就是曾经丢东西的地方）')

eq(buildChatBody({ sessionId: 's1', content: '左腿疼', parts: ['左腿', '腹部'] }),
  { sessionId: 's1', content: '左腿疼', parts: ['左腿', '腹部'] },
  '多选部位原样带上，不合并不加前缀（方位前缀在 bodyMap 里就并进词了）')

/* ---------- ② 没有声明：不写这个键（空数组与缺省等价） ---------- */

eq(buildChatBody({ sessionId: 's1', content: '两天了', parts: [] }),
  { sessionId: 's1', content: '两天了' },
  '空声明 ⇒ 不写 parts 键')

eq(buildChatBody({ sessionId: 's1', content: '两天了' }),
  { sessionId: 's1', content: '两天了' },
  '缺省 parts 同样不写这个键')

/* ---------- ③ sessionId：为空就不写（后端据此开新会话） ---------- */

eq(buildChatBody({ sessionId: '', content: '胸口闷' }), { content: '胸口闷' }, '空 sessionId ⇒ 不写这个键')

eq(Object.keys(buildChatBody({ sessionId: undefined, content: '胸口闷' })).includes('sessionId'), false,
  '…连键都不该出现（写 null 会被读成"有会话"）')

/* ---------- ④ content 原样透传（裁剪是调用方的事） ---------- */

eq(buildChatBody({ content: ' 两 天 了 ' }).content, ' 两 天 了 ', 'content 原样，不 trim 不改写')

/* ---------- 结果 ---------- */

if (failures.length) {
  console.error(`\n✗ ${failures.length} 条失败（${passed} 条通过）：\n`)
  for (const f of failures) console.error(`  ✗ ${f}\n`)
  process.exit(1)
}
console.log(`\n✓ 对话请求体纯规则 ${passed} 条断言全部通过`)
