# 健康档案 —— 实现报告

> 分支：`feat/patient-health-profile`（相对 `main`：**27 个提交 / 58 文件 / +3847 −40**）
> 本文件替代 PR 描述——本仓库 tracker 是本地 Markdown、且本机未装 `gh`，**没有 PR 这个面**。

---

## 1. 交付内容（5 个单据全部完成）

| 单据 | 内容 | 关键落点 |
|---|---|---|
| 01 | 健康档案可填、可存、可读 + 慢病标签词表 | `user_health_profile` / `health_tag` 两表；`auth` 的 entity/mapper/service/seed；`admin` 的患者端 REST；前端 `api/profile.js` + `Chat.vue` 档案抽屉 |
| 02 | 档案进模型上下文 + 推荐卡「已参考健康档案」 | `HealthProfileAssembler` 纯函数；`RagRequest.profileText`；`PromptBuilder.profileBlock()`；`prompts.yml` 加 `{profile}` 与**三条硬约束** |
| 03 | 档案进检索（召回扩容） | `RagRequest.profileQuery`；`RagService.retrieve()` 检索用串非空 ⇒ RRF **4 个排名列表**，为空 ⇒ 2 个；**精排 query 仍为主诉串** |
| 04 | 档案进证据快照 | 快照新增 `profile` 节点（存待注入文本 + 检索用串 + 结构化档案）；**改档案后旧快照不变** |
| 05 | 管理员维护慢病标签词表 | `HealthTagAdminService` + `HealthTagAdminController`（列表/新增/启停，仅 ADMIN）；**不做管理端页面**（spec 范围外） |

## 2. 验证结果

| 项 | 命令 | 结果 |
|---|---|---|
| 后端编译 | `mvn -o compile -DskipTests` | Exit 0 |
| 后端全量测试 | `mvn -o test`（backend） | **BUILD SUCCESS · 369 例 · Failures 0 / Errors 0 / Skipped 0** |
| 前端构建 | `npm run build` | ✓ built |
| 工作区 | `git status` | 干净 |

**未做端到端启动验证**：本机 MySQL / pgvector / ES 未必就绪，靠编译 + 单测覆盖。

## 3. 代码评审（双轴并行）与修复

### Standards 轴
- **硬违规 ①** 档案抽屉触控目标 36/40px < 规范的 44px → **已修**（`.p-prof__close` / `__chip` / `__select` / `__input` 全部 44px）
- **硬违规 ②** 早期若干提交缺 scope → **不修**（历史提交，不重写历史）
- smell：重复代码 / 字面量重复 / `query` 与 `RagRequest.query` 同名异义 / 死代码 `AgeRange.isValidCode` → **已修**
- smell：Data Clumps（8 字段跨 4 类型）→ **不修**，理由：这 8 个字段就是"一份健康档案"本身的形状，抽新类型只是把名字换个地方

### Spec 轴
- **缺口**：「三框合计上限」没实现（spec 要求"每框上限 **+ 合计上限**"）→ **已修**（新增受管参数 + 前端硬限 + 后端二次校验）
- **口径被放宽过头**：05 把自由文本匹配词表改成了含停用的全量 → **已修**：改回**仅启用**；"已勾选标签仍无条件进召回串"保持不变
- **存疑**：空档案时 prompt 残留连续空行 → **已修**（`{knowledge}{profile}`，档案段连同标题与前后换行整体消失）
- **夹带**：`docs/agents/*` 与 CLAUDE.md 工程技能段 → **保留**，那是用户明确要求配置的

**高风险点逐条复核全部成立**：精排 query 仍是主诉串 ✓；`cites` 只取检索切片、档案不进注号 ✓；空档案不额外 embed / 不加路 / prompt 无节 / 卡无行 ✓；三条硬约束与 spec 措辞一致 ✓；读档在规则硬门槛之后 ✓；快照无实时回读路径 ✓。

## 4. 已知限制（别误报成果）

1. ⚠️ **检索线的效果未验证。** 种子语料只有 **7 科室 14 片**，主诉双路（`topK` 初始 10）合并去重后基本覆盖全库 ⇒ 档案召回扩容**演示不出差异**。价值随知识库规模释放。**不要声称"档案提升了检索准确率"。**
2. **档案语料未建。**「以既往史为锚点的鉴别片」与「以症状为锚点的鉴别片」是两种语料——后者主诉路天然召得到（档案路冗余），前者才是档案路的用武之地。
3. **生成层仍是软约束。** "档案不许改变科室"在检索层与输出层是**结构保证**，在生成层仍是 prompt 承诺。
4. **管理端页面未做**（spec 范围外）。
5. **单据文件仍停在 `Status: ready-for-agent`** —— 本地 tracker 的 `Status:` 词表只有五个分诊角色，**没有"已完成"这个态**。要有完成态得先给 `docs/agents/triage-labels.md` 扩一个角色。

## 5. 未 push

分支停在本地，未推送远端。要推就说一声。

## 6. 复用价值较高的坑

- **MyBatis-Plus `updateById` 按 NOT_NULL 跳过 null** ⇒ 清空字段会**静默失败**，必须用 `LambdaUpdateWrapper.set`。
- `AgeRange` 编码（`0-3` / `60+`）含连字符，**过不了** `EnumConventionTest` ⇒ 不带 `@EnumValue`、不登记，已在 `数据库设计.md` §0 记为例外。
- `mvn -pl auth test` 会用到本地旧 `common`（`NoSuchFieldError`）⇒ 必须 `-pl auth -am`。
- 占位符若只把内容替换成空串，会在 prompt 留下连续空行 ⇒ 让整段（含标题与换行）一起消失。
