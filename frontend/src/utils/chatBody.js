/**
 * 对话请求体（链路 A）—— **无依赖纯函数**。
 *
 * 为什么单独放一个文件：这条规则是曾经把声明静默丢掉的那个位置，必须能被**常驻自检**
 * （`scripts/check-chat-body.mjs`，接在 `npm run check` 与 pre-commit 硬闸里）钉住；而
 * `utils/sse.js` 依赖 router / pinia，纯 node 导不进来（仓库里唯一可自检的前端形态就是纯函数）。
 *
 * 规则的边界（2026-10-14 定，别改回去）：
 * **带不带声明由前端按自己的状态决定，接不接纳由后端裁决。**
 * 所以这里只做"有就带"—— **不看 `sessionId`**。
 *
 * 历史：这里曾是 `...(sessionId || !parts?.length ? {} : { parts })`，用"我手里有没有会话 id"
 * 去猜"这一轮是不是新会话"。猜错的那一刻，恰好是唯一需要声明的那一轮——出结论后接着问、
 * 或挂号成功后回来问：后端（`ChatSession.continuable()`）会**新建会话**、`isFirstTurn=true`，
 * 正等着这份声明，而前端因为还留着旧 `sessionId` 把它丢了。后端愿意收、前端没带，两边都不报错，
 * 于是没有任何日志能说明"患者点了位置却等于没点"。
 *
 * @param {{sessionId?: string, content: string, parts?: string[]}} payload
 *   `sessionId` 续聊时回传（为空则后端开新会话）；`parts` 是患者在人体图上标明的部位，
 *   **缺省/空数组等价**，由调用方决定给不给。
 * @returns {object} 可直接 `JSON.stringify` 的请求体
 */
export function buildChatBody({ sessionId, content, parts }) {
  return {
    // 空值不写这个键：缺省与 null 对后端等价，但不写更不容易被读成"有会话"
    ...(sessionId ? { sessionId } : {}),
    content,
    // 空数组与缺省等价，所以只在真有内容时才写这个键
    ...(parts?.length ? { parts } : {})
  }
}
