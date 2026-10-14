package com.guide.llm.client;

import java.util.List;

/**
 * 重排模型接口（LLM 适配层唯一出口之一）：阿里 rerank（模型名可配）。
 * rag 层对 RRF 融合后的候选做精排，产出 Top-N 与分数（分数随片段透传入 Prompt，
 * 作为模型自报置信度的客观锚点，见 `docs/architecture/链路A-导诊对话.md` 对齐点）。
 */
public interface RerankModel {

    /**
     * 精排。
     *
     * @param query     查询文本
     * @param documents 候选文档正文（顺序即入参顺序）
     * @param topN      返回条数
     * @return 按相关度降序的结果（index 指向入参下标，score 为 0–1 相关度）
     */
    List<RerankHit> rerank(String query, List<String> documents, int topN);

    record RerankHit(int index, double score) {
    }
}
