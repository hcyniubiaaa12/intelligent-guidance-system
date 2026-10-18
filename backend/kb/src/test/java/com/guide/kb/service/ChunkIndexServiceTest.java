package com.guide.kb.service;

import com.guide.common.util.EsChunkUtil;
import com.guide.common.util.PgVectorUtil;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.mapper.KbChunkMapper;
import com.guide.llm.client.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ES 写入侧的单字过滤：**只此一处实现**，种子语料与上传文档走同一条路。
 *
 * <p>为什么必须挡：{@code medical_terms} 参与关键词打分后，一个「胸」会命中半库切片，
 * 把真正相关的正文片挤出 Top-K。这与 {@code MedicalTermService} 的 2 字下限同源——
 * 种子语料里手写的单字词（「胸」「肺」）是给演示用的历史集合，不该在写入侧原样放行。
 */
class ChunkIndexServiceTest {

    private final KbChunkMapper chunkMapper = mock(KbChunkMapper.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final PgVectorUtil pgVectorUtil = mock(PgVectorUtil.class);
    private final EsChunkUtil esChunkUtil = mock(EsChunkUtil.class);

    private final ChunkIndexService service = new ChunkIndexService(
            chunkMapper, embeddingModel, pgVectorUtil, esChunkUtil);

    @Test
    @DisplayName("写入 ES 时丢掉单字术语，多字词原样保留")
    void filtersSingleCharTermsWhenWritingToEs() {
        when(embeddingModel.embedBatch(anyList())).thenReturn(List.of(new float[]{0.1f}));

        service.indexChunks("doc-1", "dept-1", List.of(new ChunkInput(
                "心悸与心律失常", "自觉心跳过快、漏跳感。", 1,
                List.of("心悸", "心慌", "心", "黑矇"), null)));

        ArgumentCaptor<List<EsChunkUtil.ChunkDoc>> docs = ArgumentCaptor.forClass(List.class);
        verify(esChunkUtil).indexChunks(docs.capture());
        assertThat(docs.getValue()).singleElement()
                .satisfies(doc -> assertThat(doc.medicalTerms())
                        .containsExactly("心悸", "心慌", "黑矇"));
    }

    @Test
    @DisplayName("回流合成 chunk 不做术语抽取：只有一片、文本由人定稿，术语在预览时由人决定")
    void syntheticChunksCarryNoTerms() {
        when(embeddingModel.embedBatch(anyList())).thenReturn(List.of(new float[]{0.1f}));
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        service.indexSyntheticChunk("doc-1", "dept-1", "回流补充", "胸闷时应首先考虑心血管内科。");

        ArgumentCaptor<List<EsChunkUtil.ChunkDoc>> docs = ArgumentCaptor.forClass(List.class);
        verify(esChunkUtil).indexChunks(docs.capture());
        assertThat(docs.getValue()).singleElement()
                .satisfies(doc -> assertThat(doc.medicalTerms()).isEmpty());
    }
}
