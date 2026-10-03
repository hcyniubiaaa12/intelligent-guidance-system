package com.guide.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.auth.enums.HealthTagType;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 健康档案标签词表（受控词汇，auth）。
 *
 * <p>患者端选项与后续「档案召回串」共用这一份源；管理端可启用/停用（见单据 05）。
 * 表结构支持启停：{@code enabled} 只作用于患者端选项加载，历史已选项不受影响
 * （照 {@code medical_term} 的模式）。
 *
 * <p>唯一键 {@code uk_ht_term_type (term, type)}：同一个词可以在不同类别各占一行
 * （如「阿司匹林」既是长期用药也可能是过敏物）。
 */
@Getter
@Setter
@TableName("health_tag")
public class HealthTag extends BaseEntity {

    /** 标签词 */
    private String term;

    /** 类别：chronic 慢病 / medication 用药 / allergy 过敏 */
    private HealthTagType type;

    /** 1 启用 / 0 停用 */
    private Integer enabled;
}
