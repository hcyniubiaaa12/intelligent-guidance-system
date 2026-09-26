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

// 解除禁言（禁言本会到期自动解除，这里用于需要立即放行的场景）；返回是否本来在禁言中
export function unmuteUser(id) {
  return http.post(`/admin/users/${id}/unmute`)
}

// 用户违规明细：窗口内按词聚合的命中 + 处置记录
export function getUserViolations(id) {
  return http.get(`/admin/users/${id}/violations`)
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

// —— 敏感词处置规则（sys_config，与词库同页）——
// 窗口、两条禁止词阈值、观察词阈值、禁言时长；禁言阈值必须大于警告阈值，否则后端整批拒绝
export function getSensitiveRules() {
  return http.get('/admin/sensitive-words/rules')
}

export function saveSensitiveRules(items) {
  return http.put('/admin/sensitive-words/rules', { items })
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

// —— 知识库（链路 B）——
// 文档列表每行带最近一次运行的 task（stage/status/total/done）——进度按阶段显示真实量，
// 前端**不自己算总进度**：解析的百分比来自外部服务、写入的计数来自后端，两段拼不成一根 0–100% 的条
export function pageKbDocs(params) {
  return http.get('/admin/kb/docs', { params })
}

// 上传：file 走 multipart，deptId 必填；返回 { docId, taskId }，taskId 用于轮询进度
export function uploadKbDoc(file, deptId, title) {
  const form = new FormData()
  form.append('file', file)
  form.append('deptId', deptId)
  if (title) form.append('title', title)
  return http.post('/admin/kb/docs', form)
}

export function getKbTask(taskId) {
  return http.get(`/admin/kb/tasks/${taskId}`)
}

// 查看切片：正文以 MySQL 为准（pgvector 里那份只是排查用的副本）
export function getKbChunks(docId) {
  return http.get(`/admin/kb/docs/${docId}/chunks`)
}

// 重新处理：先删旧切片再重建，用 MinIO 里的原文件（没有"替换文件"这个操作）
export function reprocessKbDoc(docId) {
  return http.post(`/admin/kb/docs/${docId}/reprocess`)
}

// 删除：先终止在飞任务，再走删除补偿（ES → 向量 → 元数据 → MinIO）
export function deleteKbDoc(docId) {
  return http.delete(`/admin/kb/docs/${docId}`)
}

// 科室蓝本（科室 tab 与上传表单共用；含停用科室）
export function listKbDepts() {
  return http.get('/admin/kb/depts')
}

export function updateKbDept(deptId, data) {
  return http.put(`/admin/kb/depts/${deptId}`, data)
}

// 映射台账（只读：台账是审核事实的留痕）
export function pageKbMappings(params) {
  return http.get('/admin/kb/mappings', { params })
}

// 术语白名单（含停用）
export function pageKbTerms(params) {
  return http.get('/admin/kb/terms', { params })
}

export function toggleKbTerm(termId) {
  return http.post(`/admin/kb/terms/${termId}/toggle`)
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
