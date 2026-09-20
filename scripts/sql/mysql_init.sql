-- ============================================================
-- 智能导诊系统 MySQL 初始化脚本（管理事实数据）
-- 对应《数据库设计.md》v0.3；库 字符集 utf8mb4
-- 公共字段：id varchar(32) 雪花主键 / deleted 逻辑删除 / created_at / updated_at
-- 枚举字段：一律存英文小写编码值，列宽统一 varchar(32)（见数据库设计 §0）
-- ============================================================

CREATE DATABASE IF NOT EXISTS `guide`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `guide`;

-- ------------------------------------------------------------
-- 1. 用户与鉴权（auth，链路 D）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `user` (
    `id`         VARCHAR(32)  NOT NULL COMMENT '雪花主键',
    `username`   VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`   VARCHAR(128) NOT NULL COMMENT '密码（加密）',
    `role`       VARCHAR(32)  NOT NULL COMMENT '枚举：patient/admin',
    `nickname`   VARCHAR(64)  NULL COMMENT '昵称',
    `status`     VARCHAR(32)  NOT NULL DEFAULT 'normal' COMMENT '枚举：normal/banned，登录时校验（封禁即拒绝）',
    `mute_until` DATETIME     NULL COMMENT '禁言截止时间：NULL 或已过期 = 未禁言。与 status 分离——禁言只禁发言、仍可登录，到期自动解除',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0/1',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB COMMENT='用户表；管理员由初始化脚本创建';

CREATE TABLE IF NOT EXISTS `sensitive_word` (
    `id`         VARCHAR(32)  NOT NULL,
    `word`       VARCHAR(128) NOT NULL COMMENT '敏感词',
    `type`       VARCHAR(32)  NOT NULL COMMENT '枚举：banned 禁止词/watch 观察词',
    `hit_count`  INT          NOT NULL DEFAULT 0 COMMENT '命中次数',
    `enabled`    TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用/0 停用',
    `deleted`    TINYINT      NOT NULL DEFAULT 0,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sw_word` (`word`)
) ENGINE=InnoDB COMMENT='敏感词库（链路 A 入口前置校验）；管理端可维护、批量导入；白名单复用知识库部位词不建表';

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

CREATE TABLE IF NOT EXISTS `sys_config` (
    `id`           VARCHAR(32)  NOT NULL,
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '配置键：聚合归桶阈值/升级阈值/低置信度阈值/术语人工审核开关/检索Top-K/Top-N 等',
    `config_value` VARCHAR(255) NOT NULL COMMENT '配置值',
    `remark`       VARCHAR(255) NULL COMMENT '说明',
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cfg_key` (`config_key`)
) ENGINE=InnoDB COMMENT='运行时可调配置字典表（链路 5 参数）；LLM API Key/模型名不走本表（密钥不进库）';

-- ------------------------------------------------------------
-- 2. 会话与导诊（chat，链路 A）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`         VARCHAR(32) NOT NULL,
    `user_id`    VARCHAR(32) NOT NULL COMMENT '用户 id',
    `status`     VARCHAR(32) NOT NULL DEFAULT 'ongoing' COMMENT '枚举：ongoing/closed；register_success 即置 closed',
    `ask_round`  INT         NOT NULL DEFAULT 0 COMMENT '追问轮次 0–3',
    `has_result` TINYINT     NOT NULL DEFAULT 0 COMMENT '0/1 已出导诊结论（新主诉判定双信号之一）',
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_cs_user` (`user_id`)
) ENGINE=InnoDB COMMENT='会话表：一条主诉一个会话；同聊天页新输入=开新会话（判定双信号：has_result=1 或 status=closed）';

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`         VARCHAR(32) NOT NULL,
    `session_id` VARCHAR(32) NOT NULL COMMENT '会话 id',
    `role`       VARCHAR(32) NOT NULL COMMENT '枚举：user/ai/question（question 即追问）',
    `content`    TEXT        NOT NULL COMMENT '消息内容',
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_cm_session` (`session_id`)
) ENGINE=InnoDB COMMENT='会话消息表';

CREATE TABLE IF NOT EXISTS `guide_record` (
    `id`              VARCHAR(32)   NOT NULL,
    `session_id`      VARCHAR(32)   NOT NULL COMMENT '会话 id（一条主诉一条导诊记录）',
    `rec_dept_id`     VARCHAR(32)   NOT NULL COMMENT '推荐科室 id',
    `confidence`      DECIMAL(5,4)  NULL COMMENT '置信度',
    `rec_top3`        JSON          NULL COMMENT 'Top3 候选快照（只写）',
    `low_confidence`  TINYINT       NOT NULL DEFAULT 0 COMMENT '0/1 布尔语义，不枚举化',
    `actual_dept_id`  VARCHAR(32)   NULL COMMENT '实际科室：挂号确认接口在 register_success 时同事务写入（非 feedback 回写）',
    `top1_hit`        TINYINT       NULL COMMENT '0/1',
    `top3_hit`        TINYINT       NULL COMMENT '0/1',
    `aggregated`      TINYINT       NOT NULL DEFAULT 0 COMMENT '增量标记 0/1',
    `evidence`        JSON          NULL COMMENT '证据快照（只写，审核回放）',
    `deleted`         TINYINT       NOT NULL DEFAULT 0,
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_gr_session` (`session_id`),
    KEY `idx_gr_actual` (`actual_dept_id`),
    KEY `idx_gr_aggregated` (`aggregated`)
) ENGINE=InnoDB COMMENT='导诊记录表；历史不回改，rec_top3/evidence 供看板排序精度聚合与审核回放';

-- ------------------------------------------------------------
-- 3. 知识库（kb，链路 B）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `dept` (
    `id`         VARCHAR(32)  NOT NULL,
    `name`       VARCHAR(64)  NOT NULL COMMENT '科室名（模拟挂号科室范围）',
    `enabled`    TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用/0 停用；停用仅入口生效（chunk 留库不删、历史引用不受影响）',
    `location`   VARCHAR(128) NULL COMMENT '位置',
    `intro`      VARCHAR(512) NULL COMMENT '简介',
    `deleted`    TINYINT      NOT NULL DEFAULT 0,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='科室蓝本';

CREATE TABLE IF NOT EXISTS `kb_doc` (
    `id`          VARCHAR(32)  NOT NULL,
    `dept_id`     VARCHAR(32)  NOT NULL COMMENT '科室 id',
    `title`       VARCHAR(255) NOT NULL COMMENT '文档标题',
    `file_url`    VARCHAR(512) NOT NULL COMMENT 'MinIO 链接',
    `status`      VARCHAR(32)  NOT NULL DEFAULT 'parsing' COMMENT '枚举：parsing/done/failed（状态机）',
    `fail_reason` VARCHAR(512) NULL COMMENT '失败原因',
    `fail_type`   VARCHAR(32)  NULL COMMENT '枚举：retryable/fatal',
    `chunk_total` INT          NULL COMMENT '切片总数',
    `chunk_done`  INT          NULL COMMENT '已向量化切片数',
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kd_dept` (`dept_id`)
) ENGINE=InnoDB COMMENT='知识库文档表';

CREATE TABLE IF NOT EXISTS `kb_chunk` (
    `id`         VARCHAR(32) NOT NULL,
    `doc_id`     VARCHAR(32) NOT NULL COMMENT '文档 id；合成 chunk 指向系统级「回流知识文档」容器',
    `title`      VARCHAR(255) NULL COMMENT '切片标题（溯源引用）',
    `content`    TEXT        NOT NULL COMMENT '切片内容',
    `seq`        INT         NOT NULL COMMENT '切片序号',
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kc_doc` (`doc_id`)
) ENGINE=InnoDB COMMENT='知识片段表：MySQL 存事实，向量在 pgvector 同步；回流 approve 的合成 chunk（文本 LLM 生成、管理员预览定稿）也写这里（闭环生效唯一机制）';

CREATE TABLE IF NOT EXISTS `dept_mapping` (
    `id`             VARCHAR(32)  NOT NULL,
    `symptom`        VARCHAR(128) NOT NULL COMMENT '症状',
    `main_dept_id`   VARCHAR(32)  NOT NULL COMMENT '主科室 id',
    `cross_dept_ids` JSON         NULL COMMENT '交叉科室 id 列表',
    `source`         VARCHAR(32)  NOT NULL COMMENT '枚举：init/manual/feedback（回流 approve 写 feedback）',
    `deleted`        TINYINT      NOT NULL DEFAULT 0,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dm_symptom_main` (`symptom`, `main_dept_id`)
) ENGINE=InnoDB COMMENT='症状交叉映射台账；运行时不被 RAG 消费（闭环生效靠合成 chunk），仅管理端维护与审核事实记录；键=(症状原文,主科室)：同键合并交叉并集、异键新行';

CREATE TABLE IF NOT EXISTS `medical_term` (
    `id`         VARCHAR(32)  NOT NULL,
    `term`       VARCHAR(128) NOT NULL COMMENT '术语（白名单唯一生效源；ES 聚合只是候选池）',
    `type`       VARCHAR(32)  NOT NULL COMMENT '枚举：part/symptom',
    `source`     VARCHAR(32)  NOT NULL COMMENT '枚举：llm_extract/manual',
    `enabled`    TINYINT      NOT NULL DEFAULT 0 COMMENT 'LLM 抽取默认 0，受"人工审核"开关控制',
    `deleted`    TINYINT      NOT NULL DEFAULT 0,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mt_term_type` (`term`, `type`)
) ENGINE=InnoDB COMMENT='医疗术语白名单：chat 入口加载内存 Set 一律取自此表；管理端增删/停用即时影响线上校验';

-- ------------------------------------------------------------
-- 5. 反馈闭环（feedback，链路 C）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `track_event` (
    `id`          VARCHAR(32) NOT NULL,
    `record_id`   VARCHAR(32) NOT NULL COMMENT '导诊记录 id',
    `user_id`     VARCHAR(32) NOT NULL COMMENT '用户 id',
    `stage`       VARCHAR(32) NOT NULL COMMENT '枚举：result_view/sim_register/register_success',
    `dept_id`     VARCHAR(32) NULL COMMENT '科室 id',
    `occurred_at` DATETIME    NOT NULL COMMENT '触发时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0,
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_te_record` (`record_id`),
    KEY `idx_te_stage` (`stage`)
) ENGINE=InnoDB COMMENT='埋点三触点，旁路落库；只作漏斗/耗时过程信号，非准确率事实来源';

CREATE TABLE IF NOT EXISTS `filter_log` (
    `id`         VARCHAR(32) NOT NULL,
    `user_id`    VARCHAR(32) NOT NULL,
    `session_id` VARCHAR(32) NOT NULL,
    `word_id`    VARCHAR(32) NOT NULL COMMENT '命中敏感词 id',
    `action`     VARCHAR(32) NOT NULL COMMENT '枚举：blocked/watched',
    `matched_at` DATETIME    NOT NULL COMMENT '命中时间',
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_fl_session` (`session_id`),
    KEY `idx_fl_user_time` (`user_id`, `matched_at`)
) ENGINE=InnoDB COMMENT='敏感词过滤日志，旁路落库不阻塞主流程；窗口计数与按词明细都按 (user_id, matched_at) 查';

CREATE TABLE IF NOT EXISTS `cluster_bucket` (
    `id`             VARCHAR(32)  NOT NULL,
    `rec_dept_id`    VARCHAR(32)  NOT NULL COMMENT '错误方向：推荐科室',
    `actual_dept_id` VARCHAR(32)  NOT NULL COMMENT '错误方向：实际科室',
    `anchor_text`    VARCHAR(512) NOT NULL COMMENT '锚点文本（固定不漂移）',
    `exact_key`      VARCHAR(255) NOT NULL COMMENT '精确归桶键',
    `count`          INT          NOT NULL DEFAULT 1 COMMENT '累计次数',
    `status`         VARCHAR(32)  NOT NULL DEFAULT 'monitoring' COMMENT '枚举：monitoring/pending/approved/rejected/dismissed；rejected=审过否定/dismissed=未审清理；终态可人工修正重审回 pending',
    `deleted`        TINYINT      NOT NULL DEFAULT 0,
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cb_exact` (`exact_key`),
    KEY `idx_cb_status` (`status`)
) ENGINE=InnoDB COMMENT='错误模式聚合桶（MySQL 存事实）；锚点向量在 pgvector cluster_bucket_vec';

CREATE TABLE IF NOT EXISTS `review_task` (
    `id`             VARCHAR(32) NOT NULL,
    `bucket_id`      VARCHAR(32) NOT NULL COMMENT '聚合桶 id',
    `status`         VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '枚举：pending/done',
    `reviewed_by`    VARCHAR(32) NULL COMMENT '审核人',
    `reviewed_at`    DATETIME    NULL COMMENT '审核时间',
    `main_dept_id`   VARCHAR(32) NULL COMMENT '主科室：审核给出的修正目标（人工 approve 唯一写知识库路径）',
    `deleted`        TINYINT     NOT NULL DEFAULT 0,
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rt_bucket` (`bucket_id`)
) ENGINE=InnoDB COMMENT='待审核队列';

CREATE TABLE IF NOT EXISTS `root_cause` (
    `id`         VARCHAR(32) NOT NULL,
    `record_id`  VARCHAR(32) NOT NULL COMMENT '导诊记录 id',
    `causes`     JSON        NULL COMMENT '根因选项 key 数组（小写；字典唯一定义源 = 后端 feedback/enums/RootCauseKey，后端不校验存量值，字典外 key 原样显示并记 WARN）',
    `updated_by` VARCHAR(32) NULL COMMENT '修改人',
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rc_record` (`record_id`)
) ENGINE=InnoDB COMMENT='根因归因，可事后修改，看板按最新聚合';

CREATE TABLE IF NOT EXISTS `root_cause_log` (
    `id`            VARCHAR(32) NOT NULL,
    `record_id`     VARCHAR(32) NOT NULL,
    `causes_before` JSON        NULL COMMENT '修改前',
    `causes_after`  JSON        NULL COMMENT '修改后',
    `updated_by`    VARCHAR(32) NULL,
    `deleted`       TINYINT     NOT NULL DEFAULT 0,
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_rcl_record` (`record_id`)
) ENGINE=InnoDB COMMENT='归因修改历史，每次修改追加不覆盖；纯审计留痕，不参与看板聚合';

-- ------------------------------------------------------------
-- 7. 离线任务（async，链路 B 流水线）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `ingest_task` (
    `id`         VARCHAR(32)  NOT NULL COMMENT 'taskId，幂等与重试以本键为准',
    `doc_id`     VARCHAR(32)  NOT NULL COMMENT '文档 id',
    `stage`      VARCHAR(32)  NOT NULL DEFAULT 'parse' COMMENT '枚举：parse/split/embed/done',
    `status`     VARCHAR(32)  NOT NULL DEFAULT 'running' COMMENT '枚举：running/success/failed（任务表是权威状态）',
    `total`      INT          NULL COMMENT '总数',
    `done`       INT          NULL COMMENT '已处理数',
    `error`      VARCHAR(512) NULL COMMENT '错误信息',
    `deleted`    TINYINT      NOT NULL DEFAULT 0,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_it_doc` (`doc_id`)
) ENGINE=InnoDB COMMENT='离线入库任务表：解析→切分→向量化→入库';

-- ------------------------------------------------------------
-- 初始化数据：管理员账号 + 默认配置（密码请用 BCrypt 重新生成后替换）
-- ------------------------------------------------------------

INSERT INTO `user` (`id`, `username`, `password`, `role`, `nickname`, `status`)
VALUES
('1', 'admin',   '$2a$10$cFq.XOvb3iQqj1AyM/Pv1eZYgA61LoOXza0g/hVpGLp1RPYhg6CCe', 'admin', '系统管理员', 'normal'),
('2', 'admin02', '$2a$10$cFq.XOvb3iQqj1AyM/Pv1eZYgA61LoOXza0g/hVpGLp1RPYhg6CCe', 'admin', '管理员二号', 'normal'),
('3', 'admin03', '$2a$10$cFq.XOvb3iQqj1AyM/Pv1eZYgA61LoOXza0g/hVpGLp1RPYhg6CCe', 'admin', '管理员三号', 'normal')
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;

INSERT INTO `sys_config` (`id`, `config_key`, `config_value`, `remark`) VALUES
('c01', 'cluster.bucket.threshold', '0.85', '聚合归桶阈值'),
('c02', 'guide.upgrade.rounds',      '3',    '追问升级阈值（轮次）'),
('c03', 'guide.low.confidence',      '0.5',  '低置信度阈值'),
('c04', 'term.manual.review',        'true', '术语人工审核开关'),
('c05', 'retrieve.top.k',            '10',   '单路召回 Top-K（向量 / ES 各取）'),
('c06', 'retrieve.top.n',            '5',    '重排后 Top-N（进 Prompt）'),
('c07', 'chat.ask.max.rounds',       '3',    '追问轮数上限（超限强制出低置信度结论）'),
('c08', 'sensitive.window.minutes',      '60', '敏感词违规统计窗口（分钟，滑动窗口）'),
('c09', 'sensitive.banned.warn.count',   '10', '窗口内禁止词命中词次达此值 → 警告'),
('c10', 'sensitive.banned.mute.count',   '30', '窗口内禁止词命中词次达此值 → 禁言'),
('c11', 'sensitive.watch.warn.count',    '25', '窗口内观察词命中词次达此值 → 警告（不禁言）'),
('c12', 'sensitive.mute.minutes',        '60', '禁言时长（分钟，到期自动解除）')
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;
