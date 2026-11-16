package com.guide.memory.support;

import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档案候选的纯函数校验：字段白名单、原话、置信度、值长度、发生月份、否定句、去重。
 *
 * <p>为什么值得单独钉：候选是**二手信息**，模型听错一个字就可能让患者确认一个错误的过敏药。
 * 这里每一条丢弃规则都是「宁可不提议」的闸，后续改提示词或换模型都不会把它绕过。
 */
class CandidateValidatorTest {

    private static final YearMonth NOW = YearMonth.of(2026, 11);
    private static final double MIN = 0.6;

    @Test
    @DisplayName("三类合法候选都保留，值与原话去首尾空白，月份空 = 未填")
    void keepsValidCandidates() {
        List<ProfileCandidateDraft> kept = CandidateValidator.validCandidates(List.of(
                draft("allergy", " 青霉素 ", null, "我对青霉素过敏", "0.9"),
                draft("medication", "二甲双胍", "2024-03", "我长期吃二甲双胍", "0.8"),
                draft("surgery", "阑尾切除术", "2023-10", "做过阑尾切除术", "0.95")
        ), MIN, NOW);

        assertThat(kept).extracting(ProfileCandidateDraft::field)
                .containsExactly("allergy", "medication", "surgery");
        assertThat(kept).extracting(ProfileCandidateDraft::value)
                .containsExactly("青霉素", "二甲双胍", "阑尾切除术");
        assertThat(kept.get(0).occurredOn()).isNull();
        assertThat(kept.get(1).occurredOn()).isEqualTo("2024-03");
    }

    @Test
    @DisplayName("字段不在白名单（含空）一律丢弃")
    void dropsUnknownField() {
        List<ProfileCandidateDraft> kept = CandidateValidator.validCandidates(List.of(
                draft("chronic", "糖尿病", null, "我有糖尿病", "0.9"),
                draft("", "糖尿病", null, "我有糖尿病", "0.9"),
                draft(null, "糖尿病", null, "我有糖尿病", "0.9")
        ), MIN, NOW);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("缺原话 / 置信度低于阈值：丢弃（不能证明「从哪句话听出来的」）")
    void dropsMissingQuoteOrLowConfidence() {
        List<ProfileCandidateDraft> kept = CandidateValidator.validCandidates(List.of(
                draft("allergy", "青霉素", null, "", "0.9"),
                draft("allergy", "青霉素", null, "   ", "0.9"),
                draft("allergy", "青霉素", null, "我对青霉素过敏", "0.59"),
                draft("allergy", "青霉素", null, "我对青霉素过敏", null)
        ), MIN, NOW);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("值空白或超过 60 字：丢弃")
    void dropsBlankOrTooLongValue() {
        List<ProfileCandidateDraft> kept = CandidateValidator.validCandidates(List.of(
                draft("allergy", "  ", null, "我对青霉素过敏", "0.9"),
                draft("surgery", "手".repeat(61), null, "做过手术", "0.9")
        ), MIN, NOW);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("发生月份：合法 YYYY-MM 保留；格式非法或未来月份丢弃")
    void validatesOccurredOn() {
        List<ProfileCandidateDraft> kept = CandidateValidator.validCandidates(List.of(
                draft("surgery", "阑尾切除术", "2023-10", "做过阑尾切除术", "0.9"),
                draft("surgery", "胆囊切除术", "2023-1", "做过胆囊切除术", "0.9"),
                draft("surgery", "胃部手术", "2023-13", "做过胃部手术", "0.9"),
                draft("surgery", "疝气手术", "2026-12", "做过疝气手术", "0.9")
        ), MIN, NOW);

        assertThat(kept).extracting(ProfileCandidateDraft::value).containsExactly("阑尾切除术");
    }

    @Test
    @DisplayName("否定句：原话里值紧邻否定词时丢弃（「我没有青霉素过敏」不是过敏）")
    void dropsNegatedCandidate() {
        List<ProfileCandidateDraft> kept = CandidateValidator.validCandidates(List.of(
                draft("allergy", "青霉素", null, "我没有青霉素过敏", "0.9"),
                draft("surgery", "阑尾切除术", null, "我没做过阑尾切除术", "0.9")
        ), MIN, NOW);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("同一次模型输出里重复的 (field,value) 只留第一条")
    void deduplicatesByFieldAndValue() {
        List<ProfileCandidateDraft> kept = CandidateValidator.validCandidates(List.of(
                draft("allergy", "青霉素", null, "我对青霉素过敏", "0.9"),
                draft("allergy", "青霉素", null, "青霉素过敏很多年了", "0.8")
        ), MIN, NOW);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).quote()).isEqualTo("我对青霉素过敏");
    }

    private ProfileCandidateDraft draft(String field, String value, String occurredOn,
                                        String quote, String confidence) {
        return new ProfileCandidateDraft(field, value, occurredOn, quote,
                confidence == null ? null : new BigDecimal(confidence));
    }
}
