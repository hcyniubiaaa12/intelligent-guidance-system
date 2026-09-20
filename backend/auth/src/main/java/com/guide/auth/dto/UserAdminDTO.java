package com.guide.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 管理端用户/敏感词 DTO（链路 D 配套：用户管理页）。
 */
public final class UserAdminDTO {

    private UserAdminDTO() {
    }

    /** 用户行（列表） */
    @Getter
    @Setter
    public static class UserVO {
        private String id;
        private String username;
        private String nickname;
        private String role;
        private String status;
        private LocalDateTime createdAt;

        /** 禁言截止；null = 未禁言（已到期的由服务端归零，前端不必再比时间） */
        private LocalDateTime muteUntil;

        /**
         * 窗口内触发统计与处置。
         *
         * <p>**由 admin 侧编排填充**：计数来自 feedback.filter_log、处置次数来自 user_violation，
         * auth 不依赖任何一方（依赖方向 feedback → chat → auth）。字段挂在行 VO 上是为了
         * 让用户列表一次请求拿全，不需要前端再拿用户 id 去逐个查。
         */
        private HitStat hits;
    }

    /** 窗口内触发统计（含窗口长度，前端据此显示「近 N 分钟」而不是写死文案） */
    @Getter
    @Setter
    public static class HitStat {
        private int windowMinutes;
        /** 禁止词命中词次 */
        private int bannedHits;
        /** 观察词命中词次 */
        private int watchHits;
        /** 窗口内警告次数 */
        private int warnCount;
    }

    /** 敏感词行（列表） */
    @Getter
    @Setter
    public static class WordVO {
        private String id;
        private String word;
        private String type;
        private Integer hitCount;
        private Integer enabled;
        private LocalDateTime createdAt;
    }

    /** 敏感词新增 */
    @Getter
    @Setter
    public static class WordAdd {
        @NotBlank(message = "敏感词不能为空")
        private String word;

        /** banned / watch，缺省 banned */
        private String type;
    }

    /** 敏感词批量导入：txt 一行一词，自动去重（总体架构 6.2） */
    @Getter
    @Setter
    public static class WordImport {
        @NotBlank(message = "导入内容不能为空")
        private String text;

        /** 本批导入词的类型，缺省 banned */
        private String type;
    }

    /** 批量导入结果 */
    @Getter
    @Setter
    public static class ImportResult {
        private int imported;
        private int skipped;
    }
}
