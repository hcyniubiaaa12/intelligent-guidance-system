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
  regionById, regionOfWord, isGlobalWord, visibleWords, regionHasWords, landingWord,
  faceOfRegion, pickedWordsOf, sideOfRegion, sideOfWord, setRegionSide, clickOutcome, sidesOfLocations,
  emptyState, selectWord, selectFeeling, removeWord, clearAll,
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

/* ---------- ① 拼句与结构化声明（方位是**每块一份**） ---------- */

eq(declarationLocations(['腹部'], { abd: '左' }), ['左腹部'], '方位前缀并进词里')
eq(declarationLocations(['腹部'], {}), ['腹部'], '这块没标方位 ⇒ 无前缀')
eq(declarationLocations(['皮肤'], { head: '左' }), ['皮肤'], '全身性词不加方位前缀（"左皮肤"没有意义）')
eq(declarationLocations(['关节'], { leg: '右' }), ['关节'], '全身性词不加方位前缀')
eq(declarationLocations([], { abd: '左' }), [], '没选部位 ⇒ 空声明')

// 本模型的核心：每块各带各的方位，谁都不覆盖谁
eq(declarationLocations(['上肢'], { arm: '左' }), ['左上肢'], '点左上肢')
eq(declarationLocations(['上肢'], { arm: '两侧' }), ['两侧上肢'], '同一块点两个半边 ⇒ 两侧')
eq(declarationLocations(['上肢', '腿'], { arm: '两侧', leg: '左' }), ['两侧上肢', '左腿'], '★ 点左上肢→点右上肢→点左腿：上肢仍「两侧」，腿记它自己的「左」')
eq(declarationLocations(['上肢', '腿'], { arm: '左', leg: '右' }), ['左上肢', '右腿'], '两块各记各的侧')
eq(declarationLocations(['腹部', '腿'], { leg: '左' }), ['腹部', '左腿'], '没标方位的那块不跟着别人加前缀')

eq(sentence(['腹部'], ['疼'], { abd: '左' }), '左腹部疼，', '拼句：部位段 + 感觉 + 「，」')
eq(sentence(['上腹'], ['疼'], { abd: '左' }), '左上腹疼，', '「上腹 + 左」拼成临床规范的「左上腹」')
eq(sentence(['腹部', '腰'], ['疼', '胀'], { abd: '两侧', waist: '两侧' }), '两侧腹部、两侧腰疼、胀，', '多个部位顿号连接')
eq(sentence(['上肢', '腿'], ['疼'], { arm: '两侧', leg: '左' }), '两侧上肢、左腿疼，', '★ 用户给的目标序列（拼成一句话）')
eq(sentence(['腹部'], [], { abd: '左' }), '左腹部不舒服，', '没选感觉 ⇒ 回落"不舒服"（不是所有不适都疼）')
eq(sentence(['腹部'], ['说不上来'], {}), '腹部不舒服，', '「说不上来」是占位项，不进句子')
eq(sentence(['腹部'], ['疼', '说不上来'], {}), '腹部疼，', '「说不上来」可与其他感觉并存，但不进句子')
eq(sentence([], ['疼'], { abd: '左' }), '', '没选部位 ⇒ 空串（页面此时不该允许提交）')

eq(pickedLabel(['腹部'], { abd: '左' }), '左腹部', '「位置已锁定」回执里的部位串')
eq(pickedLabel([], { abd: '左' }), '', '没声明 ⇒ 回执换文案')

/* ---------- ①b 声明 → 区域/方位（回执与回放靠它把图重新点亮） ---------- */

eq(sidesOfLocations(['两侧上肢', '左腿']), { arm: '两侧', leg: '左' }, '把已声明的部位串拆回「区域 → 方位」')
eq(sidesOfLocations(['左腹部']), { abd: '左' }, '单侧')
eq(sidesOfLocations(['腹部']), {}, '没前缀的词不产出方位（"不标"）')
eq(sidesOfLocations(['左查不到的词']), {}, '认不出区域 ⇒ 跳过，不抛')
eq(sidesOfLocations([]), {}, '空声明 ⇒ 空')
eq(sidesOfLocations(['皮肤']), {}, '全身词没有区域、也不带方位')
eq(declarationLocations(['上肢', '腿'], sidesOfLocations(['两侧上肢', '左腿'])), ['两侧上肢', '左腿'], '与 declarationLocations 互为逆（往返不变）')

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
for (const region of REGIONS) {
  for (const w of region.words) {
    ok(PARTS.includes(w), `区域 ${region.id} 的词「${w}」必须在人体图词表 PARTS 里（两处都在 bodyMap.js，必须一致）`)
  }
}
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

/* ---------- ④c 覆盖层共用的两条小规则（同样只此一份实现） ---------- */

eq(faceOfRegion('back'), 'back', '背画在背面那张图上')
eq(faceOfRegion('waist'), 'back', '腰也在背面')
eq(faceOfRegion('leg'), 'front', '其余区域在正面')
eq(faceOfRegion('不存在的区域'), 'front', '未知区域回落正面，不抛')

eq(pickedWordsOf('leg', ['腿', '腹部']), ['腿'], '只挑出属于这块区域的词')
eq(pickedWordsOf('leg', ['下肢', '腿']), ['下肢', '腿'], '同属一整条腿的两个词都算')
eq(pickedWordsOf('abd', ['腿']), [], '这块一个词都没选 ⇒ 空')
eq(pickedWordsOf('不存在的区域', ['腿']), [], '未知区域 ⇒ 空，不抛')
ok(Array.isArray(pickedWordsOf('leg', null)), 'picked 传 null 不炸')

/* ---------- ⑤ 按后端词表过滤 ---------- */

eq(visibleWords(regionById('abd'), ['腹部']), ['腹部'], '只保留患者现在还能选的词')
eq(visibleWords(regionById('abd'), ['腿']), [], '区域里一个词都没留下 ⇒ 空')
eq(visibleWords(regionById('abd'), []), ['上腹', '腹部', '下腹'], '取不到词表 ⇒ 回落全量（不是"全停用"）')
ok(regionHasWords(regionById('abd'), ['腹部']) === true, '还有词可选')
ok(regionHasWords(regionById('abd'), ['腿']) === false, '没有词可选')
ok(regionById('不存在的区域') === null, '未知区域返回 null，不抛（图上多点一下不该弄崩页面）')

/* ---------- ⑥ 选择态的纯派生 ---------- */

eq(emptyState(), { view: 'front', act: null, picked: [], sides: {}, feelings: [] }, '初始状态：什么都没选')
ok(emptyState() !== emptyState(), '初始状态每次都是新对象（不共享引用）')
eq(selectWord(emptyState(), '腹部').picked, ['腹部'], '选词')
eq(selectWord(selectWord(emptyState(), '腹部'), '腹部').picked, [], '再选一次 = 取消')
eq(removeWord(selectWord(emptyState(), '腹部'), '腹部').picked, [], '逐条删除')
eq(selectFeeling(emptyState(), '疼').feelings, ['疼'], '感觉可选')
eq(clearAll().picked, [], '全部清空')
eq(clearAll().feelings, [], '全部清空（感觉一并清）')
eq(clearAll().sides, {}, '全部清空（方位一并清）')

eq(isGlobalWord('皮肤'), true, '皮肤是全身性词')
eq(isGlobalWord('腹部'), false, '腹部不是全身性词')
eq(sideLabel('左'), '左侧', '方位显示名更像人话')
eq(sideLabel('两侧'), '两侧', '「两侧」原样')
eq(sideLabel(''), '', '没标方位 ⇒ 空串')

/* ---------- ⑦ 方位：每块一份（工单 12） ---------- */

eq(regionOfWord('腿')?.id, 'leg', '词反查区域')
eq(regionOfWord('皮肤'), null, '全身词不属于任何区域')
eq(sideOfRegion('arm', { arm: '左' }), '左', '按区域 id 取方位')
eq(sideOfRegion('arm', {}), '', '这块没标 ⇒ 空串')
eq(sideOfRegion(null, { arm: '左' }), '', '没有目标块 ⇒ 空串，不抛')
eq(sideOfWord('上肢', { arm: '左' }), '左', '按词取方位（走它所属的区域）')
eq(sideOfWord('皮肤', { skin: '左' }), '', '全身词永远不带方位')
ok(sideOfRegion('arm', { arm: '左' }) !== sideOfRegion('leg', { arm: '左' }), '两块各取各的，不串味')

eq(setRegionSide(emptyState(), 'arm', '左').sides, { arm: '左' }, '给某块定方位')
eq(setRegionSide(setRegionSide(emptyState(), 'arm', '左'), 'leg', '右').sides, { arm: '左', leg: '右' }, '两块各记各的')
eq(setRegionSide(setRegionSide(emptyState(), 'arm', '左'), 'arm', '').sides, {}, '改回"不标" ⇒ 条目删掉')
eq(setRegionSide(emptyState(), null, '左').sides, {}, '没有目标块 ⇒ 原样返回，不抛')
eq(removeWord(setRegionSide(selectWord(emptyState(), '上肢'), 'arm', '左'), '上肢').sides, {}, '取消这块钱 ⇒ 方位一起收走（不留孤儿）')
eq(removeWord(setRegionSide(selectWord(emptyState(), '上肢'), 'arm', '左'), '上肢').picked, [], '取消这块 ⇒ 词也没了')

// ★ 图上的点击规则（只此一份实现，覆盖层只管照做）
eq(clickOutcome({ selected: false, current: '', clicked: '左' }), { keep: true, side: '左' }, '未选 ⇒ 选中，方位 = 点的那一侧')
eq(clickOutcome({ selected: true, current: '左', clicked: '左' }), { keep: false }, '★ 同一侧再点一次 ⇒ 直接取消这块')
eq(clickOutcome({ selected: true, current: '左', clicked: '右' }), { keep: true, side: '两侧' }, '点另一半边 ⇒ 两边都疼')
eq(clickOutcome({ selected: true, current: '两侧', clicked: '右' }), { keep: true, side: '左' }, '从「两侧」点右 ⇒ 只剩左（规则使然）')
eq(clickOutcome({ selected: true, current: '两侧', clicked: '左' }), { keep: true, side: '右' }, '从「两侧」点左 ⇒ 只剩右')
eq(clickOutcome({ selected: true, current: '', clicked: '右' }), { keep: true, side: '右' }, '这块还没标过 ⇒ 点哪半边记哪侧')
eq(clickOutcome({ selected: false, current: '', clicked: null }), { keep: true }, '中心块：点一下只选中，不碰方位')
eq(clickOutcome({ selected: true, current: '左', clicked: null }), { keep: false }, '中心块：再点一下取消（中心块没有半边，方位不由点击决定）')

/* ---------- 结果 ---------- */

if (failures.length) {
  console.error(`\n✗ ${failures.length} 条失败（${passed} 条通过）：\n`)
  for (const f of failures) console.error(`  ✗ ${f}\n`)
  process.exit(1)
}
console.log(`\n✓ 人体图纯规则 ${passed} 条断言全部通过`)
