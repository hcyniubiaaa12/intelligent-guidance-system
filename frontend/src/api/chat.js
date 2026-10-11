import http from './http'

// 患者端导诊接口（链路 A + 链路 C 前半）
// 对话走 SSE，不在本文件：见 src/utils/sse.js（EventSource 带不了 Bearer 头与请求体）

/** 挂号页科室列表（仅启用科室）：resolve = [{ id, name, location, intro }] */
export function listDepts() {
  return http.get('/chat/depts')
}

/**
 * 挂号确认：写 actual_dept / 命中标记 / 会话置 closed。
 * resolve = { sessionId, deptId, deptName, location }；
 * register_success 埋点由后端接口补记，前端不上报
 */
export function confirmRegister(data) {
  return http.post('/chat/register', data)
}
