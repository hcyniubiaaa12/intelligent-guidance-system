package com.guide.rag.dto;

import java.util.List;

/**
 * 模型输出的结构化解析结果（rag 层产出，业务校验由 chat 层做）。
 *
 * @param verdict        模型判定（**由模型显式声明**，四个取值见 {@link Verdict}）
 * @param reply          给患者的自然语言回复（流式内容）
 * @param top3           候选科室与置信度（**已归一化**：首位 = 顶层 dept，其后按置信度降序、null 垫底；
 *                       ask 时为空。模型给的原始顺序不可信，见 {@code AnswerParser.normalizeTop3}）
 * @param note           一句话结论说明
 * @param cites          引用注号（对应 RagContext.chunks 下标 + 1）
 * @param confidence     模型自报 top1 置信度；非法或缺失为 null（取归一化后首位那条的值）
 * @param confidenceValid 置信度是否通过校验（0–1 区间、Top3 单调递减）
 * @param alerts         模型补充的警报（工单 10）：**必须带锚点**（cite 注号或 field 档案字段），
 *                       无锚点的已在 {@code AnswerParser} 解析时丢弃；非结论轮恒为空
 */
public record RagAnswer(
        Verdict verdict,
        String reply,
        List<DeptCandidate> top3,
        String note,
        List<Integer> cites,
        Double confidence,
        boolean confidenceValid,
        List<Alert> alerts) {

    /** 老调用方兼容：没有模型警报时等价于空列表（非结论轮、老格式输出都走这条） */
    public RagAnswer(Verdict verdict, String reply, List<DeptCandidate> top3, String note,
                     List<Integer> cites, Double confidence, boolean confidenceValid) {
        this(verdict, reply, top3, note, cites, confidence, confidenceValid, List.of());
    }

    public RagAnswer {
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
    }

    /**
     * 模型在结论 JSON 里补充的一条警报。
     *
     * @param text  给患者看的文案
     * @param cite  锚点之一：引用的知识片段注号（对应 RagContext.chunks 下标 + 1）；
     *              **注号是否越界由 chat 层按实际召回条数再校验**
     * @param field 锚点之一：档案字段白名单（allergy / medication / surgery）；
     *              与 cite 同时给出时优先用 cite 作锚点
     */
    public record Alert(String text, Integer cite, String field) {
    }

    /**
     * 模型判定（2026-09-30 起由模型在结论 JSON 的 verdict 字段里**显式声明**）。
     *
     * <p>为什么要显式声明：此前判据是「没有合法结论 JSON ⟹ 追问」，等于**把异常当业务**——
     * 模型漏输出协议、JSON 畸形、答了资料但给不出科室，全都被伪装成一句"追问"发出去，
     * 既没有任何异常信号，还白吃一次追问轮次（真实踩过：问文档内容 → 回答被标成追问）。
     */
    public enum Verdict {
        /** 出结论：推荐科室。唯一会产生 result 事件的判定 */
        RECOMMEND,
        /** 信息不足，需要向患者追问（占追问轮次） */
        ASK,
        /** 资料回答：患者问的是知识库内容而不是描述症状，如实复述片段作答，不推荐科室、不占追问轮次 */
        INFO,
        /** 模型未按协议声明（无分隔符 / JSON 不可用 / 声明了结论却没给科室）：降级为普通回答 + 告警 */
        UNKNOWN
    }

    public record DeptCandidate(String dept, Double confidence) {
    }

    public static RagAnswer ask(String reply) {
        return new RagAnswer(Verdict.ASK, reply, List.of(), null, List.of(), null, false);
    }

    public static RagAnswer info(String reply) {
        return new RagAnswer(Verdict.INFO, reply, List.of(), null, List.of(), null, false);
    }

    public static RagAnswer unknown(String reply) {
        return new RagAnswer(Verdict.UNKNOWN, reply, List.of(), null, List.of(), null, false);
    }
}
