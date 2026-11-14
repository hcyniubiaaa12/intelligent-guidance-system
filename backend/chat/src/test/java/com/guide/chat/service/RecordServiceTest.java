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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

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

    // ---------------------------------------------------------------- 归档（收纳区）

    @Test
    @DisplayName("归档分流：归档的会话从 days 挪进 archivedDays，主区计数不含它")
    void archivedSplitFromMainList() {
        ChatSession active = session("s1", ME, at(20, 9, 0), SessionStatus.CLOSED, 1);
        ChatSession foldedNew = session("s2", ME, at(20, 10, 0), SessionStatus.CLOSED, 1);
        foldedNew.setArchived(1);
        ChatSession foldedOld = session("s3", ME, at(18, 8, 0), SessionStatus.CLOSED, 1);
        foldedOld.setArchived(1);
        when(sessionMapper.selectList(any())).thenReturn(List.of(active, foldedNew, foldedOld));

        RecordDTO.SessionListVO vo = service.listSessions(ME);

        // 归档区**同样按天分组**（口径=会话开始那天 startedAt），天最新在前
        assertEquals(List.of("2026-09-20", "2026-09-18"),
                vo.archivedDays().stream().map(RecordDTO.DayGroup::date).toList());
        assertEquals(List.of("s2"),
                vo.archivedDays().get(0).sessions().stream().map(RecordDTO.SessionItem::id).toList());
        assertTrue(vo.archivedDays().stream().flatMap(d -> d.sessions().stream())
                .allMatch(RecordDTO.SessionItem::archived));
        List<String> mainIds = vo.days().stream().flatMap(d -> d.sessions().stream())
                .map(RecordDTO.SessionItem::id).toList();
        assertEquals(List.of("s1"), mainIds, "归档的不能还留在主区");
        assertTrue(vo.days().stream().flatMap(d -> d.sessions().stream())
                .noneMatch(RecordDTO.SessionItem::archived));
        assertEquals(1, vo.totalSessions(), "主区计数不含归档条目");
    }

    @Test
    @DisplayName("归档自己的会话：写入 archived=1")
    void archivesOwnSession() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(20, 9, 0), SessionStatus.CLOSED, 0));

        service.setArchived(ME, "s1", true);

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getArchived());
    }

    @Test
    @DisplayName("归档幂等：已经是目标状态就不再写一次（不无谓刷新 updated_at）")
    void archiveIsIdempotent() {
        ChatSession session = session("s1", ME, at(20, 9, 0), SessionStatus.CLOSED, 0);
        session.setArchived(1);
        when(sessionMapper.selectById("s1")).thenReturn(session);

        service.setArchived(ME, "s1", true);
        service.setArchived(ME, "s1", false);

        // 第一次是"从 0 到 1"的重复请求、第二次才真正落库——只允许写一次
        verify(sessionMapper, times(1)).updateById(any());
    }

    @Test
    @DisplayName("归档别人的会话：按「不存在」处理（不泄露存在性）")
    void cannotArchiveOthersSession() {
        when(sessionMapper.selectById("s9")).thenReturn(session("s9", OTHER, at(20, 9, 0), SessionStatus.CLOSED, 0));

        assertThrows(BizException.class, () -> service.setArchived(ME, "s9", true));
        verify(sessionMapper, never()).updateById(any());
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
        assertTrue(vo.cards().isEmpty(), "没出结论的会话没有结论卡");
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

        RecordDTO.CardVO card = service.sessionDetail(ME, "s1").cards().get(0);

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

        List<ChatDTO.Cite> cites = service.sessionDetail(ME, "s1").cards().get(0).cites();

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

        RecordDTO.CardVO card = service.sessionDetail(ME, "s1").cards().get(0);

        assertEquals("神经内科", card.dept());
        assertTrue(card.top3().isEmpty());
        assertTrue(card.cites().isEmpty());
        assertNull(card.note());
        assertFalse(card.booked());
    }

    @Test
    @DisplayName("回放结论卡：「已参考档案」文本读自证据快照的 profile 节点，不回查当前档案")
    void cardProfileRefReadsSnapshot() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 1));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        GuideRecord record = new GuideRecord();
        record.setId("r1");
        record.setSessionId("s1");
        record.setRecDeptId("d-neuro");
        record.setConfidence(0.85);
        record.setLowConfidence(0);
        // 快照里存的是「当时那一份」档案：患者后来改成别的，这里也不会变
        record.setEvidence("""
                {"retrieved":[],
                 "profile":{"text":"男、45-59岁、2型糖尿病","query":"男、45-59岁、2型糖尿病",
                            "content":{"gender":"male","ageRange":"45-59","historyTags":["2型糖尿病"]}}}
                """);
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        RecordDTO.CardVO card = service.sessionDetail(ME, "s1").cards().get(0);

        assertEquals("男、45-59岁、2型糖尿病", card.profileText());
    }

    @Test
    @DisplayName("老记录无档案节点 / 无档案记录节点为空：回放不崩、档案行为空（兼容回归）")
    void cardToleratesMissingOrNullProfileNode() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 0));

        GuideRecord noNode = new GuideRecord();
        noNode.setSessionId("s1");
        noNode.setRecDeptId("d-neuro");
        noNode.setEvidence("{\"retrieved\":[]}"); // 单据 04 之前的老快照：没有 profile 节点
        when(recordMapper.selectList(any())).thenReturn(List.of(noNode));
        assertNull(service.sessionDetail(ME, "s1").cards().get(0).profileText(), "缺节点 → 空，不炸");

        GuideRecord nullNode = new GuideRecord();
        nullNode.setSessionId("s1");
        nullNode.setRecDeptId("d-neuro");
        nullNode.setEvidence("{\"retrieved\":[],\"profile\":null}"); // 无档案记录：节点为 null
        when(recordMapper.selectList(any())).thenReturn(List.of(nullNode));
        assertNull(service.sessionDetail(ME, "s1").cards().get(0).profileText(), "null 节点 → 空，不炸");
    }

    @Test
    @DisplayName("回放结论卡：alerts 读证据快照，含 memoryId；不回查当前记忆")
    void cardAlertsReadFromSnapshot() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 0));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        GuideRecord record = new GuideRecord();
        record.setId("r1");
        record.setSessionId("s1");
        record.setRecDeptId("d-neuro");
        record.setConfidence(0.85);
        record.setLowConfidence(0);
        record.setEvidence("""
                {"retrieved":[],
                 "memory":{"text":"【长期记忆】2型糖尿病","query":"胸闷","threshold":0.7,
                           "hits":[{"id":"m1","type":"background_health","key":"慢病","value":"2型糖尿病","score":0.86}]},
                 "alerts":[
                   {"level":"hard","text":"与您的过敏史「青霉素类」相关","anchor":"field:allergy","source":"rule","memoryId":null},
                   {"level":"advisory","text":"与您提到的「2型糖尿病」相关","anchor":"memory:m1","source":"memory","memoryId":"m1"}
                 ]}
                """);
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        List<ChatDTO.AlertVO> alerts = service.sessionDetail(ME, "s1").cards().get(0).alerts();

        assertEquals(2, alerts.size());
        assertEquals("hard", alerts.get(0).level());
        assertEquals("rule", alerts.get(0).source());
        assertNull(alerts.get(0).memoryId(), "档案硬警报没有记忆出处");
        assertEquals("advisory", alerts.get(1).level());
        assertEquals("memory", alerts.get(1).source());
        assertEquals("m1", alerts.get(1).memoryId());
        assertEquals("memory:m1", alerts.get(1).anchor());
        assertEquals("与您提到的「2型糖尿病」相关", alerts.get(1).text());
    }

    @Test
    @DisplayName("老记录没有 alerts / memory 节点：回放给空列表，不炸")
    void cardToleratesMissingAlertsAndMemoryNodes() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(19, 17, 0), SessionStatus.CLOSED, 0));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        GuideRecord record = new GuideRecord();
        record.setId("r1");
        record.setSessionId("s1");
        record.setRecDeptId("d-neuro");
        record.setConfidence(0.85);
        record.setLowConfidence(0);
        // 工单 10/11 之前的老快照：既没有 alerts 也没有 memory
        record.setEvidence("{\"retrieved\":[]}");
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        RecordDTO.CardVO card = service.sessionDetail(ME, "s1").cards().get(0);

        assertTrue(card.alerts().isEmpty(), "缺节点 → 空列表，不炸");
    }

    @Test
    @DisplayName("一条会话出多张卡：回放两张都在、各插在自己那一轮之后（工单 05 ③）")
    void multipleCardsAreReplayedInPosition() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(20, 9, 0), SessionStatus.CLOSED, 2));
        // 两轮对话：第一轮出卡①、患者补一句 → 第二轮出卡②（挂号挂在②上）。
        // id 用数字串，且按真实插库顺序排：患者消息 → 导诊记录 → AI 回复（saveConclusion 在 saveMessage(AI) 之前）
        ChatMessage q1 = message("s1", MessageRole.USER, "腰疼两天了", at(20, 9, 0));
        q1.setId("1000");
        ChatMessage a1 = message("s1", MessageRole.AI, "建议骨科", at(20, 9, 1));
        a1.setId("1002");
        ChatMessage q2 = message("s1", MessageRole.USER, "还伴着尿频尿急", at(20, 9, 2));
        q2.setId("1003");
        ChatMessage a2 = message("s1", MessageRole.AI, "建议泌尿外科", at(20, 9, 3));
        a2.setId("1005");
        when(messageMapper.selectList(any())).thenReturn(List.of(q1, a1, q2, a2));

        GuideRecord card1 = record("1001", "d-digest", null);
        GuideRecord card2 = record("1004", "d-neuro", "d-neuro");
        when(recordMapper.selectList(any())).thenReturn(List.of(card1, card2));

        RecordDTO.SessionDetailVO vo = service.sessionDetail(ME, "s1");

        assertEquals(2, vo.cards().size(), "一条会话的两张卡都要还回去，不能只给最新那张");
        // 卡①：下一条患者消息是 q2（下标 2）⇒ 插在 a1（下标 1）之后——跟着它自己那一轮走
        assertEquals(1, vo.cards().get(0).afterMessageIndex());
        // 卡②：没有更晚的患者消息 ⇒ 落在最后一条消息之后
        assertEquals(3, vo.cards().get(1).afterMessageIndex());
        // 每张卡的挂号态跟着自己的记录走：挂的是②，①不带就诊信息
        assertFalse(vo.cards().get(0).booked());
        assertTrue(vo.cards().get(1).booked());
    }

    @Test
    @DisplayName("「已挂号 / 挂到哪个科」取**已挂号的那条记录**，不是最新那张（工单 05 ③）")
    void bookedFollowsTheBookedRecordNotTheLatest() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(20, 9, 0), SessionStatus.CLOSED, 1));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("s1", MessageRole.USER, "头疼", at(20, 9, 0))));

        GuideRecord earlier = record("2001", "d-digest", null);      // 先出、没挂
        GuideRecord booked = record("2002", "d-neuro", "d-neuro");   // 中间出、挂了这张
        GuideRecord later = record("2003", "d-digest", null);        // 最后出、也没挂
        when(recordMapper.selectList(any())).thenReturn(List.of(earlier, booked, later));

        RecordDTO.SessionItem item = service.sessionDetail(ME, "s1").session();

        assertTrue(item.booked(), "会话里有人挂过号 ⇒ 已挂号");
        assertEquals("神经内科", item.actualDept(), "挂到的是那条记录，不是最新那张（最新是消化内科）");
        // 「结论」仍按最新一张展示：患者最近看到的那张
        assertEquals("消化内科", item.recDept());
    }

    @Test
    @DisplayName("会话没有任何挂号：已挂号为 false、不查实际科室（不产生假事实）")
    void noBookingMeansNotBooked() {
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(20, 9, 0), SessionStatus.ONGOING, 1));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("s1", MessageRole.USER, "头疼", at(20, 9, 0))));
        when(recordMapper.selectList(any())).thenReturn(List.of(record("r1", "d-neuro", null)));

        RecordDTO.SessionItem item = service.sessionDetail(ME, "s1").session();

        assertFalse(item.booked());
        assertNull(item.actualDept());
        assertEquals("神经内科", item.recDept(), "没挂号不影响结论行：仍按最新一张展示");
    }

    @Test
    @DisplayName("按 recordId 查挂号状态：已挂号给出科室与位置（工单 04 的后退进挂号页用）")
    void registerStatusReturnsBooking() {
        GuideRecord booked = record("2002", "d-neuro", "d-neuro");
        when(recordMapper.selectById("2002")).thenReturn(booked);
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(20, 9, 0), SessionStatus.CLOSED, 1));

        RecordDTO.RegisterStatusVO vo = service.registerStatus(ME, "2002");

        assertTrue(vo.booked());
        assertEquals("神经内科", vo.actualDept());
        assertEquals("门诊楼 4 层 A 区", vo.actualDeptLocation());
    }

    @Test
    @DisplayName("按 recordId 查挂号状态：没挂号 ⇒ booked=false，不编科室")
    void registerStatusWithoutBooking() {
        when(recordMapper.selectById("2001")).thenReturn(record("2001", "d-neuro", null));
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", ME, at(20, 9, 0), SessionStatus.ONGOING, 1));

        RecordDTO.RegisterStatusVO vo = service.registerStatus(ME, "2001");

        assertFalse(vo.booked());
        assertNull(vo.actualDept());
        assertNull(vo.actualDeptLocation());
    }

    @Test
    @DisplayName("查别人的记录挂号状态：按「不存在」处理（不泄露存在性）")
    void registerStatusOfOthersIsNotFound() {
        when(recordMapper.selectById("2002")).thenReturn(record("2002", "d-neuro", "d-neuro"));
        when(sessionMapper.selectById("s1")).thenReturn(session("s1", OTHER, at(20, 9, 0), SessionStatus.CLOSED, 1));

        assertThrows(BizException.class, () -> service.registerStatus(ME, "2002"));
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

    /** 一条导诊记录；{@code actualDeptId} 为空即这条卡没挂号 */
    private GuideRecord record(String id, String recDeptId, String actualDeptId) {
        GuideRecord record = new GuideRecord();
        record.setId(id);
        record.setSessionId("s1");
        record.setRecDeptId(recDeptId);
        record.setActualDeptId(actualDeptId);
        record.setLowConfidence(0);
        return record;
    }

    /** 2026-09-{day} {h}:{m} —— 用固定年份，断言里好对 */
    private LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 9, day, hour, minute);
    }
}
