-- ============================================================
-- 智能导诊系统 PostgreSQL + pgvector 初始化脚本（管语义）
-- 对应《数据库设计.md》4.1 节；三张向量表（chunk / 聚类锚点 / 长期记忆背景）
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
    chunk_type VARCHAR(32) NOT NULL DEFAULT 'unknown', -- 版面类型：title/text/table/figure/image/formula/code/header/footer/unknown
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

-- ------------------------------------------------------------
-- user_memory_vec（memory 模块直写，第三处向量库写入例外）
-- 稳定背景的按需召回：回答前用已算好的 query 向量做 top-K
-- 只写 background_*（偏好永远全量注入，不向量化）
-- 刻意不建 HNSW：每用户 ≤10 行，按 (user_id, type) 过滤后顺序扫描足够
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS user_memory_vec (
    memory_id  VARCHAR(32) PRIMARY KEY,              -- 对应 MySQL user_memory.id
    user_id    VARCHAR(32) NOT NULL,                 -- 召回按用户隔离
    type       VARCHAR(32) NOT NULL,                 -- background_health / background_other
    embedding  VECTOR(1024) NOT NULL,
    updated_at TIMESTAMP NOT NULL                    -- 向量写入时间：回填任务据此识别「比记忆旧」
);

CREATE INDEX IF NOT EXISTS idx_umv_user_type
    ON user_memory_vec (user_id, type);

COMMENT ON TABLE user_memory_vec IS '长期记忆稳定背景向量（memory 直写）；只写 background_*，不建 HNSW，召回后回 MySQL 校验用户/状态/有效期';
