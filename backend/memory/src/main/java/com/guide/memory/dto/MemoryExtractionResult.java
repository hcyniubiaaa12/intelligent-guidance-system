package com.guide.memory.dto;

import com.guide.memory.enums.MemoryDedupAction;

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
 *
 * <p><b>判重字段</b>：偏好与背景草稿带 {@code action} / {@code target}（工单 01 的预factor）——
 * 解析层已把缺失、非法值与越界 target 全部降级成 {@link MemoryDedupAction#NEW}，
 * 所以进到这里的草稿一定带着**可信**的 action；但落库层当前**不认**它（照旧全插），
 * 分派留给后续切片（见 {@code .scratch/memory-dedup/issues/02*.md}）。
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

    /**
     * 偏好草稿：key / value / 患者原话 / 置信度 / 判重动作 / 旧清单序号。
     *
     * @param target 旧清单里的序号（从 1 开始），**仅 {@code action=update} 时有值**；
     *               其余动作与「模型没给 / 给了不合法的值」一律为 {@code null}
     */
    public record PreferenceDraft(String key, String value, String quote, BigDecimal confidence,
                                  MemoryDedupAction action, Integer target) {

        /** 兼容不关心判重的调用方（既有测试、校验器内部重建草稿）：等价于 {@code action=new} */
        public PreferenceDraft(String key, String value, String quote, BigDecimal confidence) {
            this(key, value, quote, confidence, MemoryDedupAction.NEW, null);
        }

        public PreferenceDraft {
            action = action == null ? MemoryDedupAction.NEW : action;
        }
    }

    /**
     * 稳定背景草稿。
     *
     * @param type    health / other（其余值按 health 处理）
     * @param validTo 可选结束日期（已结束的长期用药等）
     * @param target  旧清单序号，仅 {@code action=update} 时有值
     */
    public record BackgroundDraft(String key, String value, String type, String quote,
                                  BigDecimal confidence, LocalDate validTo,
                                  MemoryDedupAction action, Integer target) {

        /** 兼容不关心判重的调用方：等价于 {@code action=new} */
        public BackgroundDraft(String key, String value, String type, String quote,
                               BigDecimal confidence, LocalDate validTo) {
            this(key, value, type, quote, confidence, validTo, MemoryDedupAction.NEW, null);
        }

        public BackgroundDraft {
            action = action == null ? MemoryDedupAction.NEW : action;
        }
    }

    /**
     * 档案候选草稿：目标字段 / 值 / 发生月份（YYYY-MM，空 = 未填）/ 原话 / 置信度。
     *
     * <p>{@code field} 是模型给的小写字段名（allergy / medication / surgery），
     * 白名单校验在 {@code CandidateValidator}。
     *
     * <p><b>候选不参与本次判重</b>：它本来就要患者逐条确认，重复项当场可见可拒（spec「不动的地方」），
     * 所以这里没有 {@code action} / {@code target}。
     */
    public record ProfileCandidateDraft(String field, String value, String occurredOn,
                                        String quote, BigDecimal confidence) {
    }
}
