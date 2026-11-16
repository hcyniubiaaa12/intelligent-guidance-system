package com.guide.chat.support;

import com.guide.auth.support.HealthProfileAssembler;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.common.model.ChunkHit;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 健康档案 → 硬警报（**纯函数**：不碰 Spring、不碰数据库、不依赖任何模型输出）。
 *
 * <p>为什么要有这一层：提示词里已经写了「档案与本次症状相关时必须说出来」，但那是**模型的自愿行为**——
 * 模型漏掉、或把一段提到患者过敏药的片段照常推荐出来，患者看不到任何提示。这里的规则是确定性的兜底：
 * 只要召回正文里出现了档案里的过敏 / 近期用药 / 近期手术词面，就产出一条患者可见的警报。
 *
 * <p>三条口径：
 * <ol>
 *   <li><b>只报已知冲突</b>：档案值必须真的出现在召回正文里。宁可少报，不可乱喊——
 *       警报多了，患者会把它当成背景噪音，真正危险的那条也被淹掉。</li>
 *   <li><b>「青霉素类」要能命中「青霉素」</b>：患者填的是药品类别，片段写的是具体药名。
 *       只做整串包含会漏掉最常见的一类过敏；但反向用单字匹配又会把「头」命中「头痛」，
 *       所以只做「档案值（去「类」/去「对…过敏」外壳）被正文包含」，且参与匹配的串至少 2 个字。</li>
 *   <li><b>有上限、有去重</b>：同一档案值跨多条片段只产一条；最多 {@value #MAX_ALERTS} 条，
 *       过敏优先于用药、手术（过敏是「再用可能出事」，优先级最高）。</li>
 * </ol>
 *
 * <p>「近期」的口径与 {@link HealthProfileAssembler} 一致：用药 {@code endedOn} 为空才算近期；
 * 手术发生月与当前月相差 ≤ 12 个月（或时间未填）才算近期。时间来源是<b>可注入的 {@link Clock}</b>，
 * 否则「恰好 12 个月」的断言会随系统时间漂移。
 *
 * <p><b>只写不读</b>：警报随结论下发、并进证据快照；回放读快照，绝不回查当前档案。
 */
@Slf4j
public final class ProfileAlertDetector {

    /** 一次结论最多下发的警报条数：再多就会把结论卡顶部淹掉 */
    public static final int MAX_ALERTS = 3;

    /** 过敏 / 用药 / 手术：自由文本里可能用顿号、逗号、分号并列多个值 */
    private static final String VALUE_SEPARATOR = "[、,，;；]";

    /** 手术「近期」的时间窗，与 {@link HealthProfileAssembler} 保持同一口径 */
    private static final int RECENT_SURGERY_MONTHS = 12;

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private ProfileAlertDetector() {
    }

    /**
     * 一条硬警报。
     *
     * @param level  固定 {@code hard}（本轮只有硬警报；相关提醒由 rag 的记忆命中另发）
     * @param text   患者可读的文案，必须说清与哪条档案项冲突
     * @param anchor 锚点：{@code field:allergy|medication|surgery}（规则警报按档案字段锚定）
     * @param source 产出方：{@code rule}（词面规则）或 {@code model}（模型补充）
     */
    public record Alert(String level, String text, String anchor, String source) {
    }

    /** 生产调用：系统时钟。 */
    public static List<Alert> detect(HealthProfileAssembler.Profile profile, List<ChunkHit> chunks) {
        return detect(profile, chunks, Clock.systemDefaultZone());
    }

    /**
     * 规则检测。
     *
     * @param profile 本轮结构化档案（可为 null / 各字段可空）
     * @param chunks  召回片段（按注号顺序；正文为 null 的条目跳过）
     * @param clock   判断手术是否在 12 个月内的时钟（测试注入固定值）
     * @return 至多 {@value #MAX_ALERTS} 条，过敏 → 用药 → 手术；无冲突返回空列表
     */
    public static List<Alert> detect(HealthProfileAssembler.Profile profile, List<ChunkHit> chunks,
                                     Clock clock) {
        if (profile == null || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        String body = chunks.stream()
                .filter(Objects::nonNull)
                .map(ChunkHit::content)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        if (body.isBlank()) {
            return List.of();
        }

        List<Alert> alerts = new ArrayList<>();
        Set<String> seenValues = new LinkedHashSet<>();

        // 过敏优先：值来自标签 + 自由文本切词
        for (String value : values(profile.allergyTags(), profile.allergyOther())) {
            addIfConflict(alerts, seenValues, value, body, "field:allergy", "过敏史",
                    "请务必向医生说明过敏史。");
        }
        for (String value : recentMedicationValues(profile)) {
            addIfConflict(alerts, seenValues, value, body, "field:medication", "近期用药",
                    "就诊时请务必向医生说明正在服用。");
        }
        for (String value : recentSurgeryValues(profile, clock)) {
            addIfConflict(alerts, seenValues, value, body, "field:surgery", "近期手术",
                    "请向医生说明近期手术史。");
        }
        return alerts;
    }

    /**
     * 一个档案值命中正文则追加一条警报。
     *
     * <p>去重键 = 锚点 + 原文值（不是命中片段名）：同一过敏值在两条片段里各出现一次，患者只需要看到一条。
     */
    private static void addIfConflict(List<Alert> alerts, Set<String> seenValues, String rawValue,
                                      String body, String anchor, String category, String advice) {
        if (alerts.size() >= MAX_ALERTS) {
            return;
        }
        String value = rawValue == null ? null : rawValue.trim();
        if (value == null || value.length() < 2) {
            // 单字值不参与词面匹配：它几乎必然误命中（「头」「腰」），误报会把真警报淹掉
            return;
        }
        String dedupeKey = anchor + '|' + value;
        if (seenValues.contains(dedupeKey)) {
            return;
        }
        String matched = matchToken(value, body);
        if (matched == null) {
            return;
        }
        seenValues.add(dedupeKey);
        String text = "与您的" + category + "「" + value + "」相关：知识片段提到" + matched + "，" + advice;
        alerts.add(new Alert("hard", text, anchor, "rule"));
        log.debug("档案硬警报：{}｜档案值={}｜命中片段词={}", anchor, value, matched);
    }

    /**
     * 档案值在正文里的命中词。
     *
     * <p>候选形态依次是：原值 → 去掉「对」前缀与「过敏」后缀 → 再去掉「类」后缀。
     * **只做「正文包含候选词」**，不做反向（拿正文的词去匹配档案值）——
     * 那会把「青霉素」反过来命中「青霉」之类的不完整串，误报率不可控。
     */
    private static String matchToken(String value, String body) {
        for (String form : matchForms(value)) {
            if (form.length() >= 2 && body.contains(form)) {
                return form;
            }
        }
        return null;
    }

    /** 候选形态（去重、保序）：原值、去「对」前缀/「过敏」后缀、再去「类」后缀 */
    private static Set<String> matchForms(String value) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(value);

        String normalized = value;
        if (normalized.startsWith("对") && normalized.length() > 2) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("过敏") && normalized.length() > 2) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        forms.add(normalized);

        for (String form : List.copyOf(forms)) {
            if (form.endsWith("类") && form.length() > 2) {
                forms.add(form.substring(0, form.length() - 1));
            }
        }
        return forms;
    }

    /** 过敏：标签原值 + 自由文本按顿号/逗号/分号切词 */
    private static List<String> values(List<String> tags, String other) {
        List<String> values = new ArrayList<>();
        if (tags != null) {
            tags.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty())
                    .forEach(values::add);
        }
        if (other != null) {
            for (String part : other.split(VALUE_SEPARATOR)) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /** 近期用药：结束月份为空 = 仍在用；自由文本按「近期用药」口径处理（与档案文本一致） */
    private static List<String> recentMedicationValues(HealthProfileAssembler.Profile profile) {
        List<String> values = new ArrayList<>();
        if (profile.medicationEntries() != null) {
            for (MedicationEntry entry : profile.medicationEntries()) {
                if (entry == null || entry.value() == null || entry.value().isBlank()) {
                    continue;
                }
                if (entry.endedOn() == null || entry.endedOn().isBlank()) {
                    values.add(entry.value().trim());
                }
            }
        }
        if (profile.medicationOther() != null) {
            for (String part : profile.medicationOther().split(VALUE_SEPARATOR)) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /** 近期手术：发生月与当前相差 ≤ 12 个月，或时间未填（与档案文本同一口径） */
    private static List<String> recentSurgeryValues(HealthProfileAssembler.Profile profile, Clock clock) {
        List<String> values = new ArrayList<>();
        YearMonth now = YearMonth.now(clock);
        if (profile.surgeryEntries() != null) {
            for (SurgeryEntry entry : profile.surgeryEntries()) {
                if (entry == null || entry.value() == null || entry.value().isBlank()) {
                    continue;
                }
                if (!isBeyondRecent(entry.occurredOn(), now)) {
                    values.add(entry.value().trim());
                }
            }
        }
        if (profile.surgeryOther() != null) {
            for (String part : profile.surgeryOther().split(VALUE_SEPARATOR)) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /**
     * 是否超过 12 个月。解析失败 / 未来日期一律按「近期」处理——保存侧已拒绝未来月份，
     * 这里防御性兜底，绝不让一条脏数据把近期手术挪出警报范围（与档案文本组装同一取舍）。
     */
    private static boolean isBeyondRecent(String occurredOn, YearMonth now) {
        if (occurredOn == null || occurredOn.isBlank()) {
            return false;
        }
        try {
            YearMonth occurred = YearMonth.parse(occurredOn.trim(), MONTH_FORMAT);
            return ChronoUnit.MONTHS.between(occurred, now) > RECENT_SURGERY_MONTHS;
        } catch (Exception e) {
            return false;
        }
    }
}
