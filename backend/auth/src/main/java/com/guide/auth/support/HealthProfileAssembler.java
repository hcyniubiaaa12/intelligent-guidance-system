package com.guide.auth.support;

import com.guide.auth.enums.AgeRange;
import com.guide.auth.enums.Gender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 健康档案 →（待注入文本, 检索用串）纯函数（单据 02 主缝）。
 *
 * <p><b>零依赖</b>：不碰 Spring、不碰数据库、不碰任何客户端——只吃结构化输入与一份词表，
 * 吐出两段字符串。决策密度最高的部分（标签优先 / 自由文本词表匹配 / 命中与丢弃 / 两段分流 /
 * 350 字天花板）都落在这里，可以零 mock 地测。
 *
 * <p>两条口径：
 * <ul>
 *   <li><b>标签优先</b>：多选标签词直接进两段文本。</li>
 *   <li><b>自由文本分流</b>：命中受控词表 ⇒ 进两段；未命中（口语，如"血糖有点高"）⇒
 *       <b>只进待注入文本、不进检索用串</b>——口语在知识库里命不中任何东西，塞进召回串只会稀释向量，
 *       但生成模型读得懂人话，丢掉可惜。</li>
 * </ul>
 *
 * <p><b>350 字是硬天花板，不是患者额度</b>：正常档案一百多字就见顶，超出说明配置或词表出了问题。
 * 本函数<b>不静默裁剪</b>，只把 {@link Assembly#textOverflow()} 置真，由调用方记 WARN 告警。
 *
 * <p><b>为什么词表是入参</b>：词表来自 {@code health_tag} 表，属外部状态；读它会使本函数失去
 * 「零依赖、可零 mock 测」的性质。故由调用方（{@code HealthProfileService}）读好传进来。</p>
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
     */
    public record Profile(
            String genderCode,
            String ageRangeCode,
            List<String> historyTags,
            String historyOther,
            List<String> medicationTags,
            String medicationOther,
            List<String> allergyTags,
            String allergyOther) {
    }

    /**
     * 组装结果。
     *
     * @param profileText  **档案提示文本**：按「性别 / 年龄段 / 既往病史 / 长期用药 / 过敏史」顺序拼接，
     *                     段内不含任何指令性文字；全空档案时为空串（上层据此退回原链路、不拼 prompt 段）
     * @param recallQuery  **档案召回串**：性别 + 年龄段 + 标签 + 命中词表的自由文本；全空时为空串（03 接入）。
     *                     与 {@code RagRequest.query}（主诉串）同名异义，故用词表名「召回串」而非泛名 query
     * @param textOverflow 档案提示文本是否超 {@link #TEXT_MAX} 字天花板（超限不裁剪，由调用方告警）
     */
    public record Assembly(String profileText, String recallQuery, boolean textOverflow) {
    }

    /**
     * 组装两段文本。{@code vocabulary} 为启用的受控词表（慢病 / 用药 / 过敏合集），
     * 仅用于判断自由文本是否「命中」；为空集合时所有自由文本都视为未命中（只进待注入文本）。
     */
    public static Assembly assemble(Profile profile, Set<String> vocabulary) {
        Set<String> vocab = vocabulary == null ? Set.of() : vocabulary;
        List<String> textParts = new ArrayList<>();
        List<String> queryParts = new ArrayList<>();

        String gender = genderLabel(profile.genderCode());
        if (gender != null) {
            textParts.add(gender);
            queryParts.add(gender);
        }
        String ageRange = ageRangeLabel(profile.ageRangeCode());
        if (ageRange != null) {
            textParts.add(ageRange);
            queryParts.add(ageRange);
        }
        addCategory(profile.historyTags(), profile.historyOther(), vocab, textParts, queryParts);
        addCategory(profile.medicationTags(), profile.medicationOther(), vocab, textParts, queryParts);
        addCategory(profile.allergyTags(), profile.allergyOther(), vocab, textParts, queryParts);

        String profileText = String.join(SEPARATOR, textParts);
        String recallQuery = String.join(SEPARATOR, queryParts);
        return new Assembly(profileText, recallQuery, profileText.length() > TEXT_MAX);
    }

    /** 一个类别（既往病史 / 长期用药 / 过敏史）：标签全进两段；自由文本命中词表才进检索串 */
    private static void addCategory(List<String> tags, String other, Set<String> vocabulary,
                                    List<String> textParts, List<String> queryParts) {
        if (tags != null) {
            for (String tag : tags) {
                if (isBlank(tag)) {
                    continue;
                }
                String value = tag.trim();
                textParts.add(value);
                queryParts.add(value);
            }
        }
        if (isBlank(other)) {
            return;
        }
        String freeText = other.trim();
        textParts.add(freeText);
        if (matchesVocabulary(freeText, vocabulary)) {
            queryParts.add(freeText);
        }
    }

    /** 自由文本是否命中受控词表：包含任一标签词即算命中（"我对青霉素类过敏" 命中 "青霉素类"） */
    private static boolean matchesVocabulary(String freeText, Set<String> vocabulary) {
        for (String term : vocabulary) {
            if (!isBlank(term) && freeText.contains(term.trim())) {
                return true;
            }
        }
        return false;
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
