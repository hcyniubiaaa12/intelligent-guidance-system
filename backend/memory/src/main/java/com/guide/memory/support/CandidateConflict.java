package com.guide.memory.support;

import com.guide.memory.enums.CandidateField;

import java.util.List;

/**
 * 档案候选与现有档案的冲突判定（纯函数，探索笔记 §9.5-3）。
 *
 * <p>口径：只看**同类别自由文本**里有没有明确否定（无过敏 / 未做过 / 无用药…），
 * 而候选是正向值。两条边界：
 * <ul>
 *   <li>文本里正面提到了候选值 ⇒ 不算冲突（「无过敏史，但对青霉素过敏」）；</li>
 *   <li>别的类别的否定不算（「对青霉素过敏」不会让「无用药」的候选冲突）。</li>
 * </ul>
 *
 * <p><b>只计算、不落库</b>：{@code GET /api/memory} 下发时现算，没有 conflict 列——
 * 档案随时可改，存下来的冲突位会立刻过时。
 */
public final class CandidateConflict {

    private static final List<String> ALLERGY_NEGATIONS = List.of(
            "无过敏", "不过敏", "没有过敏", "未过敏", "否认过敏", "无药物过敏");
    private static final List<String> MEDICATION_NEGATIONS = List.of(
            "无用药", "未用药", "没有用药", "未服药", "无服药", "不吃药", "未吃药", "无长期用药");
    private static final List<String> SURGERY_NEGATIONS = List.of(
            "未做过", "没做过", "无手术", "未手术", "没有手术", "未行手术");

    private CandidateConflict() {
    }

    /**
     * @param field  候选目标字段
     * @param value  候选值（用来排除「文本其实正面提到了它」）
     * @param other  档案里**同一类别**的自由文本（其余两类不参与）
     * @return true = 档案写着相反的表述，页面应标「待确认 · 冲突」
     */
    public static boolean detect(CandidateField field, String value, String other) {
        if (field == null || other == null || other.isBlank()) {
            return false;
        }
        String text = other.trim();
        if (value != null && !value.isBlank() && text.contains(value.trim())) {
            return false;
        }
        for (String negation : negationsOf(field)) {
            if (text.contains(negation)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> negationsOf(CandidateField field) {
        return switch (field) {
            case ALLERGY -> ALLERGY_NEGATIONS;
            case MEDICATION -> MEDICATION_NEGATIONS;
            case SURGERY -> SURGERY_NEGATIONS;
        };
    }
}
