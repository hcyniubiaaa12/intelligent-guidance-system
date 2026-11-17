package com.guide.memory.service;

import com.guide.auth.service.SysConfigService;
import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.enums.MemoryDedupAction;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.service.MemoryExtractor.ExistingMemory;
import com.guide.memory.service.MemoryExtractor.MergedPreference;
import com.guide.memory.service.MemoryExtractor.PreferenceItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抽取器的模型调用与容错解析：合法 JSON、围栏、坏 JSON、空输出、模型异常、合并失败。
 *
 * <p>校验规则本身由 {@code MemoryExtractValidatorTest} 覆盖；这里只钉「模型给定输出 → 读成什么」，
 * 不重复测过滤逻辑。
 */
class MemoryExtractorTest {

    private ChatModel chatModel;
    private PromptProperties prompts;
    private SysConfigService sysConfigService;
    private MemoryExtractor extractor;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        prompts = new PromptProperties();
        prompts.getMemoryExtract().setSystem("记忆抽取系统提示");
        prompts.getMemoryExtract().setUserTemplate("已记住：\n{remembered}\n对话记录：\n{dialogue}");
        prompts.getMemoryMerge().setSystem("偏好合并系统提示");
        prompts.getMemoryMerge().setUserTemplate("已记住：\n{existing}\n新抽到：\n{incoming}");
        sysConfigService = mock(SysConfigService.class);
        when(sysConfigService.getDouble(SysConfigService.KEY_MEMORY_EXTRACT_CONFIDENCE_MIN,
                SysConfigService.DEFAULT_MEMORY_EXTRACT_CONFIDENCE_MIN)).thenReturn(0.6);
        extractor = new MemoryExtractor(chatModel, prompts, sysConfigService);
    }

    @Test
    @DisplayName("背景的 retrievalText 解析：有就带出，缺失就为 null（不补默认值）")
    void parsesBackgroundRetrievalText() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[],
                 "background":[
                   {"key":"慢病","value":"2型糖尿病","type":"health","quote":"我有2型糖尿病","confidence":0.95,
                    "retrievalText":"2型糖尿病；糖尿病、血糖高"},
                   {"key":"生活","value":"长期夜班","type":"other","quote":"我一直上夜班","confidence":0.9}]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我有2型糖尿病，我一直上夜班");

        assertThat(result.backgrounds()).hasSize(2);
        assertThat(result.backgrounds().get(0).retrievalText())
                .isEqualTo("2型糖尿病；糖尿病、血糖高");
        assertThat(result.backgrounds().get(1).retrievalText()).isNull();
    }

    @Test
    @DisplayName("合法 JSON：偏好与背景两类都解析出来，对话原文进 user 消息")
    @SuppressWarnings("unchecked")
    void parsesPreferencesAndBackground() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[{"key":"回答方式","value":"讲慢一点","quote":"我希望你讲慢一点","confidence":0.9}],
                 "background":[{"key":"慢病","value":"2型糖尿病","type":"health","quote":"我有2型糖尿病","confidence":0.95,"validTo":null}]}
                """);

        // quote 必须是对话里的原话片段（工单 02 起偏好也校验这一条）：fixture 与 quote 对齐
        MemoryExtractionResult result = extractor.extract("患者：我有2型糖尿病，我希望你讲慢一点");

        assertThat(result.preferences()).extracting(p -> p.value()).containsExactly("讲慢一点");
        assertThat(result.backgrounds()).extracting(b -> b.value()).containsExactly("2型糖尿病");
        assertThat(result.backgrounds().get(0).type()).isEqualTo("health");
        assertThat(result.backgrounds().get(0).validTo()).isNull();

        ArgumentCaptor<List<ChatMsg>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        assertThat(captor.getValue().get(0).content()).isEqualTo("记忆抽取系统提示");
        assertThat(captor.getValue().get(1).content()).contains("患者：我有2型糖尿病，我希望你讲慢一点");
    }

    @Test
    @DisplayName("代码围栏与前后解释：截取第一个 { 到最后一个 } 仍能解析")
    void toleratesFencesAndPreamble() {
        when(chatModel.chat(any())).thenReturn("""
                好的，结果如下：
                ```json
                {"preferences":[{"key":"称呼","value":"叫我老王","quote":"叫我老王就行","confidence":0.8}],"background":[]}
                ```
                以上。
                """);

        MemoryExtractionResult result = extractor.extract("患者：叫我老王就行");

        assertThat(result.preferences()).extracting(p -> p.value()).containsExactly("叫我老王");
    }

    @Test
    @DisplayName("坏 JSON：返回空结果、不抛（本轮不记任何东西）")
    void badJsonReturnsEmpty() {
        when(chatModel.chat(any())).thenReturn("{\"preferences\":[{\"key\":\"回答方式\"");

        MemoryExtractionResult result = extractor.extract("患者：说慢点");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("空输出 / 非 JSON：返回空结果、不抛")
    void emptyOutputReturnsEmpty() {
        when(chatModel.chat(any())).thenReturn("", "抱歉，我无法完成该请求。");

        assertThat(extractor.extract("患者：说慢点").isEmpty()).isTrue();
        assertThat(extractor.extract("患者：说慢点").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("模型异常：返回空结果、不抛（抽取是旁路，不能影响对话）")
    void modelFailureReturnsEmpty() {
        when(chatModel.chat(any())).thenThrow(new RuntimeException("upstream 502"));

        assertThat(extractor.extract("患者：说慢点").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("低置信度 / 缺原话：解析后由校验层丢弃")
    void validationFiltersParsedDrafts() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[
                  {"key":"回答方式","value":"讲慢一点","quote":"我希望讲慢一点","confidence":0.4},
                  {"key":"称呼","value":"叫我老王","quote":"","confidence":0.9}],
                 "background":[]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我希望讲慢一点，叫我老王");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("背景 validTo 支持 YYYY-MM-DD 与 YYYY-MM（后者取当月 1 日）；类型缺失按 health")
    void parsesValidToAndDefaultType() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[],
                 "background":[
                  {"key":"慢病","value":"2型糖尿病","quote":"我有2型糖尿病","confidence":0.9,"validTo":"2023-06-30"},
                  {"key":"慢病","value":"高血压","quote":"我一直有高血压","confidence":0.9,"validTo":"2020-05"}]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我有2型糖尿病，一直有高血压");

        assertThat(result.backgrounds()).hasSize(2);
        assertThat(result.backgrounds().get(0).validTo()).isEqualTo(LocalDate.of(2023, 6, 30));
        assertThat(result.backgrounds().get(1).validTo()).isEqualTo(LocalDate.of(2020, 5, 1));
        assertThat(result.backgrounds()).allMatch(b -> "health".equals(b.type()));
    }

    @Test
    @DisplayName("profile_candidates：解析三类字段，非法字段 / 未来月份 / 低置信度在校验层丢弃")
    void parsesProfileCandidatesAndDropsInvalid() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[],"background":[],
                 "profile_candidates":[
                  {"field":"allergy","value":"青霉素","occurredOn":null,"quote":"我青霉素过敏","confidence":0.95},
                  {"field":"medication","value":"二甲双胍","occurredOn":"2024-03","quote":"长期吃二甲双胍","confidence":0.9},
                  {"field":"surgery","value":"阑尾切除术","occurredOn":"2023-10","quote":"做过阑尾切除术","confidence":0.9},
                  {"field":"chronic","value":"糖尿病","occurredOn":null,"quote":"我有糖尿病","confidence":0.9},
                  {"field":"surgery","value":"胆囊切除术","occurredOn":"2999-01","quote":"做过胆囊切除术","confidence":0.9},
                  {"field":"allergy","value":"海鲜","occurredOn":null,"quote":"我海鲜过敏","confidence":0.3}]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我青霉素过敏、长期吃二甲双胍、做过阑尾切除术");

        assertThat(result.candidates()).extracting(c -> c.field())
                .containsExactly("allergy", "medication", "surgery");
        assertThat(result.candidates()).extracting(c -> c.value())
                .containsExactly("青霉素", "二甲双胍", "阑尾切除术");
        assertThat(result.candidates().get(1).occurredOn()).isEqualTo("2024-03");
        // 候选绝不混进偏好 / 背景
        assertThat(result.preferences()).isEmpty();
        assertThat(result.backgrounds()).isEmpty();
    }

    @Test
    @DisplayName("模型仍把过敏事实塞进 background：兜底丢弃（宁可不记，也不写错地方）")
    void dropsProfileFactsFromBackgroundOutput() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[],
                 "background":[{"key":"过敏","value":"青霉素","type":"health","quote":"我青霉素过敏","confidence":0.95}],
                 "profile_candidates":[{"field":"allergy","value":"青霉素","occurredOn":null,"quote":"我青霉素过敏","confidence":0.95}]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我青霉素过敏");

        assertThat(result.backgrounds()).isEmpty();
        assertThat(result.candidates()).extracting(c -> c.value()).containsExactly("青霉素");
    }

    @Test
    @DisplayName("判重协议：action=update 带 target、action=skip 读出来；target 只对 update 有意义，其余动作清空")
    void parsesDedupActions() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[
                  {"key":"回答方式","value":"讲慢一点","quote":"我希望你讲慢一点","confidence":0.9,
                   "action":"update","target":1}],
                 "background":[
                  {"key":"慢病","value":"2型糖尿病，控制良好","type":"health","quote":"我有2型糖尿病，控制良好",
                   "confidence":0.9,"validTo":null,"action":"skip","target":2}]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我有2型糖尿病，控制良好，我希望你讲慢一点",
                List.of(new ExistingMemory(MemoryType.PREFERENCE, "回答方式", "讲慢一点"),
                        new ExistingMemory(MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病")));

        assertThat(result.preferences()).hasSize(1);
        assertThat(result.preferences().get(0).action()).isEqualTo(MemoryDedupAction.UPDATE);
        assertThat(result.preferences().get(0).target()).isEqualTo(1);
        assertThat(result.backgrounds()).hasSize(1);
        assertThat(result.backgrounds().get(0).action()).isEqualTo(MemoryDedupAction.SKIP);
        // skip 什么都不写，序号留着只会让下游以为它指得到某条旧记忆
        assertThat(result.backgrounds().get(0).target()).isNull();
    }

    @Test
    @DisplayName("判重协议降级：action 非法 / 缺失、target 缺失 / 越界 / 非数字，一律按 new 且 target 清空")
    void dedupProtocolFallsBackToNew() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[
                  {"key":"a","value":"甲","quote":"我希望甲","confidence":0.9,"action":"merge","target":1},
                  {"key":"b","value":"乙","quote":"我希望乙","confidence":0.9,"action":"update"},
                  {"key":"c","value":"丙","quote":"我希望丙","confidence":0.9,"action":"update","target":9},
                  {"key":"d","value":"丁","quote":"我希望丁","confidence":0.9,"action":"update","target":0},
                  {"key":"e","value":"戊","quote":"我希望戊","confidence":0.9,"action":"update","target":"第一条"},
                  {"key":"g","value":"庚","quote":"我希望庚","confidence":0.9,"action":"update","target":1.5},
                  {"key":"f","value":"己","quote":"我希望己","confidence":0.9}],
                 "background":[]}
                """);

        MemoryExtractionResult result = extractor.extract(
                "患者：我希望甲。我希望乙。我希望丙。我希望丁。我希望戊。我希望庚。我希望己",
                List.of(new ExistingMemory(MemoryType.PREFERENCE, "回答方式", "讲慢一点")));

        assertThat(result.preferences()).hasSize(7);
        assertThat(result.preferences()).allSatisfy(p -> {
            assertThat(p.action()).isEqualTo(MemoryDedupAction.NEW);
            assertThat(p.target()).isNull();
        });
    }

    @Test
    @DisplayName("判重协议：旧清单为空时 update 必然越界 ⇒ 也是 new")
    void updateWithoutExistingFallsBackToNew() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[{"key":"回答方式","value":"讲慢一点","quote":"我希望你讲慢一点",
                 "confidence":0.9,"action":"update","target":1}],"background":[]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我希望你讲慢一点");

        assertThat(result.preferences()).hasSize(1);
        assertThat(result.preferences().get(0).action()).isEqualTo(MemoryDedupAction.NEW);
        assertThat(result.preferences().get(0).target()).isNull();
    }

    @Test
    @DisplayName("已记住的记忆：按序号编号、分组标注 [偏好] / [背景]，无条目写「（无）」")
    @SuppressWarnings("unchecked")
    void existingMemoriesEnterUserMessage() {
        when(chatModel.chat(any())).thenReturn("{\"preferences\":[],\"background\":[]}");

        extractor.extract("患者：说慢点",
                List.of(new ExistingMemory(MemoryType.PREFERENCE, "回答方式", "讲慢一点"),
                        new ExistingMemory(MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病")));

        ArgumentCaptor<List<ChatMsg>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        String user = captor.getValue().get(1).content();
        assertThat(user).contains("1. [偏好] 回答方式：讲慢一点");
        assertThat(user).contains("2. [背景] 慢病：2型糖尿病");
        assertThat(user).contains("患者：说慢点");

        resetChat();
        when(chatModel.chat(any())).thenReturn("{\"preferences\":[],\"background\":[]}");
        extractor.extract("患者：说慢点");

        ArgumentCaptor<List<ChatMsg>> emptyCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(emptyCaptor.capture());
        assertThat(emptyCaptor.getValue().get(1).content()).contains("（无）");
    }

    @Test
    @DisplayName("只收对话文本的重载：等价于旧清单为空（金标集与既有单测据此沿用）")
    void singleArgOverloadEqualsEmptyExisting() {
        when(chatModel.chat(any())).thenReturn("{\"preferences\":[],\"background\":[]}");

        extractor.extract("患者：说慢点");

        assertThat(extractor.extract("患者：说慢点", List.of())).isNotNull();
    }

    @Test
    @DisplayName("合并成功：解析合并后的 key/value，并校验两个占位符都替换成「键：值」清单")
    @SuppressWarnings("unchecked")
    void mergeParsesResult() {
        when(chatModel.chat(any())).thenReturn(
                "{\"preferences\":[{\"key\":\"回答方式\",\"value\":\"讲慢一点\"},{\"key\":\"称呼\",\"value\":\"叫我老王\"}]}");

        List<MergedPreference> merged = extractor.merge(
                List.of(new PreferenceItem("回答方式", "讲慢一点")),
                List.of(new PreferenceItem("称呼", "叫我老王")));

        assertThat(merged).extracting(MergedPreference::value).containsExactly("讲慢一点", "叫我老王");
        ArgumentCaptor<List<ChatMsg>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        String user = captor.getValue().get(1).content();
        assertThat(user).contains("- 回答方式：讲慢一点", "- 称呼：叫我老王");
        assertThat(user).doesNotContain("{existing}", "{incoming}");
    }

    @Test
    @DisplayName("合并结果的 key 不靠模型编：值能对上输入项时沿用输入项的 key")
    void mergeReusesKeyFromInputs() {
        when(chatModel.chat(any())).thenReturn(
                "{\"preferences\":[{\"key\":\"我自己编的\",\"value\":\"讲慢一点\"}]}");

        List<MergedPreference> merged = extractor.merge(
                List.of(new PreferenceItem("回答方式", "讲慢一点")),
                List.of(new PreferenceItem("语速", "慢点")));

        assertThat(merged).extracting(MergedPreference::key).containsExactly("回答方式");
    }

    @Test
    @DisplayName("合并模型异常 / 输出不可解析：抛 IllegalStateException（调用方据此丢弃本批新偏好）")
    void mergeFailureThrows() {
        when(chatModel.chat(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn("不是 JSON");

        assertThatThrownBy(() -> extractor.merge(
                List.of(new PreferenceItem("回答方式", "a")), List.of(new PreferenceItem("称呼", "b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("合并模型调用失败");
        assertThatThrownBy(() -> extractor.merge(
                List.of(new PreferenceItem("回答方式", "a")), List.of(new PreferenceItem("称呼", "b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("合并输出不可解析");
    }

    private void resetChat() {
        org.mockito.Mockito.reset(chatModel);
    }
}
