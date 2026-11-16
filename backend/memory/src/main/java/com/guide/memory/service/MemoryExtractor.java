package com.guide.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.SysConfigService;
import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import com.guide.memory.support.CandidateValidator;
import com.guide.memory.support.MemoryExtractValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆抽取的模型调用与容错解析（参照链路 B 的 {@code TermExtractor}）。
 *
 * <p><b>失败一律降级为空结果 + WARN，绝不上抛</b>：抽取是旁路，模型抖动不能影响患者对话，
 * 也不重试（重试只会把一次抖动变成一串调用）。
 *
 * <p><b>解析与校验分开</b>：这里只负责「把模型吐出来的东西读成结构化草稿」，
 * 「哪些草稿有资格落库」全部交给 {@link MemoryExtractValidator}（纯函数、可零 mock 测）。
 * 这样提示词不守协议时，代码仍然只有一套判定规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final PromptProperties prompts;
    private final SysConfigService sysConfigService;

    /**
     * 从一段对话里抽取偏好、稳定背景与档案候选。
     *
     * @param dialogue 已拼好的对话文本（患者 / 助手逐行）
     * @return 校验后的结果；模型异常、坏 JSON、空输出都返回 {@link MemoryExtractionResult#empty()}
     */
    public MemoryExtractionResult extract(String dialogue) {
        if (dialogue == null || dialogue.isBlank()) {
            return MemoryExtractionResult.empty();
        }
        String output;
        try {
            output = chatModel.chat(messages(dialogue));
        } catch (RuntimeException e) {
            log.warn("记忆抽取模型调用失败（本轮不记任何东西）：{}", e.getMessage());
            return MemoryExtractionResult.empty();
        }

        try {
            JsonNode root = parseObject(output);
            List<PreferenceDraft> preferences = parsePreferences(root);
            List<BackgroundDraft> backgrounds = parseBackgrounds(root);
            List<ProfileCandidateDraft> candidates = parseCandidates(root);
            double minConfidence = sysConfigService.getDouble(
                    SysConfigService.KEY_MEMORY_EXTRACT_CONFIDENCE_MIN,
                    SysConfigService.DEFAULT_MEMORY_EXTRACT_CONFIDENCE_MIN);
            List<PreferenceDraft> validPreferences =
                    MemoryExtractValidator.validPreferences(preferences, minConfidence);
            List<BackgroundDraft> validBackgrounds =
                    MemoryExtractValidator.validBackgrounds(backgrounds, minConfidence);
            List<ProfileCandidateDraft> validCandidates =
                    CandidateValidator.validCandidates(candidates, minConfidence);
            log.info("记忆抽取完成：模型给出偏好 {} 条 / 背景 {} 条 / 档案候选 {} 条，"
                            + "校验后保留 {} 条 / {} 条 / {} 条",
                    preferences.size(), backgrounds.size(), candidates.size(),
                    validPreferences.size(), validBackgrounds.size(), validCandidates.size());
            return new MemoryExtractionResult(validPreferences, validBackgrounds, validCandidates);
        } catch (RuntimeException e) {
            log.warn("记忆抽取输出不可解析（本轮不记任何东西）：{}｜原始输出：{}",
                    e.getMessage(), abbreviate(output, 300));
            return MemoryExtractionResult.empty();
        }
    }

    /**
     * 偏好满额时的合并去重。
     *
     * @param existing 已有的偏好值
     * @param incoming 本批新抽到的偏好值
     * @return 合并后的偏好（key/value）
     * @throws IllegalStateException 模型调用失败或输出不可解析——调用方据此**丢弃本批新偏好**，
     *                               而不是写一半或删旧偏好
     */
    public List<MergedPreference> merge(List<String> existing, List<String> incoming) {
        List<ChatMsg> messages = List.of(
                ChatMsg.system(prompts.getMemoryMerge().getSystem()),
                ChatMsg.user(PromptProperties.render(prompts.getMemoryMerge().getUserTemplate(),
                        PromptProperties.PLACEHOLDER_EXISTING, listText(existing))
                        .replace(PromptProperties.PLACEHOLDER_INCOMING, listText(incoming))));
        String output;
        try {
            output = chatModel.chat(messages);
        } catch (RuntimeException e) {
            throw new IllegalStateException("合并模型调用失败：" + e.getMessage(), e);
        }
        try {
            JsonNode root = parseObject(output);
            List<MergedPreference> merged = new ArrayList<>();
            for (JsonNode node : root.path("preferences")) {
                String key = text(node, "key");
                String value = text(node, "value");
                if (key.isBlank() || value.isBlank()) {
                    continue;
                }
                if (key.length() > MemoryExtractValidator.MAX_KEY_LENGTH
                        || value.length() > MemoryExtractValidator.MAX_VALUE_LENGTH) {
                    continue;
                }
                merged.add(new MergedPreference(key, value));
            }
            log.info("偏好合并完成：旧 {} 条 + 新 {} 条 → {} 条", size(existing), size(incoming), merged.size());
            return merged;
        } catch (RuntimeException e) {
            throw new IllegalStateException("合并输出不可解析：" + e.getMessage(), e);
        }
    }

    private List<ChatMsg> messages(String dialogue) {
        return List.of(
                ChatMsg.system(prompts.getMemoryExtract().getSystem()),
                ChatMsg.user(PromptProperties.render(prompts.getMemoryExtract().getUserTemplate(),
                        PromptProperties.PLACEHOLDER_DIALOGUE, dialogue)));
    }

    private List<PreferenceDraft> parsePreferences(JsonNode root) {
        List<PreferenceDraft> drafts = new ArrayList<>();
        for (JsonNode node : root.path("preferences")) {
            drafts.add(new PreferenceDraft(text(node, "key"), text(node, "value"),
                    text(node, "quote"), decimal(node, "confidence")));
        }
        return drafts;
    }

    private List<BackgroundDraft> parseBackgrounds(JsonNode root) {
        List<BackgroundDraft> drafts = new ArrayList<>();
        for (JsonNode node : root.path("background")) {
            drafts.add(new BackgroundDraft(text(node, "key"), text(node, "value"),
                    text(node, "type"), text(node, "quote"), decimal(node, "confidence"),
                    date(node, "validTo")));
        }
        return drafts;
    }

    /**
     * 档案候选：{@code profile_candidates} 缺失 / 不是数组时按空处理（老提示词、模型漏字段都不崩）。
     * 字段白名单、月份、否定句等判定统一交给 {@link CandidateValidator}。
     */
    private List<ProfileCandidateDraft> parseCandidates(JsonNode root) {
        List<ProfileCandidateDraft> drafts = new ArrayList<>();
        for (JsonNode node : root.path("profile_candidates")) {
            drafts.add(new ProfileCandidateDraft(text(node, "field"), text(node, "value"),
                    text(node, "occurredOn"), text(node, "quote"), decimal(node, "confidence")));
        }
        return drafts;
    }

    /** 模型习惯把 JSON 包进 ``` 围栏或前后加解释：截取第一个 { 到最后一个 } */
    private JsonNode parseObject(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String cleaned = stripFence(output.strip());
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型输出不是 JSON 对象");
        }
        try {
            return OBJECT_MAPPER.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("模型输出的 JSON 无法解析：" + e.getMessage(), e);
        }
    }

    private String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, closing).strip();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    /** 非数值或缺失一律当「没有置信度」（后续校验会丢弃，不补默认值） */
    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    private LocalDate date(JsonNode node, String field) {
        String text = text(node, field);
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            try {
                return YearMonth.parse(text).atDay(1);
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private String listText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "（无）";
        }
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            text.append("- ").append(value).append('\n');
        }
        return text.toString().strip();
    }

    private int size(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    /** 合并结果的一条偏好（合并是系统整理，不携带新原话） */
    public record MergedPreference(String key, String value) {
    }
}
