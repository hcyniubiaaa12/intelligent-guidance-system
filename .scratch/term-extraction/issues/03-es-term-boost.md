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

**Status:** ready-for-agent

**验收**

- [ ] 删索引 → 重启重建 → 重灌后，`medical_terms` 可被 `match` 命中、检索不报错
- [ ] 标题片仍被 `mustNot` 排除（既有行为不回退）
- [ ] 同一条查询在"加 / 不加 should"两种配置下 Top-3 对比，差异记入施工日志（**不承诺准确率提升**，只记录可观察的排序变化）
- [ ] 单测：`must` 里**不含** `medical_terms`、`should` 里有 `match(medical_terms)` 且 boost == 1.0、`mustNot` 仍排除标题片、空查询/旧索引不抛
