package com.guide.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.HealthProfileService;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.HealthProfileAssembler;
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
 * → 流式生成（分流闸门逐字转发 delta）→ 解析 → 追问 / 资料回答 / 结论落库 → result → done。
 *
 * <p>SSE 五态：delta（对话流）/ question（追问，不出结论）/ info（资料回答，不出结论也不占轮次）
 * / result（结论卡片）/ done（收尾），异常统一走 error（文案说清原因与下一步）。
 * 三态由模型在结论 JSON 的 verdict 字段里显式声明，见 {@link com.guide.rag.dto.RagAnswer.Verdict}。
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
    private final HealthProfileService healthProfileService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor chatSseExecutor;

    public ChatService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                       GuideService guideService, RagService ragService,
                       AnswerParser answerParser, SensitiveGuard sensitiveGuard,
                       SufficiencyRule sufficiencyRule, DeptService deptService,
                       SysConfigService sysConfigService, HealthProfileService healthProfileService,
                       ObjectMapper objectMapper,
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
        this.healthProfileService = healthProfileService;
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
            if (guard.action() == SensitiveGuard.GuardResult.Action.BLOCKED
                    || guard.action() == SensitiveGuard.GuardResult.Action.MUTED) {
                // 拦截与禁言同构：都返回固定话术、都不调模型不进导诊，差别只在话术由谁给
                boolean muted = guard.action() == SensitiveGuard.GuardResult.Action.MUTED;
                log.info("入口校验：{}", muted
                        ? "用户处于禁言期（至 " + guard.muteUntil() + "），本轮不进入导诊"
                        : "命中禁止词「" + guard.word() + "」，拦截并返回固定引导（未调模型）");
                saveMessage(sessionId, MessageRole.AI, guard.reply());
                emitText(emitter, sessionId, guard.reply());
                // 跨过警告线时补一条处置提示：拦截话术说"请描述症状"，警告说"注意用语"，两件事都要说到
                emitNotice(emitter, sessionId, guard.notice());
                send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(sessionId, false));
                return;
            }
            log.info("入口校验：{}", guard.action() == SensitiveGuard.GuardResult.Action.WATCHED
                    ? "命中观察词「" + guard.word() + "」，放行并留痕" : "通过（无禁止词命中）");
            // 警告不阻断本轮：先提示，再照常走导诊（观察词本就要放行）
            emitNotice(emitter, sessionId, guard.notice());

            // ② 规则硬门槛：确定性无效输入 / 首条主诉过于笼统 → 模板追问，不调模型
            int askMaxRounds = sysConfigService.getInt(SysConfigService.KEY_ASK_MAX_ROUNDS, 3);
            int askRound = session.getAskRound() == null ? 0 : session.getAskRound();
            GateHit gateHit = ruleGate(session, content, askRound, askMaxRounds);
            if (gateHit != null) {
                // 连发无效输入时换第二档话术（否则患者看到同一句复读）——按"本会话是否已回过模板追问"切档
                String question = templateQuestionAsked(sessionId, sufficiencyRule.templateQuestion())
                        ? sufficiencyRule.templateQuestionRepeat()
                        : sufficiencyRule.templateQuestion();
                log.info("规则门槛：{}｜追问轮次 {}/{}，模板追问（未调模型/未检索，{}）",
                        gateHit.reason(), askRound, askMaxRounds,
                        gateHit.consumesRound() ? "计入追问轮次" : "**不计入**追问轮次");
                saveMessage(sessionId, MessageRole.QUESTION, question);
                if (gateHit.consumesRound()) {
                    ask(emitter, session, question);
                } else {
                    askWithoutBudget(emitter, session, question, askRound);
                }
                return;
            }

            // ③ 检索（查询改写 → 双路召回 → RRF → 精排）
            boolean forceConclusion = askRound >= askMaxRounds;
            // 健康档案由 chat 读档组装成两段文本再传进 rag（rag 不感知业务状态，不查档案）：
            //   profileText —— 进 prompt 的背景；profileQuery —— 进检索的召回串（非空才多开档案两路）。
            // 放在规则门槛之后：被门槛拦下的输入（含"首条主诉过于笼统"）根本不读档案，
            // 档案无从让它们跳过门槛直接出结论。为空即无档案，链路与今天一致。
            HealthProfileAssembler.Assembly profile = healthProfileService.assembleForChat(userId);
            String profileText = profile.text().isBlank() ? null : profile.text();
            String profileQuery = profile.query().isBlank() ? null : profile.query();
            log.info("进入检索：追问轮次 {}/{}｜强制结论={}｜候选科室 {} 个｜健康档案 {}（召回串 {}）",
                    askRound, askMaxRounds, forceConclusion, deptOptions().size(),
                    profileText == null ? "无" : profileText.length() + " 字",
                    profileQuery == null ? "无" : profileQuery.length() + " 字");
            RagRequest ragRequest = new RagRequest(content, loadHistory(sessionId), deptOptions(),
                    askRound, forceConclusion,
                    sysConfigService.getInt(SysConfigService.KEY_RETRIEVE_TOP_K, SysConfigService.DEFAULT_RETRIEVE_TOP_K),
                    sysConfigService.getInt(SysConfigService.KEY_RETRIEVE_TOP_N, SysConfigService.DEFAULT_RETRIEVE_TOP_N),
                    profileText, profileQuery);
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

            // ⑤ 解析与分流：按模型**显式声明**的 verdict 走三条路，见 RagAnswer.Verdict
            RagAnswer answer = answerParser.parse(rawOutput);
            if (answer.verdict() == RagAnswer.Verdict.RECOMMEND && !rawOutput.contains(AnswerParser.MARKER)) {
                // 模型漏输出结论分隔符但 JSON 齐全：JSON 已随 delta 流进气泡，这里只留痕，便于后续调 Prompt
                log.warn("模型未输出结论分隔符，按 JSON 兜底解析（结论 JSON 已随 delta 进入气泡）");
            }
            logAnswer(answer, forceConclusion);
            if (forceConclusion) {
                // 追问预算已用尽：链路 A 的承诺是"问满就问不出也要给结论"（提示词已用 {extra} 要求
                // verdict=RECOMMEND）。模型不配合时**不在这一轮降级**——照旧往下走结论路径，
                // 由 saveConclusion 回落检索片段所属科室兜底，否则这个会话会永远闭不了环
                // （ask_round 已到上限、又永远不出 result，患者挂不上号）
                if (answer.verdict() != RagAnswer.Verdict.RECOMMEND) {
                    log.warn("已达追问上限但模型未声明 RECOMMEND（verdict={}），按强制结论兜底下发", answer.verdict());
                }
            } else {
                if (answer.reply().isBlank() && answer.verdict() != RagAnswer.Verdict.RECOMMEND) {
                    // 非结论却没给任何文本：对话框里没东西可展示，只能当失败（追问文本为空、资料回答为空）
                    throw new BizException(ErrorCode.LLM_CALL_FAILED, "模型未返回可展示内容");
                }
                if (answer.verdict() == RagAnswer.Verdict.ASK) {
                    saveMessage(sessionId, MessageRole.QUESTION, answer.reply());
                    ask(emitter, session, answer.reply());
                    return;
                }
                if (answer.verdict() == RagAnswer.Verdict.INFO) {
                    answerInfo(session, answer.reply(), emitter);
                    return;
                }
                if (answer.verdict() == RagAnswer.Verdict.UNKNOWN) {
                    // 模型没按协议声明：当普通回答发出去，**不占追问轮次、不动会话状态**。
                    // 曾经这里等于"追问"——于是一次协议故障被伪装成正常追问，既没信号还白吃一次预算
                    log.warn("模型未声明 verdict，本轮按普通回答处理（不占追问轮次）：{}", abbreviate(answer.reply(), 120));
                    saveMessage(sessionId, MessageRole.AI, answer.reply());
                    send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(sessionId, false));
                    return;
                }
            }

            // ⑥ 结论：科室校验 + 导诊记录落库 + result/done
            GuideService.Conclusion conclusion = guideService.saveConclusion(session, answer, context,
                    rawOutput, profileText);
            saveMessage(sessionId, MessageRole.AI, answer.reply());
            send(emitter, SseEvents.RESULT, conclusion.payload());
            send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(sessionId, true));
        }
    }

    /**
     * 解析结果留痕：判定分支、模型自报置信度（含是否通过校验）、Top3、引用注号。
     * 判定按 verdict 三态打，UNKNOWN 单列——它就是「模型没按协议来」的告警面（可 grep `判定=未声明`）。
     */
    private void logAnswer(RagAnswer answer, boolean forceConclusion) {
        switch (answer.verdict()) {
            case ASK -> log.info("结论解析：判定=追问（信息不足{}）｜追问内容 {} 字",
                    forceConclusion ? "，但已到追问上限，将强制出低置信度结论" : "", answer.reply().length());
            case INFO -> log.info("结论解析：判定=资料回答｜回复 {} 字｜不占追问轮次", answer.reply().length());
            case UNKNOWN -> log.warn("结论解析：判定=未声明（模型未按协议给 verdict）｜回复 {} 字｜按普通回答处理",
                    answer.reply().length());
            case RECOMMEND -> {
                StringBuilder top3 = new StringBuilder();
                for (RagAnswer.DeptCandidate candidate : answer.top3()) {
                    top3.append(candidate.dept()).append('=').append(candidate.confidence()).append(' ');
                }
                log.info("结论解析：判定=出结论｜模型自报置信度={}（校验{}）｜Top3 [{}]｜引用注号 {}｜说明 {}",
                        answer.confidence(), answer.confidenceValid() ? "通过" : "未通过→置空走低置信度分流",
                        top3.toString().trim(), answer.cites(), abbreviate(answer.note(), 80));
            }
        }
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
     * 不计入追问预算的追问（规则门槛的**无效输入**专用）：只发事件、只落库，**不动 ask_round**。
     *
     * <p>存在的理由：让通用话术吃光预算，末轮就会触发 forceConclusion，而对"患者从未描述过症状"
     * 的会话强制出结论只能是编造。不计预算 ⇒ 预算永远推不动 ⇒ 永远走不到那一步。
     */
    private void askWithoutBudget(SseEmitter emitter, ChatSession session, String question, int askRound) {
        log.info("追问发出（不计预算）：轮次保持 {}｜内容：{}", askRound, abbreviate(question, 120));
        send(emitter, SseEvents.QUESTION, new SseEvents.QuestionEvent(session.getId(), question, askRound));
        send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(session.getId(), false));
    }

    /**
     * 资料回答分支（2026-09-30 加）：患者问的是知识库内容而不是描述症状，如实复述片段作答。
     *
     * <p>与追问的区别就三条，每一条都有理由：**不推进 ask_round**（它不需要患者补充信息，
     * 吃追问预算等于偷走分诊的追问机会）、**不改 has_result**（没推荐科室，会话照旧可续聊）、
     * **事件定性为 info 而不是 question**（前端据此换标签，患者不会把资料读成分诊结论）。
     */
    private void answerInfo(ChatSession session, String content, SseEmitter emitter) {
        String sessionId = session.getId();
        log.info("资料回答：{} 字｜追问轮次保持 {}/{}｜不产生结论", content.length(),
                session.getAskRound(), sysConfigService.getInt(SysConfigService.KEY_ASK_MAX_ROUNDS, 3));
        saveMessage(sessionId, MessageRole.INFO, content);
        send(emitter, SseEvents.INFO, new SseEvents.InfoEvent(sessionId, content));
        send(emitter, SseEvents.DONE, new SseEvents.DoneEvent(sessionId, false));
    }

    /**
     * 会话状态机（新主诉判定双信号）：请求带的会话可续聊（ongoing 且未出结论）则续聊，
     * 否则开新会话——判定是状态机字段，不做模型语义判断。
     */
    private SessionResolution resolveSession(String userId, String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            ChatSession existing = sessionMapper.selectById(sessionId);
            if (existing != null && userId.equals(existing.getUserId()) && existing.continuable()) {
                if (existing.getArchived() != null && existing.getArchived() == 1) {
                    // 患者又在归档会话里说话了：它立刻不该待在收纳区，自动取回主区。
                    // 只点开回放不会触发（那条路走 sessionDetail，不发消息），所以「看一眼」不会把归档打散
                    existing.setArchived(0);
                    sessionMapper.updateById(existing);
                    log.info("归档会话续聊，已自动取回主区：sessionId={}", existing.getId());
                }
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
     * 规则硬门槛判定：返回 null 表示放行，否则带上"是否消耗追问预算"。
     *
     * <p>① 确定性无效输入（语气词/寒暄/说不出/纯符号）——不区分轮次，且**不计追问预算**；
     * ② 首条主诉过于笼统（过短且未命中术语）——只对自由陈述生效，**计追问预算**。
     * 应答轮不叠加内容门槛，理由见 {@link SufficiencyRule} 类注释。
     *
     * <p><b>两类为什么会走到同一个出口、却必须在预算上分开</b>（2026-09-30 改）：
     * {@code ask_round} 的语义是"**针对患者这次回答**追问了几次"，而无效输入的模板追问是一句
     * 通用话术、不针对任何回答，扣预算属于错配。实测后果（用户报的）：连发 5 次「你好」把预算吃光 →
     * 让路护栏放行 → {@code forceConclusion} 要求模型"必须出结论" → 患者从未描述过症状，模型只能编：
     * 置信度 0.15、{@code cites} 为空、说明里自己写着"无依据的临时占位建议"，而系统照样给了推荐卡与
     * 挂号入口，还把这条假记录写进了盲区榜。<b>模板追问不吃预算，"强制出结论"的前提（患者确实描述过
     * 症状、只是信息不全）才成立。</b>
     *
     * <p><b>护栏</b>：追问预算（ask_max_rounds）用尽后规则一律让路。规则门槛位于
     * forceConclusion 判定之前，若继续拦，askRound 只涨而永远走不到检索分支，
     * forceConclusion 永不触发，会话会卡死在"回答 → 被问同一句 → 再回答"的循环里。
     * （现在只有 ② 会涨轮次，护栏就是为它留的。）
     */
    private GateHit ruleGate(ChatSession session, String content, int askRound, int askMaxRounds) {
        if (askRound >= askMaxRounds) {
            return null;
        }
        if (sufficiencyRule.noSignal(content)) {
            return new GateHit("确定性无效输入", false);
        }
        if (isFirstTurn(session) && sufficiencyRule.tooVague(content)) {
            return new GateHit("首条主诉过于笼统（未命中术语且过短）", true);
        }
        return null;
    }

    /** 规则门槛命中结果：原因 + 是否消耗追问预算 */
    private record GateHit(String reason, boolean consumesRound) {
    }

    /**
     * 本会话是否已经回过一次模板追问——决定用第一档还是第二档话术。
     *
     * <p>用**消息本身**判断而不是加计数器：计数器要么给 chat_session 加字段（要 ALTER），
     * 要么存内存（多实例与重启即失真）。这条路径不调模型、不检索，一次 count 可以接受。
     */
    private boolean templateQuestionAsked(String sessionId, String template) {
        return messageMapper.selectCount(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, MessageRole.QUESTION)
                .eq(ChatMessage::getContent, template)) > 0;
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
     * 处置提示（警告）单独一条气泡：与答案分开，患者不会把它读成诊断结论的一部分。
     * 也落库，好让后续轮次的历史里带着这次提醒（模型不必再重复处理这个问题）。
     *
     * <p>用独立的 notice 事件而不是塞进 delta：delta 是「同一个气泡的增量」，
     * 提示混进去会和紧接着的答案粘成一段话（前端无法再拆分）。
     */
    private void emitNotice(SseEmitter emitter, String sessionId, String notice) {
        if (notice == null || notice.isBlank()) {
            return;
        }
        saveMessage(sessionId, MessageRole.AI, notice);
        send(emitter, SseEvents.NOTICE, new SseEvents.NoticeEvent(sessionId, notice));
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
