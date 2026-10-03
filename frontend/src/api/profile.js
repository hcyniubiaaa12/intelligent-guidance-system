import http from './http'

// 患者端「健康档案」（auth 域）：读取自己那一份、整份覆盖写、加载标签词表。
// 档案**选填**——一个字都不填不产生任何副作用，导诊链路与以前完全一致。
// 上限来自后端受管参数（sys_config），前端不写死；前端只做即时提示，后端仍会二次校验。

/**
 * 读自己那份健康档案（未建档即返回空档案）。
 * resolve = {
 *   gender, ageRange, historyTags, historyOther,
 *   medicationTags, medicationOther, allergyTags, allergyOther,
 *   limits: { tagMax, textMax },
 *   options: { genders: [{ value, label }], ageRanges: [{ value, label }] }
 * }
 * limits 与 options 一并下发：页面不会出现「按旧上限拦人、被后端按新上限拒绝」的错位。
 */
export function getHealthProfile() {
  return http.get('/profile')
}

/**
 * 整份覆盖写：一次提交全部字段，未填即清空。
 * 标签超条数 / 自由文本超字数时后端拒绝（reject 携带可展示 message），前端据此提示。
 */
export function saveHealthProfile(data) {
  return http.put('/profile', data)
}

/**
 * 标签词表（仅启用项）。resolve = [{ id, term, type }]；type = chronic / medication / allergy，
 * 分别对应「既往病史 / 长期用药 / 过敏史」三组多选项。
 */
export function listHealthTags() {
  return http.get('/profile/tags')
}
