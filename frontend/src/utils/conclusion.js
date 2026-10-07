/**
 * 分诊结论的**纯派生**：置信度文案、低置信度判定、Top3 展示顺序、结论单纯文本。
 *
 * 这四件事原先长在 Chat.vue 里，但它们与 SSE、与回放、与会话状态都无关——输入一个
 * `card` 快照就能算出结果。抽出来有两个用处：① 推荐卡组件自己就能用，不必把整张卡
 * 的数据从页面里传下去；② 顺序那条口径（第一位必须是结论主体）是踩过坑的防御性逻辑，
 * 单独成文件才看得见、也才测得到。
 *
 * 输入形状（实时 SSE 的 `result` 事件与回放快照 `d.card` 同构）：
 *   dept / confidence / top3[] / note / cites[] / profileText / lowConfidence
 */

/** 模型未给出合法置信度时后端置 null：显示「—」并走低置信度样式，不出现 NaN% / null% */
export function confText(confidence) {
  return typeof confidence === 'number' && Number.isFinite(confidence)
    ? `${Math.round(confidence * 100)}%`
    : '—'
}

/**
 * 低置信度以**后端下发为准**（阈值来自 sys_config，管理端可调）；
 * 前端只在后端没给标志时按「置信度缺失」兜底，不自己写死阈值，避免与管理端口径打架。
 */
export function isLow(card) {
  return card.lowConfidence === true || typeof card.confidence !== 'number'
}

/**
 * Top3 展示顺序：**结论主体（card.dept）钉在第 1 位，其余按 pct 降序、null 垫底**。
 *
 * 后端 rag 层已归一化过顺序（首位 = 顶层 dept），这里是同一口径的防御性渲染：
 * ① 2026-10-02 之前落库的 rec_top3 快照里顺序是乱的，回放时那批记录仍要显示正确；
 * ② 万一后端顺序又出问题，第一位也不能是备选科室——它得跟卡片顶部的大科室名一致。
 * 纯展示层重排，不改任何数据。
 */
export function rankedTop3(card) {
  const list = Array.isArray(card.top3) ? [...card.top3] : []
  const rank = (c) => (typeof c.pct === 'number' && Number.isFinite(c.pct) ? c.pct : -1)
  // 稳定排序：pct 相同时保持后端给的先后（Array.prototype.sort 在现代引擎上稳定）
  list.sort((a, b) => rank(b) - rank(a))
  const top = list.findIndex((c) => c.name === card.dept)
  // 找到就钉到首位；找不到（老快照里科室名对不上）就保持降序——此时 pct 最高的那条就是 top1
  if (top > 0) list.unshift(...list.splice(top, 1))
  return list
}

/** 结论卡复制成纯文本：科室＋置信度＋Top3＋说明＋依据，方便贴给医生/家人看 */
export function cardText(card) {
  const lines = [`分诊结论：${card.dept}（参考置信度 ${confText(card.confidence)}）`]
  if (card.top3?.length) {
    lines.push('候选科室：' + rankedTop3(card).map((c, k) => `${k + 1}. ${c.name}${c.pct == null ? '' : ' ' + c.pct + '%'}`).join('，'))
  }
  if (card.note) lines.push(card.note)
  if (card.cites?.length) {
    lines.push('判断依据：')
    card.cites.forEach((c) => lines.push(`  注${c.no}　${c.title}\n    ${c.content}`))
  }
  if (card.profileText) lines.push(`已参考您的健康档案：${card.profileText}`)
  lines.push('（分诊建议，不能替代医生诊断）')
  return lines.join('\n')
}