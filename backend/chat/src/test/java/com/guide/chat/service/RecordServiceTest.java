package com.guide.chat.service;

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
import com.guide.common.exception.BizException;
import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import com.guide.rag.spi.ChunkTextProvider;
import com.guide.rag.support.AnswerParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 就诊记录回放单测：按天分组与排序、用户问题编号、结论卡从快照重建、越权防护。
 *
 * <p>只读服务，没有 lambda 更新语句，所以不需要 MyBatis-Plus 的 TableInfo 初始化。
 * ObjectMapper 与 AnswerParser 都用真的——note 是从 evidence.model_output_raw 里解析出来的，
 * 那正是要测的逻辑，mock 掉就等于没测。
 */
class RecordServiceTest {

    private static final String ME = "u001";
    private static final String OTHER = "u999";

    private ChatSessionMapper sessionMapper;
    private ChatMessageMapper messageMapper;
    private GuideRecordMapper recordMapper;
    private DeptService deptService;
    private ChunkTextProvider chunkTextProvider;
    private RecordService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        recordMapper = mock(GuideRecordMapper.class);
        deptService = mock(DeptService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        chunkTextProvider = mock(ChunkTextProvider.class);
        service = new RecordService(sessionMapper, messageMapper, recordMapper, deptService,
                new AnswerParser(objectMapper), chunkTextProvider, objectMapper);

        // 科室名解析：两个用到的科室
        when(deptService.getById("d-neuro")).thenReturn(dept("d-neuro", "神经内科", "门诊楼 4 层 A 区"));
        when(deptService.getById("d-digest")).thenReturn(dept("d-digest", "消化内科", "门诊楼 3 层 A 区"));
    }

    @Test
    @DisplayName("没有会话：空列表，计数都是 0")
    void empty() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        RecordDTO.SessionListVO vo = service.listSessions(ME);

        assertTrue(vo.days().isEmpty());
        assertEquals(0, vo.totalSessions());
        assertEquals(0, vo.totalBooked());
    }

    @Test
    @DisplayName("按天分组：天最新在前、天内会话也最新在前")
    void groupByDayNewestFirst() {
        // 刻意乱序传入，验证服务自己排序，而不是依赖查询顺序
        ChatSession older = session("s-old", ME, at(19, 17, 0), SessionStatus.CLOSED, 1);
        ChatSession newer = session("s-new", ME, at(20, 9, 0), SessionStatus.CLOSED, 1);
        ChatSession middle = session("s-mid", ME, at(19, 18, 0), SessionStatus.ONGOING, 0);
        when(sessionMapper.selectList(any())).thenReturn(List.of(older, newer, middle));

        RecordDTO.SessionListVO vo = service.listSessions(ME);

        assertEquals(List.of("2026-09-20", "2026-09-19"),
                vo.days().stream().map(RecordDTO.DayGroup::date).toList());
        assertEquals(1, vo.days().get(0).total());
        assertEquals(2, vo.days().get(1).total());
        assertEquals(List.of("s-mid", "s-old"),
                vo.days().get(1).sessions().stream().map(RecordDTO.SessionItem::id).toList());
    }

    @Test
    @DisplayName("会话条目：首句主诉取第一条用户消息、问题数只算 role=user、挂号标记取 actual_dept_id")
    void itemFields() {
        ChatSession session = session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 2);
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("s1", MessageRole.USER, "头痛得厉害", at(19, 17, 0)),
                message("s1", MessageRole.QUESTION, "请补充一下：哪个部位？", at(19, 17, 1)),
                message("s1", MessageRole.USER, "整个后脑勺", at(19, 17, 2)),
                message("s1", MessageRole.AI, "建议先到神经内科。", at(19, 17, 3))));

        GuideRecord record = new GuideRecord();
        record.setId("r1");
        record.setSessionId("s1");
        record.setRecDeptId("d-neuro");
        record.setConfidence(0.85);
        record.setRecTop3("[{\"deptId\":\"d-neuro\",\"dept\":\"神经内科\",\"confidence\":0.85}]");
        record.setLowConfidence(0);
        record.setActualDeptId("d-neuro");
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        RecordDTO.SessionItem item = service.listSessions(ME).days().get(0).sessions().get(0);

        assertEquals("头痛得厉害", item.firstComplaint());
        assertEquals(2, item.questionCount(), "追问是 AI 发的，不算用户问题");
        assertEquals("神经内科", item.recDept());
        assertEquals(85, item.confidence());
        assertTrue(item.booked());
        assertEquals("神经内科", item.actualDept());
        assertEquals(at(19, 17, 3), item.endedAt(), "结束时间取最后一条消息");
        assertEquals("closed", item.status());
        assertEquals(2, item.askRound());
        assertEquals(1, service.listSessions(ME).totalBooked());
    }

    @Test
    @DisplayName("一个会话多条导诊记录：只认最后落库那条（历史不回改）")
    void latestRecordWins() {
        ChatSession session = session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 2);
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        GuideRecord first = new GuideRecord();
        first.setSessionId("s1");
        first.setRecDeptId("d-digest");
        first.setConfidence(0.62);
        first.setCreatedAt(at(19, 17, 1));

        GuideRecord second = new GuideRecord();
        second.setSessionId("s1");
        second.setRecDeptId("d-neuro");
        second.setConfidence(0.85);
        second.setCreatedAt(at(19, 17, 9));

        when(recordMapper.selectList(any())).thenReturn(List.of(first, second)); // 查询已按时间升序

        RecordDTO.SessionItem item = service.listSessions(ME).days().get(0).sessions().get(0);
        assertEquals("神经内科", item.recDept());
        assertEquals(85, item.confidence());
    }

    @Test
    @DisplayName("详情：别人的会话按「不存在」处理，不区分有没有这个 id")
    void detailRejectsOtherUser() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", OTHER, at(19, 17, 0), SessionStatus.CLOSED, 0));

        assertThrows(BizException.class, () -> service.sessionDetail(ME, "s1"));
        when(sessionMapper.selectById("nope")).thenReturn(null);
        assertThrows(BizException.class, () -> service.sessionDetail(ME, "nope"));
    }

    @Test
    @DisplayName("详情：用户问题按时间从 1 编号，编号只挂在 role=user 上；追问不带编号")
    void questionAnchors() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 1));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("s1", MessageRole.USER, "头痛得厉害", at(19, 17, 0)),
                message("s1", MessageRole.QUESTION, "哪个部位？", at(19, 17, 1)),
                message("s1", MessageRole.USER, "整个后脑勺", at(19, 17, 2))));
        when(recordMapper.selectList(any())).thenReturn(List.of());

        RecordDTO.SessionDetailVO vo = service.sessionDetail(ME, "s1");

        assertEquals(2, vo.questions().size());
        assertEquals(1, vo.questions().get(0).no());
        assertEquals("头痛得厉害", vo.questions().get(0).content());
        assertEquals(2, vo.questions().get(1).no());
        assertEquals(3, vo.messages().size());
        assertNull(vo.messages().get(1).questionNo(), "追问是 AI 发的，没有书签编号");
        assertEquals(2, vo.messages().get(2).questionNo());
        assertNull(vo.card(), "没出结论的会话没有结论卡");
    }

    @Test
    @DisplayName("结论卡：从 rec_top3 与 evidence 两份快照重建，note 从模型原始输出取回")
    void cardRebuiltFromSnapshots() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 1));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        GuideRecord record = new GuideRecord();
        record.setId("r1");
        record.setSessionId("s1");
        record.setRecDeptId("d-neuro");
        record.setConfidence(0.85);
        record.setLowConfidence(0);
        record.setActualDeptId("d-neuro");
        record.setRecTop3("[{\"dept\":\"神经内科\",\"confidence\":0.85},"
                + "{\"dept\":\"骨科\",\"confidence\":0.09},"
                + "{\"dept\":\"心血管内科\",\"confidence\":null}]");
        record.setEvidence("{\"retrieved\":[{\"rank\":1,\"title\":\"头痛的分诊与危险信号\"},"
                + "{\"rank\":2,\"title\":\"颈肩腰腿痛的分诊要点\"}],"
                + "\"model_output_raw\":\"建议先到神经内科。\\n---RESULT---\\n"
                + "{\\\"dept\\\":\\\"神经内科\\\",\\\"confidence\\\":0.85,\\\"note\\\":\\\"后枕部胀痛伴低头加重\\\"}\"}");
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        RecordDTO.CardVO card = service.sessionDetail(ME, "s1").card();

        assertEquals("神经内科", card.dept());
        assertEquals(3, card.top3().size());
        assertEquals(85, card.top3().get(0).pct());
        assertNull(card.top3().get(2).pct(), "模型没给合法置信度 → pct 为 null，前端显示「—」而不是 0%");
        // 快照里没有 model_cited（老记录/模型没标引用）→ 退回列出全部召回，
        // 不让「判断依据」整块空掉
        assertEquals(2, card.cites().size());
        assertEquals("头痛的分诊与危险信号", card.cites().get(0).title());
        assertEquals(1, card.cites().get(0).no());
        assertEquals("后枕部胀痛伴低头加重", card.note());
        assertTrue(card.booked());
        assertEquals("门诊楼 4 层 A 区", card.actualDeptLocation());
    }

    @Test
    @DisplayName("判断依据只列模型引用的注：没引用的不列，老快照缺原文时回 MySQL 取")
    void cardListsOnlyCitedNotesWithContent() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 18, 0), SessionStatus.CLOSED, 0));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        GuideRecord record = new GuideRecord();
        record.setId("r2");
        record.setSessionId("s1");
        record.setRecDeptId("d-neuro");
        record.setConfidence(0.9);
        record.setLowConfidence(0);
        // 注3 召回了但模型没用上 → 不该出现在判断依据里；
        // 注2 是 2026-09-26 之前的老快照（没有 content）→ 回 MySQL 取。
        // 用文本块写：这段 JSON 里嵌套引号太多，字符串拼接错一处就变成"解析得了但取不到字段"的哑弹
        record.setEvidence("""
                {"retrieved":[
                  {"rank":1,"chunk_id":"c1","title":"头痛的分诊与危险信号","content":"头痛伴发热、颈项强直需急诊"},
                  {"rank":2,"chunk_id":"c2","title":"颈肩腰腿痛的分诊要点"},
                  {"rank":3,"chunk_id":"c3","title":"头晕与眩晕的鉴别","content":"眩晕的鉴别"}],
                 "model_cited":[1,2],
                 "model_output_raw":"---RESULT---\\n{\\"dept\\":\\"神经内科\\",\\"confidence\\":0.9}"}
                """);
        when(recordMapper.selectList(any())).thenReturn(List.of(record));
        when(chunkTextProvider.loadTexts(List.of("c2")))
                .thenReturn(Map.of("c2", new ChunkTextProvider.ChunkText("颈肩腰腿痛的分诊要点", "颈部僵硬伴手指麻木者首诊骨科")));

        List<ChatDTO.Cite> cites = service.sessionDetail(ME, "s1").card().cites();

        assertEquals(2, cites.size(), "注3 模型没引用，不该列进判断依据");
        assertEquals(1, cites.get(0).no());
        assertEquals("头痛伴发热、颈项强直需急诊", cites.get(0).content());
        assertEquals(2, cites.get(1).no());
        assertEquals("颈部僵硬伴手指麻木者首诊骨科", cites.get(1).content(), "老快照没存原文，回 MySQL 回填");
        verify(chunkTextProvider).loadTexts(List.of("c2"));
    }

    @Test
    @DisplayName("快照解析不了也不炸：卡片其余字段照常返回，note 为空")
    void cardToleratesBadSnapshot() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 0));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        GuideRecord record = new GuideRecord();
        record.setSessionId("s1");
        record.setRecDeptId("d-neuro");
        record.setRecTop3("这不是 JSON");
        record.setEvidence("{也不是");
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        RecordDTO.CardVO card = service.sessionDetail(ME, "s1").card();

        assertEquals("神经内科", card.dept());
        assertTrue(card.top3().isEmpty());
        assertTrue(card.cites().isEmpty());
        assertNull(card.note());
        assertFalse(card.booked());
    }

    // ---------------------------------------------------------------- 夹具

    private Dept dept(String id, String name, String location) {
        Dept dept = new Dept();
        dept.setId(id);
        dept.setName(name);
        dept.setLocation(location);
        dept.setEnabled(1);
        return dept;
    }

    private ChatSession session(String id, String userId, LocalDateTime createdAt, SessionStatus status, int askRound) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(userId);
        session.setStatus(status);
        session.setAskRound(askRound);
        session.setHasResult(status == SessionStatus.CLOSED ? 1 : 0);
        session.setCreatedAt(createdAt);
        return session;
    }

    private ChatMessage message(String sessionId, MessageRole role, String content, LocalDateTime at) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(at);
        return message;
    }

    /** 2026-09-{day} {h}:{m} —— 用固定年份，断言里好对 */
    private LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 9, day, hour, minute);
    }
}
