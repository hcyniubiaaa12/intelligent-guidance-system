package com.guide.kb.service;

import com.guide.common.util.EsChunkUtil;
import com.guide.common.util.MinioUtil;
import com.guide.common.util.PgVectorUtil;
import com.guide.kb.entity.KbChunk;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.mapper.KbChunkMapper;
import com.guide.kb.mapper.KbDocMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除补偿的**顺序**就是正确性：先删 ES → 再删向量 → 再删元数据 → 再删 MinIO 文件。
 * 顺序反了不会报错，只会留下"元数据没了、ES 还留着"这类半拉状态。
 */
class KbDocServiceTest {

    private static final String DOC_ID = "1001";

    private final KbDocMapper kbDocMapper = mock(KbDocMapper.class);
    private final KbChunkMapper kbChunkMapper = mock(KbChunkMapper.class);
    private final DeptService deptService = mock(DeptService.class);
    private final EsChunkUtil esChunkUtil = mock(EsChunkUtil.class);
    private final PgVectorUtil pgVectorUtil = mock(PgVectorUtil.class);
    private final MinioUtil minioUtil = mock(MinioUtil.class);

    private final KbDocService service = new KbDocService(kbDocMapper, kbChunkMapper, deptService,
            esChunkUtil, pgVectorUtil, minioUtil);

    @Test
    @DisplayName("删除文档：ES → 向量 → 元数据 → MinIO，一步不差、一步不倒")
    void deleteKeepsCompensationOrder() {
        when(kbDocMapper.selectById(DOC_ID)).thenReturn(doc("kb/" + DOC_ID + "/心内科.pdf"));
        when(kbChunkMapper.selectList(any())).thenReturn(List.of(chunk("c1"), chunk("c2")));

        service.delete(DOC_ID);

        InOrder order = inOrder(esChunkUtil, pgVectorUtil, kbChunkMapper, minioUtil, kbDocMapper);
        order.verify(esChunkUtil).deleteChunks(List.of("c1", "c2"));
        order.verify(pgVectorUtil).deleteChunkVectorsByDoc(DOC_ID);
        order.verify(kbChunkMapper).delete(any());
        order.verify(minioUtil).remove("kb/" + DOC_ID + "/心内科.pdf");
        order.verify(kbDocMapper).deleteById(DOC_ID);
    }

    @Test
    @DisplayName("种子语料与回流容器不是 MinIO 里的文件，不能去 MinIO 删")
    void deleteSkipsMinioForBuiltinContainers() {
        when(kbDocMapper.selectById(DOC_ID)).thenReturn(doc("feedback://synthetic"));
        when(kbChunkMapper.selectList(any())).thenReturn(List.of(chunk("c1")));

        service.delete(DOC_ID);

        verify(minioUtil, never()).remove(anyString());
        verify(kbDocMapper).deleteById(DOC_ID);
    }

    @Test
    @DisplayName("只删切片（重新入库的「先删旧再重建」）：不动 MinIO、不动文档行")
    void deleteChunksLeavesDocAndFileUntouched() {
        when(kbChunkMapper.selectList(any())).thenReturn(List.of(chunk("c1")));

        service.deleteChunks(DOC_ID);

        verify(esChunkUtil).deleteChunks(List.of("c1"));
        verify(pgVectorUtil).deleteChunkVectorsByDoc(DOC_ID);
        verify(minioUtil, never()).remove(anyString());
        verify(kbDocMapper, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("没有切片时不去敲 ES 与向量库（空文档不该产生三轮无用调用）")
    void deleteChunksNoopWhenEmpty() {
        when(kbChunkMapper.selectList(any())).thenReturn(List.of());

        service.deleteChunks(DOC_ID);

        verify(esChunkUtil, never()).deleteChunks(any());
        verify(pgVectorUtil, never()).deleteChunkVectorsByDoc(eq(DOC_ID));
    }

    @Test
    @DisplayName("有原文件的才能重新处理：回流容器与种子语料不给「重新处理」")
    void onlyDocsWithSourceFileAreReprocessable() {
        org.assertj.core.api.Assertions.assertThat(service.hasSourceFile(doc("seed://心血管内科"))).isFalse();
        org.assertj.core.api.Assertions.assertThat(service.hasSourceFile(doc("feedback://synthetic"))).isFalse();
        org.assertj.core.api.Assertions.assertThat(service.hasSourceFile(doc("kb/1/a.txt"))).isTrue();
    }

    private KbDoc doc(String fileUrl) {
        KbDoc doc = new KbDoc();
        doc.setId(DOC_ID);
        doc.setDeptId("dept-1");
        doc.setTitle("心血管内科分诊知识");
        doc.setFileUrl(fileUrl);
        return doc;
    }

    private KbChunk chunk(String id) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setDocId(DOC_ID);
        return chunk;
    }
}
