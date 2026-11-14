package com.guide.rag.dto;


import com.guide.common.port.MemoryRecallPort;

import java.util.List;

/**
 * RAG 检索入参。
 *
 * @param query           本轮患者输入（原始主诉 / 补充信息）
 * @param history         本会话历史消息（不含本轮），多轮时用于查询改写
 * @param deptOptions     候选科室（enabled=1），Prompt 中限定推荐范围
 * @param forceConclusion 是否强制出结论（追问超限且**本会话尚未出过结论**，由 chat 判定后传入）
 * @param topK            单路召回条数（向量 / ES 各取 topK）
 * @param topN            精排后进入 Prompt 的片段数
 * @param profileText     患者健康档案的**待注入文本**（可选背景，非检索语料）；为空即无档案。
 *                        它是患者自述，**不是医学证据**，不进注号体系、<b>不参与检索</b>——
 *                        由 chat 读档组装好传进来，rag 不感知业务状态。档案已退出检索层：
 *                        这里只进生成提示词，不产生任何召回通道。
 * @param parts           患者在**人体图**上标明的部位（部位声明，如 {@code ["左腹部"]}）；为空即未声明。
 *                        只服务<b>新会话的首条输入</b>，进模型上下文让模型知道位置已确认、不必再追问部位；
 *                        <b>不参与检索</b>——检索只用 query，患者那句话里已经含部位词。
 *                        它<b>不产生结论权</b>：患者标的位置只是范围信息，推荐哪个科室仍由知识片段与候选清单决定
 * @param preferences     患者偏好（跨会话、全量注入，最多 5 条）；为空即没有偏好段。
 *                        由 chat 从 memory 读取后传入，rag 不感知 userId。
 * @param memoryRecallPort 稳定背景的召回端口（可为 null / 返回空）；rag 用**已算好的 query 向量**回调它，
 *                        不额外 embedding、不直连向量库，也不感知用户。
 * @param memoryThreshold 稳定背景召回阈值（来自 {@code memory.background.recall.threshold}）。
 *                        rag 自己不用它做判定（判定在 memory 侧），它是**证据快照的取证字段**：
 *                        事后要能复现「当时按多少分算命中」。非法值回落默认 0.70。
 *
 * <p>注意：追问轮次（{@code ask_round}）**不再是入参**——rag 从无任何消费者读它，曾经传进来是死字段
 * （2026-10-14 工单 05 核实后删除）。rag 侧"该不该追问"只由 {@code forceConclusion} 一个开关决定。
 */
public record RagRequest(
        String query,
        List<RagTurn> history,
        List<DeptOption> deptOptions,
        boolean forceConclusion,
        int topK,
        int topN,
        String profileText,
        List<String> parts,
        List<String> preferences,
        MemoryRecallPort memoryRecallPort,
        double memoryThreshold) {

    public static final int DEFAULT_TOP_K = 20;
    public static final int DEFAULT_TOP_N = 5;
    public static final double DEFAULT_MEMORY_THRESHOLD = 0.70;

    public RagRequest {
        history = history == null ? List.of() : List.copyOf(history);
        deptOptions = deptOptions == null ? List.of() : List.copyOf(deptOptions);
        // 声明词只做"去空白 + 丢空项"：这是**模型上下文**，不是判据，
        // 在这里做筛选会让 rag 层开始替前端校验入参（职责漂移），真正要丢弃的是"整条为空"。
        parts = parts == null ? List.of() : parts.stream()
                .filter(java.util.Objects::nonNull).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        // 偏好同 parts：只去 null / 空白，不在 rag 层做条数与字数裁剪（那是 PromptBuilder 的注入上限）
        preferences = preferences == null ? List.of() : preferences.stream()
                .filter(java.util.Objects::nonNull).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        topK = topK <= 0 ? DEFAULT_TOP_K : topK;
        topN = topN <= 0 ? DEFAULT_TOP_N : topN;
        // 阈值是配置值，缺省/越界一律回落默认：快照里写一个 0.0 会让事后复现变成假象
        if (!(memoryThreshold > 0 && memoryThreshold <= 1)) {
            memoryThreshold = DEFAULT_MEMORY_THRESHOLD;
        }
    }

    /**
     * 兼容构造：调用方/测试不关心取证阈值时按默认 0.70。
     * 生产路径（chat）显式传 {@code memory.background.recall.threshold}。
     */
    public RagRequest(String query, List<RagTurn> history, List<DeptOption> deptOptions,
                      boolean forceConclusion, int topK, int topN, String profileText,
                      List<String> parts, List<String> preferences,
                      MemoryRecallPort memoryRecallPort) {
        this(query, history, deptOptions, forceConclusion, topK, topN, profileText, parts,
                preferences, memoryRecallPort, DEFAULT_MEMORY_THRESHOLD);
    }

    /** 是否多轮（有历史才做查询改写） */
    public boolean multiTurn() {
        return !history.isEmpty();
    }
}
