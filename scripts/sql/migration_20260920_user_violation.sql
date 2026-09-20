-- ============================================================
-- 增量迁移：敏感词按窗口累计后的用户处置（警告 / 禁言）
-- 背景：入口校验以前只做「命中即拦截 + 词库命中数累加 + filter_log 旁路留痕」，
--       没有任何按用户、按时间窗的累计与处置。本次新增：
--       ① user.mute_until —— 禁言状态（定时自动解除，与 status=banned 分开：禁言只禁发言、仍可登录）
--       ② user_violation  —— 警告/禁言的处置留痕（管理端展示与事后追溯的依据）
--       ③ 5 条 sys_config  —— 统计窗口、两条禁止词阈值、观察词阈值、禁言时长（管理端可调）
--       ④ filter_log 补 (user_id, matched_at) 索引 —— 窗口计数每轮命中都要跑
-- 影响：不执行时，新代码读 user.mute_until 会报 Unknown column，每轮对话直接失败。
-- 执行：已在旧库上跑过 mysql_init.sql 的环境执行本脚本；全新初始化无需执行（脚本已含）。
-- ============================================================

ALTER TABLE `user`
    ADD COLUMN `mute_until` DATETIME NULL
        COMMENT '禁言截止时间：NULL 或已过期 = 未禁言。与 status 分离——禁言只禁发言、仍可登录，到期自动解除'
        AFTER `status`;

CREATE TABLE IF NOT EXISTS `user_violation` (
    `id`             VARCHAR(32) NOT NULL,
    `user_id`        VARCHAR(32) NOT NULL COMMENT '触发用户',
    `level`          VARCHAR(32) NOT NULL COMMENT '枚举：warn 警告/mute 禁言',
    `hit_type`       VARCHAR(32) NOT NULL COMMENT '触发词类型（枚举同 sensitive_word.type：banned/watch）',
    `hit_count`      INT         NOT NULL COMMENT '判定时窗口内命中词次（含本轮）',
    `window_minutes` INT         NOT NULL COMMENT '统计窗口（分钟），便于事后复现当时口径',
    `threshold`      INT         NOT NULL COMMENT '触发阈值（当时生效值）',
    `mute_until`     DATETIME    NULL COMMENT '禁言截止（level=mute 时与 user.mute_until 同值写入）',
    `occurred_at`    DATETIME    NOT NULL COMMENT '处置时间（滑动窗口计时的锚点）',
    `deleted`        TINYINT     NOT NULL DEFAULT 0,
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_uv_user_time` (`user_id`, `occurred_at`)
) ENGINE=InnoDB COMMENT='用户违规处置留痕（敏感词/观察词按窗口累计后的警告与禁言）；只记录处置事实，不回写对话或导诊记录';

-- filter_log 补索引：窗口计数与按词明细都按 (user_id, matched_at) 查，且每轮命中都要跑一次
ALTER TABLE `filter_log`
    ADD KEY `idx_fl_user_time` (`user_id`, `matched_at`);

-- 新增运行时参数（管理端「敏感词库 → 处置规则」面板可改；值缺失时后端回落代码侧默认值，故非强制写入）
-- 冲突时不覆盖已调过的值：重复执行本脚本不应把线上调好的阈值打回默认
INSERT INTO `sys_config` (`id`, `config_key`, `config_value`, `remark`, `deleted`, `created_at`, `updated_at`)
VALUES
('c08', 'sensitive.window.minutes',    '60', '敏感词违规统计窗口（分钟，滑动窗口）', 0, NOW(), NOW()),
('c09', 'sensitive.banned.warn.count', '10', '窗口内禁止词命中词次达此值 → 警告', 0, NOW(), NOW()),
('c10', 'sensitive.banned.mute.count', '30', '窗口内禁止词命中词次达此值 → 禁言', 0, NOW(), NOW()),
('c11', 'sensitive.watch.warn.count',  '25', '窗口内观察词命中词次达此值 → 警告（不禁言）', 0, NOW(), NOW()),
('c12', 'sensitive.mute.minutes',      '60', '禁言时长（分钟，到期自动解除）', 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;
