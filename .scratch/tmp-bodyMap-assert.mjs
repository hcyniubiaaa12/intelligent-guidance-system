// 临时断言脚本（验完即删、不入库）——前端没有测试框架，本仓库既定做法。
// 跑法：node .scratch/tmp-bodyMap-assert.mjs
import {
  PARTS, REGIONS, GLOBAL_WORDS, FEELINGS, FEELING_UNSURE, SIDES,
  regionById, isGlobalWord, declarationLocations, sentence, pickedLabel, sideLabel,
  assertVocabulary, visibleWords, regionHasWords,
  emptyState, selectWord, selectFeeling, selectSide, removeWord, removeFeeling, clearAll
} from '../frontend/src/utils/bodyMap.js'

let pass = 0
const fails = []
function eq(actual, expected, name) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) pass++
  else fails.push(`${name}\n    期望 ${e}\n    实际 ${a}`)
}
function ok(cond, name) {
  if (cond) pass++
  else fails.push(`${name}\n    期望为真，实际为假`)
}

// ── 词表覆盖账（设计稿的 29 词：图内 22 / 全身 2 / 刻意不进图 5）──
eq(PARTS.length, 29, '部位词表 29 词')
eq(assertVocabulary(), [], '映射里的词全在词表内（越界即失败）')

const inMap = new Set(REGIONS.flatMap((r) => r.words))
eq(inMap.size, 22, '图内可选 22 词')
eq(GLOBAL_WORDS.length, 2, '全身快捷 2 词')
const notInMap = PARTS.filter((w) => !inMap.has(w) && !GLOBAL_WORDS.includes(w))
eq(notInMap.sort(), ['心', '肠', '肺', '胃', '腹'].sort(), '刻意不进图的 5 词')
ok(!inMap.has('腹'), '「腹」不单独进图（它是「腹部」的单字形式）')

// ── 区域映射完整性 ──
eq(REGIONS.length, 13, '13 个区域')
ok(REGIONS.every((r) => r.id && r.name && r.words.length), '每个区域都有 id/名称/词')
ok(new Set(REGIONS.map((r) => r.id)).size === REGIONS.length, '区域 id 不重复')
eq(regionById('abd').name, '腹部', '按 id 取区域')
eq(regionById('nope'), null, '未知区域返回 null 而不是抛')
eq(regionById(undefined), null, '空 id 返回 null')

// ── 拼句：只有部位（本期最小形态）──
eq(declarationLocations(['腹部']), ['腹部'], '声明 = 部位词本身')
eq(sentence(['腹部']), '腹部不舒服，', '不选感觉 ⇒ 回落「不舒服」')
eq(sentence([]), '', '没选部位 ⇒ 句子为空（页面此时不允许提交）')
eq(sentence(['上腹', '下腹']), '上腹、下腹不舒服，', '多个部位顿号连接')
eq(pickedLabel(['头']), '头', '回执标签')
eq(pickedLabel([]), '', '没选时回执为空')

// ── 去空白 / 去重 / 垃圾输入 ──
eq(declarationLocations([' 腹部 ', '腹部', '']), ['腹部'], '去空白与去重')
eq(declarationLocations(null), [], 'null 安全')
eq(sentence(['腹部'], null), '腹部不舒服，', '感觉为 null 也不炸')
eq(sentence(['腹部'], [null, 123, '疼']), '腹部疼，', '感觉列表含垃圾项时过滤掉')

// ── 方位（工单 03）──
eq(SIDES, ['左', '右', '两侧'], '三个方位选项')
eq(declarationLocations(['腹部'], '左'), ['左腹部'], '左 + 腹部 ⇒ 左腹部')
eq(sentence(['腹部'], [], '左'), '左腹部不舒服，', '句子带方位前缀')
eq(declarationLocations(['上腹'], '左'), ['左上腹'], '左 + 上腹 ⇒ 左上腹（临床规范词）')
eq(declarationLocations(['皮肤'], '左'), ['皮肤'], '左 + 皮肤 ⇒ 全身词不加前缀')
eq(declarationLocations(['关节'], '右'), ['关节'], '右 + 关节 ⇒ 全身词不加前缀')
eq(declarationLocations(['腹部'], '两侧'), ['两侧腹部'], '两侧 + 腹部')
eq(declarationLocations(['腹部', '腰'], '左'), ['左腹部', '左腰'], '多部位都带前缀')
eq(sentence(['腹部'], [], '左').includes('左腹部'), true, '句子与声明同源')
eq(pickedLabel(['腹部'], '左'), '左腹部', '回执带方位')
eq(sideLabel('左'), '左侧', '左侧更像人话')
eq(sideLabel('两侧'), '两侧', '两侧原样')
eq(sideLabel(null), '', '没选方位时标签为空')
eq(declarationLocations(['上腹', '皮肤'], '左'), ['左上腹', '皮肤'], '全身词与局部词混选时只给局部的加前缀')
// 只选方位不选部位
eq(declarationLocations([], '左'), [], '只选方位不选部位 ⇒ 声明为空')
eq(sentence([], [], '左'), '', '只选方位不选部位 ⇒ 句子为空')

// ── 感觉（工单 04）──
eq(FEELINGS.length, 8, '八个感觉选项')
ok(FEELINGS.includes(FEELING_UNSURE), '「说不上来」是可选项')
eq(sentence(['腹部'], ['绞痛']), '腹部绞痛，', '腹部 + 绞痛')
eq(sentence(['腹部'], ['疼', '胀']), '腹部疼、胀，', '感觉可多选')
eq(sentence(['腹部'], ['说不上来']), '腹部不舒服，', '只选「说不上来」不进句子，回落不舒服')
eq(sentence(['腹部'], ['疼', '说不上来']), '腹部疼，', '「说不上来」与其他感觉并存时不进句子')
eq(sentence(['腹部'], ['说不上来', '疼']), '腹部疼，', '顺序不影响：说不上来照样被滤掉')
eq(sentence(['上腹'], ['绞痛'], '左'), '左上腹绞痛，', '方位 + 感觉组合')

// ── 状态机（幂等、可逆）──
let s = emptyState()
eq(s, { view: 'front', act: null, picked: [], feelings: [], side: null }, '初始态什么都没选')
s = selectWord(s, '腹部')
eq(s.picked, ['腹部'], '选词')
s = selectWord(s, '腹部')
eq(s.picked, [], '再点一次取消')
s = selectWord(s, '腹部')
s = selectSide(s, '左')
eq(s.side, '左', '选方位')
s = selectSide(s, '左')
eq(s.side, null, '再点同一个方位取消')
s = selectFeeling(s, '疼')
eq(s.feelings, ['疼'], '选感觉')
s = removeWord(s, '腹部')
eq(s.picked, [], '逐条删部位')
eq(s.feelings, ['疼'], '删部位不影响感觉')
s = removeFeeling(s, '疼')
eq(s.feelings, [], '逐条删感觉')
s = selectWord(selectWord(selectWord(s, '腹部'), '腰'), '头')
eq(s.picked, ['腹部', '腰', '头'], '多部位按点选顺序')
eq(clearAll().picked, [], '整体清空')
// 纯函数性：任何一步都不改原对象
const before = emptyState()
const after = selectWord(before, '腹部')
eq(before.picked, [], '原状态不被改写（纯函数）')
ok(after !== before, '返回新对象')

// ── 后端词表驱动的可见性 ──
eq(visibleWords(regionById('head'), PARTS), ['头', '眼', '鼻', '耳', '咽', '喉'], '词表齐全时全部可选')
eq(visibleWords(regionById('head'), ['头', '颈']), ['头'], '只给回词表里的词')
eq(visibleWords(regionById('head'), []), ['头', '眼', '鼻', '耳', '咽', '喉'], '词表为空 ⇒ 回落全量（接口没回≠都不给）')
eq(visibleWords(regionById('chest'), ['胸', '腹痛']), ['胸'], '症状词混进词表也不会露出（图上只出部位词）')
ok(regionHasWords(regionById('chest'), ['胸']), '区域仍有可选词')
ok(!regionHasWords(regionById('chest'), ['腹痛']), '词全被停用的区域不算有词')
eq(isGlobalWord('皮肤'), true, '皮肤是全身词')
eq(isGlobalWord('腹部'), false, '腹部不是全身词')

console.log(`通过 ${pass} 组断言`)
if (fails.length) {
  console.log(`\n失败 ${fails.length} 组：`)
  fails.forEach((f, i) => console.log(`\n${i + 1}. ${f}`))
  process.exit(1)
}