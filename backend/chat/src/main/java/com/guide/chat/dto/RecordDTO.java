package com.guide.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 患者端「就诊记录」出入参（只读回放）。
 *
 * <p>这一页是对既有事实的回顾，不是新数据：会话列表来自 {@code chat_session}，
 * 对话正文来自 {@code chat_message}，结论卡从 {@code guide_record} 的 rec_top3 / evidence
 * 两份<b>只写快照</b>重建——历史不回改，回放看到的就是当时落库的那份。
 *
 * <p>「全部对话」与「挂号历史」共用同一份列表，只是前端过滤口径不同（挂过号的 / 全部），
 * 所以接口不分成两个。
 */
public class RecordDTO {

    /** 会话列表条目（左页一行） */
    public record SessionItem(
            String id,
            /** 首句主诉（会话里第一条用户消息） */
            String firstComplaint,
            LocalDateTime startedAt,
            /** 最后一条消息的时间；无消息时同 startedAt */
            LocalDateTime endedAt,
            /** SessionStatus 的 code：ongoing / closed */
            String status,
            int askRound,
            boolean hasResult,
            /** 用户问题数 = 会话里 role=user 的消息数（也是书签的数量预告） */
            int questionCount,
            /** 推荐科室名；未出结论为 null */
            String recDept,
            /** 推荐科室置信度百分比；模型未给合法置信度为 null（前端显示「—」） */
            Integer confidence,
            boolean lowConfidence,
            /** 是否挂过号（guide_record.actual_dept_id 非空） */
            boolean booked,
            String actualDept) {
    }

    /** 一天一组（天头 + 天内会话，最新在前） */
    public record DayGroup(String date, int total, List<SessionItem> sessions) {
    }

    /**
     * 会话列表。「全部对话」用 days 全量；「挂号历史」用 days 里 booked=true 的条目
     * （前端过滤即可——一个人的会话量级很小，不值得为省这点数据多一个接口）。
     */
    public record SessionListVO(List<DayGroup> days, int totalSessions, int totalBooked) {
    }

    /** 一条消息（只读回放）；questionNo 仅 role=user 有值，对应书签序号从 1 起 */
    public record MessageItem(String role, String content, LocalDateTime at, Integer questionNo) {
    }

    /** 用户问题锚点（右缘书签；最新在前由前端排） */
    public record QuestionAnchor(int no, String content, LocalDateTime at) {
    }

    /** 只读回放的结论卡（从 guide_record 快照重建，字段含义同 result 事件载荷） */
    public record CardVO(
            String recordId,
            String dept,
            Double confidence,
            List<ChatDTO.Top3Item> top3,
            String note,
            List<ChatDTO.Cite> cites,
            boolean lowConfidence,
            boolean booked,
            String actualDept,
            String actualDeptLocation) {
    }

    /** 一条会话的完整回放（右页正文 + 书签 + 结论卡） */
    public record SessionDetailVO(
            SessionItem session,
            List<MessageItem> messages,
            List<QuestionAnchor> questions,
            CardVO card) {
    }
}
