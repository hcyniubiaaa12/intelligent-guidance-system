package com.guide.feedback.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 聚合桶状态（feedback，链路 C）：monitoring 累计中，达到升级阈值转 pending 进待审队列。
 */
@Getter
@RequiredArgsConstructor
public enum BucketStatus {

    /** 监控中，未达升级阈值 */
    MONITORING("monitoring"),

    /** 已升级，待人工审核（已生成 review_task） */
    PENDING("pending"),

    /** 审核通过，已回流知识库 */
    APPROVED("approved"),

    /** 审核驳回 */
    REJECTED("rejected"),

    /** 人工忽略 */
    DISMISSED("dismissed");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /** 终态三种：修正重审的入口就是这张表。管理端筛选不选时默认就是它 */
    public static final List<BucketStatus> TERMINAL = List.of(APPROVED, REJECTED, DISMISSED);

    /**
     * 按入库编码取值。管理端筛选参数走 URL，拿的是 {@code approved} 而不是 {@code APPROVED}，
     * 所以不能靠 Spring 默认的枚举绑定（它只认常量名）。
     *
     * @return 认不出来的返回空——由调用方决定是拒绝还是忽略，别在这里替它猜
     */
    public static Optional<BucketStatus> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (BucketStatus status : values()) {
            if (status.code.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
