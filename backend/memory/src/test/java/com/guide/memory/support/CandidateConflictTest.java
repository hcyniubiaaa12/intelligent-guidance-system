package com.guide.memory.support;

import com.guide.memory.enums.CandidateField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选与档案的冲突判定（纯函数）。
 *
 * <p>口径：只看**同类别自由文本**里是否出现明确否定（无过敏 / 未做过 / 无用药…），
 * 且文本没有正面提到候选值——「无过敏史，但对青霉素过敏」不算冲突。
 * 生成时与列表下发时共用这一份判定（探索笔记 §9.5-3）。
 */
class CandidateConflictTest {

    @Test
    @DisplayName("过敏：同类别自由文本写「无过敏 / 不过敏 / 没有过敏」时冲突")
    void detectsAllergyNegation() {
        assertThat(CandidateConflict.detect(CandidateField.ALLERGY, "青霉素", "无过敏史")).isTrue();
        assertThat(CandidateConflict.detect(CandidateField.ALLERGY, "海鲜", "不过敏")).isTrue();
        assertThat(CandidateConflict.detect(CandidateField.ALLERGY, "海鲜", "没有过敏")).isTrue();
    }

    @Test
    @DisplayName("用药：写「无用药 / 未服药 / 没有用药」时冲突")
    void detectsMedicationNegation() {
        assertThat(CandidateConflict.detect(CandidateField.MEDICATION, "二甲双胍", "无用药")).isTrue();
        assertThat(CandidateConflict.detect(CandidateField.MEDICATION, "降压药", "未服药")).isTrue();
        assertThat(CandidateConflict.detect(CandidateField.MEDICATION, "布洛芬", "没有用药")).isTrue();
    }

    @Test
    @DisplayName("手术：写「未做过 / 没做过 / 无手术」时冲突")
    void detectsSurgeryNegation() {
        assertThat(CandidateConflict.detect(CandidateField.SURGERY, "阑尾切除术", "未做过")).isTrue();
        assertThat(CandidateConflict.detect(CandidateField.SURGERY, "胆囊切除术", "没做过")).isTrue();
        assertThat(CandidateConflict.detect(CandidateField.SURGERY, "胃部手术", "无手术史")).isTrue();
    }

    @Test
    @DisplayName("文本正面提到候选值：不算冲突（「无过敏史，但对青霉素过敏」）")
    void valueMentionedIsNotConflict() {
        assertThat(CandidateConflict.detect(CandidateField.ALLERGY, "青霉素", "无过敏史，但对青霉素过敏"))
                .isFalse();
    }

    @Test
    @DisplayName("空文本 / 无否定词 / 其他类别的文本都不算冲突")
    void blankOrUnrelatedIsNotConflict() {
        assertThat(CandidateConflict.detect(CandidateField.ALLERGY, "青霉素", null)).isFalse();
        assertThat(CandidateConflict.detect(CandidateField.ALLERGY, "青霉素", "  ")).isFalse();
        assertThat(CandidateConflict.detect(CandidateField.ALLERGY, "青霉素", "对花粉过敏")).isFalse();
        assertThat(CandidateConflict.detect(CandidateField.MEDICATION, "二甲双胍", "对青霉素过敏")).isFalse();
    }
}
