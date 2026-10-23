/**
 * 游客草稿纯规则自检 —— 与 `check-body-map.mjs` / `check-chat-body.mjs` 共用一个闸
 * （`npm run check` + pre-commit 通配 `scripts/check-*.mjs`）。
 *
 * 钉的是什么：游客在对话页写的字与在人体图上选好的部位，必须活过「去登录 → 登录 → 回来」
 * 这一趟。它当初丢过：`draft` / `parts` / `partStage` 都是对话页的局部状态，跳登录那一刻
 * 组件卸载，三样全没了——**而这是我们要求他去登录的，不是他改了主意**。
 *
 * 四条规则（工单 02）：
 *   ① 取走即清：`consumeGuestDraft()` 给回上次存的内容，再取一次是空；
 *   ② `reset()` 不碰草稿：它的语义是"清空这次会话"（开新咨询 / 挂号成功 / 登出三处调用），
 *      草稿恰恰是要保住的——塞进去的后果正是"游客点了去登录、回来一看输入框空了"；
 *   ③ 登出清空：登出根本不经过对话页挂载，`consumeGuestDraft()` 兜不到它，必须显式清；
 *   ④ 空草稿不算草稿：否则登录回来会用空值覆写输入框。
 *
 * 为什么直接 import store（而不是把规则抽成零依赖 utils）：Pinia 的 setup store 在 Node 里
 * 能拉起来（`createPinia()` + `setActivePinia()`，本脚本首行即验），而**要钉的规则就是 store
 * 的公开行为**（尤其「`reset()` 不碰它」只有对着真 store 才断言得了）。抽成纯函数会让脚本
 * 断言一个和线上调用路径并不相同的副本——那样绿了也不算数。node_modules 是 dev 的既有前提。
 *
 * 用法：node scripts/check-guest-draft.mjs
 */
import { readFileSync } from 'node:fs'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '../src/stores/chat.js'

let passed = 0
const failures = []

function eq(actual, expected, what) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) { passed++; return }
  failures.push(`${what}\n      期望 ${e}\n      实际 ${a}`)
}

function ok(cond, what) {
  if (cond) { passed++; return }
  failures.push(what)
}

setActivePinia(createPinia())
const chat = useChatStore()

const SAMPLE = { draft: '左腹部疼，两天了', parts: ['左腹部'], partStage: 'locked' }

/* ---------- ① 取走即清 ---------- */

chat.saveGuestDraft(SAMPLE)
eq(chat.consumeGuestDraft(), SAMPLE, '★ 取回上次存的草稿（文字 / 部位 / 阶段三样都在）')
eq(chat.guestDraft, null, '取走即清：store 里不再留着')
eq(chat.consumeGuestDraft(), null, '再取一次 ⇒ 空（一次性，不是"读"）')

/* ---------- ② reset() 不碰草稿 ---------- */

chat.setSession('s1')
chat.pushEntry({ type: 'user', content: '胸口闷' })
chat.saveGuestDraft(SAMPLE)
chat.reset()
// 先确认 reset() 真跑了——否则下一条会在"reset 根本没执行"时也绿，断言就成了摆设
eq(chat.sessionId, '', '前置：reset() 确实清了会话 id')
eq(chat.entries.length, 0, '前置：reset() 确实清了对话条目')
eq(chat.guestDraft, SAMPLE, '★ reset() 之后草稿仍在——开新咨询 / 挂号成功都不该清草稿')

/* ---------- ③ 登出清空（动作 + 接线） ---------- */

chat.saveGuestDraft(SAMPLE)
chat.clearGuestDraft()
eq(chat.guestDraft, null, 'clearGuestDraft() 清空草稿')
eq(chat.consumeGuestDraft(), null, '…清完再取也是空')

// 动作为什么要单独断言接线：登出根本不经过对话页挂载，没有人调它 = 草稿不落进下一个人眼里
// 这条就落空。动作本身对、调用点漏写，是这类"显式清除"最常见的失败形态。
const chatVue = readFileSync(new URL('../src/views/patient/Chat.vue', import.meta.url), 'utf8')
const logoutBody = chatVue.match(/async function onLogout\(\)\s*\{([\s\S]*?)\n\}/)
ok(logoutBody, '在 Chat.vue 里找得到 onLogout（找不到就没法确认登出清草稿这条接线）')
ok(logoutBody && /clearGuestDraft\(\)/.test(logoutBody[1]),
  '★ 登出路径（Chat.vue 的 onLogout）必须显式调 clearGuestDraft()')

/* ---------- ④ 空草稿不算草稿 ---------- */

chat.saveGuestDraft({ draft: '', parts: [], partStage: 'awaiting' })
eq(chat.guestDraft, null, '无文字、无部位 ⇒ 不存（否则登录回来会用空值覆写输入框）')

chat.saveGuestDraft({ draft: '   ', parts: [], partStage: 'locked' })
eq(chat.guestDraft, null, '只有空白字符同样算空（判空看 trim 后）')

chat.saveGuestDraft({ draft: '', parts: ['左腿'], partStage: 'locked' })
eq(chat.guestDraft, { draft: '', parts: ['左腿'], partStage: 'locked' },
  '有部位没文字 ⇒ 是草稿（选好的部位不能丢）')

chat.saveGuestDraft({ draft: '头疼', parts: [], partStage: 'awaiting' })
eq(chat.guestDraft, { draft: '头疼', parts: [], partStage: 'awaiting' },
  '有文字没部位 ⇒ 是草稿（写下的主诉不能丢）')

chat.saveGuestDraft({ draft: ' 头疼 ', parts: [], partStage: 'awaiting' })
eq(chat.guestDraft.draft, ' 头疼 ', '存的是原文，不替患者 trim（别静默改写他写的字）')

/* ---------- 结果 ---------- */

if (failures.length) {
  console.error(`\n✗ ${failures.length} 条失败（${passed} 条通过）：\n`)
  for (const f of failures) console.error(`  ✗ ${f}\n`)
  process.exit(1)
}
console.log(`\n✓ 游客草稿纯规则 ${passed} 条断言全部通过`)
