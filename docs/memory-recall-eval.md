# 背景召回评估（retrieval_text 富增强）

> 对应改动：`user_memory.retrieval_text` 从「等于 mvalue」改为「模型生成的检索扩展文本」（生产由背景落库后的 `MemoryEnricher` 异步补写；本评估用文件中固定的 `retrievalText` 模拟这一产物）。
> 它**只用于 embedding**，不注入 prompt、不展示给患者、不进证据快照。
>
> **评估不是单元测试**：指标低不会让构建失败，输出是给提示词与阈值校准用的诊断信息。
> 默认跳过（`MEMORY_RECALL_EVAL=true` 才跑），不产生常规测试的模型费用。

## 1. 为什么要单独评估

抽取金标集（`docs/memory-gold-set.jsonl`）测的是「从对话里抽出了什么」，覆盖不到召回。
这次改动的收益全在召回侧——**只有召回评估能回答「扩写是否让换一种说法的患者也能被找回」**。
它也填补 `进度.md` 长期挂着的「背景召回阈值尚未用真实召回 gold query 校准」这一项。

## 2. 评估什么

`MemoryRecallEvalTest`（`backend/memory/src/test/java/com/guide/memory/eval/`）不连 pgvector、不起 Spring：

1. 读 `docs/memory-recall-gold-set.json`：6 条背景 + 12 条正样本 + 6 条负样本；
2. 用真实 DashScope embedding 分别算「mvalue 向量」与「retrievalText 向量」；
3. 对每条 query 模拟生产召回：**向量 top-3（阈值 0.70）+ 词面 mvalue 补位**；
4. 对比两轮的**正样本命中率**与**负样本误召回率**。

正样本尽量让 query **不含 mvalue 原文**，只在扩写里出现同义/口语说法；但中文短词很难完全避开近义
（「血压偏高」与「高血压」共享「血压」），所以报告**同时给两行指标**：词面+向量（生产口径）与纯向量。
**纯向量那一行才是扩写带来的净收益**——词面路两轮都只比 `mvalue`，不受扩写影响。

词面路两轮都只比 `mvalue`（扩写不参与词面）——这是刻意的口径：
扩写只负责语义路，词面路保持「患者确实提到了记忆值」的高精度信号。

样本格式（`docs/memory-recall-gold-set.json`）：

```json
{
  "memories": [
    {"key": "慢病", "value": "2型糖尿病",
     "retrievalText": "2型糖尿病；糖尿病、血糖高、血糖病；我血糖高要注意什么"}
  ],
  "cases": [
    {"query": "血糖高要挂什么科", "expect": ["2型糖尿病"], "negative": false},
    {"query": "今天手指被划伤了", "expect": [], "negative": true}
  ]
}
```

- `memories` 是固定的背景集；`retrievalText` 就是生产里模型会写入的扩写文本。
- 正样本的 `expect` 写期望被召回的背景 `value`；负样本 `expect` 为空、`negative=true`。
- 正样本的 `query` 尽量不含 `value` 原文；若无法避开近义（如「血压偏高」），靠报告里的**纯向量指标**看扩写收益。
- 词面补位与生产同口径：`mentions` 且未被当前消息否定；扩写不参与词面。

## 3. 怎么跑

```bash
# 1) 配 DashScope Key（二选一）
#    a. 环境变量
export DASHSCOPE_API_KEY=sk-xxx
#    b. 已存在的本地私密配置（backend/admin/src/main/resources/application-local.yml）
#       读取 llm.dashscope.api-key

# 2) 开启评估（默认跳过）
cd backend
MEMORY_RECALL_EVAL=true mvn -B -pl memory -am test -Dtest=MemoryRecallEvalTest -Dsurefire.failIfNoSpecifiedTests=false

# 可选：覆盖配置
#   DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com
#   DASHSCOPE_EMBEDDING_MODEL=text-embedding-v3
```

输出示例：

```
背景召回 gold set（真实 embedding，阈值 0.7）
正样本：7/12（mvalue 基线） → 11/12（retrievalText 扩写）
负样本误召回：1/6（基线） → 1/6（扩写）
逐条差异：
  [救回] 血糖高要挂什么科 → 基线 [] / 扩写 [2型糖尿病]
  ...
```

## 4. 结果记录

> 每次跑完把结果追加在这里（日期 + 模型 + 数字）。**召回评估的 embedding 是确定性的，单次可复现**；
> 抽取评估是生成式、才需要多次取均值。换 embedding 模型后必须重跑本表。

| 日期 | embedding 模型 | 正样本（基线 → 扩写） | 负样本误召回（基线 → 扩写） | 备注 |
|---|---|---|---|---|
| 2026-11-17 | DashScope（`application-local.yml` 配置） | 纯向量 **6/12 → 11/12**；词面+向量同为 **6/12 → 11/12** | **0/6 → 0/6** | 5 条救回：血糖高 / 倒班 / 独居 / 喘不上气 / 血糖方面；拆分后复跑结果一致 |

**2026-11-17 结果解读**：

- 扩写带来 **+5 条正样本命中**（6 → 11），负样本误召回 **没有增加**（0 → 0）。这正是「只换说法」的目标：
  没写进新事实，所以不相关 query 没有被拉进来。
- 词面+向量与纯向量两列相同：这批 query 的命中全部来自向量路，词面路没有额外贡献
  （个别近义如「血压偏高」与「高血压」在基线里就已由向量命中，词面没有改变结果）。
- embedding 是确定性调用（同一文本同一向量），所以召回评估**单次即可复现**；
  抽取评估才是生成式、需要多次取均值。换 embedding 模型后必须重跑本表。

## 5. 指标怎么读

- **正样本命中率**：期望背景是否出现在召回结果里（向量过阈值或词面命中）。
  扩写的主要收益应该体现在这里，尤其是「query 不含 mvalue 原文」的条目。
- **负样本误召回率**：不相关 query 是否召回了任何背景。扩写会让说法变多，**这条是看它有没有变滥的尺子**；
  如果扩写后负样本误召回明显升高，说明扩写写进了无关事实或说法过宽，应先改提示词。
- **逐条差异**：只看数字不够，要看「救回了哪些、变差了哪些」——变差的通常能直接指出扩写写偏的方向。

## 6. 与阈值的关系

阈值默认 0.70（`memory.background.recall.threshold`，`sys_config`）。这次评估**不自动调阈值**：
先把扩写的收益测出来，再决定是否需要动它；调阈值前先看词面路是否已经覆盖了漏掉的样本
（`进度.md` 已知坑：短中文改写向量分经常过不了 0.70，词面第二路就是为此加的）。

## 7. 已知局限

- 评估是内存余弦模拟，不经过 pgvector 的实际索引与 SQL 排序；结论对「扩写是否改善语义命中」有效，
  对「pgvector 查询本身有没有问题」无效（后者由 `MemoryVectorStoreTest` 与真机验证覆盖）。
- 样本只有 18 条，且由单人编写；它是一把尺子，不是一份 benchmark。扩样本时保持正负比例与
  「正样本不含 mvalue 原文」这条约束。
- 评估结果与 `memory.background.recall.top.k`（默认 3）绑定；改 top-K 后要重跑。
