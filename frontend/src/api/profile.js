import http from './http'

// 患者端「健康档案」（auth 域）：读取自己那一份、整份覆盖写、加载标签词表。
// 缩表后只保留「性别 / 出生年 / 过敏 / 近期用药 / 近期手术」；
// 旧年龄段只在出生年缺失时作只读兜底，既往病史不再读写。
// 上限来自后端受管参数（sys_config），前端不写死；前端只做即时提示，后端仍会二次校验。

/**
 * 读自己那份健康档案（未建档即返回空档案）。
 * resolve = {
 *   gender, birthYear, ageRange（只读兜底）,
 *   allergyTags, allergyOther, medicationTags, medicationOther, surgeryTags, surgeryOther,
 *   limits: { tagMax, textMax, textTotalMax },
 *   options: { genders: [{ value, label }], ageRanges: [{ value, label }] }
 * }
 * limits 与 options 一并下发：页面不会出现「按旧上限拦人、被后端按新上限拒绝」的错位。
 * ageRange / ageRanges 只为工单 03 的只读兜底保留，本页不编辑。
 */
export function getHealthProfile() {
  return http.get('/profile')
}

/**
 * 整份覆盖写：一次提交全部**可编辑**字段，未填即清空。
 * 不提交 ageRange / history_*（保留库中原值，作迁移与回滚依据）。
 * 标签超条数 / 自由文本超字数 / 出生年越界时后端拒绝（reject 携带可展示 message）。
 */
export function saveHealthProfile(data) {
  return http.put('/profile', data)
}

/**
 * 标签词表（仅启用项）。resolve = [{ id, term, type }]；
 * type = allergy / medication / surgery，分别对应「过敏史 / 近期用药 / 近期手术」三组多选项。
 * （chronic 为旧慢病类别，保留在词表里但不再展示。）
 */
export function listHealthTags() {
  return http.get('/profile/tags')
}
