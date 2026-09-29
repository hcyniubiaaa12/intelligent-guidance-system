-- ============================================================
-- 增量迁移（PostgreSQL / pgvector）：kb_chunk_vec 增加版面类型
-- 历史向量没有版面识别结果，统一使用 unknown；新写入由 MySQL 同步。
-- ============================================================

ALTER TABLE kb_chunk_vec
    ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(32) NOT NULL DEFAULT 'unknown';
