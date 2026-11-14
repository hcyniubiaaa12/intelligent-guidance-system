# 长期记忆抽取 · 中文 gold set 与评估口径

> 对应：`memory` 模块的记忆抽取（`MemoryExtractor`）与稳定背景召回（`MemoryRecallService`）。
> 数据文件：`docs/memory-gold-set.jsonl`（**82 条**，一行一条 JSON）。
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

**匹配规则**：值做 `trim` 后，与 `value` 或任一 `aliases` **精确相等**即命中；候选还必须 `field` 相同。不做模糊包含——包含匹配会把"糖尿病"和"2型糖尿病"混成一条，也会放过模型把值写长的情况。

## 3. 覆盖范围（82 条）

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

**空期望共 16 条**（`current` 8 条全空 + `unrelated` 6 条全空 + `negation` 里 `M08` / `S06` 2 条全空；`A08` 是"否定青霉素、肯定花粉"的混合样本，期望抽出花粉）。它们专门测"不该记的东西有没有被记下来"——**精确率比召回率更重要**。

## 4. 指标口径

评估逐条调用 `MemoryExtractor.extract(dialogue)`（模型 + `MemoryExtractValidator` + `CandidateValidator` 的全链路，不是只解析 JSON），再按类别聚合：

- **精确率 P** = 命中期望的抽取值 / 模型实际抽出的全部值。**多抽一条就扣分**。
- **召回率 R** = 命中期望的抽取值 / 期望的全部值。**漏抽一条就扣分**。
- **F1** = 2PR / (P+R)。
- **空样本准确率** = 期望三类全空、实际也全空的条数 / 空样本条数。它回答"该沉默的时候有没有乱记"。
- **候选月份准确率** = 期望 `occurredOn` 非空且被命中的候选中，实际月份相等的比例（单独统计，不混进 P/R）。
- **背景类型准确率** = 被命中的 background 中 `type` 相同的比例。

分 `preference` / `background` / `allergy` / `medication` / `surgery` / `negation` / `inference` / `current` / `unrelated` / `mixed` 十组输出；`mixed` 同时计入总指标。**报告时必须写清样本量**，空样本不参与 P/R 分母（否则 P/R 会虚高）。

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

**成本提示**：82 条 ≈ 82 次非流式对话调用（可能再加偏好合并），按 DeepSeek 当前价格约几角到一元量级；先跑 `MEMORY_EVAL_LIMIT=3` 确认 Key 与提示词正常，再跑全量。测试默认 `@EnabledIfEnvironmentVariable(named = "MEMORY_EVAL", matches = "true")`，**CI/本地常规 `mvn test` 不会触发、不产生费用**。

## 6. 当前结果（2026-11-14）

**未执行。**

原因：本轮开发工作区（`ticket/13a-docs-and-goldset`）没有 `application-local.yml`；主检出的 `backend/admin/src/main/resources/application-local.yml` 里 `llm.deepseek.api-key` 仍是 `${DEEPSEEK_API_KEY:}` 占位符（不是真实 Key），环境变量 `DEEPSEEK_API_KEY` 也未设置。冒烟运行在装配阶段即按预期报「未找到可用的 DeepSeek Key」，没有发出任何模型请求、不产生费用，也不伪造指标。gold set、评估入口与复现命令都已就绪，等有 Key 的环境跑。

**待回填（跑完后按此格式更新）**：

| 指标 | 值 | 样本量 |
|---|---:|---:|
| 总精确率 / 召回率 / F1 | 待测 | 82 |
| 偏好 P / R | 待测 | 12 |
| 背景 P / R | 待测 | 12 |
| 候选（三类合计）P / R | 待测 | 19 |
| 否定句空样本准确率 | 待测 | 3 |
| 推断陷阱「多抽疾病」条数 | 待测 | 8 |
| 当前主诉空样本准确率 | 待测 | 8 |
| 无关寒暄空样本准确率 | 待测 | 6 |
| 候选月份准确率 | 待测 | 5 |

## 7. 阈值校准建议（跑完后填）

- **背景召回阈值 `memory.background.recall.threshold`（默认 0.70）**：gold set 目前只测**抽取**，不测召回——召回评估需要 embedding + pgvector，属可选扩展（用 `MemoryRecallService` + 固定 query 集合另写一个 `@EnabledIf...` 测试）。**在没有真实召回数据前，不要仅凭抽取结果调这个阈值**；抽取与召回是两件事，抽得准不代表向量分得开。
- **抽取置信度下限 `memory.extract.confidence.min`（默认 0.6）**：若 gold set 显示"高置信度但错误"的样本多，说明提示词口径没压住，不应靠调高阈值掩盖；若"正确的低置信度"多，再考虑下调到 0.5。
- **偏好条数上限 `memory.preference.max`（默认 5）**：gold set 的偏好样本都是单条；合并行为要另造"满额 + 同义"样本测，不在本次 82 条内。
- **任何调整都要重跑全量并记录样本量**；只报一个准确率数字、不报样本量与类别的，视为不可复现。

## 8. 维护约定

- 新增样本直接往 `docs/memory-gold-set.jsonl` 追加，`id` 不重复、`category` 用现有十类之一；
- 改提示词或校验规则后**必须重跑**，并在本文档第 6 节回填结果；
- 不要把患者真实对话原样贴进来——样本必须是人工改写、不含可识别个人信息；
- 这份文件**不是基线规范**，不参与 `.claude/rules/` 的强制校验，但**引用时请带文件名**。
