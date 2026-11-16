# 03: 稳定背景判重与向量同步

**What to build:**

稳定背景也走同一套判重，并且改了值之后向量跟着重算——否则召回会继续用旧文本的向量，患者改口等于没改。

完成后：患者先说「我有2型糖尿病」，过几天说「2型糖尿病，控制良好」，库里只留更完整的那一条；之后在相关话题里唤起的也是这一条。患者再说「我糖尿病好几年了」，系统认出是同一件事，不新增第二条。稳定背景上限只有 10 条，判重让配额不被重复项吃掉，也避免一组近义背景把 top-3 召回名额占满、把患者的另一条真实背景挤出去。

**Blocked by:** 02

**Status:** resolved

- [x] 稳定背景落库按 action 分派，复用 02 建立的分派口径（不为背景另写一套）
- [x] 背景 `update` 时同步刷新用于 embedding 的扩展文本，并重算向量
- [x] 背景 `update` 复用患者手动编辑记忆时的同一套口径，不写第二份实现
- [x] 偏好仍不向量化（偏好永远全量注入，这是既有口径）
- [x] 「留哪条」的规则在背景上同样成立：新值更具体 → 更新；旧值更具体 → 跳过；相反 → 以新说法为准
- [x] 已过期 / 已删除的记忆不参与判重（不能拿患者已经放弃的说法来比）
- [x] 满额兜底保持不变：背景满上限时保留旧背景、丢弃本批新背景，页面提示患者整理
- [x] 测试：编排层覆盖背景三条路径；`update` 触发向量重算而偏好不触发；`skip` 对背景同样零写入零审计
- [ ] 真机验证：补充细节后库里只剩一条更完整的值；`user_memory_vec` 中该行的向量已按新文本重算

## Comments

- 2026-11-15 立项。与 02 分开的理由：偏好不向量化、背景要维护向量一致性，两者失败模式不同。合在一起时，背景「改了值不刷扩展文本」这个坑容易被偏好部分的测试绿灯掩盖。
- 2026-11-16 实现（`c052e7a`）。落点：
  - `MemoryExtractionService#saveBackgrounds(userId, remembered, drafts)`：先全等去重，再 `MemoryDedupPlanner.plan(fresh, remembered)` 分派；`update` 走 `applyBackgroundUpdate`、`skip` 两边都不进、分派或更新异常一律降级为新增；`toInsert` 为空时**先返回再判满额**——本批全是 update/skip 时条数没变，满 10 条也必须放行改口。
  - `applyBackgroundUpdate`：一条 `LambdaUpdateWrapper` 写 `mkey/mvalue/source_quote/confidence/observed_at/updated_at/retrieval_text`，条件是 `id + user_id + status=ACTIVE`；写后审计 `update`（old→new, actor=extract），再刷新快照并 `vectorIndexer.refreshBackgroundValue(...)`。字段集不含 `valid_to/status/type`（有效期到期走插入、患者更正走 retract，各有归属）。
  - 向量共用口径：`MemoryVectorIndexer#refreshBackgroundValue(row, newValue, now)` 一处同步 `mvalue/retrieval_text/updated_at` 快照并 `index(row)`；`MemoryManageService#updateValue` 改为调它，不再自己拼一遍「刷 retrieval_text + 重算」。顺序是「MySQL 先落定（updated_at 已新）→ 审计 → 重算向量」；向量失败只 WARN，`MemoryVectorBackfillJob.needsIndex()`（`vectorUpdatedAt < memory.updatedAt`）下一轮补。
  - 判重候选与满额计数都读 `extract(...)` 已有的 `remembered`（送进提示词的那份清单），不再二次查库；因此 `user_memory` 的读路径抖动时，降级成空清单后满额判断会按 0 条算——沿用工单「透传 remembered」的口径，属可接受降级。
  - 测试：`MemoryExtractionServiceTest` 新增 4 例（背景 update 原地改 + 刷 retrieval_text + 只调 `refreshBackgroundValue`、背景 skip 零写入零审计、满额时改口仍生效、更新失败回退新增），并在偏好 update 用例上钉「不触发 `refreshBackgroundValue` / `index`」；`MemoryVectorIndexerTest` 新增 2 例（新文本重算、偏好空操作）；`MemoryManageServiceTest` 改为断言委托给共用方法。
  - 验证：`mvn -o -B -pl memory -am test` → **186 用例全绿**（基线 180 + 新增 6，1 skipped 为既有）；`mvn -o -B test` 全后端 11 个模块 BUILD SUCCESS。
  - **真机验证没做**：worktree 内无 `application-local.yml`（私密配置被 gitignore、不会随分支带过来），且中间件 `192.168.193.131:3306` 不可达，起不了服务；要验「库中只剩更完整的一条 + 向量按新文本重算」必须跑真实抽取与 embedding。上面两条用例已在 mock 层面钉住分派与调用，真机项留待有环境时补。

- 2026-11-16 **真机验证（部分补做）**。同一环境（MySQL 本地、其余中间件在 `192.168.193.131`、真实 DeepSeek + DashScope embedding）。
  - **「2型糖尿病」→「2型糖尿病，现在控制得挺好的」**：`skip`，库里仍是一条，`retrieval_text`/向量不动。符合规则 2「旧值已经够具体就 skip」——状态细节不是新的稳定事实。
  - **「高血压」→「除了高血压，我还有糖尿病」**：两条。那是两个不同的事实，**不该合并**；这次探针一开始写错了预期，已纠正。
  - **「2型糖尿病」→「…还查出来糖尿病肾病」**：独立一条；判重专项里同形态样本 2 次有 1 次判 `update`。
  - **未抓到背景 `update` 的端到端证据**：模型在这几种说法上分别判了 skip / new，还有一次被背景「禁止推断」校验误杀（值不是原话的连续子串）。
    因此「`retrieval_text` 与向量按新文本重算」仍只有单测（`MemoryExtractionServiceTest` 背景 update 用例 + `MemoryVectorIndexerTest`）覆盖，
    真机证据不足。偏好 `update` 走同一条编辑形状，已端到端验过（见工单 02）。
  - 首次端到端跑确实观察到 `user_memory_vec` 写入（DashScope `向量化：文本=1 条｜维度=1024` 日志），但对应的是**插入**路径，不是 update 路径。
  - 因此本工单最后一项 checkbox **保持未勾**：代码已实现并单测覆盖，端到端证据不完整。
