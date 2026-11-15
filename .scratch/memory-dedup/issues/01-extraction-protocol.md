# 01: 抽取协议扩展（预factor，行为不变）

**What to build:**

让抽取管线具备「看见已有记忆」的能力，但**先不改变患者看到的任何行为**——这是「把改动变容易，再做那个容易的改动」。

完成后：抽取入口可以收到一份「已记住的记忆」清单，模型输出里每条多出 `action`（`new`/`update`/`skip`）与 `target`（旧清单序号）；解析层把这两个字段读出来，遇到缺失、非法值或 `target` 越界时一律降级为 `new`。落库层**仍然不认 action**，照旧全插，所以对患者来说这一刀是空操作——但它把协议、解析、降级规则先钉死，让下一个切片的 diff 只剩「校验 + 分派」两件事。

同时顺手修一个既有缺陷：偏好满额时的合并提示词目前只把旧偏好的**值**送进去，却要求模型输出 key，模型只能自己编。改成送「键：值」。

**Blocked by:** None（可立即开工）

**Status:** resolved

- [x] 抽取入口接受「已有记忆」入参；保留只收对话文本的重载，语义等价于旧清单为空（金标集与既有单测据此沿用，不必全改）
- [x] `memory-extract` 提示词的 user 模板增加「已记住的记忆」段，按序号编号、分 `[偏好]` / `[背景]` 标注，无条目时写「（无）」
- [x] `memory-extract` 提示词的输出协议增加 `action` 与 `target` 两个字段，并写死四条判重口径：同一件事才 `update`；`update` 时取信息量更大的值；相反说法以新说法为准；**拿不准就 `new`**
- [x] 偏好与稳定背景两个草稿类型各增加 `action` / `target` 字段
- [x] 解析层降级规则：`action` 缺失或不在三值之内、`target` 越界或缺失 → 一律按 `new`
- [x] 合并提示词改造为接收「键：值」而非只有值；合并结果的 key 不再由模型凭空编造
- [x] 落库层**保持不变**（这是本切片的验收前提：插入路径无从感知 action）
- [x] 测试：解析层覆盖三种 action、`target` 越界、`action` 非法、旧清单确实进了 user 消息；合并提示词的占位符内容断言更新
- [x] 既有 154 个用例全绿（不得因协议扩展而需要大面积改测试）

## Comments

- 2026-11-15 立项。切片策略：本单是预factor，单独合入不改变患者行为，也不完整实现需求——真正的行为变更在 02、03。

- 2026-11-15 实现完成（分支 `ticket/memory-01-dedup-protocol`）。

  **改了什么**
  - 新增 `memory/enums/MemoryDedupAction`（`new`/`update`/`skip`，编码值 = 常量名小写）。它不进任何表，故**无 `@EnumValue`、不登记进 `EnumConventionTest`**——与 `RootCauseKey` 同一处理方式（枚举规范里明写了「不参与 MyBatis 装载的枚举不登记」这条边界）。
  - `MemoryExtractionResult`：`PreferenceDraft` / `BackgroundDraft` 各加 `action` / `target`，并保留**旧参构造器**（默认 `new` + `null`）——这是既有单测与 `MemoryExtractValidator` 内部重建草稿不用改的原因。`ProfileCandidateDraft` 刻意不加：候选不参与判重（患者逐条确认就是人肉去重）。
  - `MemoryExtractor`：新增 `extract(String, List<ExistingMemory>)`，旧的 `extract(String)` 委托给它并传空清单。新增 `ExistingMemory(type, key, value)` / `PreferenceItem(key, value)` 两个 record。
  - 降级规则落在 **`MemoryExtractor.dedup(JsonNode, int existingCount)`** 一个私有方法里（解析层唯一判定点）：`action` 缺失/非法 → `new`；`action=update` 但 `target` 缺失、非整数（含 `1.5` 这种会被 `canConvertToInt` 放行并截断的小数）、小于 1 或大于旧清单条数 → `new`；**非 `update` 时 target 一律清空**（留着会让下游误以为它指得到某条旧记忆）。
  - `prompts.yml`：`memory-extract` 的 system 写入两个字段、四条口径，另加一条「target 只能指向同一组」（偏好与背景永远不是同一件事——否则模型可能把「讲慢一点」和「2型糖尿病」判成同一条）；user 模板新增 `{remembered}` 段。`PromptProperties` 新增 `PLACEHOLDER_REMEMBERED` 并加进 `validate()` 必填——不加就会静默降级成空段。
  - `memory-merge`：入参改 `List<PreferenceItem>`，清单渲染成 `- 键：值`。**合并结果的 key 优先取输入项**（`keyFor`，按 value 反查）：模型仍可能编键，编了就退回它的键，不因为一处对不上丢掉整条合并结果。失败语义不变（`IllegalStateException`）。
  - `MemoryExtractionService`：新增 `rememberedMemories(userId)`，在 `retractCorrectedBackgrounds` **之后**、调模型**之前**取 active 记忆（偏好 + 两类背景一起编号）；取失败按空清单降级（判重退化成全 `new`，不能因为读不到旧记忆就不抽取）。`mergePreferences` 改传 `itemsOf` / `itemsOfDrafts`。**落库路径一行未改**。

  **测试**
  - `MemoryExtractorTest` 新增 5 个：三种 action 解析、六种降级形态、旧清单为空时 `update` 必然越界、`{remembered}` 真的进了 user 消息（含空清单写「（无）」）、单参重载等价于空清单；`mergeParsesResult` 改为断言 `- 回答方式：讲慢一点` 这种「键：值」形态；新增 `mergeReusesKeyFromInputs`。
  - 新增 `MemoryPromptsProtocolTest`（读真 `prompts.yml`，零 mock）：钉住提示词里确实写了 `action`/`target` 与四条口径——只测代码不测提示词的话，提示词漏写时模型不回字段，落库层就永远只走 `new`，而这不会有任何报错。
  - `MemoryExtractionServiceTest`：桩改成双参重载；`preferenceFullTriggersMerge` 的 `incoming` 断言从 `List<String>` 改成 `List<PreferenceItem>`，**行为含义不变**（新偏好确实进了合并输入），并补了 key 的断言。
  - `mvn -o -B -pl memory -am test`：**162 用例全绿**（基线 154 + 新增 8；1 个 skipped 是金标集，需 `MEMORY_EVAL=true`）。另跑 `mvn -o -B test` 全后端 BUILD SUCCESS。

  **坑**
  - 一开始把 `isNull()` 也当越界判据，结果 `skip` 带 `target` 的单测期望写错了：协议里 target「只有 update 需要」，`skip` 带了也要清空。已按「非 update 一律清空」改测试与实现，并在测试名里写清这条。
  - `target` 不能只判 `canConvertToInt`：`1.5` 会被放行并截成 `1`，等于替模型指了一条它没指的旧记忆。改成 `isIntegralNumber() && canConvertToInt()`。
  - `MemoryExtractorTest` 的 `@BeforeEach` 里 user 模板是**测试自己拼的**，不读 `prompts.yml`；加了 `{remembered}` 后必须同步改那行模板，否则断言「旧清单进了 user 消息」看到的永远是空串（第一次跑就踩了）。
  - `CONTEXT.md` 的「记忆抽取」词条补了一段协议现状（含「落库层暂不认 action」）；完整的「记忆判重」词条留工单 04（本单还不是判重）。
  - 未动前端，`frontend/` 无改动。
