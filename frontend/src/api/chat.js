import http from './http'

// 患者端导诊接口（链路 A + 链路 C 前半）
// 对话走 SSE，不在本文件：见 src/utils/sse.js（EventSource 带不了 Bearer 头与请求体）

/** 挂号页科室列表（仅启用科室）：resolve = [{ id, name, location, intro }] */
export function listDepts() {
  return http.get('/chat/depts')
}

/**
 * 人体图的部位词清单：resolve = string[]，如['腹部', '上腹', '腰', …]。
 * 词源唯一是术语白名单的部位类（type=part 且启用）——管理员停用一个词，
 * 患者端选项里立刻不再出现它。**这里只有词，没有"图上哪块区域对应哪个词"**：
 * 图形是前端资产，后端不知道也不需要知道。
 */
export function listBodyParts() {
  return http.get('/chat/parts')
}

/**
 * 挂号确认：写 actual_dept / 命中标记 / 会话置 closed。
 * resolve = { sessionId, deptId, deptName, location }；
 * register_success 埋点由后端接口补记，前端不上报
 */
export function confirmRegister(data) {
  return http.post('/chat/register', data)
}
