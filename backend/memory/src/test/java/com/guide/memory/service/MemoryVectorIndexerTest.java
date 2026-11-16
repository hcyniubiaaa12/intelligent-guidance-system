package com.guide.memory.service;

import com.guide.llm.client.EmbeddingModel;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 向量索引器：只索引稳定背景（偏好不向量化），embedding 用 retrievalText 优先；
 * 失败只降级为「这条缺向量」，由回填任务补——**绝不上抛**。
 */
class MemoryVectorIndexerTest {

    private EmbeddingModel embeddingModel;
    private MemoryVectorStore vectorStore;
    private MemoryVectorIndexer indexer;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        vectorStore = mock(MemoryVectorStore.class);
        indexer = new MemoryVectorIndexer(embeddingModel, vectorStore);
    }

    @Test
    @DisplayName("稳定背景：embedding 优先用 retrievalText（扩展文本），再 upsert 到向量表")
    void backgroundIndexesRetrievalText() {
        Memory memory = memory("m1", MemoryType.BACKGROUND_HEALTH, MemoryStatus.ACTIVE);
        memory.setMvalue("2型糖尿病");
        memory.setRetrievalText("2型糖尿病 慢病 血糖");
        float[] vector = {0.1f, 0.2f};
        when(embeddingModel.embed("2型糖尿病 慢病 血糖")).thenReturn(vector);

        boolean indexed = indexer.index(memory);

        assertThat(indexed).isTrue();
        verify(vectorStore).upsert("m1", "u1", "background_health", vector);
    }

    @Test
    @DisplayName("retrievalText 为空时退回 mvalue（老数据/人工编辑后的行）")
    void backgroundFallsBackToValue() {
        Memory memory = memory("m1", MemoryType.BACKGROUND_OTHER, MemoryStatus.ACTIVE);
        memory.setMvalue("长期夜班");
        memory.setRetrievalText("  ");
        float[] vector = {0.3f};
        when(embeddingModel.embed("长期夜班")).thenReturn(vector);

        assertThat(indexer.index(memory)).isTrue();

        verify(vectorStore).upsert("m1", "u1", "background_other", vector);
    }

    @Test
    @DisplayName("偏好不向量化：不调 embedding、不写向量表")
    void preferenceIsNotIndexed() {
        Memory memory = memory("m1", MemoryType.PREFERENCE, MemoryStatus.ACTIVE);
        memory.setMvalue("讲慢一点");

        assertThat(indexer.index(memory)).isFalse();

        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("已过期背景不索引（不占向量表，召回也读不到）")
    void expiredBackgroundIsNotIndexed() {
        Memory memory = memory("m1", MemoryType.BACKGROUND_HEALTH, MemoryStatus.EXPIRED);
        memory.setMvalue("已停用的旧药");

        assertThat(indexer.index(memory)).isFalse();

        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("embedding 抛异常：只记 WARN、不上抛、不写向量（回填任务会补）")
    void embeddingFailureIsSwallowed() {
        Memory memory = memory("m1", MemoryType.BACKGROUND_HEALTH, MemoryStatus.ACTIVE);
        memory.setMvalue("2型糖尿病");
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("模型超时"));

        assertThatCode(() -> assertThat(indexer.index(memory)).isFalse()).doesNotThrowAnyException();

        verify(vectorStore, never()).upsert(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("空向量不写表")
    void emptyVectorIsNotWritten() {
        Memory memory = memory("m1", MemoryType.BACKGROUND_HEALTH, MemoryStatus.ACTIVE);
        memory.setMvalue("2型糖尿病");
        when(embeddingModel.embed(anyString())).thenReturn(new float[0]);

        assertThat(indexer.index(memory)).isFalse();

        verify(vectorStore, never()).upsert(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("背景改口：retrieval_text / updated_at 快照同步，embedding 按新文本重算")
    void refreshBackgroundValueReindexesNewText() {
        Memory memory = memory("m1", MemoryType.BACKGROUND_HEALTH, MemoryStatus.ACTIVE);
        memory.setMvalue("2型糖尿病");
        memory.setRetrievalText("2型糖尿病");
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 10, 0);
        float[] vector = {0.4f};
        when(embeddingModel.embed("2型糖尿病，控制良好")).thenReturn(vector);

        indexer.refreshBackgroundValue(memory, "2型糖尿病，控制良好", now);

        assertThat(memory.getMvalue()).isEqualTo("2型糖尿病，控制良好");
        assertThat(memory.getRetrievalText()).isEqualTo("2型糖尿病，控制良好");
        assertThat(memory.getUpdatedAt()).isEqualTo(now);
        verify(vectorStore).upsert("m1", "u1", "background_health", vector);
    }

    @Test
    @DisplayName("偏好不走向量刷新：refreshBackgroundValue 对偏好是空操作（偏好永远全量注入）")
    void refreshBackgroundValueSkipsPreference() {
        Memory memory = memory("m1", MemoryType.PREFERENCE, MemoryStatus.ACTIVE);
        memory.setMvalue("讲慢一点");

        indexer.refreshBackgroundValue(memory, "讲快一点", LocalDateTime.now());

        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("删除向量失败只记 WARN，不影响调用方")
    void deleteFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new RuntimeException("pg down")).when(vectorStore).delete("m1");

        assertThatCode(() -> indexer.delete("m1")).doesNotThrowAnyException();

        verify(vectorStore).delete("m1");
    }

    private Memory memory(String id, MemoryType type, MemoryStatus status) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId("u1");
        memory.setType(type);
        memory.setStatus(status);
        memory.setMkey("键");
        return memory;
    }
}
