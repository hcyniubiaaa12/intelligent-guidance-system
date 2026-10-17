package com.guide.kb.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 医疗术语来源（kb）：LLM 抽取新术语落库默认未启用，受"人工审核"开关控制。
 */
@Getter
@RequiredArgsConstructor
public enum TermSource {

    LLM_EXTRACT("llm_extract"),
    MANUAL("manual");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /**
     * 按 code 解析（前端传的是小写编码值，**不能用 {@code valueOf}**——它匹配的是大写常量名）。
     *
     * <p><b>空值返回 null</b>：管理端列表的「来源」筛选可传可不传，不传即全部来源。
     * 非空但认不出来才报错——静默当成"全部"会让管理员以为筛选生效了，实际看到的是整张表。
     */
    public static TermSource fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (TermSource value : values()) {
            if (value.code.equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "术语来源仅支持 llm_extract/manual");
    }
}
