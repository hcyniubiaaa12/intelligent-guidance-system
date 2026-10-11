/**
 * 部位声明的**纯规则**：图上「哪块区域 ↔ 哪个词」的映射、把选择拼成一句话与结构化声明。
 *
 * 这是本功能唯一能把全部规则用纯逻辑表达的地方——覆盖层的视觉、门禁的时机都无法自动化，
 * 但**规则的每一个分支都能在这里被断言**。所以覆盖层只管画与点，拼句一律走这里，
 * 页面里不允许出现第二份拼句实现。
 *
 * 三条不可从简的规则：
 *
 * ① **拼出的句子必须含术语词**（信息充足性门槛的要求）——这是本功能唯一要守的硬门槛。
 *    图上**可选**（会写进句子）的词**取自**后端词表（`medical_term` 的 `type=part` 且启用），
 *    `PARTS` 是那份词清单的**镜像**。图上这块地方**叫什么**是**前端资产**，
 *    不受术语表约束（见 `CONTEXT.md` 的「图上命名」）——锚点在**句子**，不在**图**。
 *    （原有一道 `assertVocabulary` 自检把"图上的词必须落在词表内"当不变式，锚点移到句子后删除。）
 *
 * ② **方位只是前缀，不是新词**：术语表里没有左 / 右，但"左腹部 / 左上腹 / 左上肢"都含部位词，
 *    照样命中白名单。而「上腹 + 左」在临床上本来就写作「左上腹」——组合出来的词比口语更规范。
 *
 * ③ **全身性词不加方位前缀**："左皮肤"没有意义。
 *
 * 输出形状：`{ locations, sentence }`
 *   - `locations` —— **每块各自的**方位前缀 + 部位词，进请求的部位声明字段（**结构化、只装部位**）
 *   - `sentence`  —— 部位 + 感觉 +「，」，进患者那句话（`content`，患者可继续编辑）
 *
 * 为什么是两份而不是一份：患者随后可能改掉输入框里那句话，但"他在图上标过 X"这个事实始终为真。
 * 声明字段因此**不随患者的编辑变化**（见 Chat.vue 的提交处），且只在新会话首条输入携带一次。
 */

/** 部位类白名单镜像（29 词，来自 kb 的 SeedCorpus.PART_TERMS）——映射越出它即失败 */
export const PARTS = Object.freeze([
  '胸口', '胸', '上腹', '下腹', '腹部', '腹', '头', '颈', '肩', '腰', '背', '腿', '膝', '髋',
  '关节', '咽', '鼻', '耳', '皮肤', '胃', '心', '肺', '肠', '喉', '眼', '手', '脚', '上肢', '下肢'
])

/** 背面那张图上画的是背与腰；其余区域都在正面。覆盖层两处要问这件事，规则只写这一份。 */
const BACK_REGIONS = ['back', 'waist']

/**
 * 「形 ↔ 词」映射：**形是前端资产**（哪块区域画在哪），词都取自 {@link PARTS}（那份词清单的镜像）。
 *
 * `id` 是区域标识（SVG 上的 data-r）；`words` 是这块区域包含的部位词；`label` 是**图上命名**——
 * 这块在图上、读屏里、已选栏里写出来的那个字，取值恒为该区域的**首选落词**
 * （所以图上写的就是一个真能落下的词，`npm run check` 的 ④ 钉住"命名必须是自己可选词里的一员"）。
 *
 * **没有单独的"区域展示名"**：侧栏标题报的是**实际落下的词**（见 `landingWord`），图上与读屏写的
 * 是 `label`——两者都是"一个真能落下的词"。留一个只给内部看的书面区域名（"'下肢'/'足部'"）只会
 * 制造"这里说下肢、那里说腿"的第二份事实（工单 09 的口径）。
 *
 * **命名不随实时词表漂移**：管理员停用「腿」时图上仍写「腿」，只是落词回落到「下肢」。
 * 命名稳定优先——反过来的做法会让一次运营动作直接改写患者看到的解剖命名。
 *
 * 「腹」刻意不在任何区域里——它是「腹部」的单字形式，图上重复给会让患者以为点的是两处。
 */
export const REGIONS = Object.freeze([
  { id: 'head', label: '头', words: ['头', '眼', '鼻', '耳', '咽', '喉'] },
  { id: 'neck', label: '颈', words: ['颈'] },
  { id: 'shoulder', label: '肩', words: ['肩'] },
  { id: 'chest', label: '胸口', words: ['胸', '胸口'] },
  { id: 'abd', label: '腹部', words: ['上腹', '腹部', '下腹'] },
  { id: 'hip', label: '髋', words: ['髋'] },
  { id: 'back', label: '背', words: ['背'] },
  { id: 'waist', label: '腰', words: ['腰'] },
  { id: 'arm', label: '上肢', words: ['上肢'] },
  { id: 'hand', label: '手', words: ['手'] },
  { id: 'leg', label: '腿', words: ['腿', '下肢'] },
  { id: 'knee', label: '膝', words: ['膝'] },
  { id: 'foot', label: '脚', words: ['脚'] }
])

/**
 * 全身性词：挂"全身性的"那一段，**不加方位前缀**。
 * 图上画不出"整张皮肤哪儿都不舒服"，所以给的是词而不是区域。
 */
export const GLOBAL_WORDS = Object.freeze(['皮肤', '关节'])

/**
 * 「感觉」一排是**前端自持**，无法与后端同源。
 *
 * 原因：术语表里的症状词全是「部位 + 症状」的复合词（腹痛 / 胸痛 / 头痛 / 腰痛），
 * **没有"绞痛 / 刺痛"这类纯形容词**。硬要塞进术语表就得为它们单造一批 type=symptom 的词，
 * 而那批词进了白名单就会开始参与信息充足性判定与检索聚合——为一个输入框的便利去动判定口径，
 * 不划算。不影响判定：句子里已经有部位词兜住命中。
 */
export const FEELINGS = Object.freeze(['疼', '胀', '酸', '麻', '灼热', '绞痛', '刺痛', '说不上来'])

/**
 * 「说不上来」是感觉里的**未作答占位项**，不是一种感觉：它是"说不清"的显式表达。
 * 所以它可与其他感觉并存（"疼，说不上来别的"），但**绝不进句子**——进了就是
 * "腹部说不上来，"这种读不通的东西。
 */
export const FEELING_UNSURE = '说不上来'

/** 哪一侧：单选。都是前缀不是新词（见文件头规则②） */
export const SIDES = Object.freeze(['左', '右', '两侧'])

/** 没选感觉时的回落词：用"不舒服"而不是"疼"——不是所有不适都疼 */
const DEFAULT_FEELING = '不舒服'

/**
 * 常见主诉快捷词：点了**直接打开图并预选**对应区域，让必填变成省事而不是拦路。
 *
 * 只保留能明确对应一个体表区域的 5 个。**「发热咳嗽」刻意不在列**——它对应不了任何
 * 体表区域（发热是全身症状、咳嗽的器官在图上又不给），留着它等于给必填开了一条后门：
 * 患者点了它、绕过选图，门禁就形同虚设。这类主诉走「说不清在哪儿」出口才对。
 *
 * `region` 是 REGIONS 里的区域 id（预选后右栏直接列出这块的细分词）；`word` 是预选中的部位词，
 * 必须都在 {@link PARTS} 里——皮肤是全身词（走 GLOBAL_WORDS，没有区域）。
 */
export const QUICK_PICKS = Object.freeze([
  { label: '肚子疼', region: 'abd', word: '腹部' },
  { label: '头疼头晕', region: 'head', word: '头' },
  { label: '心慌胸闷', region: 'chest', word: '胸口' },
  { label: '腰背酸痛', region: 'waist', word: '腰' },
  { label: '皮肤起疹', region: null, word: '皮肤' }
])

/** 没选部位时的话（"说不清在哪儿"那条出口走的就是它） */
export const NO_LOCATION_HINT = '还没选，先点一下图上不舒服的位置'

/** 按 id 取区域；未知 id 返回 null（不抛——图上多点一下不该弄崩页面） */
export function regionById(id) {
  return REGIONS.find((r) => r.id === id) || null
}

/** 哪个区域包含这个词（每个词只属于一个区域）。不属于任何区域（皮肤 / 关节这类全身词）⇒ null */
export function regionOfWord(word) {
  return REGIONS.find((r) => r.words.includes(word)) || null
}

/** 该词是否全身性（决定加不加方位前缀） */
export function isGlobalWord(word) {
  return GLOBAL_WORDS.includes(word)
}

/**
 * 这块区域当前的方位：`''`（不标）/ `'左'` / `'右'` / `'两侧'`。
 *
 * **方位是每块一份**（`sides` 以**区域 id** 为键——不能按词存：换细分词"腹部→上腹"时方位会丢）。
 * 为什么改掉"全局一份"：全局一份时"点另一块"必然要么覆盖、要么被覆盖，患者说的
 * "上肢两侧、腿左侧"这种话在模型里根本表达不出来（2026-10-11 四轮 grill 的根因）。
 */
export function sideOfRegion(regionId, sides = {}) {
  return (regionId && sides[regionId]) || ''
}

/** 某个词的方位；全身词永远空（"左皮肤"没有意义） */
export function sideOfWord(word, sides = {}) {
  if (isGlobalWord(word)) return ''
  return sideOfRegion(regionOfWord(word)?.id, sides)
}

/** 这块区域画在哪一面：「背面」还是「正面」。点图与快捷词预选都要问，规则只写这一份。 */
export function faceOfRegion(regionId) {
  return BACK_REGIONS.includes(regionId) ? 'back' : 'front'
}

/**
 * 已选里**属于这块区域**的词——即"这块现在记下了什么"。
 *
 * 覆盖层有三处要问同一件事（这块选没选 / 点了这块现在记着什么 / 细分词要跟哪几个比），
 * 所以规则只写这一份（"同一份事实只允许一处拼装"）。区域不存在 ⇒ 空数组，不抛。
 */
export function pickedWordsOf(regionId, picked = []) {
  const region = regionById(regionId)
  if (!region) return []
  const arr = Array.isArray(picked) ? picked : []
  return arr.filter((w) => region.words.includes(w))
}

/**
 * 结构化声明：**每块各自的方位前缀 + 部位词**（全身词不加前缀，未标方位的块也不加）。
 * 这是随首条输入提交一次的部位声明字段，只装部位——感觉留在患者那句话的文本里。
 * 没选部位 ⇒ 空数组（走"说不清在哪儿"那条出口，链路回落到今天的行为）。
 */
export function declarationLocations(picked = [], sides = {}) {
  return normalizeWords(picked).map((w) => {
    const side = sideOfWord(w, sides)
    return side ? side + w : w
  })
}

/**
 * 拼成患者那句话：部位段（顿号连接）+ 感觉 +「，」。
 *
 * 结尾那个「，」是刻意的——患者要在后面接着补"两天了，一阵一阵的"，
 * 句子本身就是主诉的开头。感觉里已过滤掉"说不上来"；一个感觉都没选时回落成「不舒服」。
 * 没选部位 ⇒ 空串（页面此时不该允许提交）。
 */
export function sentence(picked = [], feelings = [], sides = {}) {
  const locations = declarationLocations(picked, sides)
  if (!locations.length) return ''
  const feels = (Array.isArray(feelings) ? feelings : [])
    .filter((f) => typeof f === 'string' && f !== FEELING_UNSURE)
  return locations.join('、') + (feels.length ? feels.join('、') : DEFAULT_FEELING) + '，'
}

/**
 * 「位置已锁定」回执里的部位串（如「左腹部」）：结构化声明的展示形态。
 * 没声明（走了"说不清"出口）⇒ 空串，回执据此换文案。
 */
export function pickedLabel(picked = [], sides = {}) {
  const locations = declarationLocations(picked, sides)
  return locations.length ? locations.join('、') : ''
}

/**
 * 把已提交的结构化位置反查回图上的区域 id。
 * 方位只是声明前缀，全身词没有对应的图块，因此只剥离左/右/两侧后匹配区域词。
 */
export function regionsOfLocations(locations = []) {
  const words = new Set(normalizeWords(locations).map(word => word.replace(/^(两侧|左|右)/, '')))
  return REGIONS.filter(region => region.words.some(word => words.has(word))).map(region => region.id)
}

/**
 * 方位在"已选"区里的显示名：「左」显示成「左侧」更像人话，「两侧」原样。
 * 纯展示，与拼句无关（拼句里前缀就是「左」本身，"左腹部"才规范）。
 */
export function sideLabel(side) {
  if (!side) return ''
  return side === '两侧' ? '两侧' : `${side}侧`
}

/**
 * 按后端词表过滤：只保留**患者现在还能选**的词。
 *
 * 图上"形"是前端资产、"词"来自后端，所以管理员停用一个词后，图上那块区域可能只剩两三个词甚至一个。
 * 刻意**不做**"某区域全被停用就隐藏该区域"——患者可能正在别处指着自己的肚子说"这儿"，
 * 区域突然消失比选项少更难解释。
 *
 * ⚠️ `available` 为空时**回落到全量**，这是刻意的，但只该由"取不到词表"触发：
 * 两种空值必须分开——① 请求失败（回全量，功能照常可用，一次网络抖动不该锁死"选部位"）；
 * ② 管理员把部位词全停用了（回全量就是**违背他刚刚做的决定**，患者会点到已下架的词）。
 * 调用方据此区分：失败走回落、全停用走"无可选项"，见BodyMapOverlay 的 loadState。
 */
export function visibleWords(region, available) {
  const allowed = new Set(Array.isArray(available) && available.length ? available : PARTS)
  return (region?.words || []).filter((w) => allowed.has(w))
}

/** 区域在当前词表下是否还有词可选（覆盖层用它决定图上那块要不要画出来） */
export function regionHasWords(region, available) {
  return visibleWords(region, available).length > 0
}

/**
 * 点一下这块区域**实际会落下的那个词** = **首选落词 + 词表过滤后的回落**。
 *
 * 首选落词就是区域的 `label`；它被管理员停用（不在 `available` 里）时，回落到该区域
 * 过滤后剩下的第一个词。**这条规则只有这一份实现**——图上命名直接读 `label`、
 * 覆盖层落词读这个函数，页面里不许再写第二份（"同一份事实只允许一处拼装"）。
 * 区域不存在 / 一个词都没得选 ⇒ null（覆盖层据此当"这块现在点不动"）。
 */
export function landingWord(regionId, available) {
  const region = regionById(regionId)
  if (!region) return null
  const words = visibleWords(region, available)
  if (!words.length) return null
  return words.includes(region.label) ? region.label : words[0]
}

/** 覆盖层初始状态：什么都没选 */
export function emptyState() {
  return { view: 'front', act: null, picked: [], sides: {}, feelings: [] }
}

/** 选择态的纯派生（覆盖层只管渲染这个结果的形状） */
export function selectWord(state, word) {
  return { ...state, picked: toggle(state.picked, word) }
}

/** 感觉可多选；"说不上来"不与其他感觉互斥——它表达的是"别的说不清"，不是"什么都不舒服" */
export function selectFeeling(state, feeling) {
  return { ...state, feelings: toggle(state.feelings, feeling) }
}

/** 给**某一块**显式定方位：`''`（不标）/ `'左'` / `'右'` / `'两侧'`；`''` 就把条目删掉 */
export function setRegionSide(state, regionId, side) {
  if (!regionId) return state
  const sides = { ...state.sides }
  if (side) sides[regionId] = side
  else delete sides[regionId]
  return { ...state, sides }
}

/**
 * 图上点某一块的某半边 ⇒ 这块该怎样（**这条规则只此一份实现**，覆盖层的 `activate` 只管照做）。
 *
 *   clicked = null（中心块，没有左右半边）  未选 ⇒ 选中；已选 ⇒ 取消
 *   未选                                     ⇒ 选中，方位 = 点的那一侧
 *   已选，当前 = 点的那一侧                    ⇒ 取消这块（同一侧再点一次直接取消）
 *   已选，当前 = 另一个单侧                    ⇒ 「两侧」（点另一半边 = 两边都疼）
 *   已选，当前 = 「两侧」                      ⇒ 只剩另一半边（点右 ⇒ 左、点左 ⇒ 右）
 *   已选，当前 = 未标                          ⇒ 点哪半边就记哪一侧
 *
 * 返回 `{ keep, side }`：`keep:false` = 把这块取消；否则保留，`side` 是这块的新方位。
 */
export function clickOutcome({ selected, current = '', clicked = null }) {
  if (!clicked) return { keep: !selected }
  if (!selected) return { keep: true, side: clicked }
  if (current === clicked) return { keep: false }
  if (current === '两侧') return { keep: true, side: clicked === '左' ? '右' : '左' }
  if (!current) return { keep: true, side: clicked }
  return { keep: true, side: '两侧' }
}

/**
 * 逐条删除：已选区每条都能单独删，删错一条不必全部重来。
 * **这块的方位跟着一起收走**——留着孤儿条目的话，患者重新选中这块时会"带着旧方位"回来。
 */
export function removeWord(state, word) {
  const region = regionOfWord(word)
  const sides = { ...state.sides }
  if (region) delete sides[region.id]
  return { ...state, picked: state.picked.filter((w) => w !== word), sides }
}

export function removeFeeling(state, feeling) {
  return { ...state, feelings: state.feelings.filter((f) => f !== feeling) }
}

export function clearAll() {
  return emptyState()
}

function toggle(list, item) {
  const arr = Array.isArray(list) ? list : []
  return arr.includes(item) ? arr.filter((x) => x !== item) : [...arr, item]
}

/** 去空白、丢空项、去重（后端与图上给的都是词，这里只做最基本的一致化） */
function normalizeWords(words) {
  const arr = Array.isArray(words) ? words : []
  const seen = new Set()
  const out = []
  for (const w of arr) {
    if (typeof w !== 'string') continue
    const word = w.trim()
    if (word && !seen.has(word)) {
      seen.add(word)
      out.push(word)
    }
  }
  return out
}
