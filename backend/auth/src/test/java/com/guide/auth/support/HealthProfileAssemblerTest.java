package com.guide.auth.support;

import com.guide.auth.support.HealthProfileAssembler.Assembly;
import com.guide.auth.support.HealthProfileAssembler.Profile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缝一：健康档案 → 待注入文本的组装（纯函数、零 mock）。
 *
 * <p>缩表后的口径：只注入「性别 / 出生年（或旧年龄段兜底）/ 过敏 / 近期用药 / 近期手术」，
 * **既往病史不再进提示词**（列保留只为迁移与回滚，见 spec §2）。
 *
 * <p>断言只落在外部行为——给定结构化档案，文本长什么样。覆盖降级保证（全空 ⇒ 空串）、
 * 新的字段顺序、空类别整段消失、旧字段不注入、350 字天花板告警（不裁剪）。
 */
class HealthProfileAssemblerTest {

    @Test
    @DisplayName("全空档案：文本为空串（上层据此退回原链路）")
    void emptyProfileYieldsEmptyText() {
        Assembly assembly = HealthProfileAssembler.assemble(empty());

        assertThat(assembly.profileText()).isEmpty();
        assertThat(assembly.textOverflow()).isFalse();
    }

    @Test
    @DisplayName("新字段顺序：性别 → 出生年 → 过敏 → 近期用药 → 近期手术，类别带抬头")
    void assemblesInNewOrderWithLabels() {
        Profile profile = new Profile("male", 1980, "45-59",
                List.of("高血压"), "血糖有点高",
                List.of("二甲双胍"), null,
                List.of("青霉素类"), "对海鲜过敏",
                List.of("阑尾切除术"), null);

        Assembly assembly = HealthProfileAssembler.assemble(profile);

        assertThat(assembly.profileText())
                .isEqualTo("男、1980 年出生、过敏：青霉素类、对海鲜过敏、"
                        + "近期用药：二甲双胍、近期手术：阑尾切除术");
    }

    @Test
    @DisplayName("既往病史（旧慢病字段）：保留在结构化档案里，但不再进提示词")
    void historyIsNotInjected() {
        Profile profile = new Profile(null, null, null,
                List.of("2型糖尿病"), "血糖偏高",
                null, null, null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile);

        assertThat(assembly.profileText()).isEmpty();
        assertThat(assembly.profileText()).doesNotContain("2型糖尿病", "血糖偏高");
    }

    @Test
    @DisplayName("出生年缺失时回落旧年龄段 label；出生年在则不再显示年龄段")
    void fallsBackToAgeRangeOnlyWhenBirthYearMissing() {
        Profile fallback = new Profile("female", null, "45-59",
                null, null, null, null, null, null, null, null);
        assertThat(HealthProfileAssembler.assemble(fallback).profileText()).isEqualTo("女、45-59岁");

        Profile withBirthYear = new Profile("female", 1980, "45-59",
                null, null, null, null, null, null, null, null);
        assertThat(HealthProfileAssembler.assemble(withBirthYear).profileText())
                .isEqualTo("女、1980 年出生")
                .doesNotContain("45-59岁");
    }

    @Test
    @DisplayName("空类别整段不出现：没有过敏就不出现「过敏：」")
    void emptyCategoriesAreOmitted() {
        Profile profile = new Profile("male", 1990, null,
                null, null,
                List.of("阿司匹林"), null,
                List.of(), "   ",
                null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile);

        assertThat(assembly.profileText()).isEqualTo("男、1990 年出生、近期用药：阿司匹林");
        assertThat(assembly.profileText()).doesNotContain("过敏：", "近期手术：");
    }

    @Test
    @DisplayName("自由文本并入所属类别；空白标签被跳过")
    void freeTextJoinsItsCategory() {
        Profile profile = new Profile(null, null, null,
                null, null,
                null, "布洛芬",
                Arrays.asList("青霉素类", "  ", null), null,
                null, "去年做过胆囊手术");

        Assembly assembly = HealthProfileAssembler.assemble(profile);

        assertThat(assembly.profileText())
                .isEqualTo("过敏：青霉素类、近期用药：布洛芬、近期手术：去年做过胆囊手术");
    }

    @Test
    @DisplayName("超 350 字硬天花板：置告警信号且**不裁剪**（原文完整保留）")
    void overLimitSignalsWithoutTruncating() {
        String longText = "糖".repeat(HealthProfileAssembler.TEXT_MAX + 1);
        Profile profile = new Profile(null, null, null,
                null, longText, null, null, null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile);

        // 既往病史不再注入 ⇒ 用它撑不出超限；用自由文本类别验证天花板
        assertThat(assembly.profileText()).isEmpty();

        Profile longMedication = new Profile(null, null, null,
                null, null,
                List.of("药".repeat(HealthProfileAssembler.TEXT_MAX + 1)), null,
                null, null, null, null);
        Assembly overflow = HealthProfileAssembler.assemble(longMedication);

        assertThat(overflow.textOverflow()).isTrue();
        assertThat(overflow.profileText()).hasSize(HealthProfileAssembler.TEXT_MAX + 1 + "近期用药：".length());
        assertThat(overflow.profileText()).contains("药".repeat(HealthProfileAssembler.TEXT_MAX + 1));
    }

    @Test
    @DisplayName("恰好等于 350 字：不告警（天花板是上界）")
    void exactlyLimitDoesNotOverflow() {
        String prefix = "近期用药：";
        String text = "药".repeat(HealthProfileAssembler.TEXT_MAX - prefix.length());
        Profile profile = new Profile(null, null, null,
                null, null, List.of(text), null, null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile);

        assertThat(assembly.profileText()).hasSize(HealthProfileAssembler.TEXT_MAX);
        assertThat(assembly.textOverflow()).isFalse();
    }

    @Test
    @DisplayName("非法枚举编码 / 空值不炸（兜底为忽略）")
    void blankAndInvalidValuesAreSkipped() {
        Profile profile = new Profile("other", null, "999",
                null, null, null, null, Arrays.asList("青霉素类", "  "), "   ", null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile);

        assertThat(assembly.profileText()).isEqualTo("过敏：青霉素类");
    }

    private Profile empty() {
        return new Profile(null, null, null,
                List.of(), null, List.of(), null, List.of(), null, List.of(), null);
    }
}
