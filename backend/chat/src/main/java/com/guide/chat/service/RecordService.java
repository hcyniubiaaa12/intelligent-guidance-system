package com.guide.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.chat.dto.ChatDTO;
import com.guide.chat.dto.RecordDTO;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.enums.SessionStatus;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import com.guide.rag.dto.RagAnswer;
import com.guide.rag.support.AnswerParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 就诊记录（患者端只读回放）：把既有事实按「一次就诊」重新组织给患者自己看。
 *
 * <p>只读——不写任何表、不改任何状态。三份数据源：
 * {@code chat_session}（会话 = 一次就诊）、{@code chat_message}（对话正文）、
 * {@code guide_record}（结论卡：rec_top3 与 evidence 是只写快照，直接回放当时的原样）。
 *
 * <p>越权防护：列表按 userId 过滤；详情先查会话再比对 userId，别人的会话一律按「不存在」处理
 * （不区分「没有」与「不是你的」，避免探出别的会话 id 是否存在）。
 *
 * <p>为什么一次把消息全查出来在内存分组：一个人的会话量级是个位到几十条，
 * 分页省下的那点数据远不值多一次往返与一套分页协议；真到几百条再改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final GuideRecordMapper guideRecordMapper;
    private final DeptService deptService;
    private final AnswerParser answerParser;
    private final ObjectMapper objectMapper;

    /** 我的会话列表（按天分组，天与会话都最新在前） */
    public RecordDTO.SessionListVO listSessions(String userId) {
        List<ChatSession> sessions = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getCreatedAt));
        if (sessions.isEmpty()) {
            return new RecordDTO.SessionListVO(List.of(), 0, 0);
        }

        List<String> sessionIds = sessions.stream().map(ChatSession::getId).toList();
        Map<String, List<ChatMessage>> messagesBySession = loadMessages(sessionIds);
        Map<String, GuideRecord> latestRecord = loadLatestRecords(sessionIds);
        Map<String, Dept> deptCache = new LinkedHashMap<>();

        List<RecordDTO.SessionItem> items = new ArrayList<>();
        for (ChatSession session : sessions) {
            items.add(toItem(session, messagesBySession.getOrDefault(session.getId(), List.of()),
                    latestRecord.get(session.getId()), deptCache));
        }

        long booked = items.stream().filter(RecordDTO.SessionItem::booked).count();
        log.debug("就诊记录：用户 {} 会话 {} 条（已挂号 {} 条）", userId, items.size(), booked);
        return new RecordDTO.SessionListVO(groupByDay(items), items.size(), (int) booked);
    }

    /** 一条会话的完整回放（正文 + 用户问题书签 + 结论卡）；不是自己的会话按不存在处理 */
    public RecordDTO.SessionDetailVO sessionDetail(String userId, String sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }

        List<ChatMessage> messages = messageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt));

        // 用户问题 = role=user 的消息，按时间从 1 编号——书签注号就是它，与轮次无关
        List<RecordDTO.QuestionAnchor> questions = new ArrayList<>();
        List<RecordDTO.MessageItem> replay = new ArrayList<>();
        int no = 0;
        for (ChatMessage message : messages) {
            Integer questionNo = null;
            if (message.getRole() == MessageRole.USER) {
                questionNo = ++no;
                questions.add(new RecordDTO.QuestionAnchor(questionNo, message.getContent(), message.getCreatedAt()));
            }
            replay.add(new RecordDTO.MessageItem(roleCode(message.getRole()), message.getContent(),
                    message.getCreatedAt(), questionNo));
        }

        GuideRecord record = loadLatestRecords(List.of(sessionId)).get(sessionId);
        Map<String, Dept> deptCache = new LinkedHashMap<>();
        return new RecordDTO.SessionDetailVO(
                toItem(session, messages, record, deptCache),
                replay, questions, toCard(record, deptCache));
    }

    // ---------------------------------------------------------------- 组装

    private RecordDTO.SessionItem toItem(ChatSession session, List<ChatMessage> messages,
                                         GuideRecord record, Map<String, Dept> deptCache) {
        List<ChatMessage> userMessages = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .toList();
        String firstComplaint = userMessages.isEmpty() ? "" : userMessages.get(0).getContent();
        LocalDateTime startedAt = session.getCreatedAt();
        LocalDateTime endedAt = messages.isEmpty() ? startedAt : messages.get(messages.size() - 1).getCreatedAt();

        String recDept = null;
        Integer confidence = null;
        boolean lowConfidence = false;
        boolean booked = false;
        String actualDept = null;
        if (record != null) {
            Dept rec = dept(record.getRecDeptId(), deptCache);
            recDept = rec == null ? null : rec.getName();
            confidence = percent(record.getConfidence());
            lowConfidence = record.getLowConfidence() != null && record.getLowConfidence() == 1;
            booked = record.getActualDeptId() != null;
            if (booked) {
                Dept actual = dept(record.getActualDeptId(), deptCache);
                actualDept = actual == null ? null : actual.getName();
            }
        }
        return new RecordDTO.SessionItem(session.getId(), firstComplaint, startedAt, endedAt,
                statusCode(session.getStatus()), session.getAskRound() == null ? 0 : session.getAskRound(),
                session.getHasResult() != null && session.getHasResult() == 1,
                userMessages.size(), recDept, confidence, lowConfidence, booked, actualDept);
    }

    /** 结论卡：从 rec_top3 与 evidence 两份只写快照重建，语义与 result 事件载荷一致 */
    private RecordDTO.CardVO toCard(GuideRecord record, Map<String, Dept> deptCache) {
        if (record == null) {
            return null;
        }
        Dept rec = dept(record.getRecDeptId(), deptCache);
        boolean booked = record.getActualDeptId() != null;
        Dept actual = booked ? dept(record.getActualDeptId(), deptCache) : null;

        List<ChatDTO.Top3Item> top3 = new ArrayList<>();
        List<ChatDTO.Cite> cites = new ArrayList<>();
        String note = null;
        try {
            JsonNode array = record.getRecTop3() == null ? null : objectMapper.readTree(record.getRecTop3());
            if (array != null) {
                for (JsonNode node : array) {
                    JsonNode conf = node.get("confidence");
                    top3.add(new ChatDTO.Top3Item(node.path("dept").asText(),
                            conf == null || conf.isNull() ? null : (int) Math.round(conf.asDouble() * 100)));
                }
            }
            JsonNode evidence = record.getEvidence() == null ? null : objectMapper.readTree(record.getEvidence());
            if (evidence != null) {
                // 注号 = 证据快照 retrieved 顺序（与 Prompt 中「注N」同一套编号）
                for (JsonNode chunk : evidence.path("retrieved")) {
                    cites.add(new ChatDTO.Cite(chunk.path("rank").asInt(), chunk.path("title").asText()));
                }
                // 结论说明不在 guide_record 列里，只在 evidence 的模型原始输出里——用同一套解析器取回，
                // 取不到就当没有（卡片少一行说明，不影响其余字段）
                RagAnswer answer = answerParser.parse(evidence.path("model_output_raw").asText(""));
                note = answer.note();
            }
        } catch (Exception e) {
            log.warn("结论卡重建失败（记录 {}）：{}", record.getId(), e.getMessage());
        }
        return new RecordDTO.CardVO(record.getId(),
                rec == null ? null : rec.getName(), record.getConfidence(), top3, note, cites,
                record.getLowConfidence() != null && record.getLowConfidence() == 1,
                booked, actual == null ? null : actual.getName(),
                actual == null ? null : actual.getLocation());
    }

    /** 按天分组（天与天内会话都最新在前）；日期口径用服务器本地日期 */
    private List<RecordDTO.DayGroup> groupByDay(List<RecordDTO.SessionItem> items) {
        Map<LocalDate, List<RecordDTO.SessionItem>> byDay = new LinkedHashMap<>();
        for (RecordDTO.SessionItem item : items) {
            LocalDateTime at = item.startedAt() == null ? LocalDateTime.now() : item.startedAt();
            byDay.computeIfAbsent(at.toLocalDate(), k -> new ArrayList<>()).add(item);
        }
        List<RecordDTO.DayGroup> days = new ArrayList<>();
        byDay.forEach((date, list) -> {
            list.sort(Comparator.comparing(RecordDTO.SessionItem::startedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            days.add(new RecordDTO.DayGroup(date.format(DAY), list.size(), list));
        });
        days.sort(Comparator.comparing(RecordDTO.DayGroup::date).reversed());
        return days;
    }

    // ---------------------------------------------------------------- 取数

    private Map<String, List<ChatMessage>> loadMessages(List<String> sessionIds) {
        Map<String, List<ChatMessage>> bySession = new LinkedHashMap<>();
        List<ChatMessage> messages = messageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .in(ChatMessage::getSessionId, sessionIds)
                .orderByAsc(ChatMessage::getCreatedAt));
        for (ChatMessage message : messages) {
            bySession.computeIfAbsent(message.getSessionId(), k -> new ArrayList<>()).add(message);
        }
        return bySession;
    }

    /**
     * 每个会话最新的那条导诊记录。
     * 一个会话可能落多条（追问后重出结论），回放只认最后落库的那条——与「历史不回改」一致。
     */
    private Map<String, GuideRecord> loadLatestRecords(List<String> sessionIds) {
        Map<String, GuideRecord> latest = new LinkedHashMap<>();
        List<GuideRecord> records = guideRecordMapper.selectList(Wrappers.<GuideRecord>lambdaQuery()
                .in(GuideRecord::getSessionId, sessionIds)
                .orderByAsc(GuideRecord::getCreatedAt));
        for (GuideRecord record : records) {
            latest.put(record.getSessionId(), record); // 升序遍历 → 最后留下的即最新
        }
        return latest;
    }

    private Dept dept(String deptId, Map<String, Dept> cache) {
        if (deptId == null) {
            return null;
        }
        return cache.computeIfAbsent(deptId, deptService::getById);
    }

    /** 置信度 → 百分比；null 保持 null（前端显示「—」而不是 0%） */
    private Integer percent(Double confidence) {
        return confidence == null ? null : (int) Math.round(confidence * 100);
    }

    private String statusCode(SessionStatus status) {
        return status == null ? SessionStatus.ONGOING.getCode() : status.getCode();
    }

    private String roleCode(MessageRole role) {
        return role == null ? MessageRole.AI.getCode() : role.getCode();
    }
}
