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
import com.guide.common.util.TextUtil;
import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import com.guide.rag.dto.RagAnswer;
import com.guide.rag.spi.ChunkTextProvider;
import com.guide.rag.support.AnswerParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 判断依据里每条证据原文的截断长度（与实时结论卡同口径） */
    private static final int CITE_CONTENT_MAX = 400;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final GuideRecordMapper guideRecordMapper;
    private final DeptService deptService;
    private final AnswerParser answerParser;
    private final ChunkTextProvider chunkTextProvider;
    private final ObjectMapper objectMapper;

    /** 我的会话列表（主区按天分组 + 归档区平铺，天与会话都最新在前） */
    public RecordDTO.SessionListVO listSessions(String userId) {
        // 归档的会话**照样查出来**（它们仍在列表数据里，只是归收纳区）——归档不是删除，
        // 这也是它与 deleted 的分工：deleted 由 @TableLogic 自动过滤掉，archived 由这里手工分流
        List<ChatSession> sessions = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getCreatedAt));
        if (sessions.isEmpty()) {
            return new RecordDTO.SessionListVO(List.of(), List.of(), 0, 0);
        }

        List<String> sessionIds = sessions.stream().map(ChatSession::getId).toList();
        Map<String, List<ChatMessage>> messagesBySession = loadMessages(sessionIds);
        Map<String, List<GuideRecord>> recordsBySession = loadRecordsBySession(sessionIds);
        Map<String, Dept> deptCache = new LinkedHashMap<>();

        List<RecordDTO.SessionItem> items = new ArrayList<>();
        for (ChatSession session : sessions) {
            items.add(toItem(session, messagesBySession.getOrDefault(session.getId(), List.of()),
                    recordsBySession.getOrDefault(session.getId(), List.of()), deptCache));
        }

        // 主区与收纳区分开：主区的天头要显示"这天几次"，混着归档条目每个计数都得重算。
        // 归档区**同样按天分组**（口径与主区一致：按 startedAt，会话开始那天算）
        List<RecordDTO.SessionItem> active = items.stream().filter(item -> !item.archived()).toList();
        List<RecordDTO.SessionItem> archivedItems = items.stream().filter(RecordDTO.SessionItem::archived).toList();
        long booked = active.stream().filter(RecordDTO.SessionItem::booked).count();
        log.debug("就诊记录：用户 {} 会话 {} 条（主区 {}、已归档 {}；主区已挂号 {} 条）",
                userId, items.size(), active.size(), archivedItems.size(), booked);
        return new RecordDTO.SessionListVO(groupByDay(active), groupByDay(archivedItems),
                active.size(), (int) booked);
    }

    /**
     * 归档 / 取回一条会话——患者对**自己的列表**做整理，不是删除：
     * 归档后仍可只读回放（详情与回放不看 archived），随时可取回，也不影响任何统计口径。
     *
     * <p>归属校验同 {@link #sessionDetail}：不是自己的会话按**不存在**处理——
     * 返回"无权访问"等于告诉调用方这条会话存在，等于给了个探测器。
     */
    public void setArchived(String userId, String sessionId, boolean archived) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }
        int target = archived ? 1 : 0;
        Integer current = session.getArchived();
        if (current != null && current == target) {
            return; // 幂等：已经是这个状态就不再写一次 updated_at
        }
        session.setArchived(target);
        sessionMapper.updateById(session);
        log.info("会话归档状态变更：sessionId={} archived={}", sessionId, target);
    }

    /** 一条会话的完整回放（正文 + 用户问题书签 + 结论卡）；不是自己的会话按不存在处理 */
    public RecordDTO.SessionDetailVO sessionDetail(String userId, String sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }

        List<ChatMessage> messages = messageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt)
                // 同一秒内 created_at 打平（DATETIME 无小数秒），id 作稳定次序：雪花按插入顺序单调递增
                .orderByAsc(ChatMessage::getId));

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

        List<GuideRecord> records = loadRecordsBySession(List.of(sessionId))
                .getOrDefault(sessionId, List.of());
        Map<String, Dept> deptCache = new LinkedHashMap<>();
        return new RecordDTO.SessionDetailVO(
                toItem(session, messages, records, deptCache),
                replay, questions, buildCards(records, messages, deptCache));
    }

    /**
     * 这条会话出过的**每一张卡**（可能多张），各带自己在消息流里的插入位置。
     *
     * <p>为什么不把卡一律堆到最后：库里两张、回放只一张就是**数据与展示不一致**，而回放是取证的地方。
     */
    private List<RecordDTO.CardVO> buildCards(List<GuideRecord> records, List<ChatMessage> messages,
                                              Map<String, Dept> deptCache) {
        List<RecordDTO.CardVO> cards = new ArrayList<>();
        for (GuideRecord record : records) {
            cards.add(toCard(record, afterMessageIndex(messages, record.getId()), deptCache));
        }
        return cards;
    }

    /**
     * 这张卡该插在消息流里哪一条之后（0 基下标）——**只此一处实现**，前端不自己再推。
     *
     * <p>规则：本会话里第一条 {@code role=user} 且 {@code id > recordId} 的消息的下标 − 1；
     * 没有这样的消息（这条卡是会话里最后发生的事）时取最后一条消息的下标。
     *
     * <p>为什么用"下一条患者消息"当右边界：导诊记录在本轮结束时落库，下一轮患者消息必然更晚
     * （同源雪花 id 单调递增），所以这个下标一定落在**这张卡自己那一轮的最后一条消息之后**；
     * 若改用 {@code created_at}，同一秒内多条消息的先后不可靠（DATETIME 无小数秒），卡会串位。
     *
     * <p>兜底夹到 0：按本规则的语义下标本就不会为负（记录总在本轮患者消息之后落库），
     * 夹一下只为防御历史数据异常，不让前端拿到一个越界下标。
     */
    private int afterMessageIndex(List<ChatMessage> messages, String recordId) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == MessageRole.USER && idAfter(message.getId(), recordId)) {
                return Math.max(0, i - 1);
            }
        }
        return Math.max(0, messages.size() - 1);
    }

    /**
     * 消息 id 是否晚于记录 id（MyBatis-Plus {@code ASSIGN_ID}：雪花，按插入顺序单调递增）。
     *
     * <p>不写"非数字 id 就退回字符串比较"那类兜底：库里的 id 全是本应用自己生成的数字雪花，
     * 真出现非数字只会是数据被外部改坏——那时**该响亮地炸**，而不是悄悄换一套比较规则，
     * 把回放的卡位默默放错（回放是取证的地方，错位比报错更糟）。
     *
     * <p>{@code null} 一律当"不晚于"：只会出现在没落过库的实体上（测试夹具），
     * 此时没有"下一条患者消息"可依，卡落在会话末尾，与旧行为一致。
     */
    private boolean idAfter(String messageId, String recordId) {
        return messageId != null && recordId != null
                && Long.compare(Long.parseLong(messageId), Long.parseLong(recordId)) > 0;
    }

    /**
     * 按 recordId 查**这条结论卡的挂号状态**（患者端挂号页用）。
     *
     * <p>为什么需要它：患者挂号成功后从挂号页**后退**回来（或直接刷新挂号页），
     * 页面要能直接显示"已挂号"的成功态，而不是再摆一个可点的「确认挂号」——
     * 点了也会被后端拒（{@code RECORD_ALREADY_REGISTERED}），那是"报了错再说"，
     * 不如一开始就不给这个入口。
     *
     * <p>只读：不写任何表、不改任何状态。归属校验同 {@link #sessionDetail}——
     * 不是自己的记录按「不存在」处理（返回"无权访问"等于告诉调用方这条记录存在）。
     */
    public RecordDTO.RegisterStatusVO registerStatus(String userId, String recordId) {
        GuideRecord record = guideRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }
        ChatSession session = sessionMapper.selectById(record.getSessionId());
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }
        if (record.getActualDeptId() == null) {
            return new RecordDTO.RegisterStatusVO(false, null, null);
        }
        Dept actual = dept(record.getActualDeptId(), new LinkedHashMap<>());
        return new RecordDTO.RegisterStatusVO(true,
                actual == null ? null : actual.getName(),
                actual == null ? null : actual.getLocation());
    }

    // ---------------------------------------------------------------- 组装

    /**
     * 会话条目。一条会话可能出多张卡（工单 05），所以这里收的是**全部记录**，两个口径分别派生：
     *
     * <ul>
     *   <li>「结论」= **最新一张**（患者最近看到的那张卡，侧栏与详情展示口径）；</li>
     *   <li>「是否已挂号 / 挂到哪个科」= **已挂号的那一条**（以会话为口径）。</li>
     * </ul>
     *
     * <p>为什么后者不能跟着"最新一张"走：患者可以只给其中一张挂号，而后又追问出第三张——
     * 那时"最新一张"并不是挂过号的那张，按它取会让侧栏把"已挂号 / 挂哪个科"指到一张没挂的卡上。
     * 一个会话最多只有 0 或 1 条已挂号记录（挂号即 {@code CLOSED}，会话不再可续），故这里取到即止。
     */
    private RecordDTO.SessionItem toItem(ChatSession session, List<ChatMessage> messages,
                                         List<GuideRecord> records, Map<String, Dept> deptCache) {
        List<ChatMessage> userMessages = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .toList();
        String firstComplaint = userMessages.isEmpty() ? "" : userMessages.get(0).getContent();
        LocalDateTime startedAt = session.getCreatedAt();
        LocalDateTime endedAt = messages.isEmpty() ? startedAt : messages.get(messages.size() - 1).getCreatedAt();

        GuideRecord latest = records.isEmpty() ? null : records.get(records.size() - 1);
        GuideRecord bookedRecord = bookedOf(records);

        String recDept = null;
        Integer confidence = null;
        boolean lowConfidence = false;
        if (latest != null) {
            Dept rec = dept(latest.getRecDeptId(), deptCache);
            recDept = rec == null ? null : rec.getName();
            confidence = percent(latest.getConfidence());
            lowConfidence = latest.getLowConfidence() != null && latest.getLowConfidence() == 1;
        }
        boolean booked = bookedRecord != null;
        String actualDept = null;
        if (booked) {
            Dept actual = dept(bookedRecord.getActualDeptId(), deptCache);
            actualDept = actual == null ? null : actual.getName();
        }
        return new RecordDTO.SessionItem(session.getId(), firstComplaint, startedAt, endedAt,
                statusCode(session.getStatus()), session.getAskRound() == null ? 0 : session.getAskRound(),
                session.getHasResult() != null && session.getHasResult() == 1,
                userMessages.size(), recDept, confidence, lowConfidence, booked, actualDept,
                session.getArchived() != null && session.getArchived() == 1);
    }

    /** 该会话**已挂号的那条记录**；没有（或没出过结论）时为 null。最多一条，见 {@link #toItem} 说明 */
    private GuideRecord bookedOf(List<GuideRecord> records) {
        for (GuideRecord record : records) {
            if (record.getActualDeptId() != null) {
                return record;
            }
        }
        return null;
    }

    /** 结论卡：从 rec_top3 与 evidence 两份只写快照重建，语义与 result 事件载荷一致 */
    private RecordDTO.CardVO toCard(GuideRecord record, int afterMessageIndex, Map<String, Dept> deptCache) {
        Dept rec = dept(record.getRecDeptId(), deptCache);
        boolean booked = record.getActualDeptId() != null;
        Dept actual = booked ? dept(record.getActualDeptId(), deptCache) : null;

        List<ChatDTO.Top3Item> top3 = new ArrayList<>();
        List<ChatDTO.Cite> cites = new ArrayList<>();
        String note = null;
        String profileText = null;
        try {
            JsonNode array = record.getRecTop3() == null ? null : objectMapper.readTree(record.getRecTop3());
            if (array != null) {
                for (JsonNode node : array) {
                    JsonNode conf = node.get("confidence");
                    // deptId 是 2026-10-02 起才写进快照的，老记录这一项为 null：
                    // 前端按「无名次」处理（不参与挂号页提序），不影响其余字段的回放
                    top3.add(new ChatDTO.Top3Item(node.path("deptId").asText(null),
                            node.path("dept").asText(),
                            conf == null || conf.isNull() ? null : (int) Math.round(conf.asDouble() * 100)));
                }
            }
            JsonNode evidence = record.getEvidence() == null ? null : objectMapper.readTree(record.getEvidence());
            if (evidence != null) {
                // 判断依据的口径与实时结论卡一致：**只列模型引用的注**（快照里的 model_cited），
                // 注号仍等于 retrieved 顺序（与正文「（注N）」同一套编号），并带上原文
                Set<Integer> cited = new HashSet<>();
                for (JsonNode no : evidence.path("model_cited")) {
                    cited.add(no.asInt());
                }
                // 一条都没引用时退回全部召回（与实时结论卡同一口径）：
                // 模型没标「（注N）」不代表没有依据，而这一块空着比多列几条更糟
                boolean filter = !cited.isEmpty();
                Map<String, String> texts = loadContentsForMissing(evidence, cited, filter);
                for (JsonNode chunk : evidence.path("retrieved")) {
                    int rank = chunk.path("rank").asInt();
                    if (filter && !cited.contains(rank)) {
                        continue;
                    }
                    String content = chunk.path("content").asText("");
                    if (content.isEmpty()) {
                        // 2026-09-26 之前的快照没存原文：回 MySQL 取一次，让老记录也有原文可看
                        content = texts.getOrDefault(chunk.path("chunk_id").asText(""), "");
                    }
                    cites.add(new ChatDTO.Cite(rank, chunk.path("title").asText(),
                            TextUtil.abbreviate(content, CITE_CONTENT_MAX)));
                }
                // 结论说明不在 guide_record 列里，只在 evidence 的模型原始输出里——用同一套解析器取回，
                // 取不到就当没有（卡片少一行说明，不影响其余字段）
                RagAnswer answer = answerParser.parse(evidence.path("model_output_raw").asText(""));
                note = answer.note();
                // 「已参考您的健康档案」行读**当时的快照**（profile.text），不回查当前档案——
                // 患者改档案不回改历史。老记录 / 无档案时节点缺失或为空，path() 取值安全、行为 null
                JsonNode profile = evidence.path("profile");
                if (profile.isObject()) {
                    String text = profile.path("text").asText("");
                    profileText = text.isBlank() ? null : text;
                }
            }
        } catch (Exception e) {
            log.warn("结论卡重建失败（记录 {}）：{}", record.getId(), e.getMessage());
        }
        return new RecordDTO.CardVO(record.getId(), afterMessageIndex,
                rec == null ? null : rec.getName(), record.getConfidence(), top3, note, cites,
                record.getLowConfidence() != null && record.getLowConfidence() == 1,
                booked, actual == null ? null : actual.getName(),
                actual == null ? null : actual.getLocation(), profileText);
    }

    /**
     * 给快照里**没存原文**的那些被引注补上正文（读 MySQL，MySQL 是切片正文的事实源）。
     * 只为 2026-09-26 之前落库的老记录服务；新记录快照里自带原文，这里返回空 Map。
     */
    private Map<String, String> loadContentsForMissing(JsonNode evidence, Set<Integer> cited, boolean filter) {
        List<String> missing = new ArrayList<>();
        for (JsonNode chunk : evidence.path("retrieved")) {
            boolean wanted = !filter || cited.contains(chunk.path("rank").asInt());
            if (wanted && chunk.path("content").asText("").isEmpty()) {
                String id = chunk.path("chunk_id").asText("");
                if (!id.isEmpty()) {
                    missing.add(id);
                }
            }
        }
        if (missing.isEmpty()) {
            return Map.of();
        }
        Map<String, String> texts = new LinkedHashMap<>();
        chunkTextProvider.loadTexts(missing)
                .forEach((id, text) -> texts.put(id, text.content()));
        return texts;
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
     * 每个会话的**全部**导诊记录，按产生顺序（createdAt → id）升序——数组最后一个即"最新一张"。
     *
     * <p>一条会话可能落多条（出结论后患者继续补充、模型再出结论，2026-10-14 工单 05 定案），
     * 回放要把每一张都还回去；「已挂号的那条」也在这里派生（见 {@link #toItem}）。
     */
    private Map<String, List<GuideRecord>> loadRecordsBySession(List<String> sessionIds) {
        Map<String, List<GuideRecord>> bySession = new LinkedHashMap<>();
        List<GuideRecord> records = guideRecordMapper.selectList(Wrappers.<GuideRecord>lambdaQuery()
                .in(GuideRecord::getSessionId, sessionIds)
                .orderByAsc(GuideRecord::getCreatedAt)
                .orderByAsc(GuideRecord::getId));
        for (GuideRecord record : records) {
            bySession.computeIfAbsent(record.getSessionId(), k -> new ArrayList<>()).add(record);
        }
        return bySession;
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
