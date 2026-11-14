package com.guide.auth.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 健康档案标签词表的类别（auth）。
 *
 * <p>缩表后患者端只展示三类「多选标签 + 其他自由文本」：过敏史、近期用药、近期手术。
 * 同一份受控词汇表（{@code health_tag}），按 type 分组供患者端选项加载。
 *
 * <p>{@code CHRONIC}（既往病史）**保留但不再展示**：旧档案的慢病标签由迁移工单转成
 * 长期背景记忆，词表行照旧留着以支持回滚；新表单不读它。
 */
@Getter
@RequiredArgsConstructor
public enum HealthTagType {

    /** 慢病（既往病史）：旧类别，保留不再展示 */
    CHRONIC("chronic", "既往病史"),

    /** 近期用药 */
    MEDICATION("medication", "近期用药"),

    /** 过敏史类别 */
    ALLERGY("allergy", "过敏史"),

    /** 近期手术 */
    SURGERY("surgery", "手术史");

    /** 入库编码值（英文小写，见《数据库设计.md》§0） */
    @EnumValue
    private final String code;

    /**
     * 档案分组展示名（既往病史 / 近期用药 / 过敏史 / 手术史）——错误提示、前端分组、词表类别共用同一份口径。
     * 患者端的可读文案在前端另有一份（跨语言边界），此处是**后端唯一出处**。
     */
    private final String label;
}
