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
 * @param profileQuery    患者健康档案的**检索用串**（性别 + 年龄段 + 命中词表的标签与词）；为空即无档案。
 *                        <b>只用于扩容召回</b>：非空时多开两路召回（向量 + 关键词），与主诉两路一起进 RRF，
 *                        精排 query 仍是主诉改写串——档案没有排序话语权（单据 03）
 * @param parts           患者在**人体图**上标明的部位（部位声明，如 {@code ["左腹部"]}）；为空即未声明。
 *                        只服务<b>新会话的首条输入</b>，进模型上下文让模型知道位置已确认、不必再追问部位；
 *                        <b>不参与检索</b>——检索只用 query，患者那句话里已经含部位词。
 *                        它<b>不产生结论权</b>：患者标的位置只是范围信息，推荐哪个科室仍由知识片段与候选清单决定
 */
public record RagRequest(
        String query,
        List<RagTurn> history,
        List<DeptOption> deptOptions,
        int askRound,
        boolean forceConclusion,
        int topK,
        int topN,
        String profileText,
        String profileQuery,
        List<String> parts) {

    public static final int DEFAULT_TOP_K = 20;
    public static final int DEFAULT_TOP_N = 5;

    public RagRequest {
        history = history == null ? List.of() : List.copyOf(history);
        deptOptions = deptOptions == null ? List.of() : List.copyOf(deptOptions);
        // 声明词只做"去空白 + 丢空项"：这是**模型上下文**，不是判据，
        // 在这里做筛选会让 rag 层开始替前端校验入参（职责漂移），真正要丢弃的是"整条为空"。
        parts = parts == null ? List.of() : parts.stream()
                .filter(java.util.Objects::nonNull).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        topK = topK <= 0 ? DEFAULT_TOP_K : topK;
        topN = topN <= 0 ? DEFAULT_TOP_N : topN;
    }

    /** 是否多轮（有历史才做查询改写） */
    public boolean multiTurn() {
        return !history.isEmpty();
    }
}
