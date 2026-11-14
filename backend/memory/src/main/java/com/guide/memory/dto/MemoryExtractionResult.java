package com.guide.memory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一次抽取的模型输出（只在 memory 模块内流转，不对外暴露）。
 *
 * <p>本工单只消费 preferences / background 两类；模型输出里的 profile_candidates
 * **刻意不在这里出现**——档案候选由工单 09 接入，提前解析会让两个工单各写一版校验。
 *
 * <p>这些 draft 是**未经校验**的原始结果：能不能落库由 {@code MemoryExtractValidator}
 * 统一判定（原话、置信度、否定句、禁止推断）。
 */
public record MemoryExtractionResult(List<PreferenceDraft> preferences, List<BackgroundDraft> backgrounds) {

    public MemoryExtractionResult {
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        backgrounds = backgrounds == null ? List.of() : List.copyOf(backgrounds);
    }

    public static MemoryExtractionResult empty() {
        return new MemoryExtractionResult(List.of(), List.of());
    }

    public boolean isEmpty() {
        return preferences.isEmpty() && backgrounds.isEmpty();
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
}
