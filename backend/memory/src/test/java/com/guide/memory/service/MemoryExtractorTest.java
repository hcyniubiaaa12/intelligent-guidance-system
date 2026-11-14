package com.guide.memory.service;

import com.guide.auth.service.SysConfigService;
import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.service.MemoryExtractor.MergedPreference;
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
        prompts.getMemoryExtract().setUserTemplate("对话记录：\n{dialogue}");
        prompts.getMemoryMerge().setSystem("偏好合并系统提示");
        prompts.getMemoryMerge().setUserTemplate("已记住：\n{existing}\n新抽到：\n{incoming}");
        sysConfigService = mock(SysConfigService.class);
        when(sysConfigService.getDouble(SysConfigService.KEY_MEMORY_EXTRACT_CONFIDENCE_MIN,
                SysConfigService.DEFAULT_MEMORY_EXTRACT_CONFIDENCE_MIN)).thenReturn(0.6);
        extractor = new MemoryExtractor(chatModel, prompts, sysConfigService);
    }

    @Test
    @DisplayName("合法 JSON：偏好与背景两类都解析出来，对话原文进 user 消息")
    @SuppressWarnings("unchecked")
    void parsesPreferencesAndBackground() {
        when(chatModel.chat(any())).thenReturn("""
                {"preferences":[{"key":"回答方式","value":"讲慢一点","quote":"我希望你讲慢一点","confidence":0.9}],
                 "background":[{"key":"慢病","value":"2型糖尿病","type":"health","quote":"我有2型糖尿病","confidence":0.95,"validTo":null}]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：我有2型糖尿病，希望你讲慢一点");

        assertThat(result.preferences()).extracting(p -> p.value()).containsExactly("讲慢一点");
        assertThat(result.backgrounds()).extracting(b -> b.value()).containsExactly("2型糖尿病");
        assertThat(result.backgrounds().get(0).type()).isEqualTo("health");
        assertThat(result.backgrounds().get(0).validTo()).isNull();

        ArgumentCaptor<List<ChatMsg>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        assertThat(captor.getValue().get(0).content()).isEqualTo("记忆抽取系统提示");
        assertThat(captor.getValue().get(1).content()).contains("患者：我有2型糖尿病，希望你讲慢一点");
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
                  {"key":"用药","value":"二甲双胍","quote":"我以前长期吃二甲双胍","confidence":0.9,"validTo":"2023-06-30"},
                  {"key":"手术","value":"阑尾切除术","quote":"我做过阑尾切除术","confidence":0.9,"validTo":"2020-05"}]}
                """);

        MemoryExtractionResult result = extractor.extract("患者：以前长期吃二甲双胍，做过阑尾切除术");

        assertThat(result.backgrounds()).hasSize(2);
        assertThat(result.backgrounds().get(0).validTo()).isEqualTo(LocalDate.of(2023, 6, 30));
        assertThat(result.backgrounds().get(1).validTo()).isEqualTo(LocalDate.of(2020, 5, 1));
        assertThat(result.backgrounds()).allMatch(b -> "health".equals(b.type()));
    }

    @Test
    @DisplayName("合并成功：解析合并后的 key/value，并校验两个占位符都替换成清单")
    @SuppressWarnings("unchecked")
    void mergeParsesResult() {
        when(chatModel.chat(any())).thenReturn(
                "{\"preferences\":[{\"key\":\"回答方式\",\"value\":\"讲慢一点\"},{\"key\":\"称呼\",\"value\":\"叫我老王\"}]}");

        List<MergedPreference> merged = extractor.merge(List.of("讲慢一点"), List.of("叫我老王"));

        assertThat(merged).extracting(MergedPreference::value).containsExactly("讲慢一点", "叫我老王");
        ArgumentCaptor<List<ChatMsg>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        String user = captor.getValue().get(1).content();
        assertThat(user).contains("- 讲慢一点", "- 叫我老王");
        assertThat(user).doesNotContain("{existing}", "{incoming}");
    }

    @Test
    @DisplayName("合并模型异常 / 输出不可解析：抛 IllegalStateException（调用方据此丢弃本批新偏好）")
    void mergeFailureThrows() {
        when(chatModel.chat(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn("不是 JSON");

        assertThatThrownBy(() -> extractor.merge(List.of("a"), List.of("b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("合并模型调用失败");
        assertThatThrownBy(() -> extractor.merge(List.of("a"), List.of("b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("合并输出不可解析");
    }
}
