package com.guide.auth.support;

import com.guide.auth.support.HealthProfileAssembler.Assembly;
import com.guide.auth.support.HealthProfileAssembler.Profile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缝一：健康档案 → 待注入文本的组装（纯函数、零 mock）。
 *
 * <p>口径：只注入「性别 / 出生年（或旧年龄段兜底）/ 过敏 / 用药 / 手术」，
 * **既往病史不再进提示词**（列保留只为迁移与回滚）。
 *
 * <p>时间语义（工单 03）用<b>固定时钟</b>断言，否则「恰好 12 个月」会随系统时间漂移：
 * 固定的"现在"是 2024-06。
 */
class HealthProfileAssemblerTest {

    /** 固定的"现在"：2024-06。所有 12 个月边界断言都以它为基准。 */
    private static final Clock NOW = Clock.fixed(Instant.parse("2024-06-15T00:00:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("全空档案：文本为空串（上层据此退回原链路）")
    void emptyProfileYieldsEmptyText() {
        Assembly assembly = HealthProfileAssembler.assemble(empty(), NOW);

        assertThat(assembly.profileText()).isEmpty();
        assertThat(assembly.textOverflow()).isFalse();
    }

    @Test
    @DisplayName("新字段顺序：性别 → 出生年 → 过敏 → 近期用药 → 近期手术，类别带抬头与时间")
    void assemblesInNewOrderWithLabels() {
        Profile profile = new Profile("male", 1980, "45-59",
                List.of("高血压"), "血糖有点高",
                List.of(new MedicationEntry("二甲双胍", "2024-03", null)), null,
                List.of("青霉素类"), "对海鲜过敏",
                List.of(new SurgeryEntry("阑尾切除术", "2024-01")), null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText())
                .isEqualTo("男、1980 年出生、过敏：青霉素类、对海鲜过敏、"
                        + "近期用药：二甲双胍（2024-03 至今）、近期手术：阑尾切除术（2024-01）");
    }

    @Test
    @DisplayName("既往病史（旧慢病字段）：保留在结构化档案里，但不再进提示词")
    void historyIsNotInjected() {
        Profile profile = new Profile(null, null, null,
                List.of("2型糖尿病"), "血糖偏高",
                null, null, null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText()).isEmpty();
        assertThat(assembly.profileText()).doesNotContain("2型糖尿病", "血糖偏高");
    }

    @Test
    @DisplayName("出生年缺失时回落旧年龄段 label；出生年在则不再显示年龄段")
    void fallsBackToAgeRangeOnlyWhenBirthYearMissing() {
        Profile fallback = new Profile("female", null, "45-59",
                null, null, null, null, null, null, null, null);
        assertThat(HealthProfileAssembler.assemble(fallback, NOW).profileText()).isEqualTo("女、45-59岁");

        Profile withBirthYear = new Profile("female", 1980, "45-59",
                null, null, null, null, null, null, null, null);
        assertThat(HealthProfileAssembler.assemble(withBirthYear, NOW).profileText())
                .isEqualTo("女、1980 年出生")
                .doesNotContain("45-59岁");
    }

    @Test
    @DisplayName("空类别整段不出现：没有过敏就不出现「过敏：」")
    void emptyCategoriesAreOmitted() {
        Profile profile = new Profile("male", 1990, null,
                null, null,
                List.of(new MedicationEntry("阿司匹林", null, null)), null,
                List.of(), "   ",
                null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText()).isEqualTo("男、1990 年出生、近期用药：阿司匹林");
        assertThat(assembly.profileText()).doesNotContain("过敏：", "近期手术：");
    }

    @Test
    @DisplayName("自由文本并入所属类别；空白标签 / 空值条目被跳过")
    void freeTextJoinsItsCategory() {
        Profile profile = new Profile(null, null, null,
                null, null,
                Arrays.asList(new MedicationEntry("  ", "2024-01", null), new MedicationEntry("布洛芬", null, null)), "退烧药",
                Arrays.asList("青霉素类", "  ", null), null,
                null, "去年做过胆囊手术");

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText())
                .isEqualTo("过敏：青霉素类、近期用药：布洛芬、退烧药、近期手术：去年做过胆囊手术");
    }

    @Test
    @DisplayName("超 350 字硬天花板：置告警信号且**不裁剪**（原文完整保留）")
    void overLimitSignalsWithoutTruncating() {
        String longText = "糖".repeat(HealthProfileAssembler.TEXT_MAX + 1);
        Profile profile = new Profile(null, null, null,
                null, longText, null, null, null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        // 既往病史不再注入 ⇒ 用它撑不出超限；用条目值验证天花板
        assertThat(assembly.profileText()).isEmpty();

        Profile longMedication = new Profile(null, null, null,
                null, null,
                List.of(new MedicationEntry("药".repeat(HealthProfileAssembler.TEXT_MAX + 1), null, null)), null,
                null, null, null, null);
        Assembly overflow = HealthProfileAssembler.assemble(longMedication, NOW);

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
                null, null, List.of(new MedicationEntry(text, null, null)), null,
                null, null, null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText()).hasSize(HealthProfileAssembler.TEXT_MAX);
        assertThat(assembly.textOverflow()).isFalse();
    }

    @Test
    @DisplayName("非法枚举编码 / 空值不炸（兜底为忽略）")
    void blankAndInvalidValuesAreSkipped() {
        Profile profile = new Profile("other", null, "999",
                null, null, null, null, Arrays.asList("青霉素类", "  "), "   ", null, null);

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText()).isEqualTo("过敏：青霉素类");
    }

    // ---------------------------------------------------------------- 时间语义

    @Test
    @DisplayName("手术恰好 12 个月：仍算「近期手术」（边界含等号）")
    void surgeryAtExactlyTwelveMonthsIsRecent() {
        Profile profile = surgery("2023-06");

        assertThat(HealthProfileAssembler.assemble(profile, NOW).profileText())
                .isEqualTo("近期手术：阑尾切除术（2023-06）");
    }

    @Test
    @DisplayName("手术超过 12 个月：归入「既往手术」，不再算近期")
    void surgeryBeyondTwelveMonthsIsPast() {
        Profile profile = surgery("2023-05");

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText()).isEqualTo("既往手术：阑尾切除术（2023-05）");
        assertThat(assembly.profileText()).doesNotContain("近期手术");
    }

    @Test
    @DisplayName("未来日期（保存侧已拒绝）：组装侧防御性按近期处理，绝不挪到既往")
    void futureSurgeryIsDefensivelyRecent() {
        Profile profile = surgery("2024-08");

        assertThat(HealthProfileAssembler.assemble(profile, NOW).profileText())
                .isEqualTo("近期手术：阑尾切除术（2024-08）");
    }

    @Test
    @DisplayName("手术时间为空：只写值，按近期处理（时间未填不阻塞保存）")
    void surgeryWithoutDateIsRecentAndValueOnly() {
        Profile profile = surgery(null);

        assertThat(HealthProfileAssembler.assemble(profile, NOW).profileText())
                .isEqualTo("近期手术：阑尾切除术");
    }

    @Test
    @DisplayName("用药结束月份非空：归入「已停用药」，绝不进「近期用药」")
    void stoppedMedicationGoesToStoppedSection() {
        Profile profile = medication(new MedicationEntry("二甲双胍", "2023-01", "2023-06"));

        Assembly assembly = HealthProfileAssembler.assemble(profile, NOW);

        assertThat(assembly.profileText()).isEqualTo("已停用药：二甲双胍（2023-01–2023-06）");
        assertThat(assembly.profileText()).doesNotContain("近期用药");
    }

    @Test
    @DisplayName("只有结束月份、没有开始月份：仍归「已停用药」，只写结束时间")
    void medicationWithOnlyEndDateIsStopped() {
        Profile profile = medication(new MedicationEntry("二甲双胍", null, "2023-06"));

        assertThat(HealthProfileAssembler.assemble(profile, NOW).profileText())
                .isEqualTo("已停用药：二甲双胍（至 2023-06）");
    }

    @Test
    @DisplayName("用药结束月份为空：算「近期用药」（开始月份有则写「至今」）")
    void ongoingMedicationIsRecent() {
        Profile profile = medication(new MedicationEntry("二甲双胍", "2024-03", null));
        assertThat(HealthProfileAssembler.assemble(profile, NOW).profileText())
                .isEqualTo("近期用药：二甲双胍（2024-03 至今）");

        Profile noDate = medication(new MedicationEntry("二甲双胍", null, null));
        assertThat(HealthProfileAssembler.assemble(noDate, NOW).profileText())
                .isEqualTo("近期用药：二甲双胍");
    }

    @Test
    @DisplayName("近期与已停同框：两段都出现，各自只装自己的条目")
    void recentAndStoppedMedicationCoexist() {
        Profile profile = medication(
                new MedicationEntry("二甲双胍", "2024-03", null),
                new MedicationEntry("布洛芬", "2023-01", "2023-02"));

        assertThat(HealthProfileAssembler.assemble(profile, NOW).profileText())
                .isEqualTo("近期用药：二甲双胍（2024-03 至今）、已停用药：布洛芬（2023-01–2023-02）");
    }

    private Profile medication(MedicationEntry... entries) {
        return new Profile(null, null, null,
                null, null, Arrays.asList(entries), null, null, null, null, null);
    }

    private Profile surgery(String occurredOn) {
        return new Profile(null, null, null,
                null, null, null, null, null, null,
                List.of(new SurgeryEntry("阑尾切除术", occurredOn)), null);
    }

    private Profile empty() {
        return new Profile(null, null, null,
                List.of(), null, List.of(), null, List.of(), null, List.of(), null);
    }
}
