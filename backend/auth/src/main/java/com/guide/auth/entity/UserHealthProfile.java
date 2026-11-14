package com.guide.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.auth.enums.Gender;
import com.guide.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 患者健康档案（用户级，一人一份，选填）。
 *
 * <p>归 auth：它是用户域数据（用户、违规留痕、全局参数都在这里），不是某条会话的一部分。
 * `user_id` 唯一（{@code uk_uhp_user}），所以一人只有一行；一次「整份覆盖写」就是改这一行。
 *
 * <p><b>为什么是独立表而不是给 {@code user} 加列</b>：档案一人一份、可空、字段还会长，
 * 挂上去会把用户表变成档案表（见 spec《数据模型》）。
 *
 * <p>缩表后只展示「性别 / 出生年 / 过敏 / 近期用药 / 近期手术」。
 * {@code ageRange} / {@code history_*} **保留列但不再写、不再展示、不再注入**
 * （{@code ageRange} 仅作出生年缺失时的只读兜底，{@code history_*} 只作迁移与回滚依据）。
 *
 * <p>标签数组以 JSON 文本落库（与 {@code guide_record.rec_top3} / {@code evidence} 同惯例），
 * 所以实体字段是 {@code String}，由 Service 负责与 {@code List<String>} 互转。
 * {@code gender} 用枚举直接落编码值。
 */
@Getter
@Setter
@TableName("user_health_profile")
public class UserHealthProfile extends BaseEntity {

    /** 用户 id（唯一，一人一份） */
    private String userId;

    /** 性别（选填，null = 未填） */
    private Gender gender;

    /** 出生年（1900–当前年；选填，null = 未填） */
    private Integer birthYear;

    /** 旧年龄段区间编码：0-3 / 4-6 / 7-14 / 15-44 / 45-59 / 60+（保留只读兜底，不再写） */
    private String ageRange;

    /** 旧既往病史标签数组（JSON 文本；只作迁移与回滚依据，不再写、不再展示、不再注入） */
    private String historyTags;

    /** 旧既往病史自由文本补充（保留不回写） */
    private String historyOther;

    /** 近期用药标签数组（JSON 文本） */
    private String medicationTags;

    /** 近期用药自由文本补充 */
    private String medicationOther;

    /** 过敏史标签数组（JSON 文本） */
    private String allergyTags;

    /** 过敏史自由文本补充（具体药名等） */
    private String allergyOther;

    /** 近期手术标签数组（JSON 文本） */
    private String surgeryTags;

    /** 近期手术自由文本补充 */
    private String surgeryOther;
}
