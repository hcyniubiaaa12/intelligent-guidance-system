/**
 * 证据链回放（evidence replay）的解析与派生。
 *
 * 读的是导诊记录留下的**证据快照**（guide_record.evidence，由后端 GuideService.evidenceJson 写入），
 * 不重新执行检索 —— 快照保证可复现「当时为什么错」，重新检索会拿到已修复后的结果。结构：
 *   retrieved[]      { chunk_id, title, score, rank, content }  精排 Top-N（content 是 400 字缩写）
 *   retrieved_query  改写后的检索串
 *   model_cited[]    模型引用了哪几条注（1-based，序号直接对应 retrieved[].rank ← 展示的连接键）
 *   profile          null 或 { text, query }（2026-10-03 的单据 04 才加，老记录是 null）
 *   prompt_snippet   把 retrieved 每条取前 200 字拼起来 —— 它是 retrieved 的**派生视图**，不单独展示
 *   model_output_raw 模型原始输出
 *
 * 老记录可能没有 profile 节点，也可能整串不是合法 JSON（或为空）。解析不了就如实返回 ok:false，
 * 页面据此降级成原始 JSON —— 这里绝不猜、不补默认值，否则审出来的「依据」是编的。
 */

const EMPTY = {
  ok: false,
  raw: '',
  json: null,
  retrieved: [],
  retrievedQuery: '',
  modelOutputRaw: '',
  cited: [],
  profile: null,
  model: null
}

export function parseEvidence(raw) {
  const rawText = typeof raw === 'string' ? raw.trim() : ''
  if (!rawText) return { ...EMPTY, raw: rawText }

  let json = null
  try {
    json = JSON.parse(rawText)
  } catch {
    json = null
  }
  if (!json || typeof json !== 'object' || Array.isArray(json)) return { ...EMPTY, raw: rawText }

  const hits = Array.isArray(json.retrieved) ? json.retrieved : []
  // 保持数组原序：后端就是按 rank 1..N 顺序写的，不重排、也不给缺 rank 的条目编号
  // ——rank 是 model_cited 的连接键，编一个序号出来就会连错条目
  const retrieved = hits
    .map((hit, index) => {
      if (!hit || typeof hit !== 'object') return null
      return {
        index,
        rank: toNumber(hit.rank),
        score: toNumber(hit.score),
        title: str(hit.title),
        chunkId: str(hit.chunk_id),
        content: str(hit.content)
      }
    })
    .filter(Boolean)

  const modelOutputRaw = str(json.model_output_raw)

  return {
    ok: true,
    raw: rawText,
    json,
    retrieved,
    retrievedQuery: str(json.retrieved_query),
    modelOutputRaw,
    cited: (Array.isArray(json.model_cited) ? json.model_cited : [])
      .map(toNumber)
      .filter((no) => no !== null),
    profile: profileOf(json.profile),
    model: parseModelOutput(modelOutputRaw)
  }
}

/**
 * 「检索相关度排序」与「模型引用」的错位提示 —— 归因线索，**不是判定**。
 *
 * 模型没引相关度最高的那条，未必是问题。本仓库实测过该现象偶发且不复现（当时的假说是
 * 小库期噪声：语料少时 Top5 的后几名本就落在噪声区）。所以文案一律写「值得看一眼」、
 * 不写「错误」，也不解释成因（成因未知，说了就是编）。
 */
export function evidenceHint(parsed) {
  if (!parsed.ok || !parsed.retrieved.length) return null
  // 缺注号的快照不做引用比对：连接键不可靠时，宁可不报，也不报错
  if (parsed.retrieved.some((item) => item.rank === null)) return null

  const cited = parsed.cited
  if (!cited.length) {
    return { level: 'info', text: '模型一条证据都没引用 —— 值得看一眼' }
  }

  const top = parsed.retrieved[0]
  if (!cited.includes(top.rank)) {
    const used = cited.map((no) => `注${no}`).join('、')
    return {
      level: 'warn',
      text: `注${top.rank} 相关度最高（${fmtScore(top.score)}）却没被引用，模型用了 ${used} —— 值得看一眼`
    }
  }

  // 只有这两种情况。不写第三条「引用的都是靠后的条目」——注1 被引用时它不可能成立
  // （此时最小引用号必为 1），注1 没被引用时上面那条先命中，属永远走不到的死分支。
  return null
}

/** 这一注被模型引用了吗。rank 缺失时一律不认 —— 不拿猜出来的序号去连线 */
export function isCited(parsed, item) {
  return item.rank !== null && parsed.cited.includes(item.rank)
}

/** 条带高度：只表达**本组内的相对**相关度，所以按本组分数归一化到 [min, max]；分数未知返回 null（不画） */
export function barHeights(retrieved, min = 8, max = 26) {
  const scores = retrieved.map((item) => item.score).filter((score) => score !== null)
  if (!scores.length) return retrieved.map(() => null)

  const low = Math.min(...scores)
  const high = Math.max(...scores)
  return retrieved.map((item) => {
    // 未知不是最低 —— 画成最矮那根就等于说"它最不相关"，那是编的
    if (item.score === null) return null
    if (high === low) return Math.round((min + max) / 2)
    return Math.round(min + ((item.score - low) / (high - low)) * (max - min))
  })
}

/** 原始快照的排版展示。复用 parseEvidence 已经解出来的对象，不再解一遍 */
export function prettySnapshot(parsed) {
  if (!parsed.ok || !parsed.json) return parsed.raw || ''
  return JSON.stringify(parsed.json, null, 2)
}

export function fmtScore(score) {
  return typeof score === 'number' && Number.isFinite(score) ? score.toFixed(2) : '—'
}

/**
 * 从模型原始输出里尽力认出结论。
 *
 * 协议字段是 verdict / dept / confidence / note（见提示词模板）。原始输出可能被 ``` 围栏包着，
 * 也可能前后带一句话 —— 进度.md 记过「模型偶发不按协议」，所以这里允许从正文里抠出 JSON；
 * 抠不出来就返回 null，页面退到「模型原始输出」折叠项，不硬编一个结论出来。
 */
function parseModelOutput(rawOutput) {
  const trimmed = rawOutput.trim()
  if (!trimmed) return null

  const candidate = trimmed.startsWith('{') ? trimmed : bracesOf(trimmed)
  if (!candidate) return null

  let json = null
  try {
    json = JSON.parse(candidate)
  } catch {
    return null
  }
  if (!json || typeof json !== 'object' || Array.isArray(json)) return null

  return {
    verdict: str(json.verdict),
    dept: str(json.dept),
    confidence: toNumber(json.confidence),
    note: str(json.note)
  }
}

function bracesOf(input) {
  const start = input.indexOf('{')
  const end = input.lastIndexOf('}')
  return start >= 0 && end > start ? input.slice(start, end + 1) : ''
}

function profileOf(node) {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return null
  const profileText = str(node.text)
  const query = str(node.query)
  if (!profileText && !query) return null
  return { text: profileText, query }
}

function str(value) {
  return typeof value === 'string' ? value : ''
}

/**
 * JSON 里的 null / undefined / 空串一律当「没有」，返回 null。
 * 不能直接用 Number()——`Number(null)` 是 0，会把「没给这个值」伪装成「值是 0」，
 * 屏幕上就出现「相关度 0.00」这种看起来像真数据的假数据。
 */
function toNumber(value) {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}
