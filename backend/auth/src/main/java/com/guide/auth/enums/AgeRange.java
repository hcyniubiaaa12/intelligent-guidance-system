package com.guide.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * 年龄段（健康档案，选填）——**区间**枚举，不是具体岁数。
 *
 * <p>具体岁数做检索锚点没有意义，"儿童 / 老年"才有（见 spec：档案的价值在年龄相关的科室差异）。
 *
 * <p><b>刻意不标 {@code @EnumValue}、也不登记进 {@code EnumConventionTest}</b>：
 * 该约定要求编码值只能是「常量名小写 + 仅小写字母/下划线」，而年龄段的编码天然含数字与连字符
 * （{@code 0-3} / {@code 60+}），无法满足。这里把它当作**受控字符串取值**处理：实体字段是
 * {@code String ageRange}，Service 用它做白名单校验、Controller 用它下发下拉选项，避免在
 * 代码里散落字面量。
 */
@Getter
@RequiredArgsConstructor
public enum AgeRange {

    AGE_0_3("0-3", "0-3岁"),
    AGE_4_6("4-6", "4-6岁"),
    AGE_7_14("7-14", "7-14岁"),
    AGE_15_44("15-44", "15-44岁"),
    AGE_45_59("45-59", "45-59岁"),
    AGE_60_PLUS("60+", "60岁及以上");

    /** 入库/传输编码值（区间文字，如 {@code 0-3}、{@code 60+}） */
    private final String code;

    /** 展示名（患者端下拉框选项） */
    private final String label;

    /** 编码值是否合法（null 视为合法——选填，未填即空） */
    public static boolean isValidCode(String code) {
        return code == null || code.isBlank() || findByCode(code).isPresent();
    }

    public static Optional<AgeRange> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(r -> r.code.equals(code)).findFirst();
    }
}
