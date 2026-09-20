import http from './http'

// 管理端接口（/api/admin/**，仅 ROLE_ADMIN）

// —— 用户管理 ——
export function pageUsers(params) {
  return http.get('/admin/users', { params })
}

export function banUser(id) {
  return http.post(`/admin/users/${id}/ban`)
}

export function unbanUser(id) {
  return http.post(`/admin/users/${id}/unban`)
}

// —— 敏感词库 ——
export function pageWords(params) {
  return http.get('/admin/sensitive-words', { params })
}

export function addWord(data) {
  return http.post('/admin/sensitive-words', data)
}

// 批量导入：text 一行一词，返回 { imported, skipped }
export function importWords(data) {
  return http.post('/admin/sensitive-words/import', data)
}

export function toggleWord(id) {
  return http.post(`/admin/sensitive-words/${id}/toggle`)
}

export function convertWordToBanned(id) {
  return http.post(`/admin/sensitive-words/${id}/to-banned`)
}

export function deleteWord(id) {
  return http.delete(`/admin/sensitive-words/${id}`)
}

// —— LLM 配置 ——
// 模型接入信息：模型名与地址可读，密钥只回是否已配置（不回值）
export function getLlmModels() {
  return http.get('/admin/llm/models')
}

// 运行时参数（sys_config）：{ key, label, value, defaultValue, type, range, remark }
export function getLlmParams() {
  return http.get('/admin/llm/params')
}

// 批量保存参数：items = [{ key, value }]，任一项非法则整批不生效
export function saveLlmParams(items) {
  return http.put('/admin/llm/params', { items })
}

// 连通性测试：三路模型各发一次最小请求，返回逐项结果
export function probeLlm() {
  return http.post('/admin/llm/probe')
}

// —— 数据看板 ——
// 首屏聚合：KPI + 每日趋势 + 根因分布（准确率口径由后端统一，见 DashboardService 注释）
export function getDashboardOverview(days = 7) {
  return http.get('/admin/dashboard/overview', { params: { days } })
}

// 最近导诊记录分页
export function pageGuideRecords(params) {
  return http.get('/admin/dashboard/records', { params })
}
