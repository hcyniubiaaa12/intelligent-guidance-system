package com.guide.kb.service;

import com.guide.common.config.PromptProperties;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.dto.ExtractedTerm;
import com.guide.kb.enums.TermType;
import com.guide.kb.mapper.MedicalTermMapper;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抽取器的输出协议与失败语义。
 *
 * <p>这里钉住的三件事都来自"抽取是旁路"这个定位：<b>输出不合协议不能把词丢掉</b>
 * （类型错了人能在管理端改，词丢了更亏）、<b>单字必须挡在入口</b>（信息充足性门槛是子串匹配）、
 * <b>失败绝不上抛</b>（一次模型抖动不能让整份文档白解析，DocumentMind 按次计费）。
 */
class TermExtractorTest {

    private static final String DOC_ID = "1001";

    private final ChatModel chatModel = mock(ChatModel.class);
    // 真校验：词面规则只此一处，抽取器用的是 MedicalTermService 的同一套
    private final MedicalTermService medicalTermService = new MedicalTermService(
            mock(MedicalTermMapper.class), mock(com.guide.auth.service.SysConfigService.class));
    private final PromptProperties prompts = new PromptProperties();

    private TermExtractor extractor;

    @BeforeEach
    void setUp() {
        prompts.getTermExtract().setSystem("从文本中抽取医学术语，输出 JSON 数组。");
        prompts.getTermExtract().setUserTemplate("文本：{text}");
        extractor = new TermExtractor(chatModel, prompts, medicalTermService);
    }

    @Test
    @DisplayName("正常输出：词与类型都落进结果，且随本批切片下发给 ES")
    void parsesTermsAndAppliesThemToBatchChunks() {
        when(chatModel.chat(anyList())).thenReturn(
                "[{\"term\":\"胸闷\",\"type\":\"symptom\"},{\"term\":\"左腹部\",\"type\":\"part\"}]");

        TermExtractor.ExtractionResult result = extractor.extract(DOC_ID, chunks(2));

        assertThat(result.terms()).containsExactly(
                new ExtractedTerm("胸闷", TermType.SYMPTOM),
                new ExtractedTerm("左腹部", TermType.PART));
        // 同一批的每片都带上本批抽出的词（ES 一次写入即带 terms，不走二次更新）
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks()).allSatisfy(c -> assertThat(c.terms()).containsExactly("胸闷", "左腹部"));
    }

    @Test
    @DisplayName("非法 type 降级为 symptom 而不是丢掉该词：类型错了人能改，词丢了更亏")
    void downgradesUnknownTypeToSymptom() {
        when(chatModel.chat(anyList())).thenReturn(
                "[{\"term\":\"反酸\",\"type\":\"disease\"},{\"term\":\"烧心\"}]");

        List<ExtractedTerm> terms = extractor.extract(DOC_ID, chunks(1)).terms();

        assertThat(terms).containsExactly(
                new ExtractedTerm("反酸", TermType.SYMPTOM),
                new ExtractedTerm("烧心", TermType.SYMPTOM));
    }

    @Test
    @DisplayName("单字 / 超长 / 内部空白一律丢弃：单字会让几乎所有主诉都被判「含医学术语」")
    void dropsTermsThatFailTheSharedValidation() {
        when(chatModel.chat(anyList())).thenReturn("""
                [{"term":"痛","type":"symptom"},
                 {"term":"胸 闷","type":"symptom"},
                 {"term":"一二三四五六七八九十一二三四五六七八九十一","type":"symptom"},
                 {"term":"胸闷","type":"symptom"}]
                """);

        assertThat(extractor.extract(DOC_ID, chunks(1)).terms())
                .containsExactly(new ExtractedTerm("胸闷", TermType.SYMPTOM));
    }

    @Test
    @DisplayName("跨批去重：同一词在别的批里再出现，只保留一条（(term,type) 为键）")
    void dedupesAcrossBatches() {
        when(chatModel.chat(anyList()))
                .thenReturn("[{\"term\":\"胸闷\",\"type\":\"symptom\"}]",
                        "[{\"term\":\"胸闷\",\"type\":\"symptom\"},{\"term\":\"心悸\",\"type\":\"symptom\"}]");

        TermExtractor.ExtractionResult result = extractor.extract(DOC_ID, chunks(40));

        assertThat(result.terms()).containsExactly(
                new ExtractedTerm("胸闷", TermType.SYMPTOM),
                new ExtractedTerm("心悸", TermType.SYMPTOM));
        // 每批的词只挂在本批切片上：第二批的两片带着它，第一批的两片仍只有第一批的词
        assertThat(result.chunks().get(0).terms()).containsExactly("胸闷");
        assertThat(result.chunks().get(39).terms()).containsExactly("胸闷", "心悸");
    }

    @Test
    @DisplayName("输出不是 JSON / 模型抛异常：返回空结果而不是抛，切片照常带着空的 terms 返回")
    void failureReturnsEmptyInsteadOfThrowing() {
        when(chatModel.chat(anyList()))
                .thenReturn("我抽不出来")
                .thenThrow(new IllegalStateException("连接超时"));

        TermExtractor.ExtractionResult notJson = extractor.extract(DOC_ID, chunks(1));
        assertThat(notJson.terms()).isEmpty();
        assertThat(notJson.chunks()).hasSize(1);

        TermExtractor.ExtractionResult broken = extractor.extract(DOC_ID, chunks(1));
        assertThat(broken.terms()).isEmpty();
        assertThat(broken.chunks()).allSatisfy(c -> assertThat(c.terms()).isEmpty());
    }

    @Test
    @DisplayName("连续 3 批失败即熔断：之后的批次不再调模型（同一路径连错三次几乎不可能是网络抖动）")
    void breaksCircuitAfterThreeConsecutiveFailures() {
        when(chatModel.chat(anyList())).thenThrow(new IllegalStateException("鉴权失败"));

        // 5 批（100 片 / 20 片一批）：只有前 3 批真的发出去了
        extractor.extract(DOC_ID, chunks(100));

        verify(chatModel, times(3)).chat(anyList());
    }

    @Test
    @DisplayName("失败后再成功，连续计数清零：偶发单批失败不拖累后面的批次")
    void resetsFailureStreakAfterSuccess() {
        when(chatModel.chat(anyList()))
                .thenThrow(new IllegalStateException("抖动"))
                .thenThrow(new IllegalStateException("抖动"))
                .thenReturn("[{\"term\":\"胸闷\",\"type\":\"symptom\"}]")
                .thenThrow(new IllegalStateException("抖动"))
                .thenReturn("[{\"term\":\"心悸\",\"type\":\"symptom\"}]");

        TermExtractor.ExtractionResult result = extractor.extract(DOC_ID, chunks(100));

        verify(chatModel, times(5)).chat(anyList());
        assertThat(result.terms()).extracting(ExtractedTerm::term).containsExactly("胸闷", "心悸");
    }

    @Test
    @DisplayName("提示词渲染：{text} 被替换成本批切片正文（标题也带上）")
    void rendersPromptWithBatchText() {
        when(chatModel.chat(anyList())).thenReturn("[]");

        extractor.extract(DOC_ID, List.of(new ChunkInput("心血管内科", "劳力性胸闷需排查冠心病。", 1, List.of())));

        ArgumentCaptor<List<ChatMsg>> messages = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(messages.capture());
        assertThat(messages.getValue()).extracting(ChatMsg::content)
                .anySatisfy(content -> assertThat(content)
                        .contains("心血管内科").contains("劳力性胸闷需排查冠心病。"))
                .noneMatch(content -> content.contains("{text}"));
    }

    @Test
    @DisplayName("空切片列表：不调模型、直接返回空结果")
    void emptyChunksDoNotCallModel() {
        TermExtractor.ExtractionResult result = extractor.extract(DOC_ID, List.of());

        assertThat(result.chunks()).isEmpty();
        assertThat(result.terms()).isEmpty();
        verify(chatModel, times(0)).chat(any());
    }

    private List<ChunkInput> chunks(int count) {
        List<ChunkInput> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // 正文里真的出现这些词：抽到的词必须在文档里出现过（不做同义词扩展）
            chunks.add(new ChunkInput("标题", "第 " + i + " 片正文：胸闷、左腹部、反酸、烧心、心悸", i + 1, List.of()));
        }
        return chunks;
    }
}
