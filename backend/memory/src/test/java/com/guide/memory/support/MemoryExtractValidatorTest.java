package com.guide.memory.support;

import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抽取校验的纯函数单测：不 mock 模型、不碰数据库。
 *
 * <p>每条规则一个用例——医疗场景里「记错了还一直用」比「没记住」更危险，
 * 过滤规则失效的代价远高于多丢一条记忆。
 */
class MemoryExtractValidatorTest {

    private static final double MIN = 0.6;
    private static final BigDecimal OK = new BigDecimal("0.9");

    @Test
    @DisplayName("合法偏好与背景都保留：带原话、置信度达标、类型按模型声明归一")
    void keepsValidDrafts() {
        List<PreferenceDraft> preferences = MemoryExtractValidator.validPreferences(List.of(
                new PreferenceDraft("回答方式", "讲慢一点", "我希望你讲慢一点", OK)), MIN);
        List<BackgroundDraft> backgrounds = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("慢病", "2型糖尿病", "health", "我有2型糖尿病", OK, null),
                new BackgroundDraft("生活", "长期夜班", "other", "我一直上夜班", OK, null)), MIN);

        assertThat(preferences).extracting(PreferenceDraft::value).containsExactly("讲慢一点");
        assertThat(backgrounds).extracting(BackgroundDraft::value).containsExactly("2型糖尿病", "长期夜班");
        assertThat(backgrounds).extracting(BackgroundDraft::type).containsExactly("health", "other");
    }

    @Test
    @DisplayName("缺患者原话：直接丢弃，不修补、不拿其它字段顶上")
    void dropsMissingQuote() {
        List<PreferenceDraft> kept = MemoryExtractValidator.validPreferences(List.of(
                new PreferenceDraft("回答方式", "讲慢一点", "  ", OK),
                new PreferenceDraft("回答方式", "讲慢一点", null, OK)), MIN);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("置信度低于阈值：丢弃；等于阈值：保留（边界按 >= 判）")
    void dropsLowConfidence() {
        List<PreferenceDraft> kept = MemoryExtractValidator.validPreferences(List.of(
                new PreferenceDraft("回答方式", "讲慢一点", "我希望慢一点", new BigDecimal("0.59")),
                new PreferenceDraft("称呼", "叫我老王", "叫我老王", new BigDecimal("0.6")),
                new PreferenceDraft("长度", "短一点", "短一点", null)), MIN);

        assertThat(kept).extracting(PreferenceDraft::value).containsExactly("叫我老王");
    }

    @Test
    @DisplayName("key / value 超长：丢弃（落库列宽与页面展示共同决定的上限）")
    void dropsOverlongFields() {
        List<PreferenceDraft> kept = MemoryExtractValidator.validPreferences(List.of(
                new PreferenceDraft("这是一个超过八个字的键", "慢一点", "我希望慢一点", OK),
                new PreferenceDraft("回答方式", "一".repeat(61), "我希望讲得慢一点", OK)), MIN);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("否定句丢弃：否定词紧邻 value（前「没有」/ 后「不过敏」）")
    void dropsNegatedStatements() {
        List<BackgroundDraft> kept = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("慢病", "糖尿病", "health", "我没有糖尿病", OK, null),
                new BackgroundDraft("过敏", "青霉素", "health", "我对青霉素不过敏", OK, null),
                new BackgroundDraft("手术", "阑尾切除术", "health", "我没做过阑尾切除术", OK, null)), MIN);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("否定词在别处不误伤：同一句里「以前没有…现在有…」必须保留后者")
    void negationElsewhereDoesNotDrop() {
        List<BackgroundDraft> kept = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("慢病", "糖尿病", "health", "我以前没有别的病，现在有糖尿病", OK, null)), MIN);

        assertThat(kept).extracting(BackgroundDraft::value).containsExactly("糖尿病");
    }

    @Test
    @DisplayName("禁止推断：原话里没有 value、也没有稳定标记 → 丢弃（吃二甲双胍 ≠ 有糖尿病）")
    void dropsInference() {
        List<BackgroundDraft> kept = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("慢病", "2型糖尿病", "health", "每天吃二甲双胍两片", OK, null)), MIN);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("稳定标记允许原话与 value 不完全一致（患者说「一直血糖高」，记「高血糖」）")
    void stableMarkerAllowsParaphrase() {
        List<BackgroundDraft> kept = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("慢病", "高血糖", "health", "我一直血糖偏高", OK, null)), MIN);

        assertThat(kept).extracting(BackgroundDraft::value).containsExactly("高血糖");
    }

    @Test
    @DisplayName("当前主诉不作为长期事实：纯症状值 + 无稳定标记 → 丢弃")
    void dropsCurrentComplaint() {
        List<BackgroundDraft> kept = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("症状", "胸闷", "health", "今天胸口有点闷", OK, null),
                new BackgroundDraft("症状", "头痛", "health", "这会儿头很痛", OK, null)), MIN);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("症状值带稳定标记则保留：它是长期背景，不是本次主诉")
    void keepsSymptomWithStableMarker() {
        List<BackgroundDraft> kept = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("慢病", "慢性头痛", "health", "我有慢性头痛", OK, null)), MIN);

        assertThat(kept).extracting(BackgroundDraft::value).containsExactly("慢性头痛");
    }

    @Test
    @DisplayName("同批去重：同一 (type,key,value) 只留第一条")
    void dedupesWithinBatch() {
        List<PreferenceDraft> kept = MemoryExtractValidator.validPreferences(List.of(
                new PreferenceDraft("回答方式", "讲慢一点", "我希望讲慢一点", OK),
                new PreferenceDraft("回答方式", "讲慢一点", "讲慢一点吧", OK)), MIN);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).quote()).isEqualTo("我希望讲慢一点");
    }

    @Test
    @DisplayName("背景兜底：过敏 / 手术 / 服药类事实即使被模型塞进 background 也一律丢弃（应走档案候选）")
    void dropsProfileFactsFromBackground() {
        List<BackgroundDraft> kept = MemoryExtractValidator.validBackgrounds(List.of(
                new BackgroundDraft("过敏", "青霉素", "health", "我青霉素过敏", OK, null),
                new BackgroundDraft("手术", "阑尾切除术", "health", "我做过阑尾切除术", OK, null),
                new BackgroundDraft("用药", "二甲双胍", "health", "我长期吃二甲双胍", OK, null),
                new BackgroundDraft("慢病", "高血压", "health", "我有高血压", OK, null)), MIN);

        // 宁可不记，也不把档案事实写进背景——同一事实只存一处，错位置比不记更危险
        assertThat(kept).extracting(BackgroundDraft::value).containsExactly("高血压");
    }

    @Test
    @DisplayName("空 key / 空 value：丢弃（模型给了空壳也按无效处理）")
    void dropsBlankFields() {
        List<PreferenceDraft> kept = MemoryExtractValidator.validPreferences(List.of(
                new PreferenceDraft("", "讲慢一点", "我希望讲慢一点", OK),
                new PreferenceDraft("回答方式", "  ", "我希望讲慢一点", OK)), MIN);

        assertThat(kept).isEmpty();
    }
}
