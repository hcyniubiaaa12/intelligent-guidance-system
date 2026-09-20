import http from './http'

// 患者端「就诊记录」（只读回放）——看自己的会话与挂号，不写任何东西。
// 越权防护在后端：列表按登录用户过滤，详情比对会话归属，别人的会话一律按「不存在」返回。

/**
 * 我的会话列表（按天分组，天与会话都最新在前）。
 * resolve = { days: [{ date, total, sessions: [SessionItem] }], totalSessions, totalBooked }
 * 「全部对话」用 days 全量，「挂号历史」用其中 booked=true 的条目——同一个接口，前端换口径过滤。
 */
export function listSessions() {
  return http.get('/records/sessions')
}

/**
 * 一条会话的完整回放。
 * resolve = { session, messages: [{ role, content, at, questionNo }],
 *             questions: [{ no, content, at }], card }
 * questions 是「用户问题」书签的锚点（role=user 的消息，按时间从 1 编号），
 * card 为 null 表示这条会话没出结论。
 */
export function sessionDetail(id) {
  return http.get(`/records/sessions/${id}`)
}
