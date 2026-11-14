package com.guide.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.HealthProfileAssembler;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.chat.dto.ChatDTO;
import com.guide.chat.entity.ChatSession;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.SessionStatus;
import com.guide.chat.mapper.ChatSessionMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.chat.support.ProfileAlertDetector;
import com.guide.chat.support.RecommendableDepts;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.util.TextUtil;
import com.guide.common.model.ChunkHit;
import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import com.guide.rag.dto.RagAnswer;
import com.guide.rag.dto.RagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * 导诊记录服务（链路 A 第 ⑥⑦ 步 + 链路 C 前半的挂号确认）。
 * 职责：科室校验（只接受可推荐科室：启用且有切片）→ 导诊记录落库（rec_top3 / evidence 只写快照）→
 * 挂号确认时同事务写入 actual_dept 与 top1_hit / top3_hit 并置会话 closed。
 * 历史不回改：结论一旦落库即为事实，回流只前向修正知识库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuideService {

    /** 证据快照中 prompt 摘要的最大长度 */
    private static final int PROMPT_SNIPPET_MAX = 2000;

    /** 判断依据里每条证据原文的截断长度：够自证，又不至于把卡片撑成一篇文章 */
    private static final int CITE_CONTENT_MAX = 400;

    /** 正文里的注号引用：「（注3、注5）」「注1」都认；「关注」这类词后面不是数字，不会被误抓 */
    private static final Pattern PROSE_CITE = Pattern.compile("注\\s*(\\d+)");

    private final GuideRecordMapper guideRecordMapper;
    private final ChatSessionMapper sessionMapper;
    private final DeptService deptService;
    private final RecommendableDepts recommendableDepts;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 结论落库 + 组装 result 事件载荷。
     *
     * @param rawOutput 模型原始输出（证据快照用，审核回放）
     * @param profile   本轮健康档案快照输入（待注入文本 + 结构化档案，可为空）；
     *                  其中待注入文本随 result 事件单独下发，供推荐卡「已参考您的健康档案」行——
     *                  它来自后端读档，**不是模型输出**；两者一并进证据快照 profile 节点
     */
    @Transactional(rollbackFor = Exception.class)
    public Conclusion saveConclusion(ChatSession session, RagAnswer answer, RagContext context,
                                     String rawOutput, ProfileSnapshot profile) {
        // 校验白名单 = 可推荐科室（启用且有切片），与 ChatService 的候选清单同源：
        // 模型能推荐的 ⇔ 能通过校验的。零切片科室即便被模型写出也会在此被过滤。
        Map<String, Dept> byName = new LinkedHashMap<>();
        for (Dept dept : recommendableDepts.list()) {
            byName.put(dept.getName(), dept);
        }

        // 科室校验：Top3 混入不可推荐（停用/不存在/无切片）科室则过滤，后续候选顶上
        List<Candidate> kept = new ArrayList<>();
        for (RagAnswer.DeptCandidate candidate : answer.top3()) {
            Dept dept = byName.get(candidate.dept());
            if (dept != null) {
                kept.add(new Candidate(dept, candidate.confidence()));
            } else {
                log.info("推荐校验：科室「{}」不可推荐已过滤（停用/不存在/知识库无切片）", candidate.dept());
            }
        }
        if (kept.size() < answer.top3().size()) {
            log.info("推荐校验：模型 Top3 {} 条 → 校验后保留 {} 条（其余为不可推荐科室：停用/不存在/无切片）",
                    answer.top3().size(), kept.size());
        }
        boolean fallback = false;
        if (kept.isEmpty()) {
            // 全被拦：取检索片段的所属科室兜底，走低置信度分流（进盲区榜）
            fallback = true;
            Dept dept = fallbackDept(context, deptService.listEnabled());
            if (dept == null) {
                throw new BizException(ErrorCode.RAG_EMPTY, "知识库暂无可用科室内容");
            }
            kept.add(new Candidate(dept, null));
            log.warn("推荐科室全被拦截，回落检索片段所属科室：{}", dept.getName());
        }

        Double confidence = answer.confidenceValid() && !fallback ? kept.get(0).confidence : null;
        double threshold = sysConfigService.getDouble(SysConfigService.KEY_LOW_CONFIDENCE, 0.5);
        boolean lowConfidence = confidence == null || confidence < threshold;

        // 顶部警报：规则（档案词面命中召回正文）优先，模型补充在后，合并去重、总量受上限约束。
        // 只算一次，下发与证据快照共用同一份——两处不一致等于"患者看到的"和"事后回放的"不是一回事。
        List<ChatDTO.AlertVO> alerts = mergeAlerts(answer, context, profile);

        GuideRecord record = new GuideRecord();
        record.setSessionId(session.getId());
        record.setRecDeptId(kept.get(0).dept().getId());
        record.setConfidence(confidence);
        record.setRecTop3(top3Json(kept));
        record.setLowConfidence(lowConfidence ? 1 : 0);
        record.setAggregated(0);
        record.setEvidence(evidenceJson(context, answer, rawOutput, profile, alerts));
        guideRecordMapper.insert(record);

        // 出卡即归零追问预算，并标记"这条会话已经出过结论"（同一次 updateById，两个事实一起落）。
        //
        // ask_round 的语义由此重定义为「**距最近一张结论卡，已追问几轮**」：
        // 出卡归零之后，结论后继续追问时日志里的"追问轮次 1/N"读起来就是"这张卡之后问了几轮"，
        // 而不是冻结在出卡时的 N/N；ruleGate 的"预算用尽则规则让路"也随之恢复可用（不再永久生效）。
        //
        // ⚠️ 不许因为"askRound 归零"就把 ChatService.isFirstTurn 里的 `has_result != 1` 去掉——
        // 归零之后 askRound==0，那一半正是唯一能阻止"出卡后的下一轮被误判成首轮"的判据
        // （误判会让声明被重新采纳、模板追问回来）。两处是咬合的。
        session.setHasResult(1);
        session.setAskRound(0);
        sessionMapper.updateById(session);

        log.info("结论落库：recordId={} 科室={} 置信度={} 低置信度={}（阈值 {}）Top3={} 条 证据片段={} 条",
                record.getId(), kept.get(0).dept().getName(), confidence, lowConfidence, threshold,
                kept.size(), context.chunks().size());
        if (lowConfidence) {
            log.info("低置信度分流：该记录进盲区榜，不参与准确率统计");
        }
        return new Conclusion(record, resultPayload(session.getId(), record, kept, answer, context,
                lowConfidence, profile, alerts));
    }

    /** 模型警报允许锚定的档案字段（与 {@code AnswerParser} 的白名单同源；此处再兜一层防御） */
    private static final Set<String> MODEL_ALERT_FIELDS = Set.of("allergy", "medication", "surgery");

    /**
     * 合并两类警报（工单 10）。
     *
     * <p><b>规则优先且不可被模型否定</b>：规则警报先全部入列，模型输出只能在其后补充，
     * 既不能挤掉、也不能覆盖它们。合并顺序固定为「规则 → 模型」，总量上限
     * {@link ProfileAlertDetector#MAX_ALERTS}——规则占满名额时模型警报整体丢弃。
     *
     * <p><b>去重口径</b>：同一文案（trim 后逐字相同）只留一条。规则侧已按「档案值」去重，
     * 这里再按文案去一次，是为了防止模型把规则那条换个来源重写一遍。
     *
     * <p><b>锚点校验</b>：模型警报的注号必须落在本轮实际召回条数内；越界且没有档案字段锚点的，
     * 丢弃并记 WARN——患者不能收到一条点不到依据的风险提示。
     */
    private List<ChatDTO.AlertVO> mergeAlerts(RagAnswer answer, RagContext context,
                                              ProfileSnapshot profile) {
        List<ChatDTO.AlertVO> merged = new ArrayList<>();
        Set<String> texts = new HashSet<>();

        List<ProfileAlertDetector.Alert> ruleAlerts = ProfileAlertDetector.detect(
                profile == null ? null : profile.structure(), context.chunks());
        for (ProfileAlertDetector.Alert alert : ruleAlerts) {
            if (merged.size() >= ProfileAlertDetector.MAX_ALERTS) {
                break;
            }
            if (texts.add(alert.text().trim())) {
                merged.add(new ChatDTO.AlertVO(alert.level(), alert.text(), alert.anchor(), alert.source()));
            }
        }

        int chunkCount = context.chunks() == null ? 0 : context.chunks().size();
        List<RagAnswer.Alert> modelAlerts = answer.alerts() == null ? List.of() : answer.alerts();
        for (RagAnswer.Alert alert : modelAlerts) {
            if (merged.size() >= ProfileAlertDetector.MAX_ALERTS) {
                break;
            }
            String anchor = modelAlertAnchor(alert, chunkCount);
            if (anchor == null) {
                log.warn("模型警报锚点不可用已丢弃（注号越界且无档案字段）：{}", TextUtil.abbreviate(alert.text(), 80));
                continue;
            }
            if (texts.add(alert.text().trim())) {
                merged.add(new ChatDTO.AlertVO("hard", alert.text(), anchor, "model"));
            }
        }

        if (!merged.isEmpty()) {
            log.info("结论警报：规则 {} 条、模型补充 {} 条，合并下发 {} 条（上限 {}）｜锚点 {}",
                    ruleAlerts.size(), modelAlerts.size(), merged.size(),
                    ProfileAlertDetector.MAX_ALERTS,
                    merged.stream().map(ChatDTO.AlertVO::anchor).toList());
        }
        return merged;
    }

    /**
     * 模型警报的锚点：注号在本轮召回范围内优先用 {@code cite:N}，否则回落合法档案字段
     * {@code field:X}；两者都没有返回 null（丢弃）。
     */
    private String modelAlertAnchor(RagAnswer.Alert alert, int chunkCount) {
        if (alert.cite() != null && alert.cite() >= 1 && alert.cite() <= chunkCount) {
            return "cite:" + alert.cite();
        }
        if (alert.field() != null && MODEL_ALERT_FIELDS.contains(alert.field())) {
            return "field:" + alert.field();
        }
        return null;
    }

    /**
     * 挂号确认（链路 C 触点 register_success 的事实来源）：
     * actual_dept 与 top1_hit / top3_hit 同事务写入；会话置 closed（新主诉判定双信号之一）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatDTO.RegisterVO confirmRegister(String userId, ChatDTO.RegisterReq request) {
        GuideRecord record = guideRecordMapper.selectById(request.getRecordId());
        if (record == null) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }
        ChatSession session = sessionMapper.selectById(record.getSessionId());
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }
        if (record.getActualDeptId() != null) {
            throw new BizException(ErrorCode.RECORD_ALREADY_REGISTERED);
        }
        Dept dept = deptService.getById(request.getDeptId());
        if (dept == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        if (dept.getEnabled() == null || dept.getEnabled() != 1) {
            throw new BizException(ErrorCode.DEPT_DISABLED);
        }

        record.setActualDeptId(dept.getId());
        record.setTop1Hit(dept.getId().equals(record.getRecDeptId()) ? 1 : 0);
        record.setTop3Hit(top3Contains(record.getRecTop3(), dept.getId()) ? 1 : 0);
        guideRecordMapper.updateById(record);
        log.info("挂号确认：recordId={} 实际科室={}｜命中 top1={} top3={}（top1 未中 top3 中=排序精度问题，不进回流）",
                record.getId(), dept.getName(), record.getTop1Hit(), record.getTop3Hit());

        session.setStatus(SessionStatus.CLOSED);
        sessionMapper.updateById(session);

        return new ChatDTO.RegisterVO(session.getId(), dept.getId(), dept.getName(), dept.getLocation());
    }

    /** 检索片段所属科室兜底（取第一个启用科室；片段科室可能已停用） */
    private Dept fallbackDept(RagContext context, List<Dept> enabledDepts) {
        for (ChunkHit chunk : context.chunks()) {
            Dept dept = deptService.getById(chunk.deptId());
            if (dept != null && dept.getEnabled() != null && dept.getEnabled() == 1) {
                return dept;
            }
        }
        return enabledDepts.isEmpty() ? null : enabledDepts.get(0);
    }

    /** Top3 快照（含 deptId：看板排序精度聚合与 top3_hit 比对都依赖它） */
    private String top3Json(List<Candidate> kept) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Candidate candidate : kept) {
            ObjectNode node = array.addObject();
            node.put("deptId", candidate.dept().getId());
            node.put("dept", candidate.dept().getName());
            if (candidate.confidence() == null) {
                node.putNull("confidence");
            } else {
                node.put("confidence", candidate.confidence());
            }
        }
        return array.toString();
    }

    /** 证据快照（只写）：系统当时看到了什么、怎么答的，供审核回放与根因归因 */
    private String evidenceJson(RagContext context, RagAnswer answer, String rawOutput,
                                ProfileSnapshot profile, List<ChatDTO.AlertVO> alerts) {
        ObjectNode evidence = objectMapper.createObjectNode();
        ArrayNode retrieved = evidence.putArray("retrieved");
        int rank = 1;
        StringBuilder snippet = new StringBuilder();
        for (ChunkHit chunk : context.chunks()) {
            ObjectNode node = retrieved.addObject();
            node.put("chunk_id", chunk.chunkId());
            node.put("title", chunk.title());
            node.put("score", chunk.score());
            node.put("rank", rank++);
            // 原文进快照：回放（就诊记录 / 审核页）要显示「判断依据」的原文，
            // 而快照的语义就是"当时的原样"——切片日后被删被重建，回放仍站得住
            node.put("content", TextUtil.abbreviate(chunk.content(), CITE_CONTENT_MAX));
            snippet.append('注').append(rank - 1).append('《').append(chunk.title()).append("》：")
                    .append(TextUtil.abbreviate(chunk.content(), 200)).append('\n');
        }
        evidence.put("retrieved_query", context.rewrittenQuery());
        ArrayNode cited = evidence.putArray("model_cited");
        answer.cites().forEach(cited::add);
        if (profile == null || isBlank(profile.profileText())) {
            // 无档案：与今天除多一个空节点外无差异（回放解析用 path() 取值，null 节点不炸）
            evidence.putNull("profile");
        } else {
            evidence.set("profile", profileNode(profile));
        }
        // 警报节点与下发内容逐条一致（含锚点与来源）：事后回放要能还原患者当时看到了什么。
        // 无警报也写空数组——「空」和「没有这个节点」在回放侧是两件事。
        ArrayNode alertArray = evidence.putArray("alerts");
        for (ChatDTO.AlertVO alert : alerts) {
            ObjectNode node = alertArray.addObject();
            node.put("level", alert.level());
            node.put("text", alert.text());
            node.put("anchor", alert.anchor());
            node.put("source", alert.source());
        }
        evidence.put("prompt_snippet", TextUtil.abbreviate(snippet.toString(), PROMPT_SNIPPET_MAX));
        evidence.put("model_output_raw", TextUtil.abbreviate(rawOutput, PROMPT_SNIPPET_MAX));
        return evidence.toString();
    }

    /**
     * 档案快照节点（单据 04）：还原"**系统当时看到的档案**"，与 {@code retrieved} /
     * {@code retrieved_query} / {@code prompt_snippet} 同构，落在同一份证据快照 JSON 里，
     * **不新增存储、不新增表、不新增列**。
     *
     * <p>两样都要，缺一不可：
     * <ul>
     *   <li>{@code text} —— 档案提示文本（模型实际看到的背景）；</li>
     *   <li>{@code content} —— 患者当时填的**结构化档案**（编码 + 标签 + 自由文本）：
     *       只存加工后的文本，审核时就看不出患者当初勾了什么、写了什么。</li>
     * </ul>
     *
     * <p><b>不再写 {@code query}</b>：档案已退出检索层，新快照里没有检索用串可记。
     * 老快照的该键仍原样留在库里、读取侧只取 {@code text}，因此安全可读、不需迁移。
     *
     * <p><b>只写不读</b>：回放与审核读的就是这一份，**绝不**在读取时回查当前档案补全——
     * 档案可变而导诊记录不可改，实时回读等于把历史改掉（患者今天删掉"糖尿病"，
     * 旧记录的推荐理由就当场失真）。患者改档案，旧记录的档案快照因此纹丝不动。
     */
    private ObjectNode profileNode(ProfileSnapshot profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("text", profile.profileText());
        HealthProfileAssembler.Profile structure = profile.structure();
        if (structure == null) {
            node.putNull("content");
            return node;
        }
        ObjectNode content = node.putObject("content");
        content.put("gender", structure.genderCode());
        content.put("birthYear", structure.birthYear());
        // 旧字段保留：既让老快照的读取口径一致，也留下迁移前的审计依据（不再进提示词）
        content.put("ageRange", structure.ageRangeCode());
        putTags(content, "historyTags", structure.historyTags());
        content.put("historyOther", structure.historyOther());
        putMedicationEntries(content, "medicationTags", structure.medicationEntries());
        content.put("medicationOther", structure.medicationOther());
        putTags(content, "allergyTags", structure.allergyTags());
        content.put("allergyOther", structure.allergyOther());
        putSurgeryEntries(content, "surgeryTags", structure.surgeryEntries());
        content.put("surgeryOther", structure.surgeryOther());
        return node;
    }

    /** 标签数组一律写数组（空则空数组）：回放侧不必区分"没这一项"与"这一项为空" */
    private void putTags(ObjectNode parent, String field, List<String> tags) {
        ArrayNode array = parent.putArray(field);
        if (tags != null) {
            tags.forEach(array::add);
        }
    }

    /**
     * 用药条目写对象数组（工单 03）：{@code value + startedOn + endedOn}，空时间写 null。
     * 键名沿用列名 {@code medicationTags}——老快照里是字符串数组，读取侧必须两种都认，
     * 所以键名不能跟着 DTO 一起改（改了就分不清"这列是什么"）。
     */
    private void putMedicationEntries(ObjectNode parent, String field, List<MedicationEntry> entries) {
        ArrayNode array = parent.putArray(field);
        if (entries == null) {
            return;
        }
        for (MedicationEntry entry : entries) {
            ObjectNode node = array.addObject();
            node.put("value", entry.value());
            putNullable(node, "startedOn", entry.startedOn());
            putNullable(node, "endedOn", entry.endedOn());
        }
    }

    /** 手术条目写对象数组：{@code value + occurredOn}，空时间写 null。 */
    private void putSurgeryEntries(ObjectNode parent, String field, List<SurgeryEntry> entries) {
        ArrayNode array = parent.putArray(field);
        if (entries == null) {
            return;
        }
        for (SurgeryEntry entry : entries) {
            ObjectNode node = array.addObject();
            node.put("value", entry.value());
            putNullable(node, "occurredOn", entry.occurredOn());
        }
    }

    /** 时间字段空即 null：证据快照要能区分"没填时间"与"填了个空串" */
    private void putNullable(ObjectNode parent, String field, String value) {
        if (value == null) {
            parent.putNull(field);
        } else {
            parent.put(field, value);
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private ChatDTO.ResultVO resultPayload(String sessionId, GuideRecord record, List<Candidate> kept,
                                           RagAnswer answer, RagContext context, boolean lowConfidence,
                                           ProfileSnapshot profile, List<ChatDTO.AlertVO> alerts) {
        List<ChatDTO.Top3Item> top3 = kept.stream()
                .map(candidate -> new ChatDTO.Top3Item(candidate.dept().getId(),
                        candidate.dept().getName(), percent(candidate.confidence())))
                .toList();
        // 判断依据脚注：注号 = 证据快照 retrieved 顺序（与 Prompt 中的「注N」一致），
        // **只列模型真正引用的那些**，并带上原文——依据要能自证，光有标题不足以
        List<ChatDTO.Cite> cites = new ArrayList<>();
        List<ChunkHit> chunks = context.chunks();
        for (int no : citedNos(withProseCites(answer), chunks.size())) {
            ChunkHit hit = chunks.get(no - 1);
            cites.add(new ChatDTO.Cite(no, hit.title(), TextUtil.abbreviate(hit.content(), CITE_CONTENT_MAX)));
        }
        return new ChatDTO.ResultVO(sessionId, record.getId(), record.getRecDeptId(),
                kept.get(0).dept().getName(), record.getConfidence(), top3, answer.note(),
                cites, lowConfidence, blankToNull(profile == null ? null : profile.profileText()),
                alerts == null ? List.of() : alerts);
    }

    /** 空白档案文本归一为 null：前端据此判断"无档案行"，不渲染一个空标签 */
    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** 置信度 → 百分比；null（模型未给合法值/兜底科室）保持 null，前端显示「—」而不是 0% */
    private Integer percent(Double confidence) {
        return confidence == null ? null : (int) Math.round(confidence * 100);
    }

    private boolean top3Contains(String recTop3, String deptId) {
        if (recTop3 == null || recTop3.isBlank()) {
            return false;
        }
        try {
            JsonNode array = objectMapper.readTree(recTop3);
            for (JsonNode node : array) {
                if (deptId.equals(node.path("deptId").asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Top3 快照解析失败：{}", e.getMessage());
        }
        return false;
    }

    /**
     * 模型引用到的注号：**去重、排序、丢掉越界的**。
     *
     * <p>越界不是假想：模型偶尔会给出段落里根本不存在的注号（比如只召回了 5 条却写「注7」），
     * 而引用解析是宽松的（`AnswerParser.parseCites` 只保证能解析成整数，不保证在范围内）。
     * 直接拿它去取 chunks 会 IndexOutOfBounds——那是**模型输出导致服务崩溃**，必须在这里拦掉。
     *
     * <p>**一条都没引用时退回全部召回**：模型没标「（注N）」不代表没有依据，
     * 而"判断依据"整块空着比多列几条更糟——这一块是推荐卡的可信度来源。
     */
    /**
     * 模型引用的注号 = **它结构化输出里的 cites ∪ 正文里写到的「注N」**。
     *
     * <p>为什么要取并集：模型这两处会不一致——实测过它正文写「（注3、注5）」而 cites 数组只有 [1,3]。
     * 只按数组取，患者就会看到一个**正文引用了、判断依据里却不存在**的注号（改"只列引用的"之前
     * 因为全列所以看不出来）。判断依据的最低保证是：**正文里出现的每个注号都能在这里查到**。
     */
    List<Integer> withProseCites(RagAnswer answer) {
        List<Integer> all = new ArrayList<>(answer.cites() == null ? List.of() : answer.cites());
        Matcher matcher = PROSE_CITE.matcher(answer.reply() == null ? "" : answer.reply());
        while (matcher.find()) {
            all.add(Integer.parseInt(matcher.group(1)));
        }
        return all;
    }

    List<Integer> citedNos(List<Integer> cites, int chunkCount) {
        // null 也当"没引用"处理：这条路径的尽头是 SSE 响应，任何一处 NPE 都会让患者端
        // 收到一个断掉的流（而且只在模型输出异常时才触发，最难复现的那类）
        List<Integer> valid = (cites == null ? List.<Integer>of() : cites).stream()
                .filter(no -> no != null && no >= 1 && no <= chunkCount)
                .distinct()
                .sorted()
                .toList();
        if (!valid.isEmpty() || chunkCount == 0) {
            return valid;
        }
        return IntStream.rangeClosed(1, chunkCount).boxed().toList();
    }

    /** 结论产出：落库记录 + SSE result 载荷 */
    public record Conclusion(GuideRecord record, ChatDTO.ResultVO payload) {
    }

    /**
     * 本轮档案快照输入（证据快照 profile 节点用）。
     *
     * @param profileText 档案提示文本（= 推荐卡「已参考健康档案」行）；空 = 本轮无档案
     * @param structure   患者当时填的结构化档案（编码 + 标签 + 自由文本）；未建档为 null
     */
    public record ProfileSnapshot(String profileText,
                                  HealthProfileAssembler.Profile structure) {
    }

    /** 通过校验的候选科室 */
    private record Candidate(Dept dept, Double confidence) {
    }
}
