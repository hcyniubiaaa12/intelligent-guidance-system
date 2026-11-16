package com.guide.memory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一次抽取的模型输出（只在 memory 模块内流转，不对外暴露）。
 *
 * <p>三类输出各走各的落点：偏好与稳定背景进 {@code user_memory}，
 * {@code profile_candidates}（过敏 / 用药 / 手术）进 {@code profile_candidate} 等患者确认——
 * **候选不写记忆**，见 spec §1「同一事实只存一处」。
 *
 * <p>这些 draft 是**未经校验**的原始结果：能不能落库由
 * {@code MemoryExtractValidator}（偏好/背景）与 {@code CandidateValidator}（候选）统一判定。
 */
public record MemoryExtractionResult(List<PreferenceDraft> preferences, List<BackgroundDraft> backgrounds,
                                     List<ProfileCandidateDraft> candidates) {

    public MemoryExtractionResult {
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        backgrounds = backgrounds == null ? List.of() : List.copyOf(backgrounds);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 兼容只关心偏好/背景的调用方（既有测试与工单边界） */
    public MemoryExtractionResult(List<PreferenceDraft> preferences, List<BackgroundDraft> backgrounds) {
        this(preferences, backgrounds, List.of());
    }

    public static MemoryExtractionResult empty() {
        return new MemoryExtractionResult(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return preferences.isEmpty() && backgrounds.isEmpty() && candidates.isEmpty();
    }

    /** 偏好草稿：key / value / 患者原话 / 置信度 */
    public record PreferenceDraft(String key, String value, String quote, BigDecimal confidence) {
    }

    /**
     * 稳定背景草稿。
     *
     * @param type    health / other（其余值按 health 处理）
     * @param validTo 可选结束日期（已结束的长期用药等）
     */
    public record BackgroundDraft(String key, String value, String type, String quote,
                                  BigDecimal confidence, LocalDate validTo) {
    }

    /**
     * 档案候选草稿：目标字段 / 值 / 发生月份（YYYY-MM，空 = 未填）/ 原话 / 置信度。
     *
     * <p>{@code field} 是模型给的小写字段名（allergy / medication / surgery），
     * 白名单校验在 {@code CandidateValidator}。
     */
    public record ProfileCandidateDraft(String field, String value, String occurredOn,
                                        String quote, BigDecimal confidence) {
    }
}
