package com.guide.chat.support;

import com.guide.auth.support.HealthProfileAssembler;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.common.model.ChunkHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档案硬警报的词面规则（纯函数，零 mock）。
 *
 * <p>要守住的三件事：
 * <ol>
 *   <li><b>只对已知冲突报警</b>——档案值没在召回正文里出现就不产生警报；召回正文为空、档案为空同样是空结果。
 *       警报多了会被患者读成"系统在乱喊"，比不报更糟。</li>
 *   <li><b>「青霉素类」要能命中「青霉素」</b>——患者填的是药品类别，知识片段写的是具体药名。
 *       只做整串包含会漏掉最常见的那类过敏。</li>
 *   <li><b>有上限、有去重</b>——同一档案值跨多条片段只报一次；最多 3 条，过敏优先于用药、手术。</li>
 * </ol>
 */
class ProfileAlertDetectorTest {

    /** 固定时钟：近期手术的 12 个月窗口不能随系统时间漂移 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("过敏命中：档案「青霉素类」在片段写「青霉素」时也要报警（去「类」后包含）")
    void allergyHitIgnoresCategorySuffix() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of("青霉素类"), null, List.of(), null, List.of(), null),
                chunks("青霉素皮试阳性者禁用阿莫西林。"), CLOCK);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).level()).isEqualTo("hard");
        assertThat(alerts.get(0).anchor()).isEqualTo("field:allergy");
        assertThat(alerts.get(0).text())
                .as("文案必须说清与哪条档案冲突，不能只写「注意」")
                .contains("过敏史")
                .contains("青霉素类")
                .contains("青霉素");
    }

    @Test
    @DisplayName("过敏命中：自由文本按顿号/逗号切词后逐词匹配")
    void allergyOtherIsSplitAndMatched() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of(), "对海鲜过敏，磺胺类药物过敏",
                        List.of(), null, List.of(), null),
                chunks("磺胺类药物可能引起皮疹。"), CLOCK);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).text()).contains("磺胺类药物");
    }

    @Test
    @DisplayName("用药命中：仍在用的药出现在片段里即报警")
    void medicationHit() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of(), null,
                        List.of(new MedicationEntry("二甲双胍", "2024-03", null)), null,
                        List.of(), null),
                chunks("二甲双胍需注意肾功能。"), CLOCK);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).anchor()).isEqualTo("field:medication");
        assertThat(alerts.get(0).text()).contains("近期用药").contains("二甲双胍");
    }

    @Test
    @DisplayName("用药命中：已停用药不进「近期用药」警报（避免把旧药当正在服用）")
    void stoppedMedicationIsNotAlerted() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of(), null,
                        List.of(new MedicationEntry("二甲双胍", "2020-01", "2020-06")), null,
                        List.of(), null),
                chunks("二甲双胍需注意肾功能。"), CLOCK);

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("手术命中：12 个月内的手术出现在片段里即报警")
    void surgeryHit() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of(), null, List.of(), null,
                        List.of(new SurgeryEntry("阑尾切除术", "2026-05")), null),
                chunks("阑尾切除术后患者出现腹痛需排除粘连。"), CLOCK);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).anchor()).isEqualTo("field:surgery");
        assertThat(alerts.get(0).text()).contains("手术史").contains("阑尾切除术");
    }

    @Test
    @DisplayName("手术命中：超过 12 个月的既往手术不报（口径与档案文本的「近期/既往」一致）")
    void pastSurgeryIsNotAlerted() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of(), null, List.of(), null,
                        List.of(new SurgeryEntry("阑尾切除术", "2020-01")), null),
                chunks("阑尾切除术后患者出现腹痛需排除粘连。"), CLOCK);

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("无冲突不产警报：档案有过敏、片段完全没提")
    void noMentionNoAlert() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of("青霉素类"), "对海鲜过敏",
                        List.of(new MedicationEntry("二甲双胍", null, null)), null,
                        List.of(new SurgeryEntry("阑尾切除术", "2026-05")), null),
                chunks("上腹部疼痛可考虑胃炎。"), CLOCK);

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("空输入不产警报：无档案 / 无召回片段")
    void emptyInputsYieldNothing() {
        assertThat(ProfileAlertDetector.detect(null, chunks("青霉素"), CLOCK)).isEmpty();
        assertThat(ProfileAlertDetector.detect(
                profile(List.of("青霉素类"), null, List.of(), null, List.of(), null),
                List.of(), CLOCK)).isEmpty();
        assertThat(ProfileAlertDetector.detect(
                profile(List.of("青霉素类"), null, List.of(), null, List.of(), null),
                null, CLOCK)).isEmpty();
    }

    @Test
    @DisplayName("同一档案值跨多条片段只产一条（去重）")
    void sameValueDedupedAcrossChunks() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of("青霉素类"), null, List.of(), null, List.of(), null),
                chunks("青霉素皮试阳性者禁用。", "青霉素类抗生素需慎用。"), CLOCK);

        assertThat(alerts).hasSize(1);
    }

    @Test
    @DisplayName("上限 3 条：过敏优先于用药与手术")
    void cappedAtThreeWithAllergyFirst() {
        List<ProfileAlertDetector.Alert> alerts = ProfileAlertDetector.detect(
                profile(List.of("青霉素类", "磺胺类", "头孢类", "海鲜类"), null,
                        List.of(new MedicationEntry("二甲双胍", null, null)), null,
                        List.of(new SurgeryEntry("阑尾切除术", "2026-05")), null),
                chunks("青霉素、磺胺、头孢、海鲜、二甲双胍、阑尾切除术都在这一段里。"), CLOCK);

        assertThat(alerts).hasSize(3);
        assertThat(alerts).allSatisfy(a -> assertThat(a.anchor()).isEqualTo("field:allergy"));
    }

    // ---------------------------------------------------------------- 夹具

    private HealthProfileAssembler.Profile profile(List<String> allergyTags, String allergyOther,
                                                    List<MedicationEntry> medications, String medicationOther,
                                                    List<SurgeryEntry> surgeries, String surgeryOther) {
        return new HealthProfileAssembler.Profile(
                null, null, null, List.of(), null,
                medications, medicationOther,
                allergyTags, allergyOther,
                surgeries, surgeryOther);
    }

    private List<ChunkHit> chunks(String... contents) {
        return java.util.stream.IntStream.range(0, contents.length)
                .mapToObj(i -> new ChunkHit("c" + i, "d1", "片段" + i, contents[i], 0.9))
                .toList();
    }
}
