package com.guide.admin.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户风险视图 DTO（管理端用户管理页）。定义在 admin 是因为它是**跨模块编排的产物**：
 * 计数来自 feedback（filter_log）、处置来自 auth（user_violation）、词面来自 auth（sensitive_word），
 * 任一侧都不该声明这个形状。
 */
public final class UserRiskDTO {

    private UserRiskDTO() {
    }

    /** 窗口内按词聚合的命中明细 */
    @Getter
    @Setter
    public static class WordHitVO {
        private String word;
        private String type;
        /** 命中词次 */
        private long count;
        private LocalDateTime lastAt;
    }

    /** 处置记录（警告/禁言） */
    @Getter
    @Setter
    public static class ViolationVO {
        private String level;
        private String hitType;
        private int hitCount;
        private int windowMinutes;
        private int threshold;
        private LocalDateTime occurredAt;
        private LocalDateTime muteUntil;
    }

    /**
     * 用户违规详情：窗口内「触发了哪些词、各几次」+「被处置过什么」。
     *
     * @param windowMinutes 当前生效的统计窗口——页面上「近 N 分钟」不写死，跟配置走
     */
    @Getter
    @Setter
    public static class DetailVO {
        private String userId;
        private int windowMinutes;
        private long bannedHits;
        private long watchHits;
        private LocalDateTime muteUntil;
        private List<WordHitVO> words;
        private List<ViolationVO> records;
    }
}
