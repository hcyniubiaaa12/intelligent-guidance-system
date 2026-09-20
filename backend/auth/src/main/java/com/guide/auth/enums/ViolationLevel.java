package com.guide.auth.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 违规处置级别（auth，敏感词按窗口累计后的处置）。
 * 与 {@link UserStatus} 无关：封禁改 status（登录即拒绝），警告/禁言是这里的处置留痕，
 * 禁言状态本身落在 {@code user.mute_until}。
 */
@Getter
@RequiredArgsConstructor
public enum ViolationLevel {

    /** 警告：仅提示与留痕，不禁言 */
    WARN("warn"),

    /** 禁言：写入 user.mute_until，到期自动解除 */
    MUTE("mute");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /** 按编码值匹配（禁止用 valueOf——入参是小写编码值，见进度.md 已知坑） */
    public static ViolationLevel fromCode(String code) {
        for (ViolationLevel level : values()) {
            if (level.code.equals(code)) {
                return level;
            }
        }
        return null;
    }
}
