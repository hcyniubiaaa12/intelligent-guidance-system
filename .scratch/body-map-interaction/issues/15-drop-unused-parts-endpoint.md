# 15: 删掉零消费者的 `GET /api/chat/parts` 与 `enabledParts`

**What to build:** 工单 13 把人体图的词表改成前端自持之后，后端这一对接口就没消费者了——按仓库"删零消费者"的规矩清掉。

**Blocked by:** 13

**Status:** resolved

- [x] `GET /api/chat/parts`（admin 模块 `ChatController`）删除
- [x] `ChatService.listParts()` 删除
- [x] `MedicalTermService.enabledParts()` / `loadParts()` / `partCache` / `PartCacheEntry` 删除，`refresh()` 的日志不再报部位数，顺带清掉随之无用的 `TermType` / `ArrayList` / `List` import
- [x] 跟着删的两个测试用例（kb 测试 5 条、chat 测试 1 条）
- [x] 后端全量测试绿

## Comments

实现完成（集成分支 `feat/body-map-interaction`）。

- 落点：`admin` 的 `ChatController`、`chat` 的 `ChatService`、`kb` 的 `MedicalTermService`，外加两处测试。
- 验证：`mvn test` → **BUILD SUCCESS**（kb 61 / chat 81 / async 14 / feedback 39 / stats 9 / admin 14 … 全绿；受影响的 kb 由 8 → 3 条、chat 由 21 → 20 条，正是删掉的那些）。
- 顺带清掉的死物：`MedicalTermService` 里 `TermType` / `ArrayList` / `List` 三个 import 在方法删掉后没了用处；`refresh()` 的日志原带"（部位 N 个）"，一并去掉。
- 到这里，"人体图与术语表"这条链上前后端都不再有取词关系：前端 `bodyMap.js` 自持词表，后端术语表只服务入口防误杀与信息充足性判定两类读者。
