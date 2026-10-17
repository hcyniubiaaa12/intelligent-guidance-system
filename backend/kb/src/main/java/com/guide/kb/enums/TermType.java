package com.guide.kb.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 医疗术语类型（kb，白名单）。
 */
@Getter
@RequiredArgsConstructor
public enum TermType {

    /** 部位词 */
    PART("part"),

    /** 症状词 */
    SYMPTOM("symptom");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /**
     * 按 code 解析（前端传的是小写编码值，**不能用 {@code valueOf}**——它匹配的是大写常量名）。
     *
     * <p>解析只此一处：编排层（admin）与领域层（kb）都要把请求体里的字符串变成枚举，
     * 各写一份遍历就会出现两套错误文案、迟早对不上。
     */
    public static TermType fromCode(String code) {
        if (code != null && !code.isBlank()) {
            for (TermType value : values()) {
                if (value.code.equalsIgnoreCase(code.trim())) {
                    return value;
                }
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "术语类型仅支持 part/symptom");
    }
}
