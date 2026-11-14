package com.guide.memory.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 档案候选的目标字段（profile_candidate.field）。
 *
 * <p>只允许三类**能落进健康档案字段**的健康事实：过敏、用药、手术。
 * 慢病、生活背景等属于稳定背景记忆，不走候选（见 spec §1「同一事实只存一处」）。
 */
@Getter
@RequiredArgsConstructor
public enum CandidateField {

    ALLERGY("allergy", "过敏史"),
    MEDICATION("medication", "用药"),
    SURGERY("surgery", "手术");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /** 展示名（患者端「待确认」段、错误文案共用这一份后端口径） */
    private final String label;

    /** 按入库编码值查枚举；未知编码返回 null（调用方按非法处理，不做默认猜测） */
    public static CandidateField fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (CandidateField field : values()) {
            if (field.code.equals(code)) {
                return field;
            }
        }
        return null;
    }
}
