# 02: 查看切片改真分页——内容在 SQL 分页，派生仍单处实现

**What to build:**

`GET /kb/docs/{docId}/chunks?page=&size=`（**参数名同文档列表**，不是 `pageNum`/`pageSize`）返回 `PageVO<ChunkVO>`（复用既有 `KbAdminDTO.PageVO` 与 `pageVO(total, records)`）；内容用 `selectPage` 按 `seq` 分页；**派生（`noInSection` / `sectionTotal`）保持单处 Java 实现**，改吃一份瘦投影（`id` + `chunk_type`，按 `seq` 序）。前端切片抽屉接分页件（`utils/pager.js` 的 `PAGE_SIZES` / `applySizeChange`）。

**已定的规则**：

1. **派生不许复制进 SQL**。「本标题下第几块 / 共几块」的规则是"遇到新的标题片就重算、只统计 text 片"——节**可能跨页**，写成 SQL 窗口函数就变成第二份实现（仓库明令禁止同一份事实两处拼装）。做法：瘦投影喂原有那段 Java 推导，内容页单独查，两边按 `id` 对齐。
2. **页码参数按既有口径夹紧**：`Math.max(1, pageNum)` + `clamp(pageSize)`（与管理端其余分页一致，别自创一套）。
3. **`total` 取 `IPage` 给的值**，不另发一条 count。
4. **前端沿用管理端分页约定**：`PAGE_SIZES` 同一份常量、切条数走 `applySizeChange(page, size, reload)`（顺序：**先改 current 再请求**，否则 el-pagination 会连发两次请求）。
5. **空态文案**：`total=0` 时仍是「这份文档还没有切片」；**新增一条**——`total>0` 但本页没片时说「本页没有切片」（审查时补的：翻页边界不该被说成"这份文档没切片"）。
6. **不改的东西**：切片正文的事实源仍是 MySQL（pgvector 那份只是副本）；`chunk_type` 展示文案、异常类型（header/footer/unknown）醒目标记、标题片不重复渲染正文——一律不动。

**验收**

- [x] 接口返回 `{ total, records }`：`records` 是该页内容，`total` 是全量片数
- [x] 第 2 页的 `noInSection` / `sectionTotal` 与全量推导**逐片一致**（单测 `chunksNumberingSurvivesPaging` 钉住跨页不串号）
- [x] `pageNum` 超范围夹回、`pageSize` 走 `clamp`（单测 `chunksClampsPageArgs` 用 ArgumentCaptor 钉住实际入参 1 / 100）
- [x] 前端：抽屉出现分页件、切条数走 `applySizeChange`（先改 current 再请求）、翻页不重复请求
- [x] 前端 build 通过、`npm run check` 全绿
- [x] 文档：`进度.md` 该条遗留改写为已做 + 记下"派生不落 SQL"的理由

**Blocked by:** 无

**Status:** resolved

## Comments

**实现落点**：`KbDocService.pageChunks(docId, pageNum, pageSize)`（`selectPage`，按 seq）+ `listChunkSkeleton(docId)`（`select(id, chunk_type)`，按 seq）；`KbAdminService.chunks(docId, pageNum, pageSize)` 里把原来那段推导抽成 `numberWithinSections(...)`，**只有一份实现**，吃的是瘦投影；`KbAdminController` 的 `chunks` 加 `page`/`size` 两个 `@RequestParam`（与文档列表同一套默认值与口径）。前端 `api/admin.js` 的 `getKbChunks(docId, page, size)` 带 params，抽屉底部接 `el-pagination`。

**没删 `listChunks`（本来想删）**：它还有消费者 —— `feedback` 的 `ApprovalService` 要按容器全篇看切片（`.size()` 与取 id）。**零消费者才删**是仓库规矩，它有消费者，所以留着；本次只是不再被「查看切片」调用。

**一条刻意接受的代价**：现在一次请求打两条查询（瘦投影 + 内容页）。换来的是**派生规则不必复制进 SQL**——写成窗口函数就意味着同一套编号口径有两份实现，而节跨页正是最容易让两份跑偏的地方。几十片量级下，多一次"只有 id 与类型"的轻查询可以忽略。

**为什么不做（a）「查询仍全量、只把响应分页」**：那只是把渲染压力挪走，DB 仍把整篇正文捞回来；而"真分页"在这份代码里并不贵——多一次瘦投影查询，编号规则一行不用改。

**测试先红后绿**：先改测试（新签名 + 两条新用例），`mvn -pl admin -am test` 编译失败 = 红；实现后 `KbAdminServiceTest` **8/8 全绿**，后端全量 `mvn test` **BUILD SUCCESS**。

**代码审查后补的一处**：规格轴指出新增的「本页没有切片」属 spec 未要求（原写"空态文案不变"）——**不回退，改回写规则 5**：翻页边界（`total>0` 而本页为空）不该被说成"这份文档还没有切片"。审查也确认核心决定都落地正确（内容按页取、派生单处吃全篇瘦投影、参数夹紧同文档列表口径）。
