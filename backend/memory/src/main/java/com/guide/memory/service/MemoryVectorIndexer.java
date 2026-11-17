package com.guide.memory.service;

import com.guide.llm.client.EmbeddingModel;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 记忆向量写入：只把**稳定背景**送进 embedding，偏好不向量化（偏好永远全量注入，无需按需召回）。
 *
 * <p><b>失败是旁路</b>：embedding 或 pgvector 写失败只记 WARN、返回 false，**绝不上抛**——
 * 记忆抽取/患者编辑不能因为向量库抖动而失败。缺向量的行由 {@code MemoryVectorBackfillJob}
 * 周期性补上，MySQL 与向量库不会长期不一致。
 *
 * <p><b>embedding 文本</b>：优先 {@code retrievalText}（抽取时给出的扩展文本），为空回退 {@code mvalue}。
 * 因此值变了必须同步刷新 {@code retrievalText}，否则重算出来的还是旧文本的向量。
 * 患者编辑与抽取改口都走 {@link #refreshBackgroundValue(Memory, String, LocalDateTime)}，不各写一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryVectorIndexer {

    private final EmbeddingModel embeddingModel;
    private final MemoryVectorStore vectorStore;

    /**
     * 生成/刷新一条记忆的向量。
     *
     * @return true = 已写入向量表；false = 跳过（非背景/非生效/空文本）或失败
     */
    public boolean index(Memory memory) {
        if (memory == null || memory.getId() == null || memory.getUserId() == null) {
            return false;
        }
        if (!isBackground(memory.getType()) || memory.getStatus() != MemoryStatus.ACTIVE) {
            return false;
        }
        String text = embeddingText(memory);
        if (text == null) {
            log.warn("记忆没有可向量化的文本，跳过：memoryId={}", memory.getId());
            return false;
        }
        try {
            float[] embedding = embeddingModel.embed(text);
            if (embedding == null || embedding.length == 0) {
                log.warn("记忆向量为空，跳过写入（回填任务会重试）：memoryId={}", memory.getId());
                return false;
            }
            vectorStore.upsert(memory.getId(), memory.getUserId(), memory.getType().getCode(), embedding);
            return true;
        } catch (RuntimeException e) {
            log.warn("记忆向量写入失败（不回滚记忆本身，回填任务会补）：memoryId={}｜原因={}",
                    memory.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 背景值变了：同步 {@code retrieval_text} / {@code updated_at} 快照并重算向量。
     *
     * <p>患者手动编辑（{@code MemoryManageService#updateValue}）与抽取改口
     * （{@code MemoryExtractionService#applyBackgroundUpdate}）共用这一处——两条路径在 MySQL 里改的列
     * 不同（编辑只改值，改口还改键/原话/置信度/观察时间），但「{@code retrieval_text} 必须跟着值走、
     * {@code updated_at} 必须刷新、然后重算向量」是同一件事。写成两份，迟早有一边漏刷
     * {@code retrieval_text}，重算出来的向量仍指向旧说法（患者改口等于没改）。
     *
     * <p>调用前提：MySQL 已经写成新值、{@code updated_at} 也已刷新——本方法只同步内存快照并重算向量。
     * 重算失败不上抛（{@link #index} 已降级为 WARN），回填任务按 {@code updated_at} 收敛。
     */
    public void refreshBackgroundValue(Memory row, String newValue, LocalDateTime now) {
        if (row == null || !isBackground(row.getType())) {
            return;
        }
        row.setMvalue(newValue);
        // 先回退成新值：检索扩展文本由背景落库后的 MemoryEnricher 异步覆盖，
        // 在它成功之前，召回行为与「没有扩写」完全一致
        row.setRetrievalText(newValue);
        row.setUpdatedAt(now);
        index(row);
    }

    /** 删除向量；失败只记 WARN（记忆已经在 MySQL 逻辑删除，残留向量召回时会被回表校验过滤）。 */
    public void delete(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return;
        }
        try {
            vectorStore.delete(memoryId);
        } catch (RuntimeException e) {
            log.warn("记忆向量删除失败（回表校验会拦住，不阻塞删除）：memoryId={}｜原因={}",
                    memoryId, e.getMessage());
        }
    }

    private boolean isBackground(MemoryType type) {
        return type == MemoryType.BACKGROUND_HEALTH || type == MemoryType.BACKGROUND_OTHER;
    }

    private String embeddingText(Memory memory) {
        String text = memory.getRetrievalText();
        if (text == null || text.isBlank()) {
            text = memory.getMvalue();
        }
        return text == null || text.isBlank() ? null : text.strip();
    }
}
