-- ============================================================
-- 长期记忆（memory）存量库迁移 —— 给**已在运行**的 guide 库补结构
--
-- 为什么需要它：mysql_init.sql 全部是 CREATE TABLE IF NOT EXISTS，重跑不会改已存在的表；
-- 老库停在旧结构上，应用启动后第一次读写就会 Unknown column。
--
-- 用法（MySQL CLI，不要用 JDBC 执行——本文件用了 DELIMITER）：
--   mysql -uroot -p --default-character-set=utf8mb4 < scripts/sql/memory_migration.sql
--
-- 可重复执行：
--   · 补列先查 information_schema（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）；
--   · 建表用 CREATE TABLE IF NOT EXISTS；
--   · 配置用 INSERT IGNORE（sys_config 的 uk_cfg_key 兜住重复键）。
--
-- 只补结构、不删数据、不改旧列：age_range / history_tags / history_other 保留，
-- 旧档案数据由 MemoryMigrationRunner 在应用启动后迁移成长期记忆（幂等）。
-- ============================================================

USE `guide`;

DROP PROCEDURE IF EXISTS `_apply_memory_migration`;
DELIMITER $$
CREATE PROCEDURE `_apply_memory_migration`()
BEGIN
    -- 1) user：自动记忆开关（关闭只停抽取与注入，档案照常生效）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'memory_enabled'
    ) THEN
        ALTER TABLE `user`
            ADD COLUMN `memory_enabled` TINYINT NOT NULL DEFAULT 1
                COMMENT '自动记忆开关 1/0：关闭只停抽取与注入，档案照常生效、旧记忆仍可见'
                AFTER `mute_until`;
    END IF;

    -- 2) user_health_profile：出生年 + 手术史（用药/过敏沿用旧列，值形态升级为对象数组）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_health_profile' AND COLUMN_NAME = 'birth_year'
    ) THEN
        ALTER TABLE `user_health_profile`
            ADD COLUMN `birth_year` SMALLINT NULL
                COMMENT '出生年（1900–当前年；选填；NULL=未填）'
                AFTER `gender`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_health_profile' AND COLUMN_NAME = 'surgery_tags'
    ) THEN
        ALTER TABLE `user_health_profile`
            ADD COLUMN `surgery_tags` JSON NULL
                COMMENT '近期手术标签数组'
                AFTER `medication_other`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_health_profile' AND COLUMN_NAME = 'surgery_other'
    ) THEN
        ALTER TABLE `user_health_profile`
            ADD COLUMN `surgery_other` VARCHAR(512) NULL
                COMMENT '手术史自由文本'
                AFTER `surgery_tags`;
    END IF;

    -- 3) chat_session：本会话已被长期记忆抽取消费的次数（每会话上限 memory.extract.session.max）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'memory_extract_count'
    ) THEN
        ALTER TABLE `chat_session`
            ADD COLUMN `memory_extract_count` INT NOT NULL DEFAULT 0
                COMMENT '本会话已被长期记忆抽取消费的次数（每会话上限 memory.extract.session.max，原子递增）'
                AFTER `archived`;
    END IF;
END$$
DELIMITER ;

CALL `_apply_memory_migration`();
DROP PROCEDURE IF EXISTS `_apply_memory_migration`;

-- ------------------------------------------------------------
-- 4) 新表（定义与 mysql_init.sql §8 一致）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `user_memory` (
    `id`             VARCHAR(32)  NOT NULL,
    `user_id`        VARCHAR(32)  NOT NULL COMMENT '归属用户',
    `type`           VARCHAR(32)  NOT NULL COMMENT '枚举：preference / background_health / background_other',
    `mkey`           VARCHAR(32)  NOT NULL COMMENT '记忆键（≤8 字）',
    `mvalue`         VARCHAR(128) NOT NULL COMMENT '记忆值（≤60 字）',
    `retrieval_text` VARCHAR(256) NULL COMMENT '供 embedding 的扩展文本，不注入',
    `source_quote`   VARCHAR(512) NULL COMMENT '患者原话出处',
    `confidence`     DECIMAL(4,3) NULL COMMENT '抽取置信度 0–1',
    `observed_at`    DATETIME     NOT NULL COMMENT '观察时间：这条事实从哪一刻起被记住',
    `valid_from`     DATE         NULL COMMENT '有效期起（空=无期限）',
    `valid_to`       DATE         NULL COMMENT '有效期止（空=无期限）',
    `status`         VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT '枚举：active/expired',
    `deleted`        TINYINT      NOT NULL DEFAULT 0,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_um_user_type` (`user_id`, `type`, `status`)
) ENGINE=InnoDB COMMENT='长期记忆：偏好与稳定背景（用户级、跨会话；带原话出处，可查可改可删）';

CREATE TABLE IF NOT EXISTS `profile_candidate` (
    `id`           VARCHAR(32)  NOT NULL,
    `user_id`      VARCHAR(32)  NOT NULL COMMENT '归属用户',
    `field`        VARCHAR(32)  NOT NULL COMMENT '枚举：allergy / medication / surgery',
    `value`        VARCHAR(128) NOT NULL COMMENT '候选值（≤60 字）',
    `occurred_on`  VARCHAR(7)   NULL COMMENT '发生月份 YYYY-MM（用药=开始、手术=发生；过敏不用）',
    `source_quote` VARCHAR(512) NULL COMMENT '患者原话出处',
    `confidence`   DECIMAL(4,3) NULL COMMENT '抽取置信度 0–1',
    `status`       VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '枚举：pending/accepted/rejected/expired',
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_pc_user_status` (`user_id`, `status`)
) ENGINE=InnoDB COMMENT='档案候选：二手健康事实，患者确认后才写档案；未确认不注入、不预警';

CREATE TABLE IF NOT EXISTS `user_memory_log` (
    `id`         VARCHAR(32)  NOT NULL,
    `memory_id`  VARCHAR(32)  NOT NULL COMMENT '被变更的记忆 id',
    `user_id`    VARCHAR(32)  NOT NULL COMMENT '归属用户',
    `op`         VARCHAR(32)  NOT NULL COMMENT '枚举：add/update/delete/merge/promote',
    `old_value`  VARCHAR(512) NULL COMMENT '变更前值（新增时为空）',
    `new_value`  VARCHAR(512) NULL COMMENT '变更后值（删除时为空）',
    `actor`      VARCHAR(32)  NOT NULL COMMENT '枚举：extract/user/migration',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_uml_memory` (`memory_id`),
    KEY `idx_uml_user` (`user_id`)
) ENGINE=InnoDB COMMENT='记忆变更审计：只追加，每次新增/修改/删除/合并/提升一行';

-- ------------------------------------------------------------
-- 5) 运行时配置（与 mysql_init.sql 的 c21–c28 一致；已存在则跳过）
-- ------------------------------------------------------------

INSERT IGNORE INTO `sys_config` (`id`, `config_key`, `config_value`, `remark`) VALUES
('c21', 'memory.preference.max',             '5',    '长期记忆：偏好最多条数（满了由模型合并去重）'),
('c22', 'memory.background.max',             '10',   '长期记忆：稳定背景最多条数（满了提示患者整理，不静默淘汰）'),
('c23', 'memory.background.recall.top.k',    '3',    '长期记忆：背景召回 top-K'),
('c24', 'memory.background.recall.threshold','0.70', '长期记忆：背景召回余弦阈值'),
('c25', 'memory.extract.confidence.min',     '0.6',  '长期记忆：抽取结果最小置信度（低于它丢弃）'),
('c26', 'memory.extract.session.max',        '3',    '长期记忆：单会话最多抽取次数'),
('c27', 'memory.candidate.expire.days',      '30',   '档案候选：未处理自动失效天数'),
('c28', 'memory.block.chars',                '500',  '长期记忆：注入块总字数上限');
