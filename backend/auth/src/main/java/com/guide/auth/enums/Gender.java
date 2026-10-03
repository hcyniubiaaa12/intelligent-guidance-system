package com.guide.auth.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 性别（健康档案，选填）。
 *
 * <p>只做与科室差异相关的粗分类，不追求完备——档案是选填的，未填即 null。
 */
@Getter
@RequiredArgsConstructor
public enum Gender {

    MALE("male", "男"),

    FEMALE("female", "女");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /** 展示名（患者端下拉框选项） */
    private final String label;
}
