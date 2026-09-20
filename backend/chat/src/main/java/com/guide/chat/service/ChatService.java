package com.guide.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.SysConfigService;
import com.guide.chat.config.ChatExecutorConfig;
import com.guide.chat.dto.ChatDTO;
import com.guide.chat.dto.SseEvents;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.enums.SessionStatus;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.chat.support.StreamGate;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import com.guide.rag.RagService;
import com.guide.rag.dto.DeptOption;
import com.guide.rag.dto.RagAnswer;
import com.guide.rag.dto.RagContext;
import com.guide.rag.dto.RagRequest;
import com.guide.rag.dto.RagTurn;
import com.guide.rag.support.AnswerParser;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 问诊对话编排（链路 A 主链路）。
 * 单轮流：会话状态机 → 消息落库 → 敏感词入口前置校验 → 规则硬门槛 → RAG 检索
 * → 流式生成（分流闸门逐字转发 delta）→ 解析 → 追问 或 结论落库 → result → done。
 *
 * <p>SSE 四态：delta（对话流）/ question（追问，不出结论）/ result（结论卡片）/ done（收尾），
 * 异常统一走 error（文案说清原因与下一步）。
 */
@Slf4j
@Service
public class ChatService {

    /** 建流超时：模型流式生成期间可能长时间无数据，取宽松值 */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    /** 送入模型的历史消息条数上限（控制 token 与串话风险） */
    private static final int HISTORY_LIMIT = 8;

    /** MDC 键：轮次标记（日志按一次导诊归组用，见 turnTag） */
    private static final String TURN_KEY = "turn";

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final GuideService guideService;
    private final RagService ragService;
    private final AnswerParser answerParser;
    private final SensitiveGuard sensitiveGuard;
    private final SufficiencyRule sufficiencyRule;
    private final DeptService deptService;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor chatSseExecutor;

    public ChatService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                       GuideService guideService, RagService ragService,
                       AnswerParser answerParser, SensitiveGuard sensitiveGuard,
                       SufficiencyRule sufficiencyRule, DeptService deptService,
                       SysConfigService sysConfigService, ObjectMapper objectMapper,
                       @Qualifier(ChatExecutorConfig.CHAT_SSE_EXECUTOR) ThreadPoolTaskExecutor chatSseExecutor) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.guideService = guideService;
        this.ragService = ragService;
        this.answerParser = answerParser;
        this.sensitiveGuard = sensitiveGuard;
        this.sufficiencyRule = sufficiencyRule;
        this.deptService = deptService;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
        this.chatSseExecutor = chatSseExecutor;
    }

    /** 建立 SSE 流：立即返回 emitter，编排在线程池中执行（与离线入库线程隔离） */
    public SseEmitter stream(String userId, ChatDTO.MessageReq request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        try {
            chatSseExecutor.execute(() -> {
                try {
                    handle(userId, request, emitter);
                } catch (Exception e) {
                    log.error("导诊流处理异常", e);
                    safeComplete(emitter);
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池已满（AbortPolicy）：宁可明确报错，也不在 Tomcat 线程里同步跑完模型生成
            // （那样 emitter 尚未交给 MVC，流式渲染会整体失效）
            log.warn("导诊线程池已满，拒绝本轮请求：{}", e.getMessage());
            emitError(emitter, null, "当前问诊人数较多，请稍后再试。");
            safeComplete(emitter);
        }
        return emitter;
    }

    /** 挂号科室范围（患者端挂号页） */
    public List<ChatDTO.DeptVO> listDepts() {
        List<ChatDTO.DeptVO> result = new ArrayList<>();
        for (Dept dept : deptService.listEnabled()) {
            result.add(new ChatDTO.DeptVO(dept.getId(), dept.getName(), dept.getLocation(), dept.getIntro()));
        }
        return result;
    }

    /** 挂号确认（写入 actual_dept 与命中标记、会话置 closed） */
    public ChatDTO.RegisterVO confirmRegister(String userId, ChatDTO.RegisterReq request) {
        return guideService.confirmRegister(userId, request);
    }

    private void handle(String userId, ChatDTO.MessageReq request, SseEmitter emitter) {
        String sessionId = null;
        long start = System.currentTimeMillis();
        try {
            String content = request.getContent() == null ? "" : request.getContent().trim();
            SessionResolution resolution = resolveSession(userId, request.getSessionId());
            ChatSession session = resolution.session();
            sessionId = session.getId();
            // 轮次标记进 MDC：本轮所有日志（含 rag/kb/llm 各层）都带同一个标记，便于顺链排查
            MDC.put(TURN_KEY, turnTag(session));
            log.info("会话{}：askRound={} hasResult={} status={}", resolution.created() ? "新建" : "续聊",
                    session.getAskRound(), session.getHasResult(), session.getStatus());
            handleTurn(userId, session, content, emitter);
        } catch (BizException e) {
            log.warn("导诊流业务异常：{}", e.getMessage());
            emitError(emitter, sessionId, patientMessage(e));
        } catch (Exception e) {
            log.error("导诊流异常", e);
            emitError(emitter, sessionId, "系统繁忙，请稍后重试；您也可以重新描述一次症状。");
        } finally {
            log.info("本轮结束：总耗时 {} ms", System.currentTimeMillis() - start);
            MDC.remove(TURN_KEY);
            safeComplete(emitter);
        }
    }

    /** 单轮编排主体：会话落定后执行（sessionId 已确定，异常由外层统一转 SSE error） */
    private void handleTurn(String userId, ChatSession session, String content, SseEmitter emitter) {
        {
            String sessionId = session.getId();
            log.info("患者输入：{}", abbreviate(content, 120));
            send(emitter, SseEvents.SESSION, new SseEvents.SessionEvent(sessionId));

            saveMessage(sessionId, MessageRole.USER, content);

            // ① 敏感词入口前置校验（先于信息充足性判定）
            SensitiveGuard.GuardResult guard = sensitiveGuard.check(userId, sessionId, content);
            if (guard.action() == SensitiveGuard.GuardResult.Action.BLOCKED) {
                log.info("入口校验：命中禁止词「{}」，拦截并返回固定引导（未调模型）", guard.word());
                saveMessage(sessionId, MessageRole.AI, guard.reply());
                emitText(emitter, sessionId, guard.reply());
                send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(sessionId, false));
                return;
            }
            log.info("入口校验：{}", guard.action() == SensitiveGuard.GuardResult.Action.WATCHED
                    ? "命中观察词「" + guard.word() + "」，放行并留痕" : "通过（无禁止词命中）");

            // ② 规则硬门槛：确定性无效输入 / 首条主诉过于笼统 → 模板追问，不调模型
            int askMaxRounds = sysConfigService.getInt(SysConfigService.KEY_ASK_MAX_ROUNDS, 3);
            int askRound = session.getAskRound() == null ? 0 : session.getAskRound();
            String gateReason = ruleGateReason(session, content, askRound, askMaxRounds);
            if (gateReason != null) {
                String question = sufficiencyRule.templateQuestion();
                log.info("规则门槛：{}｜追问轮次 {}/{}，模板追问（未调模型/未检索）",
                        gateReason, askRound, askMaxRounds);
                saveMessage(sessionId, MessageRole.QUESTION, question);
                ask(emitter, session, question);
                return;
            }

            // ③ 检索（查询改写 → 双路召回 → RRF → 精排）
            boolean forceConclusion = askRound >= askMaxRounds;
            log.info("进入检索：追问轮次 {}/{}｜强制结论={}｜候选科室 {} 个", askRound, askMaxRounds,
                    forceConclusion, deptOptions().size());
            RagRequest ragRequest = new RagRequest(content, loadHistory(sessionId), deptOptions(),
                    askRound, forceConclusion,
                    sysConfigService.getInt(SysConfigService.KEY_RETRIEVE_TOP_K, RagRequest.DEFAULT_TOP_K),
                    sysConfigService.getInt(SysConfigService.KEY_RETRIEVE_TOP_N, RagRequest.DEFAULT_TOP_N));
            RagContext context = ragService.retrieve(ragRequest);

            // ④ 流式生成：闸门分流——自然语言进气泡，结论 JSON 截留待解析
            StreamGate gate = new StreamGate(AnswerParser.MARKER);
            long[] generation = new long[3];  // [0] 首字时间戳 [1] 分片数 [2] 可见文本字数
            long start = System.currentTimeMillis();
            generation[0] = 0;
            String rawOutput = ragService.streamAnswer(ragRequest, context, delta -> {
                if (generation[0] == 0) {
                    generation[0] = System.currentTimeMillis();
                }
                generation[1]++;
                String visible = gate.accept(delta);
                if (!visible.isEmpty()) {
                    generation[2] += visible.length();
                    emitText(emitter, sessionId, visible);
                }
            });
            String tail = gate.flush();
            if (!tail.isEmpty()) {
                generation[2] += tail.length();
                emitText(emitter, sessionId, tail);
            }
            long generationMs = System.currentTimeMillis() - start;
            long firstTokenMs = generation[0] == 0 ? generationMs : generation[0] - start;
            log.info("模型生成：首字 {} ms｜总耗时 {} ms｜输出 {} 字（可见 {} 字）｜分片 {} 个",
                    firstTokenMs, generationMs, rawOutput.length(), generation[2], generation[1]);
            log.debug("模型原始输出：{}", abbreviate(rawOutput, 800));

            // ⑤ 解析与分流
            RagAnswer answer = answerParser.parse(rawOutput);
            if (answer.verdict() == RagAnswer.Verdict.RECOMMEND && !rawOutput.contains(AnswerParser.MARKER)) {
                // 模型漏输出结论分隔符：JSON 已随 delta 流进气泡，这里只留痕，便于后续调 Prompt
                log.warn("模型未输出结论分隔符，按 JSON 兜底解析（结论 JSON 已随 delta 进入气泡）");
            }
            logAnswer(answer, forceConclusion);
            if (answer.verdict() == RagAnswer.Verdict.ASK && !forceConclusion) {
                saveMessage(sessionId, MessageRole.QUESTION, answer.reply());
                ask(emitter, session, answer.reply());
                return;
            }

            // ⑥ 结论：科室校验 + 导诊记录落库 + result/done
            GuideService.Conclusion conclusion = guideService.saveConclusion(session, answer, context, rawOutput);
            saveMessage(sessionId, MessageRole.AI, answer.reply());
            send(emitter, SseEvents.RESULT, conclusion.payload());
            send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(sessionId, true));
        }
    }

    /** 解析结果留痕：判定分支、模型自报置信度（含是否通过校验）、Top3、引用注号 */
    private void logAnswer(RagAnswer answer, boolean forceConclusion) {
        if (answer.verdict() == RagAnswer.Verdict.ASK) {
            log.info("结论解析：判定=追问（信息不足{}）｜追问内容 {} 字",
                    forceConclusion ? "，但已到追问上限，将强制出低置信度结论" : "", answer.reply().length());
            return;
        }
        StringBuilder top3 = new StringBuilder();
        for (RagAnswer.DeptCandidate candidate : answer.top3()) {
            top3.append(candidate.dept()).append('=').append(candidate.confidence()).append(' ');
        }
        log.info("结论解析：判定=出结论｜模型自报置信度={}（校验{}）｜Top3 [{}]｜引用注号 {}｜说明 {}",
                answer.confidence(), answer.confidenceValid() ? "通过" : "未通过→置空走低置信度分流",
                top3.toString().trim(), answer.cites(), abbreviate(answer.note(), 80));
    }

    /**
     * 追问分支：轮次 +1 + question/done 事件（消息落库由调用方负责）。
     * 注意追问文本在 ASK 轮已随 delta 流过——question 事件是「这条气泡属追问」的权威标记，
     * 前端据此把流式气泡定性为追问，而不是再渲染一遍。
     */
    private void ask(SseEmitter emitter, ChatSession session, String question) {
        int nextRound = (session.getAskRound() == null ? 0 : session.getAskRound()) + 1;
        log.info("追问发出：轮次 {} → {}｜内容：{}", session.getAskRound(), nextRound, abbreviate(question, 120));
        session.setAskRound(nextRound);
        sessionMapper.updateById(session);
        send(emitter, SseEvents.QUESTION, new SseEvents.QuestionEvent(session.getId(), question, nextRound));
        send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(session.getId(), false));
    }

    /**
     * 会话状态机（新主诉判定双信号）：请求带的会话可续聊（ongoing 且未出结论）则续聊，
     * 否则开新会话——判定是状态机字段，不做模型语义判断。
     */
    private SessionResolution resolveSession(String userId, String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            ChatSession existing = sessionMapper.selectById(sessionId);
            if (existing != null && userId.equals(existing.getUserId()) && existing.continuable()) {
                return new SessionResolution(existing, false);
            }
        }
        ChatSession created = new ChatSession();
        created.setUserId(userId);
        created.setStatus(SessionStatus.ONGOING);
        created.setAskRound(0);
        created.setHasResult(0);
        sessionMapper.insert(created);
        return new SessionResolution(created, true);
    }

    /**
     * 轮次标记（MDC）：会话后 6 位 + 本轮序号，例如 s666690-r2。
     * 一轮导诊跨 chat/rag/kb/llm 多层，日志按它归组才看得清顺序。
     */
    private String turnTag(ChatSession session) {
        String id = session.getId();
        String shortId = id.length() > 6 ? id.substring(id.length() - 6) : id;
        int round = (session.getAskRound() == null ? 0 : session.getAskRound()) + 1;
        return "s" + shortId + "-r" + round;
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\s+", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    /** 会话解析结果：created=true 表示本轮开了新会话 */
    private record SessionResolution(ChatSession session, boolean created) {
    }

    /**
     * 是否仍是本会话的首条主诉：用状态机信号判定（尚未追问、尚未出结论），
     * 不用消息条数——被敏感词拦截的消息也会落库，按条数判定会让规则门槛在拦截后失效。
     */
    private boolean isFirstTurn(ChatSession session) {
        int askRound = session.getAskRound() == null ? 0 : session.getAskRound();
        return askRound == 0 && (session.getHasResult() == null || session.getHasResult() == 0);
    }

    /**
     * 规则硬门槛判定：返回 null 表示放行。命中任意一条即模板追问（不调模型、不检索）。
     *
     * <p>① 确定性无效输入（语气词/寒暄/说不出/纯符号）——不区分轮次；
     * ② 首条主诉过于笼统（过短且未命中术语）——只对自由陈述生效。
     * 应答轮不叠加内容门槛，理由见 {@link SufficiencyRule} 类注释。
     *
     * <p><b>护栏</b>：追问预算（ask_max_rounds）用尽后规则一律让路。规则门槛位于
     * forceConclusion 判定之前，若继续拦，askRound 只涨而永远走不到检索分支，
     * forceConclusion 永不触发，会话会卡死在"回答 → 被问同一句 → 再回答"的循环里。
     */
    private String ruleGateReason(ChatSession session, String content, int askRound, int askMaxRounds) {
        if (askRound >= askMaxRounds) {
            return null;
        }
        if (sufficiencyRule.noSignal(content)) {
            return "确定性无效输入";
        }
        if (isFirstTurn(session) && sufficiencyRule.tooVague(content)) {
            return "首条主诉过于笼统（未命中术语且过短）";
        }
        return null;
    }

    /** 历史消息（不含刚落的当前用户消息），role：question/ai → assistant */
    private List<RagTurn> loadHistory(String sessionId) {
        List<ChatMessage> rows = messageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .orderByAsc(ChatMessage::getId));
        List<RagTurn> history = new ArrayList<>();
        int end = Math.max(0, rows.size() - 1);
        int from = Math.max(0, end - HISTORY_LIMIT);
        for (ChatMessage row : rows.subList(from, end)) {
            String role = row.getRole() == MessageRole.USER ? "user" : "assistant";
            history.add(new RagTurn(role, row.getContent()));
        }
        return history;
    }

    private List<DeptOption> deptOptions() {
        List<DeptOption> options = new ArrayList<>();
        for (Dept dept : deptService.listEnabled()) {
            options.add(new DeptOption(dept.getId(), dept.getName()));
        }
        return options;
    }

    private void saveMessage(String sessionId, MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        messageMapper.insert(message);
    }

    private void emitText(SseEmitter emitter, String sessionId, String text) {
        send(emitter, SseEvents.DELTA, new SseEvents.DeltaEvent(sessionId, text));
    }

    /**
     * 异常 → 患者可见文案：业务异常（如知识库无可用科室）原样透出，
     * 模型/系统类异常换成面向患者的说法——异常原文里有上游响应体、配置项名甚至 Key 片段，
     * 只能进日志，不能进患者气泡。
     */
    private String patientMessage(BizException e) {
        return switch (e.getCode()) {
            case 4000 -> e.getMessage();                       // RAG_EMPTY：知识库暂无相关内容
            case 3001 -> e.getMessage();                       // SENSITIVE_BLOCKED 等业务提示
            default -> "服务暂时不可用，请稍后重试；您也可以重新描述一次症状。";
        };
    }

    /** 错误事件推送：本身就是兜底路径，推送失败只记日志不抛 */
    private void emitError(SseEmitter emitter, String sessionId, String message) {
        try {
            send(emitter, SseEvents.ERROR, new SseEvents.ErrorEvent(sessionId, message));
            send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(sessionId, false));
        } catch (Exception e) {
            log.debug("错误事件推送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            log.debug("SSE 事件：{}｜{} 字", event, json.length());
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(json, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            // 客户端断开：终止本轮流，不再继续生成与落库
            throw new BizException(ErrorCode.INTERNAL_ERROR, "连接已断开");
        } catch (IllegalStateException e) {
            log.debug("SSE 已完成，跳过事件 {}：{}", event, e.getMessage());
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开或已结束，忽略
        }
    }
}
