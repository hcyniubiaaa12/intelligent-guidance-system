/**
 * 部位声明的**纯规则**：图上「哪块区域 ↔ 哪个词」的映射、把选择拼成一句话与结构化声明。
 *
 * 这是本功能唯一能把全部规则用纯逻辑表达的地方——覆盖层的视觉、门禁的时机都无法自动化，
 * 但**规则的每一个分支都能在这里被断言**。所以覆盖层只管画与点，拼句一律走这里，
 * 页面里不允许出现第二份拼句实现。
 *
 * 三条不可从简的规则：
 *
 * ① **图上只出现术语白名单里已有的词**（`medical_term` 的 `type=part` 且启用）。
 *    拼出来的句子若不含部位词，就绕不过信息充足性门槛，这个功能等于白做。
 *    `PARTS` 是那份词清单的**镜像**，越界即失败（见 `assertVocabulary`）——
 *    这不是运行时校验，是给"有人改了映射却忘了同步词表"留一道会红的闸。
 *    本仓库没有前端测试框架，守这条闸的办法是临时 node 脚本跑断言（验完即删）。
 *
 * ② **方位只是前缀，不是新词**：术语表里没有左 / 右，但"左腹部 / 左上腹 / 左上肢"都含部位词，
 *    照样命中白名单。而「上腹 + 左」在临床上本来就写作「左上腹」——组合出来的词比口语更规范。
 *
 * ③ **全身性词不加方位前缀**："左皮肤"没有意义。
 *
 * 输出形状：`{ locations, sentence }`
 *   - `locations` —— 方位前缀 + 部位词，进请求的部位声明字段（**结构化、只装部位**）
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

/**
 * 「形 ↔ 词」映射：**形是前端资产**（哪块区域画在哪），词必须都在 {@link PARTS} 里。
 *
 * `id` 是区域标识（SVG 上的 data-r），`name` 是给人看的区域名，`words` 是这块区域包含的部位词。
 * 「腹」刻意不在任何区域里——它是「腹部」的单字形式，图上重复给会让患者以为点的是两处。
 */
export const REGIONS = Object.freeze([
  { id: 'head', name: '头部', words: ['头', '眼', '鼻', '耳', '咽', '喉'] },
  { id: 'neck', name: '颈部', words: ['颈'] },
  { id: 'shoulder', name: '肩部', words: ['肩'] },
  { id: 'chest', name: '胸部', words: ['胸', '胸口'] },
  { id: 'abd', name: '腹部', words: ['上腹', '腹部', '下腹'] },
  { id: 'hip', name: '髋部', words: ['髋'] },
  { id: 'back', name: '背部', words: ['背'] },
  { id: 'waist', name: '腰部', words: ['腰'] },
  { id: 'arm', name: '上肢', words: ['上肢'] },
  { id: 'hand', name: '手部', words: ['手'] },
  { id: 'leg', name: '下肢', words: ['腿', '下肢'] },
  { id: 'knee', name: '膝部', words: ['膝'] },
  { id: 'foot', name: '足部', words: ['脚'] }
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

/** 没选部位时的话（"说不清在哪儿"那条出口走的就是它） */
export const NO_LOCATION_HINT = '还没选，先点一下图上不舒服的位置'

/** 按 id 取区域；未知 id 返回 null（不抛——图上多点一下不该弄崩页面） */
export function regionById(id) {
  return REGIONS.find((r) => r.id === id) || null
}

/** 该词是否全身性（决定加不加方位前缀） */
export function isGlobalWord(word) {
  return GLOBAL_WORDS.includes(word)
}

/**
 * 结构化声明：**方位前缀 + 部位词**（全身词不加前缀）。
 * 这是随首条输入提交一次的部位声明字段，只装部位——感觉留在患者那句话的文本里。
 * 没选部位 ⇒ 空数组（走"说不清在哪儿"那条出口，链路回落到今天的行为）。
 */
export function declarationLocations(picked = [], side = null) {
  const words = normalizeWords(picked)
  if (!words.length) return []
  const prefix = side ? side : ''
  return words.map((w) => (isGlobalWord(w) ? w : prefix + w))
}

/**
 * 拼成患者那句话：部位段（顿号连接）+ 感觉 +「，」。
 *
 * 结尾那个「，」是刻意的——患者要在后面接着补"两天了，一阵一阵的"，
 * 句子本身就是主诉的开头。感觉里已过滤掉"说不上来"；一个感觉都没选时回落成「不舒服」。
 * 没选部位 ⇒ 空串（页面此时不该允许提交）。
 */
export function sentence(picked = [], feelings = [], side = null) {
  const locations = declarationLocations(picked, side)
  if (!locations.length) return ''
  const feels = (Array.isArray(feelings) ? feelings : [])
    .filter((f) => typeof f === 'string' && f !== FEELING_UNSURE)
  return locations.join('、') + (feels.length ? feels.join('、') : DEFAULT_FEELING) + '，'
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
 * 映射自检：区域与全身词里的每个词都必须落在 {@link PARTS} 白名单镜像内。
 *
 * 用途是**让改动会红**：有人给 REGIONS 加了「后背」而没同步后端术语表，
 * 跑断言时这里就失败，而不是等患者点了图才发现拼出的句子命中不了门槛。
 * 词表不齐**不会**报这个错（患者图上点的词被管理员停用是正常运营动作），
 * 那个由页面上"选中的词不在词表里就不渲染选项"来兜。
 */
export function assertVocabulary() {
  const known = new Set(PARTS)
  const bad = []
  for (const region of REGIONS) {
    for (const word of region.words) {
      if (!known.has(word)) bad.push(`区域「${region.name}」的词「${word}」不在部位词表内`)
    }
  }
  for (const word of GLOBAL_WORDS) {
    if (!known.has(word)) bad.push(`全身词「${word}」不在部位词表内`)
  }
  return bad
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

/** 覆盖层初始状态：什么都没选 */
export function emptyState() {
  return { view: 'front', act: null, picked: [], feelings: [], side: null }
}

/** 选择态的纯派生（覆盖层只管渲染这个结果的形状） */
export function selectWord(state, word) {
  return { ...state, picked: toggle(state.picked, word) }
}

/** 感觉可多选；"说不上来"不与其他感觉互斥——它表达的是"别的说不清"，不是"什么都不舒服" */
export function selectFeeling(state, feeling) {
  return { ...state, feelings: toggle(state.feelings, feeling) }
}

/** 方位单选：再点一次同一个就是取消（患者不该为了反选去找"清除"按钮） */
export function selectSide(state, side) {
  return { ...state, side: state.side === side ? null : side }
}

/** 逐条删除：已选区每条都能单独删，删错一条不必全部重来 */
export function removeWord(state, word) {
  return { ...state, picked: state.picked.filter((w) => w !== word) }
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