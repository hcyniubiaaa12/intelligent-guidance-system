-- ============================================================
-- 增量迁移（PostgreSQL / pgvector）：kb_chunk_vec 收紧约束（第二步）
-- 前置：migration_20260926_kb_chunk_vec_payload_step1.sql 已执行，
--       且存量行的 doc_id / content 已回填完成（见 scripts/sql/README.md）。
-- 语义：doc_id 是「行自证归属」——一列一半是 NULL 的外键不叫自证，故收紧为 NOT NULL。
--
-- 执行失败（column "doc_id" of relation "kb_chunk_vec" contains null values）时，
-- 先查清楚是哪种情况，**不要直接删**：
--   ① 回填没跑完 → 重跑回填（最常见）
--        SELECT count(*) FROM kb_chunk_vec WHERE doc_id IS NULL;
--   ② 有向量但 MySQL kb_chunk 里查不到 → 孤儿向量（MySQL 已回滚、向量已落库），
--      它们本来就进不了 Prompt（回填时被丢弃），删掉即可：
--        SELECT chunk_id FROM kb_chunk_vec WHERE doc_id IS NULL;   -- 先看清是哪些
--        DELETE FROM kb_chunk_vec WHERE doc_id IS NULL;            -- 确认后再删
-- ============================================================

ALTER TABLE kb_chunk_vec ALTER COLUMN doc_id  SET NOT NULL;
ALTER TABLE kb_chunk_vec ALTER COLUMN content SET NOT NULL;
