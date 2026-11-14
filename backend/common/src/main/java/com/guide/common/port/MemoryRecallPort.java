package com.guide.common.port;

import com.guide.common.model.MemoryHit;

import java.util.List;

/**
 * 长期记忆召回端口（由 chat 按「用户 + 会话」构造闭包传入 rag）。
 *
 * <p>rag 不感知 userId、也不直连向量库：它只把**已经算好的 query 向量**交给端口，
 * 由 memory 侧完成背景记忆的向量召回。复用同一个 query 向量是硬要求——
 * 记忆召回不得再调一次 embedding（首字延迟会直接变差）。
 *
 * <p>记忆是旁路：端口抛异常或返回 null 时，rag 按「无命中」处理，检索主流程不受影响。
 */
@FunctionalInterface
public interface MemoryRecallPort {

    /**
     * @param query       改写后的查询串（与算 query 向量时用的是同一个）
     * @param queryVector 已算好的查询向量（不得在此再次 embedding）
     * @return 命中项；可为 null（按空列表处理）
     */
    List<MemoryHit> recall(String query, float[] queryVector);
}
