package com.guide.auth.support;

import com.guide.auth.enums.AgeRange;
import com.guide.auth.enums.Gender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 健康档案 → 待注入文本（纯函数）。
 *
 * <p><b>零依赖</b>：不碰 Spring、不碰数据库、不碰任何客户端——只吃结构化输入，
 * 吐出一段字符串。缩表后的档案只保留「性别 / 出生年 / 过敏 / 近期用药 / 近期手术」，
 * 既往病史（旧慢病字段）只留在结构化档案里供证据快照审计，**不再进提示词**。
 *
 * <p>两条口径：
 * <ul>
 *   <li><b>顺序固定</b>：性别 → 出生年（缺失时回落旧年龄段）→ 过敏 → 近期用药 → 近期手术。</li>
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

    private HealthProfileAssembler() {
    }

    /**
     * 结构化档案输入（纯数据，与实体 / DTO 解耦，便于零依赖构造测试）。
     * 字段全部可空；{@code genderCode} / {@code ageRangeCode} 是入库编码值（如 {@code male} / {@code 45-59}）。
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
            List<String> medicationTags,
            String medicationOther,
            List<String> allergyTags,
            String allergyOther,
            List<String> surgeryTags,
            String surgeryOther) {
    }

    /**
     * 组装结果。
     *
     * @param profileText  档案提示文本：按「性别 → 出生年（或旧年龄段）→ 过敏 → 近期用药 → 近期手术」拼接；
     *                     全空档案时为空串（上层据此不拼 prompt 段）
     * @param recallQuery  遗留字段，**已无消费者**（档案退出检索层），工单 03 删除
     * @param textOverflow 档案提示文本是否超 {@link #TEXT_MAX} 字天花板（超限不裁剪，由调用方告警）
     */
    public record Assembly(String profileText, String recallQuery, boolean textOverflow) {
    }

    /** 组装待注入文本。 */
    public static Assembly assemble(Profile profile) {
        List<String> textParts = new ArrayList<>();
        List<String> queryParts = new ArrayList<>();

        String gender = genderLabel(profile.genderCode());
        if (gender != null) {
            textParts.add(gender);
            queryParts.add(gender);
        }
        String age = birthYearLabel(profile.birthYear());
        if (age == null) {
            age = ageRangeLabel(profile.ageRangeCode());
        }
        if (age != null) {
            textParts.add(age);
            queryParts.add(age);
        }
        addCategory("过敏", profile.allergyTags(), profile.allergyOther(), textParts, queryParts);
        addCategory("近期用药", profile.medicationTags(), profile.medicationOther(), textParts, queryParts);
        addCategory("近期手术", profile.surgeryTags(), profile.surgeryOther(), textParts, queryParts);

        String profileText = String.join(SEPARATOR, textParts);
        String recallQuery = String.join(SEPARATOR, queryParts);
        return new Assembly(profileText, recallQuery, profileText.length() > TEXT_MAX);
    }

    /** 一个类别：标签 + 自由文本合并成「类别：A、B」；整类为空时整段不出现 */
    private static void addCategory(String label, List<String> tags, String other,
                                    List<String> textParts, List<String> queryParts) {
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
        if (values.isEmpty()) {
            return;
        }
        textParts.add(label + "：" + String.join(SEPARATOR, values));
        queryParts.addAll(values);
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
