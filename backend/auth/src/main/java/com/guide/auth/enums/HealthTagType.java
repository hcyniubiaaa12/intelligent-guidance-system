package com.guide.auth.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 健康档案标签词表的类别（auth）。
 *
 * <p>三个类别对应档案里三种「多选标签 + 其他自由文本」的填写方式：既往病史、长期用药、过敏史。
 * 同一份受控词汇表（{@code health_tag}），按 type 分组供患者端选项加载。
 */
@Getter
@RequiredArgsConstructor
public enum HealthTagType {

    /** 慢病（既往病史） */
    CHRONIC("chronic", "既往病史"),

    /** 长期用药 */
    MEDICATION("medication", "长期用药"),

    /** 过敏史类别 */
    ALLERGY("allergy", "过敏史");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /**
     * 档案分组展示名（既往病史 / 长期用药 / 过敏史）——错误提示、前端分组、词表类别共用同一份口径。
     * 患者端的可读文案在前端另有一份（跨语言边界），此处是**后端唯一出处**。
     */
    private final String label;
}
