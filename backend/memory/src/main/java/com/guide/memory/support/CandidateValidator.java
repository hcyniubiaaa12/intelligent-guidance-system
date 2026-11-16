package com.guide.memory.support;

import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 档案候选的**纯函数**校验（工单 09）：模型说什么是模型的事，能不能进「待确认」由这里判定。
 *
 * <p>为什么单独一层：候选是二手信息，错一个字就可能让患者确认一个错误的过敏药。
 * 规则全部可零 mock 逐条钉住，换模型 / 改提示词都不会绕过：
 * <ol>
 *   <li>field 必须在白名单（allergy / medication / surgery）；</li>
 *   <li>quote 非空（每条候选必须能追溯到患者原话）；</li>
 *   <li>confidence ≥ 配置阈值（不补默认值）；</li>
 *   <li>value 非空且 ≤ 60 字；</li>
 *   <li>occurredOn 空 = 未填；否则必须是 {@code YYYY-MM} 且不是未来月份；</li>
 *   <li>否定句丢弃（「我没有青霉素过敏」不是过敏）；</li>
 *   <li>同一次输出里按 {@code (field, value)} 去重。</li>
 * </ol>
 */
public final class CandidateValidator {

    /** 与 user_memory 的值列宽、患者端输入框上限保持一致 */
    public static final int MAX_VALUE_LENGTH = 60;

    private static final Set<String> FIELDS = Set.of("allergy", "medication", "surgery");

    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(ResolverStyle.STRICT);

    private CandidateValidator() {
    }

    /** 生产入口：按系统当前月份判未来。 */
    public static List<ProfileCandidateDraft> validCandidates(List<ProfileCandidateDraft> drafts,
                                                              double minConfidence) {
        return validCandidates(drafts, minConfidence, YearMonth.now());
    }

    /**
     * 可注入当前月份的校验入口（测试用固定月份，避免跨月边界让「未来月份」断言漂移）。
     */
    public static List<ProfileCandidateDraft> validCandidates(List<ProfileCandidateDraft> drafts,
                                                              double minConfidence, YearMonth now) {
        List<ProfileCandidateDraft> result = new ArrayList<>();
        if (drafts == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (ProfileCandidateDraft draft : drafts) {
            ProfileCandidateDraft cleaned = clean(draft);
            if (cleaned == null) {
                continue;
            }
            String field = cleaned.field().toLowerCase(Locale.ROOT);
            if (!FIELDS.contains(field)) {
                continue;
            }
            if (cleaned.value().isEmpty() || cleaned.value().length() > MAX_VALUE_LENGTH) {
                continue;
            }
            if (cleaned.quote().isEmpty()) {
                continue;
            }
            BigDecimal confidence = cleaned.confidence();
            if (confidence == null || confidence.doubleValue() < minConfidence) {
                continue;
            }
            String occurredOn = normalizeMonth(cleaned.occurredOn(), now);
            if (occurredOn == INVALID_MONTH) {
                continue;
            }
            if (MemoryExtractValidator.negated(cleaned.quote(), cleaned.value())) {
                continue;
            }
            if (seen.add(field + '\u0000' + cleaned.value())) {
                result.add(new ProfileCandidateDraft(field, cleaned.value(), occurredOn,
                        cleaned.quote(), confidence));
            }
        }
        return result;
    }

    /** 哨兵：区分「未填（null）」与「格式非法 / 未来（丢弃）」 */
    private static final String INVALID_MONTH = "\u0000invalid";

    private static String normalizeMonth(String raw, YearMonth now) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!MONTH_PATTERN.matcher(value).matches()) {
            return INVALID_MONTH;
        }
        YearMonth month = YearMonth.parse(value, MONTH_FORMAT);
        if (month.isAfter(now)) {
            return INVALID_MONTH;
        }
        return month.format(MONTH_FORMAT);
    }

    private static ProfileCandidateDraft clean(ProfileCandidateDraft draft) {
        if (draft == null) {
            return null;
        }
        return new ProfileCandidateDraft(trim(draft.field()), trim(draft.value()), trim(draft.occurredOn()),
                trim(draft.quote()), draft.confidence());
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }
}
