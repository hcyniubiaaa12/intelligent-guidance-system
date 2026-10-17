# 03: 关键词通道给术语加分

**What to build:**

用「胸闷」检索时，正文里写着「胸闷伴出汗」的切片本就命中——本票让**术语字段里列出胸闷、但正文用的是别的说法**的切片也能被拉上来。改动只在 ES 关键词通道，其余（向量通道、RRF 融合、精排、Prompt、链路 A 判定）一律不动。

目标查询形状：

```
bool:
  must:    multi_match(query, fields: ["title^2", "content"])
  should:  match(medical_terms, query), boost = 1.0
  mustNot: chunk_type = "title"      // 既有 ChunkRetrievalFilter，原样保留
```

**已定的规则：**

1. **为什么用 `should` 而不是把 `medical_terms` 加进 `multiMatch`**：`best_fields` 是"取最高分字段"，术语多的切片会把标题信号顶掉；`should` 才是加法，且默认 `minimum_should_match = 0`，**不改变原有召回集**（不命中不影响进 Top-K，命中才加分）。
2. **`boost` 初值 1.0，写死常量**，注释注明来历：`medical_terms` 是短字段，BM25 字段长度归一化（`b=0.75`）会让它的命中分天然偏高，直接拍 2.0 会让"只列了术语、正文无关"的切片冲进 Top-K。**本轮不进 `sys_config`**——为一个尚未验证的数新增受管参数要连带白名单、页面、文档四处。
3. **mapping：`medical_terms` 由 `keyword` 改为 `text`（`ik_max_word` / `ik_smart`）+ `.keyword` 子字段**（留给精确匹配与将来聚合）。`keyword` 在 `match` 查询里匹配不上中文术语。
4. **ES 不允许原地改字段类型**：`ensureIndex` 现在对已存在索引只 `putMapping` 补新字段。需要一条明确的重建路径（删索引重建后重灌），并写进文档。**这一步不能省——省了加分完全无效。**
5. **单字术语在写入侧过滤**（属 01），查询侧不再判断——每次检索都判断一遍是把职责放错了地方。
6. **查询构造抽成包内可见的纯函数**供单测钉住形状：`EsChunkUtil` 现在没有单测，且直接持有 `ElasticsearchClient`，mock 它成本高收益低。新增测试缝只有这一条，**不引入 Testcontainers / 内嵌 ES**（新依赖，且项目无先例）。
7. 加分效果**不做单测**：单测只能证明"查询长这样"，证明不了"排序变好"。效果靠真实环境对比，结果记入施工日志。

**Blocked by:** None (can start immediately)

> 可先拿种子语料（`SeedCorpus` 手写的 terms 已在 ES 里）验证加分，不必等 01。但**最终验收要再用 01 抽出的真实词跑一遍**，避免只拿手写词证明功能。

**Status:** resolved

**验收**

- [ ] 删索引 → 重启重建 → 重灌后，`medical_terms` 可被 `match` 命中、检索不报错
- [ ] 标题片仍被 `mustNot` 排除（既有行为不回退）
- [ ] 同一条查询在"加 / 不加 should"两种配置下 Top-3 对比，差异记入施工日志（**不承诺准确率提升**，只记录可观察的排序变化）
- [x] 单测：`must` 里**不含** `medical_terms`、`should` 里有 `match(medical_terms)` 且 boost == 1.0、`mustNot` 仍排除标题片、空查询/旧索引不抛

## Comments

**做了什么**

- `EsChunkUtil` 抽出两个包内可见纯函数：
  - `keywordQuery(String)`：`must: multi_match(title^2, content)` + `should: match(medical_terms).boost(MEDICAL_TERMS_BOOST)` + `mustNot: chunk_type = title`（仍取 `ChunkRetrievalFilter.excludedCode()`）；空/空白查询返回 `match_none`。
  - `chunkIndexMapping()`：`medical_terms` 改 `text`（`ik_max_word` / `ik_smart`）+ `.keyword` 子字段；其余字段原样。
- `searchChunks` 改为直接 `.query(keywordQuery(query))`，形状只有一处实现。
- `MEDICAL_TERMS_BOOST = 1.0f` 写死常量，注释写明来历（短字段 + BM25 `b=0.75` 长度归一化天然偏高，拍 2.0 会让"只列术语、正文无关"的片冲进 Top-K），不进 `sys_config`。
- 重建路径写进 `ensureIndex` 的类注释，并新增 `termMappingNeedsRebuild(TypeMapping)` + `warnIfTermMappingStale`：已存在索引时读 mapping，发现 `medical_terms` 仍是 `keyword` 只打一条 WARN（写明"删索引 → 重启重建 → 重灌"）。**没有**自动删索引——删索引丢数据，必须是人的运维决定。
- 新增 2 个测试类（共 7 例）：`EsChunkUtilKeywordQueryTest`（must 不含 medical_terms、should 的 field/boost/`minimum_should_match`、mustNot 排除标题片、空查询 match_none）、`EsChunkUtilMappingTest`（mapping 字段类型 + 重建判定三态）。
- `mvn -B test -pl common -am` 全绿（common 19 例，含新增 7 例）。

**偏差**

- 纯函数签名是 `keywordQuery(String)`（只接收查询串、返回 `Query`），没有把 `topK` 带进来——它不属于查询形状，单测也不需要。工单说"bool query 抽出纯函数"，这一点一致。
- 类注释里"具体步骤见 `docs/architecture/链路A-导诊对话.md`"：该册目前**没有**写这个重建步骤（那是工单 04 的活），这里只是指向；本票不改 `docs/`。
- 未做 01 的写入侧单字过滤（按边界不碰）；验收里"用 01 抽出的真实词跑一遍"依赖 01 合入后再做。

**真机验收步骤（ES 拒绝原地改字段类型，必须重建）**

1. 确认旧索引 mapping 是 keyword：
   `curl -s -u <user>:<pass> http://192.168.193.131:9200/guide_chunk/_mapping?pretty | grep -A3 medical_terms`
   应看到 `"type": "keyword"`。
2. 删索引（人工、一次）：`curl -X DELETE http://192.168.193.131:9200/guide_chunk`
3. 重启后端 → 启动日志出现 `ES 索引已创建：guide_chunk`；再查 mapping 应为 `text` + `fields.keyword`。
4. 重灌切片：开种子（`guide.seed.enabled=true`）重启，或走管理端重处理文档；写完后
   `curl -s 'http://192.168.193.131:9200/guide_chunk/_search' -H 'Content-Type: application/json' -d '{"query":{"match":{"medical_terms":"胸闷"}},"size":3}'`
   应能命中（此前 keyword 字段匹配为空）。
5. 检索不报错：患者端发一条主诉，后端日志 `双路召回：… 关键词 N 条` N>0；标题片仍被排除（`关键词路明细` 里不出现 content=标题 的片）。
6. **加/不加 should 的 Top-3 对比**（不改数据，只对比"形状"）：
   - 基线（不加 should）：临时把 `keywordQuery` 里的 `.should(...)` 注释掉、重启，发同一条主诉（如「胸闷」），抄下日志 `关键词路明细：score#chunkId …` 的前 3 条与 `精排 #1..#3`；
   - 恢复 should、重启，再发同一条，抄同样两行；
   - 对比两次的 chunkId 顺序差异。**不承诺准确率提升，只记录可观察的排序变化**。
   - 记录位置：`进度-施工日志.md`（按仓库规范，过程记录写那里；本票不改该文件，留给工单 04 / 汇总时落笔）。

