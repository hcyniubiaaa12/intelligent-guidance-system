# 长期记忆抽取 · 中文 gold set 与评估口径

> 对应：`memory` 模块的记忆抽取（`MemoryExtractor`）与稳定背景召回（`MemoryRecallService`）。
> 数据文件：`docs/memory-gold-set.jsonl`（**90 条 = 82 抽取 + 8 判重**，一行一条 JSON）。
> 评估入口：`backend/memory/src/test/java/com/guide/memory/eval/MemoryGoldSetEvalTest.java`（默认跳过）。
> 本文档只描述**怎么测、测什么、结果怎么读**；样本是人工构造的评测集，不用于训练、不进入运行时。

## 1. 为什么需要它

记忆抽取是"模型说什么是模型的事，能不能落库代码说了算"的两层结构：

- 提示词负责说清口径（只记跨会话事实、否定句不记、禁止推断、当前主诉不算长期事实）；
- `MemoryExtractValidator` / `CandidateValidator` 是确定性兜底（缺原话、低置信度、否定、推断、档案事实误入背景都丢弃）。

只测"模型返回 JSON 能否解析"不够——**真正要防的是"记错"**：把「没有糖尿病」记成「糖尿病」、把「吃二甲双胍」推成「有糖尿病」、把今天的主诉记成长期背景。这份 gold set 就是逐条钉这些边界。

## 2. 样本结构

```json
{
  "id": "X01",
  "category": "mixed",
  "dialogue": "患者：我有2型糖尿病，希望你讲慢一点。",
  "expect": {
    "preferences": [{"value": "讲慢一点", "aliases": ["说慢一点", "慢一点"]}],
    "background": [{"value": "2型糖尿病", "aliases": ["糖尿病", "二型糖尿病"], "type": "health"}],
    "candidates": []
  },
  "note": "偏好 + 慢病"
}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `dialogue` | 送进 `MemoryExtractor.extract()` 的对话文本（`患者：` / `助手：` 逐行，与生产侧 `dialogueText()` 同形） |
| `preferences[].value` | 期望抽出的偏好值；`aliases` 是可接受的同义写法 |
| `background[].value` | 期望抽出的稳定背景值；`type` 为 `health` / `other`（评估里类型单独统计，不与值混算） |
| `candidates[].field/value` | 期望抽出的档案候选；`field` 只能是 `allergy` / `medication` / `surgery` |
| `candidates[].occurredOn` | 期望的发生月份 `YYYY-MM`；不做主指标，单独算「月份准确率」 |
| `note` | 这条样本在防什么（否定 / 推断 / 当前主诉 / 寒暄……） |
| `existing` | **可选**：送进提示词的「已记住的记忆」，`[{type, key, value}]`，`type` 取 `preference` / `background`，序号 = 数组下标 + 1。**没有该字段的样本行为与原来完全一致**，也不参与判重指标 |
| `preferences[].action` / `background[].action` | 判重样本专用：期望的 `new` / `update` / `skip`；**只有带 `existing` 的样本才读它** |
| `preferences[].target` / `background[].target` | 判重样本专用：期望的旧清单序号（仅 `update` 计分） |

判重样本示例（`category: "dedup"`）：

```json
{"id":"D01","category":"dedup","existing":[{"type":"preference","key":"语速","value":"讲慢一点"}],
 "dialogue":"患者：你以后别讲那么慢了，正常语速就行。",
 "expect":{"preferences":[{"value":"正常语速","action":"update","target":1}],"background":[],"candidates":[]}}
```

**判重样本的值匹配比抽取样本宽一档**：实际值**包含**期望值或任一 `aliases` 即算命中。判重问的是「这条事实被认成了哪一条旧记忆」，不是「value 最终写成几个字」——写成「和老伴一起住，不再独居」与「和老伴一起住」是同一个判断；值粒度由抽取指标单独把关（实际值仍逐条打印）。

**匹配规则**：值做 `trim` 后，与 `value` 或任一 `aliases` **精确相等**即命中；候选还必须 `field` 相同。不做模糊包含——包含匹配会把"糖尿病"和"2型糖尿病"混成一条，也会放过模型把值写长的情况。

## 3. 覆盖范围（90 条：82 抽取 + 8 判重）

| 类别 | 条数 | 覆盖点 |
|---|---:|---|
| `preference` | 12 | 语气 / 详略 / 称呼 / 语言 / 输出形式 / 节奏；都应抽成偏好 |
| `background` | 12 | 2型糖尿病、高血压、高血脂、冠心病、慢性胃炎、哮喘、类风湿、慢性肾病；夜班、独居、素食；抑郁病史 |
| `allergy` | 7 | 青霉素、头孢、磺胺类、海鲜、花粉、乳胶、阿司匹林 |
| `medication` | 7 | 二甲双胍、氨氯地平、阿托伐他汀、胰岛素、华法林、阿司匹林；一条带开始月份 |
| `surgery` | 5 | 阑尾切除术（带月份）、胆囊切除术、剖宫产（带月份）、骨折内固定术、白内障手术 |
| `negation` | 3 | 「没有青霉素过敏」「没有吃药 / 没做过手术」「不过敏但另一半过敏」——否定部分必须为空 |
| `inference` | 8 | 只给药物、不给疾病：吃二甲双胍/氨氯地平/阿托伐他汀、打胰岛素、医生开药、抗凝药、阿司匹林、硝苯地平——候选可抽，**不得反推出糖尿病/高血压/高血脂** |
| `current` | 8 | 今天胸口闷、昨晚拉肚子、这会儿头疼、发烧咳嗽、摔伤、头晕、心慌、嗓子疼——**当前主诉不进长期记忆** |
| `unrelated` | 6 | 你好 / 谢谢 / 在吗 / 天气 / 再见 / 几点上班——应为空 |
| `mixed` | 14 | 偏好+慢病、慢病+用药、过敏+手术+偏好、当前主诉+候选、否定一条肯定另一条、时间不精确到月 |
| `dedup` | 8 | **带 `existing`**：偏好相反改口（update）、偏好同义更具体（update）、旧值更具体（skip）、措辞相近但不是一回事（new）、背景补充细节（update）、跨类别不合并（new）、背景相反改口（update）、偏好/背景混排清单里指第 3 条（update target=3） |

**空期望共 16 条**（`current` 8 条全空 + `unrelated` 6 条全空 + `negation` 里 `M08` / `S06` 2 条全空；`A08` 是"否定青霉素、肯定花粉"的混合样本，期望抽出花粉）。它们专门测"不该记的东西有没有被记下来"——**精确率比召回率更重要**。**8 条 `dedup` 不计入空期望与 P/R/F1**（见 §4 判重指标）。

## 4. 指标口径

评估逐条调用 `MemoryExtractor.extract(dialogue, existing)`（模型 + `MemoryExtractValidator` + `CandidateValidator` 的全链路，不是只解析 JSON；没有 `existing` 的样本传空清单，与旧口径等价），再按类别聚合：

- **精确率 P** = 命中期望的抽取值 / 模型实际抽出的全部值。**多抽一条就扣分**。
- **召回率 R** = 命中期望的抽取值 / 期望的全部值。**漏抽一条就扣分**。
- **F1** = 2PR / (P+R)。
- **空样本准确率** = 期望三类全空、实际也全空的条数 / 空样本条数。它回答"该沉默的时候有没有乱记"。
- **候选月份准确率** = 期望 `occurredOn` 非空且被命中的候选中，实际月份相等的比例（单独统计，不混进 P/R）。
- **背景类型准确率** = 被命中的 background 中 `type` 相同的比例。

分 `preference` / `background` / `allergy` / `medication` / `surgery` / `negation` / `inference` / `current` / `unrelated` / `mixed` 十组输出；`mixed` 同时计入总指标。**报告时必须写清样本量**，空样本不参与 P/R 分母（否则 P/R 会虚高）。

**判重指标**（只有带 `existing` 的样本参与，**单独汇总、不并入上面的 P/R/F1**）：

- **action 命中** = 期望 `action` 与实际草稿一致的数量 / 期望条目数。先按值（含 aliases、按「包含」匹配，见 §2）找到实际草稿；**连值都没抽出算一次未命中**——判重的前提是先认出这条事实。
- **target 命中** = 期望序号与实际 `target` 一致的数量 / 期望为 `update` 的条目数。非 `update` 时按协议 `target` 必须为空，比它只会把「正确地留空」算成错，所以**只计 update**。

## 5. 怎么跑

前置：本机有可用的 DeepSeek Key，并且**不把 Key 写进仓库**。

```bash
cd backend
# 方式一：环境变量（推荐）
export MEMORY_EVAL=true
export DEEPSEEK_API_KEY=sk-xxx
mvn -o -B -Dstdout.encoding=UTF-8 -pl memory -am test \
  -Dtest=MemoryGoldSetEvalTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# 方式二：指向本地 application-local.yml（不提交）
export MEMORY_EVAL=true
export MEMORY_EVAL_CONFIG="C:/.../backend/admin/src/main/resources/application-local.yml"
mvn -o -B -Dstdout.encoding=UTF-8 -pl memory -am test \
  -Dtest=MemoryGoldSetEvalTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

可选环境变量：

| 变量 | 作用 |
|---|---|
| `MEMORY_EVAL_LIMIT` | 只跑前 N 条（冒烟用，例如 `3`） |
| `MEMORY_EVAL_GOLD_SET` | gold set 路径（默认自动向上找 `docs/memory-gold-set.jsonl`） |
| `MEMORY_EVAL_BASE_URL` / `MEMORY_EVAL_MODEL` | 覆盖 DeepSeek base URL / 模型名（默认 `https://api.deepseek.com` / `deepseek-chat`） |

**成本提示**：90 条 ≈ 90 次非流式对话调用（可能再加偏好合并），按 DeepSeek 当前价格约几角到一元量级；先跑 `MEMORY_EVAL_LIMIT=3` 确认 Key 与提示词正常，再跑全量。**只调判重样本时**把 `MEMORY_EVAL_GOLD_SET` 指向只含 `category=dedup` 的临时文件（8 次调用、约 17 秒），不必每次跑全量。测试默认 `@EnabledIfEnvironmentVariable(named = "MEMORY_EVAL", matches = "true")`，**CI/本地常规 `mvn test` 不会触发、不产生费用**。

## 6. 基线结果（2026-11-14，已执行；最新复跑见 §6.1）

**执行环境**：真实 DeepSeek `deepseek-chat`（`application-local.yml` 的 `${DEEPSEEK_API_KEY:...}` 默认值），
82 条全量，单次非流式调用；执行命令见 §5；完整日志留在本地规划目录、不入基线。

**总体：TP=60 FP=13 FN=16｜P=0.822 R=0.789 F1=0.805（82 条）**

| 指标 | P | R | 样本量 |
|---|---:|---:|---:|
| 总 | 0.822 | 0.789 | 82 |
| 偏好 preference | 0.500 | 0.500 | 12 |
| 稳定背景 background | 0.667 | 0.615 | 12 |
| 候选 · 过敏 allergy | 1.000 | 1.000 | 7 |
| 候选 · 用药 medication | 1.000 | 0.857 | 7 |
| 候选 · 手术 surgery | 0.750 | 0.600 | 5 |
| 混合样本 mixed | 0.913 | 0.913 | 23 |
| 否定句（空期望） | 1.000 | 1.000 | 3 |
| 推断陷阱（不得多抽疾病） | 1.000 | 1.000 | 8 |
| 当前主诉（空期望） | 1.000 | 1.000 | 8 |
| 无关寒暄（空期望） | 1.000 | 1.000 | 6 |

**误差结构（重要于总分）**：13 条 FP 与 16 条 FN 里，绝大多数是**同一个事实的表述粒度差异**——
期望「讲慢一点」、模型给「讲慢一点，别用太专业的词」；期望「哮喘」、模型给「哮喘，换季时容易发作」；
期望「长期夜班」、模型给「长期上夜班，作息比较乱」。**没有出现把否定句记成肯定、把用药推断成疾病、
把当前主诉记成长期背景的错误**（那三类样本全部满分）。这说明抽取的**事实方向**是对的，
差在「值该写到多细」——属于提示词与评估口径的粒度对齐问题，不是安全边界失守。

**候选月份准确率**：评估按「字段:值」命中，月份未单列；候选值命中 20/21，月份由 `CandidateValidator`
的 `YYYY-MM` 校验单独保证（单测覆盖），本轮不把它混进主指标。

**结论**：可以进入下一阶段；若要提升总分，优先把提示词里「value 取最短可独立表达的事实」写死，
并让 gold set 的比较对「期望值是抽取值的前缀」放宽（两者选一，不能两头都松）。**不要**为了这个分数
去调置信度阈值——错误是粒度不是置信度。

### 6.1 2026-11-16 复跑（判重落地后，**门禁未过**）

金标集扩到 90 条（82 抽取 + 8 判重）。抽取的 P/R/F1 **仍只按 82 条算**，判重样本单独汇总。

| 运行 | 提示词 | 抽取 F1 | 偏好 F1 | 背景 F1 | 手术 F1 | 空期望 | 判重 action | 判重 target |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 当前（含判重段） | 0.792 | 0.593 | 0.462 | 0.600 | 16/16 | 6/8 | 3/5 |
| 2 | 当前 | 0.776 | 0.615 | 0.480 | 0.400 | 16/16 | 5/8 | 3/5 |
| 3 | 当前（样本修正中） | 0.758 | 0.538 | 0.538 | 0.400 | 16/16 | 4/8 | 3/5 |
| 4 | 当前 | 0.800 | 0.667 | 0.593 | 0.400 | 16/16 | 6/8 | 4/5 |
| 5 | 当前（样本定稿） | 0.792 | 0.667 | 0.538 | 0.400 | 16/16 | 7/8 | 5/5 |
| A/B-1 | 基线提交 `fb56ef5`（无判重段） | 0.844 | 0.455 | 0.800 | 0.889 | 16/16 | — | — |
| A/B-2 | 同上 | 0.803 | 0.571 | 0.720 | 0.400 | 16/16 | — | — |

判重专项（只跑 8 条，`MEMORY_EVAL_GOLD_SET` 指向临时文件，约 17s/次）：action 8/8 与 7/8、target 5/5 与 5/5。未命中集中在模型偶尔不抽某条生活背景（「口味比较重」），不是 action 判错。

**结论：门禁未过**。当前提示词 5 次都在 0.758–0.800，同机旧提示词 0.803–0.844。差异不是「旧清单把输入撑长」（82 条样本的 `{remembered}` 只是「（无）」），而是系统提示词新增的判重段让 value 更容易带上补充信息（背景 / 手术 FP 上升）；偏好反而上升。**修法在提示词侧**：把「update 时 value 取信息量更大的那个」限定为仅 update，并重申 `action=new` 时 value 仍取最短可独立表达的事实。该评估单次噪声可达 4 个点（旧提示词两次 0.844 / 0.803），**改完后至少跑 2 次取均值**再判门禁。

## 7. 阈值校准建议（跑完后填）

- **背景召回阈值 `memory.background.recall.threshold`（默认 0.70）**：gold set 目前只测**抽取**，不测召回——召回评估需要 embedding + pgvector，属可选扩展（用 `MemoryRecallService` + 固定 query 集合另写一个 `@EnabledIf...` 测试）。**在没有真实召回数据前，不要仅凭抽取结果调这个阈值**；抽取与召回是两件事，抽得准不代表向量分得开。
  - **2026-11-14 真机反馈**：患者说「我有2型糖尿病」被记住后，换会话问「是不是和我的糖尿病有关」，**没有召回**（模型答"档案里没有糖尿病"）。说明 0.70 对短中文改写偏严——同义改写不一定能过阈值。处理方式是**加一路词面命中**（完整子串 / 足够长的公共子串，见 `MemoryNegation`），而不是直接把阈值往下调：词面命中是可解释的"患者明说到了"，阈值仍管语义。后续若要做召回评测，样本应覆盖「近义改写 / 只提上位词 / 无关」三类，据此再决定是否调阈值。
- **抽取置信度下限 `memory.extract.confidence.min`（默认 0.6）**：若 gold set 显示"高置信度但错误"的样本多，说明提示词口径没压住，不应靠调高阈值掩盖；若"正确的低置信度"多，再考虑下调到 0.5。
- **偏好条数上限 `memory.preference.max`（默认 5）**：gold set 的偏好样本都是单条；合并行为要另造"满额 + 同义"样本测，不在本次 82 条内。
- **任何调整都要重跑全量并记录样本量**；只报一个准确率数字、不报样本量与类别的，视为不可复现。

## 8. 维护约定

- 新增样本直接往 `docs/memory-gold-set.jsonl` 追加，`id` 不重复、`category` 用现有十类抽取类或 `dedup`（判重样本必须带 `existing`，否则会按普通抽取样本计入 P/R/F1）；
- 改提示词或校验规则后**必须重跑**，并在本文档第 6 节回填结果；
- 不要把患者真实对话原样贴进来——样本必须是人工改写、不含可识别个人信息；
- 这份文件**不是基线规范**，不参与 `.claude/rules/` 的强制校验，但**引用时请带文件名**。
