package com.guide.memory.support;

import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 抽取结果的**纯函数**校验：模型不管说什么，能不能落库由这里判定。
 *
 * <p>为什么单独一层而不是写在 {@code MemoryExtractor} 里：这些规则全是「给定草稿 → 留或丢」，
 * 不依赖模型、不依赖数据库，可以零 mock 逐条钉住。医疗场景下「记错了还一直用」比「没记住」更危险，
 * 所以每条丢弃规则都要有独立用例（见 {@code MemoryExtractValidatorTest}）。
 *
 * <p>七类规则（与 spec §4 / 探索笔记 §6 对齐）：
 * <ol>
 *   <li>缺患者原话 → 丢（每条记忆必须可追溯「从哪句话记的」）；</li>
 *   <li>置信度低于阈值 → 丢（不修补、不猜）；</li>
 *   <li>key &gt; 8 字 / value &gt; 60 字 → 丢（落库列宽与页面展示共同决定）；</li>
 *   <li>否定句 → 丢（「没有糖尿病」不是「糖尿病」）；</li>
 *   <li>禁止推断：原话里既没有 value、也没有稳定标记 → 丢（从用药推疾病就是这一类）；</li>
 *   <li>当前主诉不作为长期事实：背景值是纯症状描述且原话没有稳定标记 → 丢；</li>
 *   <li>背景兜底（工单 09）：过敏 / 手术 / 服药类档案事实 → 丢（应走档案候选，同一事实只存一处）。</li>
 * </ol>
 * 最后按 {@code (type, key, value)} 去重（同一次模型输出里重复的只留第一条）。
 */
@Slf4j
public final class MemoryExtractValidator {

    public static final int MAX_KEY_LENGTH = 8;
    public static final int MAX_VALUE_LENGTH = 60;

    /** 否定词：出现在 value 紧邻位置时，这一条是在说「没有」，不能记成肯定事实 */
    private static final List<String> NEGATIONS = List.of(
            "没有", "不是", "不过敏", "没做过", "没吃", "未做过", "未吃", "没", "未");
    /** 稳定标记：有这些词才说明是长期事实，而不是本次主诉或模型的归纳 */
    /**
     * 稳定标记。「有」必须排除「有点 / 有些 / 有时」这类程度副词——它们不是事实陈述，
     * 却是「今天胸口有点闷」这种当前主诉里最常见的写法。
     */
    private static final Pattern STABLE_MARKER = Pattern.compile(
            "(?:^|[^没未])有(?!点|些|时|的|人|一|两|几|多|少|无)|患有|长期|一直|平时|慢性|既往|家族");
    /** 症状词：背景值像一次主诉时，不满足稳定标记就丢 */
    private static final List<String> SYMPTOM_WORDS = List.of(
            "疼", "痛", "闷", "痒", "晕", "麻", "酸", "胀", "恶心", "呕吐", "发热", "发烧",
            "咳嗽", "咳痰", "腹泻", "便秘", "心慌", "气短", "乏力", "出血", "失眠", "反酸");
    /** 用药动词：紧挨在背景值前面时，这个值本身就是药名（应走档案候选） */
    private static final Pattern MEDICATION_VERB = Pattern.compile("(?:吃|服用|服|用|打|注射|输)\\s*$");
    /** 服药动词在值之前多远的窗口内算「这个值就是药」 */
    private static final int MEDICATION_VERB_WINDOW = 4;

    private MemoryExtractValidator() {
    }

    /** 校验偏好草稿：逐条过滤并去重 */
    public static List<PreferenceDraft> validPreferences(List<PreferenceDraft> drafts, double minConfidence) {
        List<PreferenceDraft> result = new ArrayList<>();
        if (drafts == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (PreferenceDraft draft : drafts) {
            PreferenceDraft cleaned = clean(draft);
            if (cleaned == null || !validCommon(cleaned.key(), cleaned.value(), cleaned.quote(),
                    cleaned.confidence(), minConfidence)) {
                continue;
            }
            if (negated(cleaned.quote(), cleaned.value())) {
                continue;
            }
            if (!supportedByQuote(cleaned.quote(), cleaned.value())) {
                continue;
            }
            if (seen.add(key("preference", cleaned.key(), cleaned.value()))) {
                result.add(cleaned);
            }
        }
        return result;
    }

    /** 校验背景草稿：逐条过滤并去重（比偏好多一条「当前主诉不作为长期事实」） */
    public static List<BackgroundDraft> validBackgrounds(List<BackgroundDraft> drafts, double minConfidence) {
        List<BackgroundDraft> result = new ArrayList<>();
        if (drafts == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (BackgroundDraft draft : drafts) {
            BackgroundDraft cleaned = clean(draft);
            if (cleaned == null || !validCommon(cleaned.key(), cleaned.value(), cleaned.quote(),
                    cleaned.confidence(), minConfidence)) {
                continue;
            }
            if (negated(cleaned.quote(), cleaned.value())) {
                continue;
            }
            if (!supportedByQuote(cleaned.quote(), cleaned.value())) {
                continue;
            }
            if (looksLikeCurrentComplaint(cleaned.value(), cleaned.quote())) {
                continue;
            }
            if (looksLikeProfileFact(cleaned.value(), cleaned.quote())) {
                log.warn("背景抽取命中了档案事实关键词，已丢弃（应走档案候选）：value={}｜quote={}",
                        cleaned.value(), cleaned.quote());
                continue;
            }
            String type = "other".equalsIgnoreCase(cleaned.type()) ? "other" : "health";
            if (seen.add(key(type, cleaned.key(), cleaned.value()))) {
                result.add(new BackgroundDraft(cleaned.key(), cleaned.value(), type, cleaned.quote(),
                        cleaned.confidence(), cleaned.validTo(), cleaned.action(), cleaned.target()));
            }
        }
        return result;
    }

    private static boolean validCommon(String key, String value, String quote,
                                       BigDecimal confidence, double minConfidence) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        if (quote == null || quote.isBlank()) {
            return false;
        }
        if (key.length() > MAX_KEY_LENGTH || value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        // 没有置信度 = 模型没给依据，按「低于阈值」处理（不补默认值）
        return confidence != null && confidence.doubleValue() >= minConfidence;
    }

    /**
     * 否定判定：只看 value 在原话里的**紧邻位置**（前后各 6 个字）。
     *
     * <p>为什么不扫整句：患者可能先说「我以前没有过敏」、后说「现在有糖尿病」，
     * 整句扫描会把后者误判为否定；「紧邻」才是这类规则真正想表达的语义。
     * 原话里找不到 value 时不做否定判定——那种情况交给「禁止推断」去挡。
     */
    static boolean negated(String quote, String value) {
        if (quote == null || value == null || value.isBlank()) {
            return false;
        }
        int index = quote.indexOf(value);
        if (index < 0) {
            return false;
        }
        String before = quote.substring(Math.max(0, index - 6), index);
        int afterEnd = Math.min(quote.length(), index + value.length() + 6);
        String after = quote.substring(index + value.length(), afterEnd);
        for (String negation : NEGATIONS) {
            if (before.contains(negation) || after.contains(negation)) {
                return true;
            }
        }
        return false;
    }

    /** 禁止推断：原话含 value，或含稳定标记，才算「患者说过」 */
    static boolean supportedByQuote(String quote, String value) {
        return quote.contains(value) || STABLE_MARKER.matcher(quote).find();
    }

    /**
     * 背景值像档案事实（过敏 / 手术 / 药名）⇒ 它应走档案候选，不能以背景身份注入。
     *
     * <p><b>只按「值 + 值的上下文」判，不因为原话里出现用药词就一刀切</b>：
     * 「我有糖尿病，一直在吃二甲双胍」里的「糖尿病」是患者明确说出的稳定背景，
     * 按原话含「吃」就丢会把 User Story 13 的常见句式误杀（spec §4 只禁止无依据的推断）。
     * 药名靠「吃/服用/打」紧挨在值前面判定，而不是靠原话里任何位置出现用药词。
     */
    static boolean looksLikeProfileFact(String value, String quote) {
        String fact = value == null ? "" : value;
        String said = quote == null ? "" : quote;
        // 过敏 / 手术：值与原话任一出现即按档案事实处理（疾病背景极少带这两个词）
        if (fact.contains("过敏") || fact.contains("切除术") || fact.contains("手术")
                || said.contains("过敏") || said.contains("切除术") || said.contains("手术")) {
            return true;
        }
        // 值本身就是用药描述
        for (String marker : List.of("服用", "服药", "吃药", "用药")) {
            if (fact.contains(marker)) {
                return true;
            }
        }
        // 原话里「吃/服用/打」紧挨在值前面 ⇒ 这个值是药名
        if (!fact.isEmpty() && said.contains(fact)) {
            int at = said.indexOf(fact);
            int from = Math.max(0, at - MEDICATION_VERB_WINDOW);
            if (MEDICATION_VERB.matcher(said.substring(from, at)).find()) {
                return true;
            }
        }
        return false;
    }

    /** 背景值像症状、且原话没有稳定标记 ⇒ 它是本次主诉，不是长期背景 */
    static boolean looksLikeCurrentComplaint(String value, String quote) {
        if (STABLE_MARKER.matcher(quote).find()) {
            return false;
        }
        for (String symptom : SYMPTOM_WORDS) {
            if (value.contains(symptom)) {
                return true;
            }
        }
        return false;
    }

    private static PreferenceDraft clean(PreferenceDraft draft) {
        if (draft == null) {
            return null;
        }
        // 判重字段原样带过：它是解析层已经降级好的结论，校验层只判「留或丢」，不改判重结果
        return new PreferenceDraft(trim(draft.key()), trim(draft.value()), trim(draft.quote()),
                draft.confidence(), draft.action(), draft.target());
    }

    private static BackgroundDraft clean(BackgroundDraft draft) {
        if (draft == null) {
            return null;
        }
        String type = draft.type() == null ? "health" : draft.type().trim().toLowerCase(Locale.ROOT);
        return new BackgroundDraft(trim(draft.key()), trim(draft.value()), type, trim(draft.quote()),
                draft.confidence(), draft.validTo(), draft.action(), draft.target());
    }

    private static String trim(String text) {
        return text == null ? null : text.trim();
    }

    private static String key(String type, String key, String value) {
        return type + '\u0000' + key + '\u0000' + value;
    }
}
