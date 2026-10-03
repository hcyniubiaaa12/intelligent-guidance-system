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
 * 检索装配单测（单据 03 缝二）：档案检索用串非空 ⇒ 多开两路召回、RRF 输入 4 个排名列表；
 * 为空 ⇒ 与今天的两路召回完全一致、不产生任何额外调用。
 *
 * <p>只断言**外部行为（发出的调用）**，不断言实现细节：mock 掉 embedding / 向量库 / 关键词库 / 精排，
 * 用调用次数与参数证明"多开了哪两路、精排用的是哪条串"。RRF 是静态纯函数（无 mock 点），
 * 故以"四路各回的片全部进入融合"作为它收到 4 个列表的行为证据。
 *
 * <p><b>精排 query 仍是主诉串</b>是"档案不能决定推荐科室"在检索层的结构性保证，
 * 有一条用例专门钉住它，防止后续"为了让档案更有效"把档案混进精排 query。
 */
class RagServiceTest {

    private static final String MAIN = "胸口闷";
    private static final String PROFILE_QUERY = "男、45-59岁、糖尿病史";
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
        // 默认两路都空；各用例按需覆盖
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
    @DisplayName("检索用串非空 ⇒ 多一次 embedding + 两路召回，RRF 收到 4 个排名列表")
    void profileQueryExpandsToFourLists() {
        // 主诉向量路 v1 / 档案向量路 pv1；主诉关键词路 e1 / 档案关键词路 pe1——四路各不相同
        when(pgVectorUtil.searchChunks(any(), anyInt()))
                .thenReturn(List.of(hit("v1")))
                .thenReturn(List.of(hit("pv1")));
        when(esChunkUtil.searchChunks(anyString(), anyInt()))
                .thenReturn(List.of(hit("e1")))
                .thenReturn(List.of(hit("pe1")));

        RagContext context = ragService.retrieve(request(MAIN, PROFILE_QUERY));

        // 四路的片全部进入融合（union=4）⇒ 证据：RRF 确实吃到了 4 个排名列表
        assertThat(context.chunks()).extracting(ChunkHit::chunkId)
                .containsExactlyInAnyOrder("v1", "e1", "pv1", "pe1");
        verify(pgVectorUtil, times(2)).searchChunks(any(), eq(TOP_K));
        verify(esChunkUtil, times(2)).searchChunks(anyString(), eq(TOP_K));

        // 档案路检索用串真的被送去向量化与关键词召回
        ArgumentCaptor<String> embedTexts = ArgumentCaptor.forClass(String.class);
        verify(embeddingModel, times(2)).embed(embedTexts.capture());
        assertThat(embedTexts.getAllValues()).containsExactlyInAnyOrder(MAIN, PROFILE_QUERY);
        verify(esChunkUtil).searchChunks(eq(PROFILE_QUERY), eq(TOP_K));
    }

    @Test
    @DisplayName("检索用串为空 ⇒ RRF 收到 2 个排名列表（回归：与今天完全一致）")
    void emptyProfileQueryKeepsTwoLists() {
        when(pgVectorUtil.searchChunks(any(), anyInt())).thenReturn(List.of(hit("v1")));
        when(esChunkUtil.searchChunks(anyString(), anyInt())).thenReturn(List.of(hit("e1")));

        RagContext context = ragService.retrieve(request(MAIN, null));

        assertThat(context.chunks()).extracting(ChunkHit::chunkId)
                .containsExactlyInAnyOrder("v1", "e1");
        verify(pgVectorUtil, times(1)).searchChunks(any(), anyInt());
        verify(esChunkUtil, times(1)).searchChunks(anyString(), anyInt());
    }

    @Test
    @DisplayName("检索用串为空 ⇒ 不产生额外的 embedding 调用（只对主诉串向量化一次）")
    void emptyProfileQueryAddsNoEmbeddingCall() {
        when(pgVectorUtil.searchChunks(any(), anyInt())).thenReturn(List.of(hit("v1")));
        when(esChunkUtil.searchChunks(anyString(), anyInt())).thenReturn(List.of());

        ragService.retrieve(request(MAIN, null));

        ArgumentCaptor<String> embedTexts = ArgumentCaptor.forClass(String.class);
        verify(embeddingModel, times(1)).embed(embedTexts.capture());
        assertThat(embedTexts.getValue()).isEqualTo(MAIN);
    }

    @Test
    @DisplayName("精排的 query 是主诉串，未被档案污染（档案没有排序话语权）")
    void rerankQueryIsMainComplaintNotProfile() {
        when(pgVectorUtil.searchChunks(any(), anyInt()))
                .thenReturn(List.of(hit("v1")))
                .thenReturn(List.of(hit("pv1")));
        when(esChunkUtil.searchChunks(anyString(), anyInt()))
                .thenReturn(List.of(hit("e1")))
                .thenReturn(List.of(hit("pe1")));

        ragService.retrieve(request(MAIN, PROFILE_QUERY));

        ArgumentCaptor<String> rerankQuery = ArgumentCaptor.forClass(String.class);
        verify(rerankModel).rerank(rerankQuery.capture(), anyList(), anyInt());
        assertThat(rerankQuery.getValue()).isEqualTo(MAIN);
        assertThat(rerankQuery.getValue()).doesNotContain("糖尿病史");
    }

    private RagRequest request(String query, String profileQuery) {
        return new RagRequest(query, List.of(), List.of(new DeptOption("d1", "心血管内科")),
                0, false, TOP_K, 3, profileQuery, profileQuery);
    }

    private ChunkHit hit(String id) {
        return new ChunkHit(id, "dept-" + id, "标题" + id, "正文" + id, 0.9);
    }
}
