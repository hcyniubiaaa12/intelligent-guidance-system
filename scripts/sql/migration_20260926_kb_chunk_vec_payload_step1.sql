-- ============================================================
-- 增量迁移（PostgreSQL / pgvector）：kb_chunk_vec 补「归属 + 正文副本」
-- 背景：kb_chunk_vec 原先只有 (chunk_id, dept_id, embedding)，排查时既看不出
--       一条向量属于哪个文档，也看不到切片内容是什么。本次新增三列：
--       ① doc_id  —— 行自证归属 + 按文档批量清理（带索引）
--       ② title   —— 切片标题副本
--       ③ content —— 切片正文副本
--       ②③ 是 MySQL kb_chunk 正文的副本，**只作人工排查用**：searchChunks 不
--       SELECT 它们，检索仍经 ChunkTextProvider 回填 MySQL——MySQL 为准，副本不作数。
--       副产品：孤儿向量（MySQL 已回滚、向量已落库）从此肉眼可见。
-- 影响：不执行时，新代码写这三列会报 column does not exist，chunk 入库直接失败。
-- 执行：分两步。本文件是第一步（加可空列 + 索引），可重复执行；
--       第二步（收紧 NOT NULL）见 migration_20260926_kb_chunk_vec_payload_step2.sql，
--       必须在存量行回填完成之后再执行——否则已有行的 NULL 会让约束加不上。
-- 回填方式：见 scripts/sql/README.md「存量向量回填」一节。
-- ============================================================

ALTER TABLE kb_chunk_vec ADD COLUMN IF NOT EXISTS doc_id  VARCHAR(32);
ALTER TABLE kb_chunk_vec ADD COLUMN IF NOT EXISTS title   VARCHAR(255);
ALTER TABLE kb_chunk_vec ADD COLUMN IF NOT EXISTS content TEXT;

-- 按文档批量清理 / 排查归属都按 doc_id 查
CREATE INDEX IF NOT EXISTS idx_kcv_doc ON kb_chunk_vec (doc_id);
