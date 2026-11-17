package com.guide.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.guide.auth.service.SysConfigService;
import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import com.guide.memory.enums.MemoryDedupAction;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.support.CandidateValidator;
import com.guide.memory.support.MemoryExtractValidator;
import com.guide.memory.support.MemoryJsonSupport;
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

    private final ChatModel chatModel;
    private final PromptProperties prompts;
    private final SysConfigService sysConfigService;

    /**
     * 从一段对话里抽取偏好、稳定背景与档案候选（不带旧清单的重载，语义等价于旧清单为空）。
     *
     * <p>金标集评估（{@code MemoryGoldSetEvalTest}）与不关心判重的既有单测走这里。
     *
     * @param dialogue 已拼好的对话文本（患者 / 助手逐行）
     * @return 校验后的结果；模型异常、坏 JSON、空输出都返回 {@link MemoryExtractionResult#empty()}
     */
    public MemoryExtractionResult extract(String dialogue) {
        return extract(dialogue, List.of());
    }

    /**
     * 从一段对话里抽取偏好、稳定背景与档案候选，并让模型对每条给出判重动作。
     *
     * <p><b>旧清单进提示词、不进代码判重</b>：模型看到的是按序号编号的已记住记忆，
     * 它回 {@code action}（new / update / skip）与 {@code target}（序号）。
     * 序号越界、action 不认识时**一律降级为 new**——见 {@link #dedupAction}。
     *
     * @param dialogue 已拼好的对话文本（患者 / 助手逐行）
     * @param existing 已记住的记忆（**只含生效中的**，按类型分别传入）；空 = 没有可判重的候选
     * @return 校验后的结果；模型异常、坏 JSON、空输出都返回 {@link MemoryExtractionResult#empty()}
     */
    public MemoryExtractionResult extract(String dialogue, List<ExistingMemory> existing) {
        if (dialogue == null || dialogue.isBlank()) {
            return MemoryExtractionResult.empty();
        }
        List<ExistingMemory> remembered = existing == null ? List.of() : existing;
        String output;
        try {
            output = chatModel.chat(messages(dialogue, remembered));
        } catch (RuntimeException e) {
            log.warn("记忆抽取模型调用失败（本轮不记任何东西）：{}", e.getMessage());
            return MemoryExtractionResult.empty();
        }

        try {
            JsonNode root = MemoryJsonSupport.parseObject(output);
            List<PreferenceDraft> preferences = parsePreferences(root, remembered.size());
            List<BackgroundDraft> backgrounds = parseBackgrounds(root, remembered.size());
            List<ProfileCandidateDraft> candidates = parseCandidates(root);
            double minConfidence = sysConfigService.getDouble(
                    SysConfigService.KEY_MEMORY_EXTRACT_CONFIDENCE_MIN,
                    SysConfigService.DEFAULT_MEMORY_EXTRACT_CONFIDENCE_MIN);
            List<PreferenceDraft> validPreferences =
                    MemoryExtractValidator.validPreferences(preferences, minConfidence, dialogue);
            List<BackgroundDraft> validBackgrounds =
                    MemoryExtractValidator.validBackgrounds(backgrounds, minConfidence);
            List<ProfileCandidateDraft> validCandidates =
                    CandidateValidator.validCandidates(candidates, minConfidence);
            log.info("记忆抽取完成：模型给出偏好 {} 条 / 背景 {} 条 / 档案候选 {} 条，"
                            + "校验后保留 {} 条 / {} 条 / {} 条（旧记忆 {} 条参与判重）",
                    preferences.size(), backgrounds.size(), candidates.size(),
                    validPreferences.size(), validBackgrounds.size(), validCandidates.size(),
                    remembered.size());
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
     * <p><b>入参是「键：值」而不是只有值</b>：合并结果要带 key，若只喂值，模型只能凭空编一个键，
     * 编出来的键与患者原来那条对不上，「我记住的」里就会出现一条没人说过的偏好。
     *
     * <p><b>key 仍以输入为准</b>：模型给的 key 若能对上某个输入值的 key，用输入的那个
     * （见 {@link #keyFor}）——这一步只是不让模型的自由发挥落地，不改变合并结果本身。
     *
     * @param existing 已有的偏好（键 + 值）
     * @param incoming 本批新抽到的偏好（键 + 值）
     * @return 合并后的偏好（key/value）
     * @throws IllegalStateException 模型调用失败或输出不可解析——调用方据此**丢弃本批新偏好**，
     *                               而不是写一半或删旧偏好
     */
    public List<MergedPreference> merge(List<PreferenceItem> existing, List<PreferenceItem> incoming) {
        List<ChatMsg> messages = List.of(
                ChatMsg.system(prompts.getMemoryMerge().getSystem()),
                ChatMsg.user(PromptProperties.render(prompts.getMemoryMerge().getUserTemplate(),
                        PromptProperties.PLACEHOLDER_EXISTING, itemText(existing))
                        .replace(PromptProperties.PLACEHOLDER_INCOMING, itemText(incoming))));
        String output;
        try {
            output = chatModel.chat(messages);
        } catch (RuntimeException e) {
            throw new IllegalStateException("合并模型调用失败：" + e.getMessage(), e);
        }
        try {
            JsonNode root = MemoryJsonSupport.parseObject(output);
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
                merged.add(new MergedPreference(keyFor(key, value, existing, incoming), value));
            }
            log.info("偏好合并完成：旧 {} 条 + 新 {} 条 → {} 条", size(existing), size(incoming), merged.size());
            return merged;
        } catch (RuntimeException e) {
            throw new IllegalStateException("合并输出不可解析：" + e.getMessage(), e);
        }
    }

    private List<ChatMsg> messages(String dialogue, List<ExistingMemory> existing) {
        String user = PromptProperties.render(prompts.getMemoryExtract().getUserTemplate(),
                PromptProperties.PLACEHOLDER_REMEMBERED, rememberedText(existing));
        return List.of(
                ChatMsg.system(prompts.getMemoryExtract().getSystem()),
                ChatMsg.user(PromptProperties.render(user, PromptProperties.PLACEHOLDER_DIALOGUE, dialogue)));
    }

    private List<PreferenceDraft> parsePreferences(JsonNode root, int existingCount) {
        List<PreferenceDraft> drafts = new ArrayList<>();
        for (JsonNode node : root.path("preferences")) {
            Dedup dedup = dedup(node, existingCount);
            drafts.add(new PreferenceDraft(text(node, "key"), text(node, "value"),
                    text(node, "quote"), decimal(node, "confidence"), dedup.action(), dedup.target()));
        }
        return drafts;
    }

    private List<BackgroundDraft> parseBackgrounds(JsonNode root, int existingCount) {
        List<BackgroundDraft> drafts = new ArrayList<>();
        for (JsonNode node : root.path("background")) {
            Dedup dedup = dedup(node, existingCount);
            drafts.add(new BackgroundDraft(text(node, "key"), text(node, "value"),
                    text(node, "type"), text(node, "quote"), decimal(node, "confidence"),
                    date(node, "validTo"), text(node, "retrievalText"), dedup.action(), dedup.target()));
        }
        return drafts;
    }

    /**
     * 判重字段的**降级规则**（解析层唯一的判定点）。
     *
     * <p>一律按 {@code new}：{@code action} 缺失或不在三值之内、{@code target} 缺失 / 非整数 /
     * 越界（小于 1 或大于旧清单条数）。非 {@code update} 时 target 一律清空——
     * 留着它只会让下游以为「这条指得到某条旧记忆」，而模型并没有这么说。
     *
     * <p>为什么宁可降级成 new 而不是丢：多记一条患者看得见、能自己删；
     * 丢一条他永远不知道自己被漏记了（spec「失败一律按不重复落库」）。
     */
    private Dedup dedup(JsonNode node, int existingCount) {
        MemoryDedupAction action = MemoryDedupAction.fromCode(text(node, "action"))
                .orElse(MemoryDedupAction.NEW);
        if (action != MemoryDedupAction.UPDATE) {
            return new Dedup(action, null);
        }
        JsonNode value = node.get("target");
        // 只认整数：2.5 会被 canConvertToInt 放行并截成 2，那等于替模型指了一条它没指的旧记忆；
        // 超出 int 范围的值同理（intValue() 会溢出成一个看似合法的序号）
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return new Dedup(MemoryDedupAction.NEW, null);
        }
        int target = value.intValue();
        if (target < 1 || target > existingCount) {
            return new Dedup(MemoryDedupAction.NEW, null);
        }
        return new Dedup(MemoryDedupAction.UPDATE, target);
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

    /** 「已记住的记忆」段：按序号编号（模型不认 id），分 [偏好] / [背景]，无条目写「（无）」 */
    private String rememberedText(List<ExistingMemory> values) {
        if (values == null || values.isEmpty()) {
            return "（无）";
        }
        StringBuilder text = new StringBuilder();
        int index = 1;
        for (ExistingMemory value : values) {
            text.append(index++).append(". [").append(groupLabel(value.type())).append("] ")
                    .append(value.key()).append('：').append(value.value()).append('\n');
        }
        return text.toString().strip();
    }

    /** 「键：值」清单（合并提示词用）：只给值，模型就得自己编一个键 */
    private String itemText(List<PreferenceItem> items) {
        if (items == null || items.isEmpty()) {
            return "（无）";
        }
        StringBuilder text = new StringBuilder();
        for (PreferenceItem item : items) {
            text.append("- ").append(item.key()).append('：').append(item.value()).append('\n');
        }
        return text.toString().strip();
    }

    private String groupLabel(MemoryType type) {
        return type == MemoryType.PREFERENCE ? "偏好" : "背景";
    }

    /**
     * 合并结果的 key 以输入为准：模型给的 key 若对得上某个输入值，用那个输入的 key；
     * 对不上（模型编了一个新键）时退回模型给的 key——不因为一处对不上就丢掉整条合并结果。
     */
    private String keyFor(String key, String value, List<PreferenceItem> existing, List<PreferenceItem> incoming) {
        for (List<PreferenceItem> group : List.of(existing, incoming)) {
            if (group == null) {
                continue;
            }
            for (PreferenceItem item : group) {
                if (value.equals(item.value())) {
                    return item.key();
                }
            }
        }
        return key;
    }

    private int size(List<?> values) {
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

    /**
     * 一条「已记住的记忆」，喂给抽取提示词供模型判重。
     *
     * <p>**只传生效中的行**：过期/被删的旧说法不该参与判重，否则系统会拿患者已经放弃的说法去比。
     * 偏好与背景混在一个清单里按序号编号（模型不认 id，只认序号），靠 {@code [偏好]} / {@code [背景]}
     * 标注分组——两类不会互相合并（「怎么回答我」与「我是谁」是两回事）。
     */
    public record ExistingMemory(MemoryType type, String key, String value) {
    }

    /** 合并输入的一条偏好：key 与 value 一起送，模型不必凭空编 key */
    public record PreferenceItem(String key, String value) {
    }

    /** 解析出的判重结果（action + 旧清单序号；非 update 时 target 为 null） */
    private record Dedup(MemoryDedupAction action, Integer target) {
    }
}
