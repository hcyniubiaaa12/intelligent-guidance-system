package com.guide.rag.dto;

import com.guide.common.model.ChunkHit;
import com.guide.common.model.MemoryHit;

import java.util.List;

/**
 * RAG 检索产出：送入 Prompt 的精排片段 + 证据快照素材。
 *
 * @param query          原始查询
 * @param rewrittenQuery 改写后查询（未改写时等于原查询）
 * @param chunks         精排后 Top-N 片段，顺序即注号顺序（注1 = chunks[0]）
 * @param vectorHits     向量路召回条数（诊断用）
 * @param esHits         关键词路召回条数（诊断用）
 * @param memoryHits     稳定背景的召回命中（记忆是旁路；无端口/未命中即为空列表）
 * @param memoryText     **本轮实际注入 Prompt 的长期记忆段文本**（{@link com.guide.rag.support.PromptBuilder#memoryBlock} 的产出）。
 *                       证据快照直接写它——同一份文本只由一处生成，prompt 与快照不会各算一遍而漂移
 * @param memoryQuery    稳定背景召回用的查询串（改写后的 query；无召回端口时为 null）
 * @param memoryThreshold 稳定背景召回阈值（快照取证用，见 {@link RagRequest#memoryThreshold()}）
 */
public record RagContext(
        String query,
        String rewrittenQuery,
        List<ChunkHit> chunks,
        int vectorHits,
        int esHits,
        List<MemoryHit> memoryHits,
        String memoryText,
        String memoryQuery,
        double memoryThreshold) {

    public RagContext {
        memoryHits = memoryHits == null ? List.of() : List.copyOf(memoryHits);
    }

    /**
     * 不关心记忆取证字段的便捷构造：记忆段文本为空、查询串为 null、阈值取默认。
     * 正常检索路径（{@code RagService}）显式传这三项，不走这里。
     */
    public RagContext(String query, String rewrittenQuery, List<ChunkHit> chunks, int vectorHits, int esHits,
                      List<MemoryHit> memoryHits) {
        this(query, rewrittenQuery, chunks, vectorHits, esHits, memoryHits, "", null,
                RagRequest.DEFAULT_MEMORY_THRESHOLD);
    }

    /** 无记忆命中的便捷构造：给不关心记忆的调用方/测试用。 */
    public RagContext(String query, String rewrittenQuery, List<ChunkHit> chunks, int vectorHits, int esHits) {
        this(query, rewrittenQuery, chunks, vectorHits, esHits, List.of());
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }
}
