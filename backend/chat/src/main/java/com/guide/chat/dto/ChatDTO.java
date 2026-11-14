package com.guide.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 链路 A 患者端接口出入参。
 */
public class ChatDTO {

    /** 发送消息（SSE 建立请求）：sessionId 为空 or 指向已结束会话 → 开新会话 */
    @Data
    public static class MessageReq {

        private String sessionId;

        @NotBlank(message = "请输入症状描述")
        private String content;

        /**
         * 部位声明：患者在<b>人体图</b>上标明的部位（如 {@code ["左腹部"]}）。
         *
         * <p>可选入参，<b>刻意不加 {@code @NotEmpty}</b>——「说不清在哪儿 · 直接描述」那条出口
         * 必须能走通（发烧、乏力、头晕这批人指不出位置），强制部位声明会把它彻底断掉。
         * 缺省为空 ⇒ 链路行为与今天完全一致。
         *
         * <p>只在新会话的首条输入携带：追问轮上下文里已经有了位置，再带一次是重复注入。
         */
        private List<String> parts;
    }

    /** 挂号确认：recordId 为后续埋点/挂号的唯一凭证 */
    @Data
    public static class RegisterReq {

        @NotBlank(message = "缺少导诊记录")
        private String recordId;

        @NotBlank(message = "请选择科室")
        private String deptId;
    }

    /** 科室（挂号页列表 / 推荐校验） */
    public record DeptVO(String id, String name, String location, String intro) {
    }

    /**
     * 推荐卡 Top3 的一行。
     *
     * @param deptId 科室 id；**2026-10-02 新增**。此前只有科室名，挂号页拿不到 id 就无法把 top2/top3
     *               与科室列表对上、也没法按推荐次序重排（列表只能整体按创建时间序，推荐科室夹在中间）。
     *               2026-10-02 之前的快照没有这个字段，回放时为 null，前端按「无名次」处理、不参与提序
     * @param name   科室名（展示用）
     * @param pct    置信度百分比；null 表示模型未给出合法置信度（前端显示「—」而不是 0%）
     */
    public record Top3Item(String deptId, String name, Integer pct) {
    }

    /** 结论溯源引用（注号对应证据快照 retrieved 顺序） */
    /**
     * 判断依据的一条：注号 + 切片标题 + **证据原文**（截断，见 {@code CITE_CONTENT_MAX}）。
     *
     * <p>只列**模型真正引用**的注——召回但没用上的那些列进"依据"会让人以为系统参考了 5 条，
     * 而实际只用了 2 条（2026-09-26 实测：卡片列 5 条、模型只引 2 条，且其中 4 条标题一模一样）。
     * 光有标题也说明不了什么：同一节的多个切片共用一个小标题，标题给不出区分度，
     * 原文才是能自证的那部分。
     */
    public record Cite(int no, String title, String content) {
    }

    /**
     * 结论卡顶部的一条警报（工单 10）。
     *
     * <p>只有结论轮才下发：ASK / INFO / UNKNOWN 走别的分支，根本不产 {@link ResultVO}——
     * 「非结论轮不弹结构化警报」是结构上保证的，不靠前端判断。
     *
     * @param level    {@code hard}（档案硬冲突，危险色）/ {@code advisory}（相关提醒，主色浅底）
     * @param text     患者可读文案，说清与哪条档案项冲突
     * @param anchor   锚点：{@code cite:N}（知识片段注号）、
     *                 {@code field:allergy|medication|surgery}（档案字段）或
     *                 {@code memory:<id>}（稳定背景条目）；患者据此能核对到具体依据
     * @param source   产出方：{@code rule}（后端词面规则）/ {@code model}（模型补充）/
     *                 {@code memory}（稳定背景命中）
     * @param memoryId 命中的记忆 id（仅 {@code source=memory} 有值）；用来指回具体一条记忆，
     *                 指不回的提醒不会出现在这里
     */
    public record AlertVO(String level, String text, String anchor, String source, String memoryId) {

        /** 档案硬警报（规则/模型）的便捷构造：没有记忆出处 */
        public AlertVO(String level, String text, String anchor, String source) {
            this(level, text, anchor, source, null);
        }
    }

    /**
     * result 事件载荷（渲染推荐卡的完整数据）。
     *
     * @param profileText 患者健康档案的**档案提示文本**（后端读档组装，非模型输出）；为空 = 本轮无档案，
     *                    前端据此不渲染「已参考您的健康档案」行。它与 cites 分开呈现——档案是患者自述，
     *                    **不是医学证据、不进注号体系**（单据 02）
     * @param alerts      顶部警报（规则 + 模型补充，已合并去重、最多 3 条）；空列表 = 无冲突，前端不渲染
     */
    public record ResultVO(
            String sessionId,
            String recordId,
            String deptId,
            String dept,
            Double confidence,
            List<Top3Item> top3,
            String note,
            List<Cite> cites,
            boolean lowConfidence,
            String profileText,
            List<AlertVO> alerts) {
    }

    /** 挂号确认结果 */
    public record RegisterVO(String sessionId, String deptId, String deptName, String location) {
    }
}
