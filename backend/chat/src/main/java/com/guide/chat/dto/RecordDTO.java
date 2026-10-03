package com.guide.chat.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 患者端「就诊记录」出入参。
 *
 * <p>这一页是对既有事实的回顾，不是新数据：会话列表来自 {@code chat_session}，
 * 对话正文来自 {@code chat_message}，结论卡从 {@code guide_record} 的 rec_top3 / evidence
 * 两份<b>只写快照</b>重建——历史不回改，回放看到的就是当时落库的那份。
 *
 * <p>唯一的写操作是**归档**（{@link ArchiveReq}）：患者把自己的会话收进收纳区，
 * 不是删除——归档后仍可只读回放，也随时可取回。
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
            String actualDept,
            /** 是否被患者收进了「已归档」（收纳区，不是删除：仍可回放、可取回） */
            boolean archived) {
    }

    /** 一天一组（天头 + 天内会话，最新在前） */
    public record DayGroup(String date, int total, List<SessionItem> sessions) {
    }

    /**
     * 会话列表。
     *
     * <p>{@code days} 只含**未归档**会话（主区，按天分组）；{@code archivedDays} 是归档区（收纳区），
     * **同样按天分组**、天最新在前——分组口径与主区一致：按 {@code startedAt}（会话开始那天）算。
     * **在后端就分开、而不是给条目标记让前端过滤**：主区的天头要显示"这天几次"，
     * 前端过滤会让每个计数都重算一遍，分开返回口径只有一处。
     * {@code totalSessions}/{@code totalBooked} 同样是主区口径（与 days 一致），归档区条数自己数。
     *
     * <p>「全部对话」与「挂号历史」仍共用这一份，只是前端换过滤口径（挂过号的 / 全部）——
     * 归档区跟着同一口径过滤。
     */
    public record SessionListVO(List<DayGroup> days, List<DayGroup> archivedDays,
                                int totalSessions, int totalBooked) {
    }

    /** 归档 / 取回请求：archived=true 收进收纳区，false 取回主区 */
    public record ArchiveReq(@NotNull(message = "请指定 archived") Boolean archived) {
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
            String actualDeptLocation,
            /**
             * 当时的健康档案提示文本（单据 04）：读**证据快照** profile 节点的 text，
             * **不回查当前档案**——患者改档案不回改历史；为空 = 当时无档案 / 老记录无该节点。
             */
            String profileRef) {
    }

    /** 一条会话的完整回放（右页正文 + 书签 + 结论卡） */
    public record SessionDetailVO(
            SessionItem session,
            List<MessageItem> messages,
            List<QuestionAnchor> questions,
            CardVO card) {
    }
}
