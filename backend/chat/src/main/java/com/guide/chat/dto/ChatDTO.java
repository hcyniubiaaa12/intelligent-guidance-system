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

    /** 推荐卡 Top3 置信度条；pct 为 null 表示模型未给出合法置信度（前端显示「—」） */
    public record Top3Item(String name, Integer pct) {
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

    /** result 事件载荷（渲染推荐卡的完整数据） */
    public record ResultVO(
            String sessionId,
            String recordId,
            String deptId,
            String dept,
            Double confidence,
            List<Top3Item> top3,
            String note,
            List<Cite> cites,
            boolean lowConfidence) {
    }

    /** 挂号确认结果 */
    public record RegisterVO(String sessionId, String deptId, String deptName, String location) {
    }
}
