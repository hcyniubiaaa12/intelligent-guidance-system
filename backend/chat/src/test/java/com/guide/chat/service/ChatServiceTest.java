package com.guide.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.HealthProfileService;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.HealthProfileAssembler;
import com.guide.chat.dto.ChatDTO;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.enums.SessionStatus;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.kb.service.DeptService;
import com.guide.rag.RagService;
import com.guide.rag.dto.RagContext;
import com.guide.rag.dto.RagRequest;
import com.guide.rag.support.AnswerParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单轮分流单测（链路 A 第 ⑤ 步）：模型声明的 verdict 决定这一轮怎么发出去。
 *
 * <p>为什么要有这组用例：2026-09-30 前判据是「没有结论 JSON ⟹ 追问」，于是"答了资料却给不出科室"
 * 被伪装成追问——患者看到一段回答被贴上「追问」标签，追问轮次还白涨一次。三条路各自的副作用
 * （落哪种 role、动不动 ask_round、产不产生结论）就是本测试的断言对象。
 *
 * <p>断言落在**落库副作用**而不是 SSE 事件名：{@code stream()} 内部自建 SseEmitter（无注入点），
 * 而 role 与事件一一对应（INFO 分支才写 role=info），落库能等价证明走到了哪条路。
 */
class ChatServiceTest {

    /** 追问：信息不足，模型显式声明 ASK */
    private static final String RAW_ASK = "胸闷大概持续多久了？有没有向左肩放射？\n"
            + AnswerParser.MARKER + "\n{\"verdict\":\"ASK\"}";

    /** 资料回答：患者问的是知识库内容，模型显式声明 INFO */
    private static final String RAW_INFO = "根据知识片段，P1 事故必须记录根因、影响用户数、恢复时间与补偿方案。\n"
            + AnswerParser.MARKER + "\n{\"verdict\":\"INFO\"}";

    /** 未声明：模型既没给分隔符也没给 JSON——这正是历史上被误判成追问的那种输出 */
    private static final String RAW_UNKNOWN = "P1事故必须记录：根因、影响用户数、恢复时间和补偿方案。";

    /** 出结论：模型声明 RECOMMEND（档案行回传路径需要走到 saveConclusion） */
    private static final String RAW_RECOMMEND = "结合您的糖尿病史，优先排查心血管来源。\n"
            + AnswerParser.MARKER + "\n{\"verdict\":\"RECOMMEND\",\"dept\":\"心血管内科\",\"confidence\":0.82,"
            + "\"top3\":[{\"dept\":\"心血管内科\",\"confidence\":0.82}],\"note\":\"结合既往史\",\"cites\":[1]}";

    private static final String TEMPLATE = "请补充一下部位、感觉和持续时间。";
    private static final String TEMPLATE_REPEAT = "我还是没听到症状相关的信息，可以这样说：胸口闷三天了。";

    private ChatSessionMapper sessionMapper;
    private ChatMessageMapper messageMapper;
    private GuideService guideService;
    private RagService ragService;
    private SufficiencyRule sufficiencyRule;
    private SysConfigService sysConfigService;
    private HealthProfileService healthProfileService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        guideService = mock(GuideService.class);
        ragService = mock(RagService.class);
        SensitiveGuard sensitiveGuard = mock(SensitiveGuard.class);
        sufficiencyRule = mock(SufficiencyRule.class);
        DeptService deptService = mock(DeptService.class);
        sysConfigService = mock(SysConfigService.class);
        healthProfileService = mock(HealthProfileService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

        // 新会话：insert 时补 id（真实环境由 MyBatis-Plus 生成；turnTag 要读它，null 会 NPE）
        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChatSession.class).setId("s1234567890");
            return 1;
        });
        when(sensitiveGuard.check(anyString(), any(), anyString())).thenReturn(SensitiveGuard.GuardResult.pass());
        when(sufficiencyRule.templateQuestion()).thenReturn(TEMPLATE);
        when(sufficiencyRule.templateQuestionRepeat()).thenReturn(TEMPLATE_REPEAT);
        when(sysConfigService.getInt(anyString(), anyInt())).thenReturn(3);
        when(deptService.listEnabled()).thenReturn(List.of());
        // 默认无档案：两段空串、结构化档案 null ⇒ 不拼 prompt 段、不加推荐卡行、快照节点写空（与本单据前的链路一致）
        when(healthProfileService.assembleForChat(anyString()))
                .thenReturn(new HealthProfileService.ChatProfile(
                        new HealthProfileAssembler.Assembly("", "", false), null));
        when(ragService.retrieve(any())).thenAnswer(invocation -> new RagContext("q", "q", List.of(), 0, 0));
        // 线程池 mock 成同步执行：编排在调用线程里跑完，断言不必等
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        chatService = new ChatService(sessionMapper, messageMapper, guideService, ragService,
                new AnswerParser(new ObjectMapper()), sensitiveGuard, sufficiencyRule, deptService,
                sysConfigService, healthProfileService, new ObjectMapper(), executor);
    }

    @Test
    @DisplayName("声明 ASK：落 question 消息、追问轮次 +1、不出结论")
    void askVerdictAdvancesRound() {
        modelReturns(RAW_ASK);

        chatService.stream("u1", req("胸口闷"));

        assertEquals(MessageRole.QUESTION, lastMessage().getRole());
        ArgumentCaptor<ChatSession> session = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionMapper).updateById(session.capture());
        assertEquals(1, session.getValue().getAskRound(), "追问必须推进轮次");
        verify(guideService, never()).saveConclusion(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("声明 INFO：落 info 消息、**不动追问轮次**、不出结论")
    void infoVerdictKeepsRound() {
        modelReturns(RAW_INFO);

        chatService.stream("u1", req("P1事故要记录什么"));

        ChatMessage saved = lastMessage();
        assertEquals(MessageRole.INFO, saved.getRole());
        assertTrue(saved.getContent().contains("P1 事故必须记录"), "资料回答的正文要完整落库");
        verify(sessionMapper, never()).updateById(any());
        verify(guideService, never()).saveConclusion(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("未声明 verdict：落 ai 消息、不动轮次、不出结论——不再冒充追问")
    void undeclaredFallsBackToPlainAnswer() {
        modelReturns(RAW_UNKNOWN);

        chatService.stream("u1", req("P1事故要记录什么"));

        assertEquals(MessageRole.AI, lastMessage().getRole());
        verify(sessionMapper, never()).updateById(any());
        verify(guideService, never()).saveConclusion(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("每条分支都先落用户消息：role=user 在最前，本轮消息在其后")
    void userMessageAlwaysSavedFirst() {
        modelReturns(RAW_INFO);

        chatService.stream("u1", req("P1事故要记录什么"));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        assertEquals(MessageRole.USER, captor.getAllValues().get(0).getRole());
        assertEquals("P1事故要记录什么", captor.getAllValues().get(0).getContent());
        assertEquals("s1234567890", captor.getAllValues().get(0).getSessionId());
    }

    @Test
    @DisplayName("追问预算用尽：即便模型没声明 RECOMMEND，也照旧走结论兜底（会话必须能闭环）")
    void forceConclusionStillConcludesWhenModelDoesNotComply() {
        // 已达追问上限的会话（可续聊：ongoing + 未出结论）
        ChatSession ongoing = ongoingSession(3);
        when(sessionMapper.selectById("s1234567890")).thenReturn(ongoing);
        when(guideService.saveConclusion(any(), any(), any(), any(), any())).thenReturn(conclusion());
        modelReturns(RAW_UNKNOWN);

        chatService.stream("u1", req("胸口闷", "s1234567890"));

        // 关键：UNKNOWN 在这一轮**不能**降级成普通回答，否则 ask_round 已满、又永远不出 result，会话闭不了环
        verify(guideService).saveConclusion(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("连发无效输入不吃追问预算：连发 5 次「你好」永不触发结论（2026-09-30 的回归）")
    void invalidInputNeverConsumesBudget() {
        // 复现路径：无效输入 → 规则门槛的通用模板追问。它若扣预算，连发 5 次就会扣光，
        // 让路护栏放行 → forceConclusion 强制出结论 → 患者从未描述症状，模型只能编。
        when(sufficiencyRule.noSignal("你好")).thenReturn(true);
        when(sessionMapper.selectById("s1234567890")).thenReturn(ongoingSession(0));
        // 第一档尚未发过（0）→ 第二档起（1）：切档看的是"本会话有没有回过模板追问"
        when(messageMapper.selectCount(any())).thenReturn(0L, 1L);

        chatService.stream("u1", req("你好"));
        for (int i = 0; i < 4; i++) {
            chatService.stream("u1", req("你好", "s1234567890"));
        }

        // 轮次一次都不许动（动了就会走到 forceConclusion）
        verify(sessionMapper, never()).updateById(any());
        // 更硬的断言：5 轮下来一个结论都不许产生
        verify(guideService, never()).saveConclusion(any(), any(), any(), any(), any());
        verify(ragService, never()).retrieve(any());

        List<String> questions = questions();
        assertEquals(5, questions.size());
        assertEquals(TEMPLATE, questions.get(0), "第一次用第一档话术");
        assertTrue(questions.subList(1, 5).stream().allMatch(TEMPLATE_REPEAT::equals),
                "之后换成第二档，不再复读同一句");
    }

    @Test
    @DisplayName("首条主诉过于笼统仍吃预算：它是真的在追问信息（护栏为它而留）")
    void vagueFirstInputConsumesBudget() {
        when(sufficiencyRule.tooVague("我不舒服")).thenReturn(true);
        when(sessionMapper.selectById("s1234567890")).thenReturn(ongoingSession(0));

        chatService.stream("u1", req("我不舒服", "s1234567890"));

        ArgumentCaptor<ChatSession> session = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionMapper).updateById(session.capture());
        assertEquals(1, session.getValue().getAskRound());
        assertEquals(TEMPLATE, questions().get(0));
    }

    @Test
    @DisplayName("填了档案也不能让「我不舒服」直接出结论：规则硬门槛仍跑在检索/模型之前（安全阀）")
    void healthProfileCannotBypassRuleGate() {
        when(sufficiencyRule.tooVague("我不舒服")).thenReturn(true);
        when(sessionMapper.selectById("s1234567890")).thenReturn(ongoingSession(0));
        // 患者确实填了档案
        when(healthProfileService.assembleForChat("u1"))
                .thenReturn(new HealthProfileService.ChatProfile(
                        new HealthProfileAssembler.Assembly("糖尿病史", "糖尿病史", false), null));

        chatService.stream("u1", req("我不舒服", "s1234567890"));

        // 门槛在档案之前：不检索、不出结论、只给模板追问
        verify(ragService, never()).retrieve(any());
        verify(guideService, never()).saveConclusion(any(), any(), any(), any(), any());
        assertEquals(TEMPLATE, questions().get(0));
    }

    @Test
    @DisplayName("档案进模型上下文 + 结论载荷：待注入文本既进 RagRequest，也随结论回传推荐卡与证据快照")
    void profileTextFlowsIntoRagAndConclusion() {
        HealthProfileAssembler.Profile structure = new HealthProfileAssembler.Profile(
                "male", "45-59", List.of("2型糖尿病"), null,
                List.of(), null, List.of(), null);
        when(healthProfileService.assembleForChat("u1"))
                .thenReturn(new HealthProfileService.ChatProfile(
                        new HealthProfileAssembler.Assembly("男、45-59岁、糖尿病史",
                                "男、45-59岁、糖尿病史", false), structure));
        when(guideService.saveConclusion(any(), any(), any(), any(), any())).thenReturn(conclusion());
        modelReturns(RAW_RECOMMEND);

        chatService.stream("u1", req("胸口闷"));

        ArgumentCaptor<RagRequest> ragCaptor = ArgumentCaptor.forClass(RagRequest.class);
        verify(ragService).retrieve(ragCaptor.capture());
        assertEquals("男、45-59岁、糖尿病史", ragCaptor.getValue().profileText());

        // 结论回传的是「档案快照输入」：待注入文本 + 检索用串 + 结构化档案三者一起进 saveConclusion，
        // 由 GuideService 落进证据快照 profile 节点（单据 04）
        ArgumentCaptor<GuideService.ProfileSnapshot> snapshot =
                ArgumentCaptor.forClass(GuideService.ProfileSnapshot.class);
        verify(guideService).saveConclusion(any(), any(), any(), any(), snapshot.capture());
        assertEquals("男、45-59岁、糖尿病史", snapshot.getValue().text(), "推荐卡档案行来自后端读到的档案");
        assertEquals("男、45-59岁、糖尿病史", snapshot.getValue().query(), "检索用串一并进快照");
        assertEquals(structure, snapshot.getValue().structure(), "结构化档案一并进快照");
    }

    @Test
    @DisplayName("无档案：RagRequest 的档案字段为空（回归——链路与今天一致）")
    void emptyProfileYieldsNullProfileText() {
        modelReturns(RAW_ASK);

        chatService.stream("u1", req("胸口闷"));

        ArgumentCaptor<RagRequest> ragCaptor = ArgumentCaptor.forClass(RagRequest.class);
        verify(ragService).retrieve(ragCaptor.capture());
        assertNull(ragCaptor.getValue().profileText());
    }

    @Test
    @DisplayName("在归档会话里续聊：自动取回主区（它不该还待在收纳区）")
    void resumingArchivedSessionUnarchives() {
        // 只点开回放不会触发（那条路不发消息）；真正的续聊才会把它带回主区
        ChatSession archived = ongoingSession(0);
        archived.setArchived(1);
        when(sessionMapper.selectById("s1234567890")).thenReturn(archived);
        modelReturns(RAW_INFO);

        chatService.stream("u1", req("P1事故要记录什么", "s1234567890"));

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getArchived(), "续聊必须把归档标记清掉");
    }

    /** 模型原始输出灌进本轮：streamAnswer 把它推给 onDelta 并原样返回 */
    @SuppressWarnings("unchecked")
    private void modelReturns(String raw) {
        when(ragService.streamAnswer(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, Consumer.class).accept(raw);
            return raw;
        });
    }

    /** 本轮的第二条消息（第一条永远是用户消息） */
    private ChatMessage lastMessage() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        return captor.getAllValues().get(1);
    }

    /** 落库的追问消息内容（按顺序）——用来断言两档话术的切换 */
    private List<String> questions() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().stream()
                .filter(message -> message.getRole() == MessageRole.QUESTION)
                .map(ChatMessage::getContent)
                .toList();
    }

    /** 可续聊的会话（ongoing + 未出结论） */
    private ChatSession ongoingSession(int askRound) {
        ChatSession session = new ChatSession();
        session.setId("s1234567890");
        session.setUserId("u1");
        session.setStatus(SessionStatus.ONGOING);
        session.setAskRound(askRound);
        session.setHasResult(0);
        return session;
    }

    /** 结论兜底的返回值（saveConclusion 是 mock，这里只保证 payload 非空，避免 NPE 掩盖断言） */
    private GuideService.Conclusion conclusion() {
        return new GuideService.Conclusion(new GuideRecord(), new ChatDTO.ResultVO("s1234567890", "r1", "d1",
                "心血管内科", 0.4, List.of(), "信息不足，按检索片段兜底", List.of(), true, null));
    }

    private ChatDTO.MessageReq req(String content) {
        return req(content, null);
    }

    private ChatDTO.MessageReq req(String content, String sessionId) {
        ChatDTO.MessageReq request = new ChatDTO.MessageReq();
        request.setContent(content);
        request.setSessionId(sessionId);
        return request;
    }
}
