package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.event.TurnCompletedEvent;
import com.guide.common.port.SessionActivityPort;
import com.guide.common.port.SessionActivityPort.DialogueTurn;
import com.guide.common.port.SessionActivityPort.IdleSession;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryExtractor.ExistingMemory;
import com.guide.memory.service.MemoryExtractor.MergedPreference;
import com.guide.memory.service.MemoryExtractor.PreferenceItem;
import com.guide.memory.support.MemoryNegation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 记忆抽取编排：开关 → 取最近对话 → 规则预筛 → 申领配额 → 调模型 → 校验 → 落库 + 审计。
 *
 * <p><b>全链路旁路</b>：任何一步失败都只记 WARN，不重试、不上抛——抽取慢或挂掉不能影响患者当前这轮回答。
 * 唯一的「副作用」是配额可能已被消费（配额在调模型前申领，这是有意的：失败也计入次数，避免通过抖动白嫖）。
 *
 * <p><b>两条入口共用同一落库路径</b>：
 * <ul>
 *   <li>轮次完成（{@link #onTurnCompleted}）：先做规则预筛，没命中连模型都不调；</li>
 *   <li>会话空闲 30 分钟（{@link #extractIdleSession}）：真正的兜底——预筛可能漏掉的长对话，
 *       或有价值信息出现在最后一句之外的会话。配额仍是同一个计数，最多 3 次。</li>
 * </ul>
 *
 * <p><b>满额口径</b>：偏好满配置上限（memory.preference.max，默认 5）调一次合并去重（合并失败丢弃本批新偏好、保留旧偏好）；
 * 背景满 10 条保留旧背景、丢弃新背景并把 needsCleanup 留给人手整理（页面已有提示）。
 *
 * <p><b>判重前置（工单 02）</b>：偏好落库前按模型给的 {@code action} 分派——{@code new} 插入、
 * {@code update} 原地改旧行、{@code skip} 零写入；分派由纯函数 {@link MemoryDedupPlanner} 完成，
 * 它以**送进提示词的那份清单**为准解析 {@code target} 序号。满额合并降为兜底。
 * 背景的分派（含 {@code retrieval_text} 与向量同步）在工单 03，本单仍全插。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    /** 送入模型的最近对话条数（8 条 ≈ 4 轮问答，够覆盖当次就诊的关键陈述） */
    static final int DIALOGUE_LIMIT = 8;

    /**
     * 规则预筛：最后一条患者消息命中这些词才值得花一次模型调用。
     * 命中「我」几乎覆盖了自我陈述；「喜欢 / 希望 / 记得 / 讲 / 说」偏好；
     * 「有 / 做过 / 吃 / 过敏 / 不能」健康事实。
     */
    private static final Pattern PREFILTER = Pattern.compile("我|喜欢|希望|有|做过|吃|过敏|不能|记得|讲|说");

    private final MemorySettingService memorySettingService;
    private final SessionActivityPort sessionActivityPort;
    private final MemoryExtractor memoryExtractor;
    private final MemoryMapper memoryMapper;
    private final MemoryLogService memoryLogService;
    private final SysConfigService sysConfigService;
    private final MemoryVectorIndexer vectorIndexer;
    private final ProfileCandidateService profileCandidateService;

    /** 轮次完成入口（由 {@code MemoryExtractionListener} 在线程池里调用） */
    public void onTurnCompleted(TurnCompletedEvent event) {
        if (event == null) {
            return;
        }
        try {
            extract(event.userId(), event.sessionId(), true);
        } catch (RuntimeException e) {
            log.warn("记忆抽取失败（不影响对话）：sessionId={}｜原因={}", event.sessionId(), e.getMessage());
        }
    }

    /** 空闲会话兜底入口（由 {@code MemoryIdleExtractionJob} 调用；跳过规则预筛） */
    public void extractIdleSession(IdleSession session) {
        if (session == null) {
            return;
        }
        try {
            extract(session.userId(), session.sessionId(), false);
        } catch (RuntimeException e) {
            log.warn("记忆空闲兜底抽取失败：sessionId={}｜原因={}", session.sessionId(), e.getMessage());
        }
    }

    private void extract(String userId, String sessionId, boolean applyPrefilter) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<DialogueTurn> turns = sessionActivityPort.recentDialogue(sessionId, DIALOGUE_LIMIT);
        if (turns == null || turns.isEmpty()) {
            return;
        }
        // 患者更正先处理：这是收回既有记忆，不是"自动记忆"的产出，不调模型、不占抽取配额；
        // 开关关闭时也要执行——否则患者说了"我没有糖尿病"，重新开启记忆后旧结论又活了。
        retractCorrectedBackgrounds(userId, turns);
        // 开关关闭：不调模型（关的是自动记忆，档案与旧记忆都不受影响）
        if (!memorySettingService.isEnabled(userId)) {
            log.debug("自动记忆已关闭，跳过抽取：userId={} sessionId={}", userId, sessionId);
            return;
        }
        if (applyPrefilter && !shouldExtract(turns)) {
            log.debug("规则预筛未命中，跳过抽取：sessionId={}", sessionId);
            return;
        }
        int sessionMax = sysConfigService.getInt(SysConfigService.KEY_MEMORY_EXTRACT_SESSION_MAX,
                SysConfigService.DEFAULT_MEMORY_EXTRACT_SESSION_MAX);
        // 只在真要调模型时计数；原子条件更新，并发下不会超出上限
        if (!sessionActivityPort.tryIncrementExtractCount(sessionId, sessionMax)) {
            log.info("会话抽取次数已用尽（上限 {} 次），跳过：sessionId={}", sessionMax, sessionId);
            return;
        }

        List<Memory> remembered = rememberedRows(userId);
        MemoryExtractionResult result = memoryExtractor.extract(dialogueText(turns), existingMemories(remembered));
        if (result == null || result.isEmpty()) {
            log.info("本轮未抽到可落库的记忆：sessionId={}", sessionId);
            return;
        }
        // 判重候选就是**送进提示词的那一份清单**：模型回的 target 是它的序号，
        // 另查一次等于换了一套编号，会把患者的一条偏好改成另一条。
        savePreferences(userId, remembered, result.preferences());
        saveBackgrounds(userId, result.backgrounds());
        // 档案事实（过敏/用药/手术）只进候选表：未确认前不注入、不预警，患者接受后才写档案
        profileCandidateService.recordCandidates(userId, result.candidates());
    }

    /** 预筛只看最后一条患者消息（助手消息、追问不算患者的自我陈述） */
    private boolean shouldExtract(List<DialogueTurn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            DialogueTurn turn = turns.get(i);
            if ("user".equals(turn.role()) && turn.content() != null) {
                return PREFILTER.matcher(turn.content()).find();
            }
        }
        return false;
    }

    /**
     * 患者明确更正/否认一条已有背景时，把那条标记失效。
     *
     * <p>为什么必须做：抽取侧的否定句过滤只会"不记这句"，**不会收回之前记对的那条**——
     * 患者说「说错了我没有2型糖尿病」之后，旧记忆仍然 active，换个会话继续拿它当背景。
     *
     * <p>落点是 **expired + validTo=今天**，不物理删除：控制面不再显示、召回不再注入，
     * 但审计行还在（`user_memory_log` 记一笔 delete），出了问题能查"当初从哪句记的"。
     */
    private void retractCorrectedBackgrounds(String userId, List<DialogueTurn> turns) {
        String message = lastUserMessage(turns);
        if (message == null) {
            return;
        }
        try {
            for (Memory row : activeMemories(userId,
                    MemoryType.BACKGROUND_HEALTH, MemoryType.BACKGROUND_OTHER)) {
                if (row.getMvalue() == null || !MemoryNegation.retracted(message, row.getMvalue())) {
                    continue;
                }
                row.setStatus(MemoryStatus.EXPIRED);
                row.setValidTo(LocalDate.now());
                memoryMapper.updateById(row);
                vectorIndexer.delete(row.getId());
                memoryLogService.append(row.getId(), userId, MemoryOp.DELETE,
                        row.getMvalue(), null, MemoryActor.EXTRACT);
                log.info("患者更正了长期背景，已标记失效：userId={} memoryId={} value={}",
                        userId, row.getId(), row.getMvalue());
            }
        } catch (RuntimeException e) {
            log.warn("背景更正处理失败（不影响对话）：userId={}｜原因={}", userId, e.getMessage());
        }
    }

    private String lastUserMessage(List<DialogueTurn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            DialogueTurn turn = turns.get(i);
            if ("user".equals(turn.role()) && turn.content() != null && !turn.content().isBlank()) {
                return turn.content();
            }
        }
        return null;
    }

    /**
     * 取给模型判重用的旧记忆清单：**只取生效中的**，在「患者更正收回」之后取。
     *
     * <p>顺序很重要：更正那一步会把被否定的背景置 expired，先取旧清单就等于把患者刚放弃的说法
     * 又摆回模型面前。偏好与背景一起编号（模型不认 id，只认序号）。
     *
     * <p>取不到（DB 抖动）按空清单处理：判重退化成「全部 new」，仍是可用的降级——
     * 不能因为拿不到旧记忆就连抽取都不做。
     */
    private List<Memory> rememberedRows(String userId) {
        try {
            return activeMemories(userId, MemoryType.PREFERENCE,
                    MemoryType.BACKGROUND_HEALTH, MemoryType.BACKGROUND_OTHER);
        } catch (RuntimeException e) {
            log.warn("读取已记住的记忆失败，本轮判重按「全部新增」处理：userId={}｜原因={}",
                    userId, e.getMessage());
            return List.of();
        }
    }

    /** 送进提示词的形态（模型只认序号 + 键 + 值，不认 id） */
    private List<ExistingMemory> existingMemories(List<Memory> rows) {
        List<ExistingMemory> items = new ArrayList<>();
        for (Memory row : rows) {
            items.add(new ExistingMemory(row.getType(), row.getMkey(), row.getMvalue()));
        }
        return items;
    }

    // ---------------- 偏好 ----------------

    /**
     * 偏好落库：**先判重、再决定插还是改**（工单 02）。
     *
     * <p>三步：
     * <ol>
     *   <li>{@code (key, value)} 全等去重——挡的是模型把同一条说两遍（与旧行全等、与本批重复）；</li>
     *   <li>按 {@code action} 分派：{@code update} 原地改旧行（id 不变），{@code skip} 零写入，
     *       其余插入；</li>
     *   <li>满额仍保留合并兜底（历史口径，主路径已改为判重）。</li>
     * </ol>
     *
     * <p><b>判重出任何异常都降级为「全部新增」</b>：多一条患者看得见、能自己删；
     * 整批丢掉他永远不知道自己被漏记了。
     *
     * @param remembered **送进提示词的那份清单**（同一个顺序）——target 的序号就是它的下标
     */
    private void savePreferences(String userId, List<Memory> remembered, List<PreferenceDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        try {
            List<PreferenceDraft> fresh = dedupePreferences(remembered, drafts);
            if (fresh.isEmpty()) {
                return;
            }
            List<MemoryDedupPlanner.Update<PreferenceDraft>> updates;
            List<PreferenceDraft> toInsert;
            try {
                MemoryDedupPlanner.Plan<PreferenceDraft> plan = MemoryDedupPlanner.plan(fresh, remembered);
                updates = plan.updates();
                toInsert = new ArrayList<>(plan.news());
            } catch (RuntimeException e) {
                log.warn("偏好判重分派失败，本批按「全部新增」落库：userId={}｜原因={}",
                        userId, e.getMessage());
                updates = List.of();
                toInsert = new ArrayList<>(fresh);
            }
            for (MemoryDedupPlanner.Update<PreferenceDraft> update : updates) {
                if (!applyPreferenceUpdate(userId, update)) {
                    // 这一条改不动就改成新增：整批丢掉患者永远不知道自己被漏记了
                    toInsert.add(update.draft());
                }
            }            if (toInsert.isEmpty()) {
                return;
            }
            List<Memory> existing = filterType(remembered, MemoryType.PREFERENCE);
            int max = preferenceMax();
            if (existing.size() + toInsert.size() <= max) {
                for (PreferenceDraft draft : toInsert) {
                    Memory saved = insert(userId, MemoryType.PREFERENCE, draft.key(), draft.value(),
                            draft.quote(), draft.confidence(), null);
                    audit(saved, userId, MemoryOp.ADD, null, draft.value());
                }
                return;
            }
            mergePreferences(userId, existing, toInsert, max);
        } catch (RuntimeException e) {
            log.warn("偏好落库失败（丢弃本批）：userId={}｜原因={}", userId, e.getMessage());
        }
    }

    /**
     * 全等去重：与**已有的生效偏好**全等、以及同一批里重复的，都只留第一条。
     *
     * <p>它挡的是「同一句话被说两遍」，与语义判重（模型给的 action）是两件事，两者都保留：
     * 模型漏判时这里仍能挡住最明显的重复。
     */
    private List<PreferenceDraft> dedupePreferences(List<Memory> remembered, List<PreferenceDraft> drafts) {
        Set<String> existingKeys = new HashSet<>();
        for (Memory row : filterType(remembered, MemoryType.PREFERENCE)) {
            existingKeys.add(key(row.getMkey(), row.getMvalue()));
        }
        List<PreferenceDraft> fresh = new ArrayList<>();
        for (PreferenceDraft draft : drafts) {
            if (existingKeys.add(key(draft.key(), draft.value()))) {
                fresh.add(draft);
            }
        }
        return fresh;
    }

    /**
     * 原地改一条偏好：id 不变，只改值 / 原话 / 置信度 / 观察时间。
     *
     * <p><b>用 {@code LambdaUpdateWrapper} 显式列字段，不用 {@code updateById}</b>：后者会把整份实体
     * 写回去，手上这份是判重时取的旧快照，会把别的列（例如同批前一条刚改过的值）覆盖掉。
     *
     * <p><b>偏好不碰 retrieval_text</b>：偏好永远全量注入、不向量化，刷它是给一个不会被用到的派生列做无用功
     * （背景那边必须刷，见工单 03）。
     *
     * @return 是否改成功（false ⇒ 调用方把这条改成新增，不丢）
     */
    private boolean applyPreferenceUpdate(String userId, MemoryDedupPlanner.Update<PreferenceDraft> update) {
        Memory row = update.row();
        PreferenceDraft draft = update.draft();
        String oldValue = row.getMvalue();
        try {
            LocalDateTime now = LocalDateTime.now();
            memoryMapper.update(null, Wrappers.<Memory>lambdaUpdate()
                    .set(Memory::getMkey, draft.key())
                    .set(Memory::getMvalue, draft.value())
                    .set(Memory::getSourceQuote, draft.quote())
                    .set(Memory::getConfidence, draft.confidence())
                    .set(Memory::getObservedAt, now)
                    .set(Memory::getUpdatedAt, now)
                    .eq(Memory::getId, row.getId())
                    .eq(Memory::getUserId, userId)
                    .eq(Memory::getStatus, MemoryStatus.ACTIVE));
            memoryLogService.append(row.getId(), userId, MemoryOp.UPDATE,
                    oldValue, draft.value(), MemoryActor.EXTRACT);
            // 快照跟着改：这一批若还要走满额合并，送进合并提示词的必须是**改口后的值**，
            // 否则患者刚说的新说法会被合并回改口前的旧说法。
            row.setMkey(draft.key());
            row.setMvalue(draft.value());
            row.setSourceQuote(draft.quote());
            row.setConfidence(draft.confidence());
            row.setObservedAt(now);
            log.info("偏好已按新说法原地更新：userId={} memoryId={} {} → {}",
                    userId, row.getId(), oldValue, draft.value());
            return true;
        } catch (RuntimeException e) {
            log.warn("偏好原地更新失败，本条改为新增：userId={} memoryId={}｜原因={}",
                    userId, row.getId(), e.getMessage());
            return false;
        }
    }

    private List<Memory> filterType(List<Memory> rows, MemoryType type) {
        List<Memory> matched = new ArrayList<>();
        if (rows == null) {
            return matched;
        }
        for (Memory row : rows) {
            if (row.getType() == type) {
                matched.add(row);
            }
        }
        return matched;
    }

    /**
     * 偏好满额：调一次合并提示词，用合并结果替换旧偏好。
     *
     * <p>失败兜底：**丢弃本批新偏好、保留旧偏好**——合并是整理，不是拿旧数据冒险的场合。
     * 先插入合并结果再删旧行：插入失败时旧偏好仍在，不会因为一次模型抖动把患者的记忆清空。
     */
    private void mergePreferences(String userId, List<Memory> existing,
                                  List<PreferenceDraft> fresh, int max) {
        List<MergedPreference> merged;
        try {
            merged = memoryExtractor.merge(itemsOf(existing), itemsOfDrafts(fresh));
        } catch (RuntimeException e) {
            log.warn("偏好合并失败，丢弃本批新偏好（旧偏好保留）：userId={}｜原因={}", userId, e.getMessage());
            return;
        }
        if (merged == null || merged.isEmpty()) {
            log.warn("偏好合并结果为空，丢弃本批新偏好（旧偏好保留）：userId={}", userId);
            return;
        }
        if (merged.size() > max) {
            log.warn("偏好合并结果 {} 条超过上限 {}，截断保留前 {} 条", merged.size(), max, max);
            merged = merged.subList(0, max);
        }

        String mergedSummary = String.join("、", merged.stream().map(MergedPreference::value).toList());
        for (MergedPreference item : merged) {
            String quote = bestQuote(item.value(), existing, fresh);
            BigDecimal confidence = bestConfidence(item.value(), existing, fresh);
            Memory saved = insert(userId, MemoryType.PREFERENCE, item.key(), item.value(),
                    quote, confidence, null);
            audit(saved, userId, MemoryOp.MERGE, null, item.value());
        }
        // 合并结果已写入后再清旧行：审计逐条留痕，谁被合并掉了可追溯
        for (Memory old : existing) {
            memoryMapper.deleteById(old.getId());
            memoryLogService.append(old.getId(), userId, MemoryOp.MERGE,
                    old.getMvalue(), mergedSummary, MemoryActor.EXTRACT);
        }
        log.info("偏好已合并：旧 {} 条 + 新 {} 条 → {} 条", existing.size(), fresh.size(), merged.size());
    }

    // ---------------- 稳定背景 ----------------

    private void saveBackgrounds(String userId, List<BackgroundDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        try {
            List<Memory> existing = activeMemories(userId, MemoryType.BACKGROUND_HEALTH,
                    MemoryType.BACKGROUND_OTHER);
            Set<String> existingKeys = new HashSet<>();
            for (Memory row : existing) {
                existingKeys.add(typedKey(row.getType().getCode(), row.getMkey(), row.getMvalue()));
            }
            List<BackgroundDraft> fresh = new ArrayList<>();
            for (BackgroundDraft draft : drafts) {
                String type = "other".equalsIgnoreCase(draft.type())
                        ? MemoryType.BACKGROUND_OTHER.getCode() : MemoryType.BACKGROUND_HEALTH.getCode();
                if (existingKeys.add(typedKey(type, draft.key(), draft.value()))) {
                    fresh.add(draft);
                }
            }
            if (fresh.isEmpty()) {
                return;
            }
            int max = backgroundMax();
            if (existing.size() + fresh.size() > max) {
                log.warn("稳定背景已满（{} + {} > {}），保留旧背景、丢弃本批新背景；页面会提示患者整理",
                        existing.size(), fresh.size(), max);
                return;
            }
            for (BackgroundDraft draft : fresh) {
                MemoryType type = "other".equalsIgnoreCase(draft.type())
                        ? MemoryType.BACKGROUND_OTHER : MemoryType.BACKGROUND_HEALTH;
                Memory saved = insert(userId, type, draft.key(), draft.value(), draft.quote(),
                        draft.confidence(), draft.validTo());
                audit(saved, userId, MemoryOp.ADD, null, draft.value());
                // 稳定背景需要向量才能被按需召回；写入失败由 indexer 降级（WARN + 回填任务补），
                // 抽取本身已在线程池里，这里同步调用即可，不再多包一层异步。
                vectorIndexer.index(saved);
            }
            log.info("稳定背景已写入 {} 条：userId={}", fresh.size(), userId);
        } catch (RuntimeException e) {
            log.warn("稳定背景落库失败（丢弃本批）：userId={}｜原因={}", userId, e.getMessage());
        }
    }

    // ---------------- 公共落库 ----------------

    private Memory insert(String userId, MemoryType type, String key, String value, String quote,
                          BigDecimal confidence, LocalDate validTo) {
        LocalDateTime now = LocalDateTime.now();
        Memory memory = new Memory();
        memory.setUserId(userId);
        memory.setType(type);
        memory.setMkey(key);
        memory.setMvalue(value);
        // 08 工单接 embedding 前先写 value：retrieval_text 是「供 embedding 的扩展文本」，
        // 现在与 value 同值，后续补向量时不必回填历史行
        memory.setRetrievalText(value);
        memory.setSourceQuote(quote);
        memory.setConfidence(confidence);
        memory.setObservedAt(now);
        memory.setValidTo(validTo);
        memory.setStatus(validTo != null && validTo.isBefore(LocalDate.now())
                ? MemoryStatus.EXPIRED : MemoryStatus.ACTIVE);
        memoryMapper.insert(memory);
        return memory;
    }

    /** 审计必须挂在真实 id 上：insert 已由 MyBatis-Plus 回填 id，缺失只可能是 mock/异常路径 */
    private void audit(Memory saved, String userId, MemoryOp op, String oldValue, String newValue) {
        if (saved == null || saved.getId() == null) {
            log.warn("记忆已写入但 id 缺失，跳过审计日志：userId={} op={} value={}",
                    userId, op, newValue);
            return;
        }
        memoryLogService.append(saved.getId(), userId, op, oldValue, newValue, MemoryActor.EXTRACT);
    }

    private List<Memory> activeMemories(String userId, MemoryType... types) {
        List<Memory> rows = memoryMapper.selectList(Wrappers.<Memory>lambdaQuery()
                .eq(Memory::getUserId, userId)
                .in(Memory::getType, List.of(types))
                .eq(Memory::getStatus, MemoryStatus.ACTIVE)
                .orderByDesc(Memory::getObservedAt));
        return rows == null ? List.of() : rows;
    }

    private String dialogueText(List<DialogueTurn> turns) {
        StringBuilder text = new StringBuilder();
        for (DialogueTurn turn : turns) {
            if (turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            text.append("user".equals(turn.role()) ? "患者：" : "助手：")
                    .append(turn.content().strip()).append('\n');
        }
        return text.toString().strip();
    }

    /** 合并只需「键 + 值」：旧偏好带上自己的 key，模型不必凭空编一个新键 */
    private List<PreferenceItem> itemsOf(List<Memory> rows) {
        return rows.stream().map(row -> new PreferenceItem(row.getMkey(), row.getMvalue())).toList();
    }

    private List<PreferenceItem> itemsOfDrafts(List<PreferenceDraft> drafts) {
        return drafts.stream().map(draft -> new PreferenceItem(draft.key(), draft.value())).toList();
    }

    /**
     * 合并结果的一条该挂谁的原话。
     *
     * <p>顺序是「**新原话优先**，其次旧原话，最后才是旧原话串联」：合并后的值若是本批新说法的改写，
     * 依据就该是患者刚说的那句；只在旧行里找（原先的行为）会把一条改口挂到**改口前**的原话上——
     * 「我记住的」里看到的出处与值互相矛盾，患者据此无法判断系统到底记住了什么。
     *
     * <p>仍保留「值全等才算对上」这条保守规则：对不上任何一条（模型自己揉出来的新说法）时，
     * 串起旧原话，至少还能追到这批记忆从哪来。
     */
    private String bestQuote(String value, List<Memory> existing, List<PreferenceDraft> fresh) {
        for (PreferenceDraft draft : fresh) {
            if (value.equals(draft.value()) && draft.quote() != null && !draft.quote().isBlank()) {
                return draft.quote();
            }
        }
        for (Memory row : existing) {
            if (value.equals(row.getMvalue()) && row.getSourceQuote() != null) {
                return row.getSourceQuote();
            }
        }
        StringBuilder quotes = new StringBuilder();
        for (Memory row : existing) {
            if (row.getSourceQuote() == null || row.getSourceQuote().isBlank()) {
                continue;
            }
            if (quotes.length() > 0) {
                quotes.append("；");
            }
            quotes.append(row.getSourceQuote());
            if (quotes.length() >= 500) {
                break;
            }
        }
        return quotes.length() == 0 ? null : quotes.toString();
    }

    private BigDecimal bestConfidence(String value, List<Memory> existing, List<PreferenceDraft> fresh) {
        BigDecimal best = null;
        for (Memory row : existing) {
            if (value.equals(row.getMvalue())) {
                best = max(best, row.getConfidence());
            }
        }
        for (PreferenceDraft draft : fresh) {
            if (value.equals(draft.value())) {
                best = max(best, draft.confidence());
            }
        }
        return best;
    }

    private BigDecimal max(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    private String key(String mkey, String mvalue) {
        return mkey + '\u0000' + mvalue;
    }

    private String typedKey(String type, String mkey, String mvalue) {
        return type + '\u0000' + key(mkey, mvalue);
    }

    private int preferenceMax() {
        return sysConfigService.getInt(SysConfigService.KEY_MEMORY_PREFERENCE_MAX,
                SysConfigService.DEFAULT_MEMORY_PREFERENCE_MAX);
    }

    private int backgroundMax() {
        return sysConfigService.getInt(SysConfigService.KEY_MEMORY_BACKGROUND_MAX,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_MAX);
    }
}
