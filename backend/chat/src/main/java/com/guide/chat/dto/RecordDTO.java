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
            /** SessionStatus 的 code：ongoing / closed（closed = 已挂号，会话唯一的结束信号） */
            String status,
            /**
             * 距**最近一张结论卡**已追问几轮（出卡时归零，2026-10-14 工单 05 重定义语义）。
             * 患者端不读它，只给管理端看。
             */
            int askRound,
            boolean hasResult,
            /** 用户问题数 = 会话里 role=user 的消息数（也是书签的数量预告） */
            int questionCount,
            /** 推荐科室名；未出结论为 null */
            String recDept,
            /** 推荐科室置信度百分比；模型未给合法置信度为 null（前端显示「—」） */
            Integer confidence,
            boolean lowConfidence,
            /**
             * 是否挂过号（该会话里有记录 actual_dept_id 非空）。
             * **以会话为口径**：一条会话可能出多张卡，取的是**已挂号的那条记录**，
             * 不是最新那张（患者挂的不是最后一张时，按"最新"会指错，工单 05 ③）。
             */
            boolean booked,
            /** 挂到哪个科；同 {@link #booked()} 的口径——取已挂号的那条记录 */
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

    /**
     * 只读回放的结论卡（从 guide_record 快照重建，字段含义同 result 事件载荷）。
     *
     * <p><b>一条会话可以有多张卡</b>（出结论后患者继续补充、模型再出结论，2026-10-14 工单 05 定案），
     * 所以卡必须带自己在消息流里的位置，否则回放会把它们一律堆到最后——那是**数据与展示不一致**，
     * 而回放是取证的地方（审核侧「证据链回放」看的是同一份事实，必须与当时发生的一致）。
     */
    public record CardVO(
            String recordId,
            /**
             * 这张卡插在消息流里哪一条**之后**（0 基下标）。
             *
             * <p>规则（只此一处实现，在 {@code RecordService}）：本会话里第一条 {@code role=user}
             * 且 {@code id > 本卡对应记录 id} 的消息的下标 − 1；没有这样的消息时取最后一条消息的下标。
             * 前端只负责"插到这条后面"，**不自己再推一次位置**。
             *
             * <p>为什么用"下一条患者消息"当右边界而不是 {@code created_at}：{@code created_at} 是
             * {@code DATETIME} 无小数秒，同一秒内多条消息的先后不可靠；而 {@code chat_message} 与
             * {@code guide_record} 的 id 出自同一套雪花生成器（MyBatis-Plus {@code ASSIGN_ID}），
             * 按插入顺序单调递增，导诊记录在本轮结束时落库、下一轮患者消息必然更晚——用它定右边界，
             * 卡一定落在**它自己那一轮的最后一条消息之后**，且不受时间戳打平影响。
             */
            int afterMessageIndex,
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
             * 当时的**档案提示文本**（单据 04）：读**证据快照** profile 节点的 text，
             * **不回查当前档案**——患者改档案不回改历史；为空 = 当时无档案 / 老记录无该节点。
             */
            String profileText) {
    }

    /**
     * 一条会话的完整回放（右页正文 + 书签 + 结论卡）。
     *
     * <p>{@code cards} 按产生顺序给出**这条会话出过的每一张卡**（可能多张，见 {@link CardVO}）；
     * 前端按 {@link CardVO#afterMessageIndex()} 把它们插回消息流的对应位置。
     */
    public record SessionDetailVO(
            SessionItem session,
            List<MessageItem> messages,
            List<QuestionAnchor> questions,
            List<CardVO> cards) {
    }

    /**
     * 一条结论卡的挂号状态（患者端挂号页用）。
     *
     * <p>只读查询：挂号页加载时按 {@code recordId} 查一次，已挂号就直接渲染成功态，
     * 不再摆一个可点的「确认挂号」（点了也会被后端拒，不如一开始就不给这个入口）。
     */
    public record RegisterStatusVO(
            boolean booked,
            /** 挂到哪个科；未挂号为 null */
            String actualDept,
            /** 该科位置；未挂号（或科室已删）为 null */
            String actualDeptLocation) {
    }
}
