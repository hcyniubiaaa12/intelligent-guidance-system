# 记忆判重前置 · 探索笔记（2026-11-15）

> 这是规划期的事实台账，供实现工单 01–04 的 subagent 少读代码。
> **不是基线文档**：行为口径以 spec 为准，代码位置以仓库当前状态为准。

## 1. 现有链路（改动前的样子）

### 1.1 抽取入口

`backend/memory/src/main/java/com/guide/memory/service/MemoryExtractor.java`

```java
public MemoryExtractionResult extract(String dialogue)   // 唯一入口
public List<MergedPreference> merge(List<String> existing, List<String> incoming)
```

- `extract` 只收对话文本；`messages()` 只替换 `{dialogue}` 一个占位符。
- 解析三组：`parsePreferences` / `parseBackgrounds` / `parseCandidates`。
- 校验统一委托 `MemoryExtractValidator`（偏好/背景）与 `CandidateValidator`（候选）。
- `merge` 的 `existing` / `incoming` 都是**纯值清单**（`listText` 拼成 `- 值`），
  但 `memory-merge` 提示词要求模型输出 `{"key","value"}` —— **模型只能自己编 key**。这就是工单 01 要修的既有缺陷。

### 1.2 草稿类型

`backend/memory/src/main/java/com/guide/memory/dto/MemoryExtractionResult.java`

```java
record PreferenceDraft(String key, String value, String quote, BigDecimal confidence)
record BackgroundDraft(String key, String value, String type, String quote, BigDecimal confidence, LocalDate validTo)
record ProfileCandidateDraft(String field, String value, String occurredOn, String quote, BigDecimal confidence)
```

两个 `MemoryExtractionResult` 构造器：三参（全量）与两参（兼容只关心偏好/背景的调用方）。

### 1.3 校验纯函数

`backend/memory/src/main/java/com/guide/memory/support/MemoryExtractValidator.java`

- `validPreferences(drafts, minConfidence)`：`validCommon` → `negated` → **`supportedByQuote`** → 同批去重。
- `validBackgrounds(...)`：多两步 `looksLikeCurrentComplaint`、`looksLikeProfileFact`。
- `supportedByQuote(quote, value)` 现在 = `quote.contains(value) || STABLE_MARKER.matcher(quote).find()`
  —— **偏好放宽**要改的是「偏好这一条不再要求 value 逐字出现」，
  改成「原话必须整体出现在本轮对话原文里」。注意：`validPreferences` 现在的签名**没有对话原文**，
  放宽后需要新增入参（spec「校验函数需要拿到本轮对话原文，入参增加一项」）。
- 背景/候选的严格判定**原样不动**。

### 1.4 落库编排

`backend/memory/src/main/java/com/guide/memory/service/MemoryExtractionService.java`

主流程 `extract(userId, sessionId, applyPrefilter)`：

```
recentDialogue → retractCorrectedBackgrounds（患者更正收回）
→ 开关判断 → 规则预筛 → 配额申领 → memoryExtractor.extract(dialogueText(turns))
→ savePreferences → saveBackgrounds → profileCandidateService.recordCandidates
```

- `savePreferences`：取 active 偏好 → `(key,value)` 全等去重 → 未满额逐条 `insert`，
  满了走 `mergePreferences`。
- `saveBackgrounds`：取 active 背景（health + other）→ `(type,key,value)` 全等去重 →
  满额直接丢弃本批 → 否则逐条 `insert` + `vectorIndexer.index(saved)`。
- `insert(...)`：写 `retrieval_text = value`、`observed_at = now`、`status` 按 `validTo` 推。
- `audit(saved, userId, op, oldValue, newValue)`：挂 `MemoryOp` + `MemoryActor.EXTRACT`。

**取旧记忆的时机**：`retractCorrectedBackgrounds` 之后。所以 `saveXxx` 里的
`activeMemories(userId, ...)` 天然满足「不把刚被置失效的记忆当判重候选」——
但判重候选清单要在**调模型之前**取（要送进 prompt），所以必须显式放在 retract 之后、
`memoryExtractor.extract(...)` 之前。

### 1.5 向量与编辑口径

- `MemoryVectorIndexer.index(Memory)`：只对 `background_*` + `ACTIVE` 生效；
  embedding 文本优先 `retrievalText`，为空回退 `mvalue`；失败只 WARN。
- `MemoryManageService.updateValue(...)`（患者手动编辑）是**背景同步刷新的既有实现**：
  `set(mvalue)` + `set(updatedAt)` + 背景额外 `set(retrievalText)` → `vectorIndexer.index(row)`。
  **工单 03 的「背景 update 复用同一套口径」可以调用它，也可以抽出共用方法**；
  关键是不要写第二份「改值 + 刷 retrievalText + 重算向量」。注意 `updateValue` 是
  `@Transactional` 且带「归属校验 + ACTIVE 校验 + 长度校验」，直接复用前要判断是否合适
  （它按 id 查行并抛 `BizException`，而抽取侧已经有行对象在手）。

### 1.6 金标集评估

`backend/memory/src/test/java/com/guide/memory/eval/MemoryGoldSetEvalTest.java`

- `loadPrompts(root)` 只读了 `memory-extract` 的 `system` / `user-template`，
  其他段留空 —— 工单 04 加「已有记忆」后，这里要同步（否则评估跑的是缺占位符的模板）。
- `score(expect, actual)` 只比对值；工单 04 要加「有 `existing` 的样本同时校验 `action` / `target`」。
- 指标汇总在 `Metrics`，报告写到 `target/memory-gold-set-eval.txt`。
- 基线（2026-11-14 实测）：总体 P=0.822 / R=0.789 / F1=0.805；偏好 0.500/0.500；背景 0.667/0.615。

### 1.7 提示词

`backend/admin/src/main/resources/prompts.yml` 的 `memory-extract` / `memory-merge` 两节；
占位符常量与启动校验在 `backend/common/src/main/java/com/guide/common/config/PromptProperties.java`
的 `validate()`（**新增占位符必须同时进 `require(...)` 的必填列表**，否则配错会静默降级）。

现有占位符常量：`PLACEHOLDER_DIALOGUE` / `PLACEHOLDER_EXISTING` / `PLACEHOLDER_INCOMING`。

## 2. 既有测试的缝（复用，不新开）

| 缝 | 文件 | 现状 |
|---|---|---|
| 纯函数校验 | `support/MemoryExtractValidatorTest.java` | 零 mock，14 个用例 |
| 解析层 | `service/MemoryExtractorTest.java` | mock `ChatModel` + `PromptProperties` + `SysConfigService` |
| 编排层 | `service/MemoryExtractionServiceTest.java` | mock `MemoryMapper` / `MemoryLogService` / `MemoryVectorIndexer`；`ArgumentCaptor` 抓 insert/updateById/deleteById |
| 金标集 | `eval/MemoryGoldSetEvalTest.java` | `MEMORY_EVAL=true` 才跑 |

`MemoryExtractionServiceTest` 的 helper：`pref(...)` / `background(...)` / `existing(...)`。
`existing(...)` 造 `Memory` 行（ACTIVE、带 observedAt）。

## 3. 关键约束（别违反）

- **表结构不变**：`user_memory` 现有列够用，`user_memory_log.op` 已有 `update`。
- **偏好不向量化**：`MemoryVectorIndexer.index` 对 PREFERENCE 直接返回 false。
- **档案候选不做语义判重**：患者逐条确认是天然去重，且同族药名合并有医学风险。
- **患者端界面不变**：不新增「已更新」标记。
- **判重失败按 `new` 落库**：多一条患者看得见能删，少一条永远不知道。
- **`skip` 零写入零审计**：值没变却刷新 `observed_at` 会让它无理由跳到列表最前。
- 依赖方向：`memory` 不依赖 chat/rag/kb；运行时参数经 `auth` 的 `SysConfigService`。

## 4. 文档回写点（工单 04）

| 文件 | 改什么 |
|---|---|
| `docs/architecture/链路A-导诊对话.md` §「抽取管线」的**模型**条 | 「偏好满额时才合并」→「抽取即判重」，三 action 口径 + 「拿不准就 new」的安全性理由 |
| `.claude/rules/数据库设计.md` §8 `user_memory` 行 | 补「偏好/背景改口原地更新（id 不变）」这一句口径 |
| `CONTEXT.md` | 新增「记忆判重」词条（放在「记忆抽取」附近） |
| `进度.md` | 总览 / 已知坑 / 下一步三节 |
| `进度-施工日志.md` | 顶部加一条 2026-11-15 的过程记录 |
| `.claude/rules/前端设计方案-*.md` | 本轮不动前端，预计无须改；**仍需过一遍确认**不是过时 |

## 5. 验证命令

```bash
# 后端（离线，避免沙箱出站被拦）
cd backend && mvn -o -B -pl memory -am test

# 全量
cd backend && mvn -o -B test

# 金标集（需要真实 Key，见 docs/memory-gold-set.md §5）
export MEMORY_EVAL=true
export MEMORY_EVAL_CONFIG="C:/Users/陈增/Desktop/基于Spring Boot和Vue的智能导诊系统设计与实现/backend/admin/src/main/resources/application-local.yml"
cd backend && mvn -o -B -Dstdout.encoding=UTF-8 -pl memory -am test -Dtest=MemoryGoldSetEvalTest -Dsurefire.failIfNoSpecifiedTests=false
```
