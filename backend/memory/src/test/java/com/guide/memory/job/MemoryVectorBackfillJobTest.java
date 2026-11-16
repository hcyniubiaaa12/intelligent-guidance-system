package com.guide.memory.job;

import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryVectorIndexer;
import com.guide.memory.service.MemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 向量回填任务：只有「缺向量」或「向量比记忆旧」的背景才重算；单条失败继续下一条。
 *
 * <p>它不是「重算全部」：换 embedding 模型是运维任务（spec Out of Scope），
 * 这里的职责只是把写入失败/迁移留下的空洞补上，MySQL 与向量库不长期不一致。
 */
class MemoryVectorBackfillJobTest {

    private MemoryMapper memoryMapper;
    private MemoryVectorStore vectorStore;
    private MemoryVectorIndexer indexer;
    private MemoryVectorBackfillJob job;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(MemoryMapper.class);
        vectorStore = mock(MemoryVectorStore.class);
        indexer = mock(MemoryVectorIndexer.class);
        job = new MemoryVectorBackfillJob(memoryMapper, vectorStore, indexer, mock(ThreadPoolTaskExecutor.class));
    }

    @Test
    @DisplayName("缺向量行：重算并写入")
    void missingVectorIsIndexed() {
        Memory row = memory("m1");
        when(memoryMapper.selectList(any())).thenReturn(List.of(row));
        when(vectorStore.vectorUpdatedAt(any())).thenReturn(Map.of());

        job.backfill();

        verify(indexer).index(row);
    }

    @Test
    @DisplayName("向量比记忆新：不重算（避免每 10 分钟白付一次 embedding）")
    void freshVectorIsSkipped() {
        Memory row = memory("m1");
        row.setUpdatedAt(LocalDateTime.of(2026, 11, 14, 9, 0));
        when(memoryMapper.selectList(any())).thenReturn(List.of(row));
        when(vectorStore.vectorUpdatedAt(any())).thenReturn(Map.of("m1", row.getUpdatedAt().plusMinutes(1)));

        job.backfill();

        verify(indexer, never()).index(any());
    }

    @Test
    @DisplayName("记忆被编辑过（updated_at 晚于向量时间）：重算")
    void staleVectorIsReindexed() {
        Memory row = memory("m1");
        row.setUpdatedAt(LocalDateTime.of(2026, 11, 14, 10, 0));
        when(memoryMapper.selectList(any())).thenReturn(List.of(row));
        when(vectorStore.vectorUpdatedAt(any())).thenReturn(Map.of("m1", row.getUpdatedAt().minusMinutes(5)));

        job.backfill();

        verify(indexer).index(row);
    }

    @Test
    @DisplayName("单条 index 抛异常：继续处理后面的行（任务不整体失败）")
    void singleFailureDoesNotStopBatch() {
        Memory first = memory("m1");
        Memory second = memory("m2");
        when(memoryMapper.selectList(any())).thenReturn(List.of(first, second));
        when(vectorStore.vectorUpdatedAt(any())).thenReturn(Map.of());
        when(indexer.index(first)).thenThrow(new RuntimeException("boom"));

        job.backfill();

        verify(indexer).index(first);
        verify(indexer).index(second);
    }

    @Test
    @DisplayName("没有背景记忆：不查向量表")
    void noRowsShortCircuits() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        job.backfill();

        verifyNoInteractions(vectorStore);
        verifyNoInteractions(indexer);
    }

    @Test
    @DisplayName("向量表读取失败：本轮跳过（下一轮继续），不抛")
    void storeFailureIsSwallowed() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory("m1")));
        when(vectorStore.vectorUpdatedAt(any())).thenThrow(new RuntimeException("pg down"));

        job.backfill();

        verify(indexer, never()).index(any());
    }

    private Memory memory(String id) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId("u1");
        memory.setType(MemoryType.BACKGROUND_HEALTH);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMkey("慢病");
        memory.setMvalue("糖尿病");
        return memory;
    }
}
