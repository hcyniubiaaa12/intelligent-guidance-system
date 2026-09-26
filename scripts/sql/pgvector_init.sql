-- ============================================================
-- 智能导诊系统 PostgreSQL + pgvector 初始化脚本（管语义）
-- 对应《数据库设计.md》4.1 节；仅两张向量表
-- 前置：目标库需已安装 pgvector 扩展
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 4.1 kb_chunk_vec（kb 唯一 RAG 语料写入）
-- embedding 维度 1024 对应阿里 text-embedding-v3；换模型需同步调整
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS kb_chunk_vec (
    chunk_id   VARCHAR(32) PRIMARY KEY,              -- 对应 MySQL kb_chunk.id
    dept_id    VARCHAR(32) NOT NULL,                 -- 科室 id，召回过滤用
    doc_id     VARCHAR(32) NOT NULL,                 -- 所属文档 id：行自证归属 + 按文档批量清理
    title      VARCHAR(255) NULL,                    -- 切片标题副本（只作人工排查用，检索不读）
    content    TEXT NOT NULL,                        -- 切片正文副本（只作人工排查用，检索不读）
    embedding  VECTOR(1024) NOT NULL                 -- 语义向量
);

-- HNSW 索引：余弦距离
CREATE INDEX IF NOT EXISTS idx_kcv_embedding
    ON kb_chunk_vec USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_kcv_dept
    ON kb_chunk_vec (dept_id);

CREATE INDEX IF NOT EXISTS idx_kcv_doc
    ON kb_chunk_vec (doc_id);

COMMENT ON TABLE kb_chunk_vec IS '向量召回；与 MySQL kb_chunk 以 chunk_id 对应（kb 唯一 RAG 语料写入）。title/content 是 MySQL 切片正文的副本，只作人工排查用——searchChunks 不 SELECT 它们，检索仍经 ChunkTextProvider 回填 MySQL；MySQL 为准，副本不作数，kb_chunk 只写不改';

-- ------------------------------------------------------------
-- cluster_bucket_vec（feedback 模块直写，例外于 kb 的"唯一写向量库入口"）
-- 开新桶时写入；语义归桶按 direction 过滤 + 余弦检索
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS cluster_bucket_vec (
    bucket_id      VARCHAR(32) PRIMARY KEY,          -- 对应 MySQL cluster_bucket.id
    rec_dept_id    VARCHAR(32) NOT NULL,             -- 错误方向：推荐科室
    actual_dept_id VARCHAR(32) NOT NULL,             -- 错误方向：实际科室
    embedding      VECTOR(1024) NOT NULL             -- 锚点向量
);

CREATE INDEX IF NOT EXISTS idx_cbv_embedding
    ON cluster_bucket_vec USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_cbv_direction
    ON cluster_bucket_vec (rec_dept_id, actual_dept_id);

COMMENT ON TABLE cluster_bucket_vec IS '聚类锚点向量；语义归桶按 direction 过滤 + 余弦检索（feedback 直写）';
