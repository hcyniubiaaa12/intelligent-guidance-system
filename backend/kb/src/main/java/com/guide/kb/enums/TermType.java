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
     * <p>与 {@link TermSource#fromCode} 刻意各写一份遍历：**两者对空值的语义不同**——
     * 术语类型是必填（空值报错），来源筛选是可选的（空值返回 null 表示"不过滤"）。
     * 抽公共方法得把两种语义都塞进签名，收益不抵复杂度；真正的约定只有一条：
     * **同一个枚举内的解析只能有一份**，别在编排层再抄一遍遍历。
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
