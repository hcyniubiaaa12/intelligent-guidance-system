package com.guide.llm.service;

import com.guide.llm.client.ChatModel;
import com.guide.llm.client.EmbeddingModel;
import com.guide.llm.client.RerankModel;
import com.guide.llm.config.LlmProperties;
import com.guide.llm.dto.LlmDiagDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 连通性自检单测：三路结果互不影响、失败原因对密钥脱敏、维度不一致要报错。
 */
class LlmDiagnosticServiceTest {

    private static final String CHAT_KEY = "sk-deepseek-secret-1234";
    private static final String DASH_KEY = "sk-dashscope-secret-5678";

    private ChatModel chatModel;
    private EmbeddingModel embeddingModel;
    private RerankModel rerankModel;
    private LlmProperties properties;
    private LlmDiagnosticService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        embeddingModel = mock(EmbeddingModel.class);
        rerankModel = mock(RerankModel.class);
        properties = new LlmProperties();
        properties.getDeepseek().setApiKey(CHAT_KEY);
        properties.getDeepseek().setModel("deepseek-chat");
        properties.getDeepseek().setBaseUrl("https://api.deepseek.com");
        properties.getDashscope().setApiKey(DASH_KEY);
        properties.getDashscope().setEmbeddingModel("text-embedding-v3");
        properties.getDashscope().setRerankModel("qwen3-rerank");
        properties.getDashscope().setEmbeddingDimension(4);
        service = new LlmDiagnosticService(chatModel, embeddingModel, rerankModel, properties);
    }

    private static float[] vector(int size) {
        float[] v = new float[size];
        for (int i = 0; i < size; i++) {
            v[i] = 0.1f;
        }
        return v;
    }

    private static LlmDiagDTO.ProbeResult byKind(List<LlmDiagDTO.ProbeResult> results, String kind) {
        return results.stream().filter(r -> kind.equals(r.getKind())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("三路都通时全绿，并给出可核对的摘要（返回字数/维度/最高分）")
    void allProbesOk() {
        when(chatModel.chat(anyList())).thenReturn("你好，请描述症状");
        when(embeddingModel.embed(anyString())).thenReturn(vector(4));
        when(rerankModel.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModel.RerankHit(1, 0.93), new RerankModel.RerankHit(0, 0.41)));

        List<LlmDiagDTO.ProbeResult> results = service.probeAll();

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(r -> Boolean.TRUE.equals(r.getOk()));
        assertThat(byKind(results, "chat").getDetail()).contains("返回 8 字");
        assertThat(byKind(results, "embedding").getDetail()).contains("向量维度 4");
        assertThat(byKind(results, "rerank").getDetail()).contains("0.9300");
        assertThat(results).allMatch(r -> r.getLatencyMs() != null);
    }

    @Test
    @DisplayName("一路失败不拖累另外两路：各自独立兜异常")
    void oneFailureDoesNotAffectOthers() {
        when(chatModel.chat(anyList())).thenReturn("ok");
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("连接超时"));
        when(rerankModel.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModel.RerankHit(0, 0.5)));

        List<LlmDiagDTO.ProbeResult> results = service.probeAll();

        assertThat(byKind(results, "chat").getOk()).isTrue();
        assertThat(byKind(results, "rerank").getOk()).isTrue();
        LlmDiagDTO.ProbeResult embedding = byKind(results, "embedding");
        assertThat(embedding.getOk()).isFalse();
        assertThat(embedding.getDetail()).contains("连接超时");
    }

    @Test
    @DisplayName("失败原因里的密钥被抹掉：上游响应体带 Key 片段时不能回给管理端")
    void failureDetailMasksApiKey() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException(
                "401 Unauthorized: {\"request\":\"Bearer " + CHAT_KEY + "\",\"code\":\"invalid_api_key\"}"));
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException(
                "403 Forbidden, header Authorization=" + DASH_KEY));
        when(rerankModel.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModel.RerankHit(0, 0.5)));

        List<LlmDiagDTO.ProbeResult> results = service.probeAll();

        String chatDetail = byKind(results, "chat").getDetail();
        String embeddingDetail = byKind(results, "embedding").getDetail();
        assertThat(chatDetail).doesNotContain(CHAT_KEY).contains("******").contains("invalid_api_key");
        assertThat(embeddingDetail).doesNotContain(DASH_KEY).contains("******");
    }

    @Test
    @DisplayName("模型回空内容算失败，不能因为「没抛异常」就判绿")
    void blankReplyIsFailure() {
        when(chatModel.chat(anyList())).thenReturn("   ");
        when(embeddingModel.embed(anyString())).thenReturn(vector(4));
        when(rerankModel.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModel.RerankHit(0, 0.5)));

        LlmDiagDTO.ProbeResult chat = byKind(service.probeAll(), "chat");

        assertThat(chat.getOk()).isFalse();
        assertThat(chat.getDetail()).contains("空内容");
    }

    @Test
    @DisplayName("向量维度与配置不一致要报错并提示重建向量")
    void embeddingDimMismatchIsFailure() {
        when(chatModel.chat(anyList())).thenReturn("ok");
        when(embeddingModel.embed(anyString())).thenReturn(vector(1024));
        when(rerankModel.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModel.RerankHit(0, 0.5)));

        LlmDiagDTO.ProbeResult embedding = byKind(service.probeAll(), "embedding");

        assertThat(embedding.getOk()).isFalse();
        assertThat(embedding.getDetail()).contains("1024").contains("重建向量");
    }

    @Test
    @DisplayName("超长失败原因被截断，避免上游响应体糊满页面")
    void longDetailIsTruncated() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("x".repeat(500)));
        when(embeddingModel.embed(anyString())).thenReturn(vector(4));
        when(rerankModel.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankModel.RerankHit(0, 0.5)));

        String detail = byKind(service.probeAll(), "chat").getDetail();

        assertThat(detail).endsWith("…");
        assertThat(detail.length()).isLessThanOrEqualTo(161);
    }

    @Test
    @DisplayName("模型接入信息：模型名与地址可读，密钥只回是否已配置")
    void modelInfoExposesFlagsNotSecrets() {
        LlmDiagDTO.ModelInfo info = service.modelInfo();

        assertThat(info.getChatModel()).isEqualTo("deepseek-chat");
        assertThat(info.getChatBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(info.getChatKeySet()).isTrue();
        assertThat(info.getEmbeddingDim()).isEqualTo(4);
        assertThat(info.getEmbeddingKeySet()).isTrue();
        assertThat(info.getRerankModel()).isEqualTo("qwen3-rerank");

        properties.getDeepseek().setApiKey("  ");
        properties.getDashscope().setApiKey(null);
        LlmDiagDTO.ModelInfo unset = service.modelInfo();
        assertThat(unset.getChatKeySet()).isFalse();
        assertThat(unset.getEmbeddingKeySet()).isFalse();
        assertThat(unset.getRerankKeySet()).isFalse();
    }
}
