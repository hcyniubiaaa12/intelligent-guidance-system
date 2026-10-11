/**
 * 人体图纯规则自检 —— `npm run check`
 *
 * 为什么要有个**常驻**脚本：本仓库没有前端测试框架，历史上验证这些规则的办法是
 * "临时 node 脚本跑一遍、验完即删"。代价是每次都要从零重推一遍断言，而且推完就扔掉，
 * 成果不积累。这个脚本把断言固定下来：改完 `utils/bodyMap.js` 跑一次，红了就是破坏了外部行为。
 *
 * 只断言**外部可观察的行为**（给定选择状态，拼出的句子 / 结构化声明是什么），
 * 不断言实现细节——不锁私有函数、不锁中间数据结构。
 *
 * 与旧 `assertVocabulary` 的区别：那个函数要守的是"图上的词必须落在词表内"，
 * 该不变式已不再强制，函数随之删除。这里守的是另一件事——**图上的字必须是一个真能落下的词**
 * （见下面「④ 命名与可选词同源」），也就是"同一份事实只允许一处拼装"那条坑。
 *
 * 用法：node scripts/check-body-map.mjs
 */
import {
  sentence, declarationLocations, pickedLabel, regionsOfLocations, sideLabel,
  regionById, isGlobalWord, visibleWords, regionHasWords, landingWord,
  emptyState, selectWord, selectFeeling, selectSide, removeWord, clearAll,
  PARTS, REGIONS, GLOBAL_WORDS, QUICK_PICKS, NO_LOCATION_HINT
} from '../src/utils/bodyMap.js'

let passed = 0
const failures = []

function eq(actual, expected, what) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) { passed++; return }
  failures.push(`${what}\n      期望 ${e}\n      实际 ${a}`)
}

function ok(cond, what) {
  if (cond) { passed++; return }
  failures.push(what)
}

/* ---------- ① 拼句与结构化声明 ---------- */

eq(declarationLocations(['腹部'], '左'), ['左腹部'], '方位前缀并进词里')
eq(declarationLocations(['腹部'], null), ['腹部'], '没选方位 ⇒ 无前缀')
eq(declarationLocations(['皮肤'], '左'), ['皮肤'], '全身性词不加方位前缀（"左皮肤"没有意义）')
eq(declarationLocations(['关节'], '右'), ['关节'], '全身性词不加方位前缀')
eq(declarationLocations([], '左'), [], '没选部位 ⇒ 空声明')

eq(sentence(['腹部'], ['疼'], '左'), '左腹部疼，', '拼句：部位段 + 感觉 + 「，」')
eq(sentence(['上腹'], ['疼'], '左'), '左上腹疼，', '「上腹 + 左」拼成临床规范的「左上腹」')
eq(sentence(['腹部', '腰'], ['疼', '胀'], '两侧'), '两侧腹部、两侧腰疼、胀，', '多个部位顿号连接')
eq(sentence(['腹部'], [], '左'), '左腹部不舒服，', '没选感觉 ⇒ 回落"不舒服"（不是所有不适都疼）')
eq(sentence(['腹部'], ['说不上来']), '腹部不舒服，', '「说不上来」是占位项，不进句子')
eq(sentence(['腹部'], ['疼', '说不上来']), '腹部疼，', '「说不上来」可与其他感觉并存，但不进句子')
eq(sentence([], ['疼'], '左'), '', '没选部位 ⇒ 空串（页面此时不该允许提交）')

eq(pickedLabel(['腹部'], '左'), '左腹部', '「位置已锁定」回执里的部位串')
eq(pickedLabel([], '左'), '', '没声明 ⇒ 回执换文案')

/* ---------- ② 结构化位置反查回图上的区域 ---------- */

eq(regionsOfLocations(['左腹部']), ['abd'], '方位前缀要先剥离再匹配区域词')
eq(regionsOfLocations(['左上腹']), ['abd'], '「上腹」落在腹部那块')
eq(regionsOfLocations(['右腿']), ['leg'], '整条下肢是一个区域')
eq(regionsOfLocations(['左下肢']), ['leg'], '「下肢」与「腿」同属一块')
eq(regionsOfLocations(['背']), ['back'], '背部区域')
eq(regionsOfLocations(['皮肤']), [], '全身性词没有对应图块')
eq(regionsOfLocations([]), [], '空声明 ⇒ 不点亮任何区域')

/* ---------- ③ 区域表与快捷入口的结构性不变式 ---------- */

const ids = REGIONS.map((r) => r.id)
ok(new Set(ids).size === ids.length, '区域 id 必须唯一')
ok(REGIONS.every((r) => Array.isArray(r.words) && r.words.length > 0), '每个区域至少有一个可选词')
ok(new Set(PARTS).size === PARTS.length, '词表镜像里不能有重复词')
ok(typeof NO_LOCATION_HINT === 'string' && NO_LOCATION_HINT.length > 0, '未选部位的提示语不能为空')

for (const pick of QUICK_PICKS) {
  if (pick.region === null) {
    ok(GLOBAL_WORDS.includes(pick.word), `快捷词「${pick.label}」标了无区域，它的词「${pick.word}」必须是全身性词`)
  } else {
    const region = regionById(pick.region)
    ok(region !== null, `快捷词「${pick.label}」指向的区域 ${pick.region} 必须存在`)
    ok(region?.words.includes(pick.word) === true,
      `快捷词「${pick.label}」的「${pick.word}」必须属于区域 ${pick.region} 的可选词（实际：${region?.words.join(' / ')}）`)
  }
}

/* ---------- ④ 命名与可选词同源（"同一份事实只允许一处拼装"） ---------- */

// 图上的字必须是一个**真能落下的词**：每个区域都必须有命名，且命名是自己可选词里的一员。
// 命名与可选词若各写一份，就会漂移成"图上说右大腿、已选栏写腿"。
for (const region of REGIONS) {
  ok(typeof region.label === 'string' && region.label.length > 0, `区域 ${region.id} 的命名必须是非空字符串`)
  ok(region.words.includes(region.label),
    `区域 ${region.id} 的命名「${region.label}」必须是它自己可选词里的一员（实际：${region.words.join(' / ')}）`)
}

/* ---------- ④b 首选落词 + 该词被停用后的回落（只此一份实现） ---------- */

eq(landingWord('leg', ['腿', '下肢']), '腿', '有首选落词就用它（「腿」，不是「下肢」）')
eq(landingWord('leg', ['下肢']), '下肢', '首选被停用 ⇒ 回落该区域剩下的第一个词')
eq(landingWord('chest', ['胸', '胸口']), '胸口', '胸部的首选是「胸口」不是「胸」')
eq(landingWord('abd', ['上腹', '腹部', '下腹']), '腹部', '腹部的首选是「腹部」')
eq(landingWord('leg', []), '腿', '取不到词表（回落全量）⇒ 还是首选落词')
eq(landingWord('leg', ['头']), null, '该区域一个可选词都没有 ⇒ null（这块现在点不动）')
eq(landingWord('不存在的区域', ['腿']), null, '未知区域 ⇒ null，不抛')

/* ---------- ⑤ 按后端词表过滤 ---------- */

eq(visibleWords(regionById('abd'), ['腹部']), ['腹部'], '只保留患者现在还能选的词')
eq(visibleWords(regionById('abd'), ['腿']), [], '区域里一个词都没留下 ⇒ 空')
eq(visibleWords(regionById('abd'), []), ['上腹', '腹部', '下腹'], '取不到词表 ⇒ 回落全量（不是"全停用"）')
ok(regionHasWords(regionById('abd'), ['腹部']) === true, '还有词可选')
ok(regionHasWords(regionById('abd'), ['腿']) === false, '没有词可选')
ok(regionById('不存在的区域') === null, '未知区域返回 null，不抛（图上多点一下不该弄崩页面）')

/* ---------- ⑥ 选择态的纯派生 ---------- */

eq(emptyState(), { view: 'front', act: null, picked: [], feelings: [], side: null }, '初始状态：什么都没选')
ok(emptyState() !== emptyState(), '初始状态每次都是新对象（不共享引用）')
eq(selectWord(emptyState(), '腹部').picked, ['腹部'], '选词')
eq(selectWord(selectWord(emptyState(), '腹部'), '腹部').picked, [], '再选一次 = 取消')
eq(removeWord(selectWord(emptyState(), '腹部'), '腹部').picked, [], '逐条删除')
eq(selectFeeling(emptyState(), '疼').feelings, ['疼'], '感觉可选')
eq(selectSide(emptyState(), '左').side, '左', '方位单选')
eq(selectSide(selectSide(emptyState(), '左'), '左').side, null, '再点一次同一个方位 = 取消')
eq(clearAll().picked, [], '全部清空')
eq(clearAll().feelings, [], '全部清空（感觉一并清）')

eq(isGlobalWord('皮肤'), true, '皮肤是全身性词')
eq(isGlobalWord('腹部'), false, '腹部不是全身性词')
eq(sideLabel('左'), '左侧', '方位显示名更像人话')
eq(sideLabel('两侧'), '两侧', '「两侧」原样')
eq(sideLabel(null), '', '没选方位 ⇒ 空串')

/* ---------- 结果 ---------- */

if (failures.length) {
  console.error(`\n✗ ${failures.length} 条失败（${passed} 条通过）：\n`)
  for (const f of failures) console.error(`  ✗ ${f}\n`)
  process.exit(1)
}
console.log(`\n✓ 人体图纯规则 ${passed} 条断言全部通过`)
