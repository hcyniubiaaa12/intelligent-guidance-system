package com.guide.auth.support;

import com.guide.auth.support.HealthProfileAssembler.Assembly;
import com.guide.auth.support.HealthProfileAssembler.Profile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缝一：健康档案 → 两段文本的组装（纯函数、零 mock）。
 *
 * <p>断言只落在外部行为——给定结构化档案与词表，两段文本各是什么。覆盖降级保证（全空 ⇒ 两空串）、
 * 标签优先、自由文本命中 / 未命中分流、350 字天花板告警（不裁剪）。
 */
class HealthProfileAssemblerTest {

    private static final Set<String> VOCAB = Set.of("高血压", "2型糖尿病", "青霉素类", "海鲜");

    @Test
    @DisplayName("全空档案：两段均为空串（上层据此退回原链路）")
    void emptyProfileYieldsEmptyBoth() {
        Assembly assembly = HealthProfileAssembler.assemble(empty(), VOCAB);

        assertThat(assembly.profileText()).isEmpty();
        assertThat(assembly.recallQuery()).isEmpty();
        assertThat(assembly.textOverflow()).isFalse();
    }

    @Test
    @DisplayName("标签全进两段：按「性别 / 年龄段 / 既往病史 / 长期用药 / 过敏史」顺序拼接")
    void tagsGoIntoBothSegmentsInOrder() {
        Profile profile = new Profile("male", "45-59",
                List.of("高血压", "2型糖尿病"), null,
                List.of("二甲双胍"), null,
                List.of("青霉素类"), null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, VOCAB);

        assertThat(assembly.profileText()).isEqualTo("男、45-59岁、高血压、2型糖尿病、二甲双胍、青霉素类");
        assertThat(assembly.recallQuery()).isEqualTo("男、45-59岁、高血压、2型糖尿病、二甲双胍、青霉素类");
    }

    @Test
    @DisplayName("自由文本命中词表：进两段；未命中口语：只进待注入文本、不进检索串")
    void freeTextSplitsByVocabularyHit() {
        Profile profile = new Profile(null, null,
                List.of("高血压"), "血糖有点高",          // 未命中词表 → 只进 text
                null, null,
                null, "我对青霉素类过敏");                 // 含词表词 → 进两段

        Assembly assembly = HealthProfileAssembler.assemble(profile, VOCAB);

        assertThat(assembly.profileText()).isEqualTo("高血压、血糖有点高、我对青霉素类过敏");
        // 检索串：标签「高血压」+ 命中的自由文本；口语"血糖有点高"被丢在检索串之外
        assertThat(assembly.recallQuery()).isEqualTo("高血压、我对青霉素类过敏");
        assertThat(assembly.recallQuery()).doesNotContain("血糖有点高");
    }

    @Test
    @DisplayName("词表为空：自由文本一律视为未命中，只进待注入文本")
    void emptyVocabularyKeepsFreeTextOutOfQuery() {
        Profile profile = new Profile(null, null, List.of(), "血糖有点高",
                null, null, null, "海鲜过敏");

        Assembly assembly = HealthProfileAssembler.assemble(profile, Set.of());

        assertThat(assembly.profileText()).isEqualTo("血糖有点高、海鲜过敏");
        assertThat(assembly.recallQuery()).isEmpty();
    }

    @Test
    @DisplayName("超 350 字硬天花板：置告警信号且**不裁剪**（原文完整保留）")
    void overLimitSignalsWithoutTruncating() {
        String longText = "糖".repeat(HealthProfileAssembler.TEXT_MAX + 1);
        Profile profile = new Profile(null, null, List.of(), longText, null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, VOCAB);

        assertThat(assembly.textOverflow()).isTrue();
        assertThat(assembly.profileText()).hasSize(HealthProfileAssembler.TEXT_MAX + 1);
        assertThat(assembly.profileText()).isEqualTo(longText);
    }

    @Test
    @DisplayName("恰好等于 350 字：不告警（天花板是上界）")
    void exactlyLimitDoesNotOverflow() {
        String text = "糖".repeat(HealthProfileAssembler.TEXT_MAX);
        Profile profile = new Profile(null, null, List.of(), text, null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, VOCAB);

        assertThat(assembly.textOverflow()).isFalse();
    }

    @Test
    @DisplayName("空标签 / 空白自由文本被跳过；非法枚举编码不炸（兜底为忽略）")
    void blankAndInvalidValuesAreSkipped() {
        Profile profile = new Profile("other", "999",
                java.util.Arrays.asList("高血压", "  ", null), "   ",
                null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, VOCAB);

        assertThat(assembly.profileText()).isEqualTo("高血压");
        assertThat(assembly.recallQuery()).isEqualTo("高血压");
    }

    private Profile empty() {
        return new Profile(null, null, List.of(), null, List.of(), null, List.of(), null);
    }
}
