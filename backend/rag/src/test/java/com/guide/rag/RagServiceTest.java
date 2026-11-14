package com.guide.rag;

import com.guide.common.config.PromptProperties;
import com.guide.common.model.ChunkHit;
import com.guide.common.util.EsChunkUtil;
import com.guide.common.util.PgVectorUtil;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.EmbeddingModel;
import com.guide.llm.client.RerankModel;
import com.guide.rag.dto.DeptOption;
import com.guide.rag.dto.RagContext;
import com.guide.rag.dto.RagRequest;
import com.guide.rag.spi.ChunkTextProvider;
import com.guide.rag.support.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索装配单测：检索固定是**两路召回**——主诉向量 + 主诉关键词，各取 topK 后进 RRF。
 *
 * <p>健康档案已退出检索层（本单据）：{@code RagRequest} 不再有档案召回串，
 * 因此这里钉住「一次 embedding、两路检索、精排只吃主诉串」。
 * 档案若被重新塞回检索层，这些用例会先红。
 *
 * <p>只断言**外部行为（发出的调用）**，不断言实现细节：mock 掉 embedding / 向量库 / 关键词库 / 精排，
 * 用调用次数与参数证明"只有两路"与"精排 query 是主诉"。
 */
class RagServiceTest {

    private static final String MAIN = "胸口闷";
    private static final int TOP_K = 5;

    private EmbeddingModel embeddingModel;
    private RerankModel rerankModel;
    private PgVectorUtil pgVectorUtil;
    private EsChunkUtil esChunkUtil;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        rerankModel = mock(RerankModel.class);
        ChatModel chatModel = mock(ChatModel.class);
        pgVectorUtil = mock(PgVectorUtil.class);
        esChunkUtil = mock(EsChunkUtil.class);
        ChunkTextProvider chunkTextProvider = mock(ChunkTextProvider.class);

        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(pgVectorUtil.searchChunks(any(), anyInt())).thenReturn(List.of());
        when(esChunkUtil.searchChunks(anyString(), anyInt())).thenReturn(List.of());
        // 正文回填：召回命中的 id 都当作在库（正文由回填端口给，rag 不依赖业务库）
        when(chunkTextProvider.loadTexts(any())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            Map<String, ChunkTextProvider.ChunkText> texts = new HashMap<>();
            for (String id : ids) {
                texts.put(id, new ChunkTextProvider.ChunkText("标题" + id, "正文" + id));
            }
            return texts;
        });
        // 精排：把送来的候选全部按序返回（本测试关注"融合进了哪些片"，不关心重排顺序）
        when(rerankModel.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<?> documents = invocation.getArgument(1);
            List<RerankModel.RerankHit> hits = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                hits.add(new RerankModel.RerankHit(i, 1.0 - i * 0.01));
            }
            return hits;
        });

        ragService = new RagService(embeddingModel, rerankModel, chatModel, pgVectorUtil, esChunkUtil,
                chunkTextProvider, mock(PromptBuilder.class), mock(PromptProperties.class));
    }

    @Test
    @DisplayName("检索固定两路召回：向量路与关键词路各一条排名列表，全部进入融合")
    void retrievesExactlyTwoLists() {
        when(pgVectorUtil.searchChunks(any(), anyInt())).thenReturn(List.of(hit("v1")));
        when(esChunkUtil.searchChunks(anyString(), anyInt())).thenReturn(List.of(hit("e1")));

        RagContext context = ragService.retrieve(request(MAIN));

        assertThat(context.chunks()).extracting(ChunkHit::chunkId)
                .containsExactlyInAnyOrder("v1", "e1");
        verify(pgVectorUtil, times(1)).searchChunks(any(), eq(TOP_K));
        verify(esChunkUtil, times(1)).searchChunks(anyString(), eq(TOP_K));
    }

    @Test
    @DisplayName("只对主诉串向量化一次：不再有档案召回串的第二路 embedding")
    void embedsMainComplaintOnlyOnce() {
        when(pgVectorUtil.searchChunks(any(), anyInt())).thenReturn(List.of(hit("v1")));
        when(esChunkUtil.searchChunks(anyString(), anyInt())).thenReturn(List.of());

        ragService.retrieve(request(MAIN));

        ArgumentCaptor<String> embedTexts = ArgumentCaptor.forClass(String.class);
        verify(embeddingModel, times(1)).embed(embedTexts.capture());
        assertThat(embedTexts.getValue()).isEqualTo(MAIN);
    }

    @Test
    @DisplayName("精排的 query 是主诉串，未被档案污染（档案没有排序话语权）")
    void rerankQueryIsMainComplaint() {
        when(pgVectorUtil.searchChunks(any(), anyInt())).thenReturn(List.of(hit("v1")));
        when(esChunkUtil.searchChunks(anyString(), anyInt())).thenReturn(List.of(hit("e1")));

        ragService.retrieve(request(MAIN));

        ArgumentCaptor<String> rerankQuery = ArgumentCaptor.forClass(String.class);
        verify(rerankModel).rerank(rerankQuery.capture(), anyList(), anyInt());
        assertThat(rerankQuery.getValue()).isEqualTo(MAIN);
    }

    private RagRequest request(String query) {
        return new RagRequest(query, List.of(), List.of(new DeptOption("d1", "心血管内科", "诊治心脏与血管疾病。")),
                false, TOP_K, 3, null, List.of());
    }

    private ChunkHit hit(String id) {
        return new ChunkHit(id, "dept-" + id, "标题" + id, "正文" + id, 0.9);
    }
}
