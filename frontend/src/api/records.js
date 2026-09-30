import http from './http'

// 患者端「就诊记录」——看自己的会话与挂号。除归档外全是只读回放。
// 越权防护在后端：列表按登录用户过滤，详情与归档比对会话归属，别人的会话一律按「不存在」返回。

/**
 * 我的会话列表（主区按天分组 + 归档区平铺，都最新在前）。
 * resolve = { days: [{ date, total, sessions: [SessionItem] }], archived: [SessionItem],
 *             totalSessions, totalBooked }
 * 「全部对话」用 days 全量、「挂号历史」用其中 booked=true 的条目——同一个接口，前端换口径过滤；
 * archived 是收进收纳区的那批（跟着同一口径过滤）。
 * SessionItem.archived 也可用于二次判断，但正常情况下按后端分好的两份数组走就行。
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

/**
 * 归档 / 取回一条会话（患者整理自己的列表，**不是删除**）：archived=true 收进收纳区，false 取回主区。
 * 归档后仍可只读回放，随时可取回；若在归档会话里又发了消息，后端会自动把它取回主区。
 */
export function archiveSession(id, archived) {
  return http.post(`/records/sessions/${id}/archive`, { archived })
}
