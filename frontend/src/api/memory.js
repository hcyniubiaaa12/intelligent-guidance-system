import http from './http'

// 患者端「长期记忆」（memory 域）：只读控制面。
// 本工单只有列表；编辑 / 删除 / 开关 / 候选接受拒绝由后续工单补。

/**
 * 读自己的长期记忆。
 * resolve = {
 *   memoryEnabled,                       // 自动记忆开关：关闭只停抽取与注入，旧记忆仍可见
 *   preferences: [{ id, key, value, sourceQuote, observedAt, status }],
 *   backgrounds: [{ ...同上 }],          // 两类背景（background_health / background_other）
 *   candidates: [],                      // 待确认候选：本工单固定为空
 *   limits: { preferenceMax, backgroundMax },  // 来自 sys_config，前端不写死
 *   needsCleanup                         // 背景达到上限：提示患者整理
 * }
 * observedAt 为后端本地时间（ISO 字符串），页面本地化展示。
 * 服务端每次打开浮层都重拉，前端不缓存——记忆可能刚被抽取写入。
 */
export function getMemory() {
  return http.get('/memory')
}
