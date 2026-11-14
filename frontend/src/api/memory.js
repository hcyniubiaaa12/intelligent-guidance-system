import http from './http'

// 患者端「长期记忆」（memory 域）控制面。
// 列表 + 开关 + 编辑 + 删除 + 清空 + 档案候选接受/拒绝。

/**
 * 读自己的长期记忆。
 * resolve = {
 *   memoryEnabled,                       // 自动记忆开关：关闭只停抽取与注入，旧记忆仍可见
 *   preferences: [{ id, key, value, sourceQuote, observedAt, status }],
 *   backgrounds: [{ ...同上 }],          // 两类背景（background_health / background_other）
 *   candidates: [{ id, field, fieldLabel, value, occurredOn, sourceQuote, status, conflict }],
 *                                        // 档案候选（待确认）：未确认前不进回答；conflict 置顶
 *   limits: { preferenceMax, backgroundMax },  // 来自 sys_config，前端不写死
 *   needsCleanup                         // 背景达到上限：提示患者整理
 * }
 * observedAt 为后端本地时间（ISO 字符串），页面本地化展示。
 * 服务端每次打开浮层都重拉，前端不缓存——记忆可能刚被抽取写入。
 */
// 「我记住的」与「待确认」是同一次 GET 的两个消费方，浮层打开时会同时请求。
// 这里只合并**同一时刻在飞的请求**（响应回来即清），不是缓存：下一次打开仍重新拉，
// 不会被前端缓存抵消后端的即时生效。失败也清空，让下一次点击「重试」真的重试。
let pending = null
export function getMemory() {
  if (!pending) {
    pending = http.get('/memory').finally(() => { pending = null })
  }
  return pending
}

/**
 * 自动记忆开关。关闭只停抽取与注入，旧记忆仍在列表里可见（页面标「已暂停」）。
 * resolve = undefined（Result.ok()）
 */
export function setMemoryEnabled(memoryEnabled) {
  return http.put('/memory/setting', { memoryEnabled })
}

/** 改一条记忆的值（后端只改 mvalue，出处与观察时间保留） */
export function updateMemory(id, value) {
  return http.put(`/memory/${encodeURIComponent(id)}`, { value })
}

/** 删除一条记忆（后端逻辑删除 + 审计留痕） */
export function deleteMemory(id) {
  return http.delete(`/memory/${encodeURIComponent(id)}`)
}

/** 清空当前用户的全部生效记忆；resolve = 删除条数 */
export function clearMemories() {
  return http.delete('/memory')
}

/**
 * 接受一条档案候选（先改再收）。
 * value 省略 = 按原候选值接受；传值 = 用患者改后的值写档案。
 */
export function acceptCandidate(id, value) {
  return http.post(
    `/memory/candidates/${encodeURIComponent(id)}/accept`,
    value == null ? {} : { value }
  )
}

/** 拒绝一条档案候选：只标 rejected，不写档案；同一值以后不再提议 */
export function rejectCandidate(id) {
  return http.post(`/memory/candidates/${encodeURIComponent(id)}/reject`)
}
