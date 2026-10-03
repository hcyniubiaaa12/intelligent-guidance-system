package com.guide.rag.dto;


import java.util.List;

/**
 * RAG 检索入参。
 *
 * @param query           本轮患者输入（原始主诉 / 补充信息）
 * @param history         本会话历史消息（不含本轮），多轮时用于查询改写
 * @param deptOptions     候选科室（enabled=1），Prompt 中限定推荐范围
 * @param askRound        已追问轮次（达到上限时不允许再追问）
 * @param forceConclusion 是否强制出结论（追问超限）
 * @param topK            单路召回条数（向量 / ES 各取 topK）
 * @param topN            精排后进入 Prompt 的片段数
 * @param profileText     患者健康档案的**待注入文本**（可选背景，非检索语料）；为空即无档案。
 *                        它是患者自述，**不是医学证据**，不进注号体系、不参与排序——
 *                        由 chat 读档组装好传进来，rag 不感知业务状态（单据 02）
 */
public record RagRequest(
        String query,
        List<RagTurn> history,
        List<DeptOption> deptOptions,
        int askRound,
        boolean forceConclusion,
        int topK,
        int topN,
        String profileText) {

    public static final int DEFAULT_TOP_K = 20;
    public static final int DEFAULT_TOP_N = 5;

    public RagRequest {
        history = history == null ? List.of() : List.copyOf(history);
        deptOptions = deptOptions == null ? List.of() : List.copyOf(deptOptions);
        topK = topK <= 0 ? DEFAULT_TOP_K : topK;
        topN = topN <= 0 ? DEFAULT_TOP_N : topN;
    }

    /** 是否多轮（有历史才做查询改写） */
    public boolean multiTurn() {
        return !history.isEmpty();
    }
}
