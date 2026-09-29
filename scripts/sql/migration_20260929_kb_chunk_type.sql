-- ============================================================
-- 增量迁移：MySQL kb_chunk 增加版面类型
-- 历史切片没有外部版面识别结果，统一使用 unknown；新入库由 layout.type 映射。
-- ============================================================

ALTER TABLE `kb_chunk`
    ADD COLUMN `chunk_type` VARCHAR(32) NOT NULL DEFAULT 'unknown'
        COMMENT '版面类型：title/text/table/unknown'
        AFTER `seq`;
