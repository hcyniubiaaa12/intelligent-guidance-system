-- ============================================================
-- 长期记忆向量表（pgvector 存量库迁移）
--
-- 定义与 pgvector_init.sql 的 user_memory_vec 一致；单独拆出来是给已有 guide_vec 库用，
-- 不必重跑整份初始化脚本。
--
-- 用法（psql 不可用时见 scripts/sql/PgExec.java）：
--   psql -U postgres -d guide_vec -f scripts/sql/pgvector_memory_migration.sql
--
-- 可重复执行（CREATE ... IF NOT EXISTS）。
-- 只写 background_*，偏好不向量化；不建 HNSW：每用户 ≤10 行，按 (user_id, type) 过滤后顺序扫描足够。
-- ============================================================

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
