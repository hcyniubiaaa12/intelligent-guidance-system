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

// 新增科室：name 必填且全库唯一（模型按名字回填科室）；新建即出现在挂号页与候选清单
export function createKbDept(data) {
  return http.post('/admin/kb/depts', data)
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

// —— 审核队列（链路 C）——
// 立即聚合：归桶是整点定时任务，演示与排查等不了那一小时。返回本次成功归桶的记录数。
// 这里单独放宽超时：聚合是同步跑批（每条记录还可能调一次 embedding），比普通 CRUD 慢得多，
// 全局的 15s 会让它在跑完之前被前端掐断——后端其实归桶成功了，用户却看到超时失败，
// 于是再点一次，撞上 6004「聚合任务正在运行」，前后矛盾。
export function aggregateBuckets() {
  return http.post('/admin/review/aggregate', null, { timeout: 120000 })
}

// 待归桶记录数：审核页顶部那行提示。与「立即聚合」实际会扫到的集合同源（后端共用一份查询条件）。
// 叫 records 不叫 samples——词表里「样本」是桶内代表样本，这里是还没归桶的导诊记录
export function countPendingRecords() {
  return http.get('/admin/review/pending-records/count')
}

// 待审桶按样本数降序；科室名由后端拼好，页面不拿科室 id 给人看
export function pagePendingBuckets(params) {
  return http.get('/admin/review/pending', { params })
}

// 终态桶：修正重审的入口
export function pageTerminalBuckets(params) {
  return http.get('/admin/review/terminal', { params })
}

// 侧栏徽标：没有待审就是 0，不写死数字
export function countPendingBuckets() {
  return http.get('/admin/review/pending/count')
}

// 桶详情：代表样本 + 证据快照 + 交叉科室预填
export function getReviewBucket(id) {
  return http.get(`/admin/review/buckets/${id}`)
}

// 根因字典：key 入库、label 显示，页面不本地镜像
export function listRootCauses() {
  return http.get('/admin/review/causes')
}

// 科室选择器：含停用科室（审核是知识层动作，与开不开诊无关）
export function listReviewDepts() {
  return http.get('/admin/review/depts')
}

// 逐条覆盖根因（归因只写到单条记录上，没有桶级一键套用）
export function updateRecordCauses(recordId, causes) {
  return http.post(`/admin/review/records/${recordId}/causes`, { causes })
}

// 预览合成 chunk：症状与科室名由后端按桶推导，这里只传科室 id
export function previewSyntheticChunk(id, data) {
  return http.post(`/admin/review/buckets/${id}/preview`, data)
}

// 确认 approve：syntheticText 是管理员定稿后的文本
export function approveBucket(id, data) {
  return http.post(`/admin/review/buckets/${id}/approve`, data)
}

export function rejectBucket(id) {
  return http.post(`/admin/review/buckets/${id}/reject`)
}

export function dismissBucket(id) {
  return http.post(`/admin/review/buckets/${id}/dismiss`)
}

// 修正重审：终态桶回到待审；已 approve 的会撤掉上次的台账与合成 chunk
export function reReviewBucket(id) {
  return http.post(`/admin/review/buckets/${id}/re-review`)
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
