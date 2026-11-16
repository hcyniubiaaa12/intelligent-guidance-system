package com.guide.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.guide.common.entity.BaseEntity;
import com.guide.memory.enums.CandidateField;
import com.guide.memory.enums.CandidateStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 档案候选（profile_candidate）：模型从对话里听出来的、能落进档案字段的健康事实。
 *
 * <p><b>它不是记忆</b>：不进 {@code user_memory}、不进 prompt、不产生预警——
 * 患者确认之前，它只是一条「我可能听错了」的二手信息。接受后才由
 * {@code HealthProfileService.appendProfileFact} 写进正式档案。
 *
 * <p>{@link #occurredOn} 精确到月（{@code YYYY-MM}），空 = 未填；
 * 用药解读为开始月份、手术解读为发生月份，过敏不使用时间。
 */
@Getter
@Setter
@TableName("profile_candidate")
public class ProfileCandidate extends BaseEntity {

    private String userId;

    /** 目标档案字段：过敏 / 用药 / 手术 */
    private CandidateField field;

    /** 候选值（≤60 字） */
    private String value;

    /** 发生月份（YYYY-MM，空 = 未填） */
    private String occurredOn;

    /** 患者原话出处（可追溯「从哪句话听出来的」） */
    private String sourceQuote;

    /** 抽取置信度 0–1 */
    private BigDecimal confidence;

    /** pending / accepted / rejected / expired */
    private CandidateStatus status;
}
