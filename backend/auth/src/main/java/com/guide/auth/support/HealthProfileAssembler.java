package com.guide.auth.support;

import com.guide.auth.enums.AgeRange;
import com.guide.auth.enums.Gender;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 健康档案 → 待注入文本（纯函数）。
 *
 * <p><b>零依赖</b>：不碰 Spring、不碰数据库、不碰任何客户端——只吃结构化输入，
 * 吐出一段字符串。缩表后的档案只保留「性别 / 出生年 / 过敏 / 用药 / 手术」，
 * 既往病史（旧慢病字段）只留在结构化档案里供证据快照审计，**不再进提示词**。
 *
 * <p><b>时间语义</b>（工单 03）：用药带开始/结束月份，手术带发生月份。结束月份非空
 * 归入「已停用药」；手术发生超过 12 个月归入「既往手术」。所以「近期」不再是一个
 * 没有日期的标签。时间来源是<b>可注入的 {@link Clock}</b>——「恰好 12 个月」这类
 * 断言不能随系统时间漂移。
 *
 * <p>两条口径：
 * <ul>
 *   <li><b>顺序固定</b>：性别 → 出生年（缺失时回落旧年龄段）→ 过敏 → 近期用药 →
 *       已停用药 → 近期手术 → 既往手术。</li>
 *   <li><b>空类别整段消失</b>：没有内容的类别连抬头一起不出现（与 prompt 空段整块消失同一态度）。</li>
 * </ul>
 *
 * <p><b>350 字是硬天花板，不是患者额度</b>：正常档案一百多字就见顶，超出说明配置或词表出了问题。
 * 本函数<b>不静默裁剪</b>，只把 {@link Assembly#textOverflow()} 置真，由调用方记 WARN 告警。
 */
public final class HealthProfileAssembler {

    /** 待注入文本的硬天花板（字符）。超出只告警、不裁剪。 */
    public static final int TEXT_MAX = 350;

    /** 段内分隔符：段内与跨段统一用顿号，与推荐卡「已参考您的健康档案：A、B」同一形态 */
    private static final String SEPARATOR = "、";

    /** 手术「近期」的时间窗：与当前月相差 ≤ 12 个月仍算近期，> 12 个月算既往。 */
    private static final int RECENT_SURGERY_MONTHS = 12;

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private HealthProfileAssembler() {
    }

    /**
     * 结构化档案输入（纯数据，与实体 / DTO 解耦，便于零依赖构造测试）。
     * 字段全部可空；{@code genderCode} / {@code ageRangeCode} 是入库编码值
     * （如 {@code male} / {@code 45-59}）。
     *
     * <p>{@code historyTags} / {@code historyOther} 只供证据快照审计，**不参与文本组装**；
     * {@code ageRangeCode} 只在出生年缺失时兜底展示。
     */
    public record Profile(
            String genderCode,
            Integer birthYear,
            String ageRangeCode,
            List<String> historyTags,
            String historyOther,
            List<MedicationEntry> medicationEntries,
            String medicationOther,
            List<String> allergyTags,
            String allergyOther,
            List<SurgeryEntry> surgeryEntries,
            String surgeryOther) {
    }

    /**
     * 组装结果。
     *
     * @param profileText  档案提示文本：按「性别 → 出生年（或旧年龄段）→ 过敏 → 近期用药 →
     *                     已停用药 → 近期手术 → 既往手术」拼接；全空档案时为空串（上层据此不拼 prompt 段）
     * @param textOverflow 档案提示文本是否超 {@link #TEXT_MAX} 字天花板（超限不裁剪，由调用方告警）
     */
    public record Assembly(String profileText, boolean textOverflow) {
    }

    /** 组装待注入文本（系统时钟：生产调用走这条）。 */
    public static Assembly assemble(Profile profile) {
        return assemble(profile, Clock.systemDefaultZone());
    }

    /**
     * 组装待注入文本（可注入时钟）。
     *
     * <p>{@code clock} 只影响「手术是否在 12 个月内」的判断；测试用
     * {@link Clock#fixed} 固定时间，避免「恰好 12 个月」随当天日期漂移。
     */
    public static Assembly assemble(Profile profile, Clock clock) {
        List<String> textParts = new ArrayList<>();

        String gender = genderLabel(profile.genderCode());
        if (gender != null) {
            textParts.add(gender);
        }
        String age = birthYearLabel(profile.birthYear());
        if (age == null) {
            age = ageRangeLabel(profile.ageRangeCode());
        }
        if (age != null) {
            textParts.add(age);
        }

        addCategory("过敏", profile.allergyTags(), profile.allergyOther(), textParts);
        addMedicationCategories(profile, textParts);
        addSurgeryCategories(profile, clock, textParts);

        String profileText = String.join(SEPARATOR, textParts);
        return new Assembly(profileText, profileText.length() > TEXT_MAX);
    }

    /** 用药拆两段：结束月份为空 = 仍在用；非空 = 已停用（开始月份可空）。自由文本归「近期用药」。 */
    private static void addMedicationCategories(Profile profile, List<String> textParts) {
        List<String> recent = new ArrayList<>();
        List<String> stopped = new ArrayList<>();
        if (profile.medicationEntries() != null) {
            for (MedicationEntry entry : profile.medicationEntries()) {
                if (entry == null || isBlank(entry.value())) {
                    continue;
                }
                String value = entry.value().trim();
                if (isBlank(entry.endedOn())) {
                    recent.add(value + sinceLabel(entry.startedOn()));
                } else {
                    stopped.add(value + stoppedLabel(entry.startedOn(), entry.endedOn()));
                }
            }
        }
        if (!isBlank(profile.medicationOther())) {
            recent.add(profile.medicationOther().trim());
        }
        addSection("近期用药", recent, textParts);
        addSection("已停用药", stopped, textParts);
    }

    /**
     * 手术拆两段：发生月份与当前相差 ≤ 12 个月（含未来日期，保存时已拒绝、这里防御性按近期）
     * 算「近期手术」，> 12 个月算「既往手术」。时间空只写值、按近期处理（与原口径一致）。
     * 自由文本归「近期手术」。
     */
    private static void addSurgeryCategories(Profile profile, Clock clock, List<String> textParts) {
        List<String> recent = new ArrayList<>();
        List<String> past = new ArrayList<>();
        YearMonth now = YearMonth.now(clock);
        if (profile.surgeryEntries() != null) {
            for (SurgeryEntry entry : profile.surgeryEntries()) {
                if (entry == null || isBlank(entry.value())) {
                    continue;
                }
                String value = entry.value().trim();
                String occurredOn = isBlank(entry.occurredOn()) ? null : entry.occurredOn().trim();
                String label = occurredOn == null ? value : value + "（" + occurredOn + "）";
                if (isBeyondRecentSurgery(occurredOn, now)) {
                    past.add(label);
                } else {
                    recent.add(label);
                }
            }
        }
        if (!isBlank(profile.surgeryOther())) {
            recent.add(profile.surgeryOther().trim());
        }
        addSection("近期手术", recent, textParts);
        addSection("既往手术", past, textParts);
    }

    /**
     * 是否超过 12 个月。解析失败 / 未来日期一律按「近期」处理：保存侧已经拒绝未来月份，
     * 这里防御性兜底，绝不让一条脏数据把患者的手术从「近期」挪到「既往」。
     */
    private static boolean isBeyondRecentSurgery(String occurredOn, YearMonth now) {
        if (occurredOn == null) {
            return false;
        }
        try {
            YearMonth occurred = YearMonth.parse(occurredOn, MONTH_FORMAT);
            return ChronoUnit.MONTHS.between(occurred, now) > RECENT_SURGERY_MONTHS;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用药仍在用：有开始月份时写「（2024-03 至今）」，没有则只写值。 */
    private static String sinceLabel(String startedOn) {
        return isBlank(startedOn) ? "" : "（" + startedOn.trim() + " 至今）";
    }

    /** 已停用药：两个月份都有写区间；只有结束月份时写「（至 2023-06）」。 */
    private static String stoppedLabel(String startedOn, String endedOn) {
        String end = endedOn.trim();
        if (isBlank(startedOn)) {
            return "（至 " + end + "）";
        }
        return "（" + startedOn.trim() + "–" + end + "）";
    }

    /** 一个类别：标签 + 自由文本合并成「类别：A、B」；整类为空时整段不出现 */
    private static void addCategory(String label, List<String> tags, String other, List<String> textParts) {
        List<String> values = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                if (!isBlank(tag)) {
                    values.add(tag.trim());
                }
            }
        }
        if (!isBlank(other)) {
            values.add(other.trim());
        }
        addSection(label, values, textParts);
    }

    /** 值列表为空时整段不出现 */
    private static void addSection(String label, List<String> values, List<String> textParts) {
        if (values.isEmpty()) {
            return;
        }
        textParts.add(label + "：" + String.join(SEPARATOR, values));
    }

    /** 性别编码 → 展示名；未填或非法返回 null（非法值在校验层已拦，这里只作兜底） */
    private static String genderLabel(String code) {
        if (isBlank(code)) {
            return null;
        }
        String value = code.trim();
        return Arrays.stream(Gender.values())
                .filter(g -> g.getCode().equals(value))
                .map(Gender::getLabel)
                .findFirst()
                .orElse(null);
    }

    /** 出生年 → 「1980 年出生」；未填返回 null */
    private static String birthYearLabel(Integer birthYear) {
        return birthYear == null ? null : birthYear + " 年出生";
    }

    /** 年龄段编码 → 展示名（如 45-59 → 45-59岁）；未填或非法返回 null */
    private static String ageRangeLabel(String code) {
        if (isBlank(code)) {
            return null;
        }
        return AgeRange.findByCode(code.trim()).map(AgeRange::getLabel).orElse(null);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
