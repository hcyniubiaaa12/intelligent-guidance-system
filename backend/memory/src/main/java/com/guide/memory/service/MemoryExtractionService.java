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
import com.guide.memory.service.MemoryExtractor.MergedPreference;
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
 * <p><b>满额口径</b>：偏好满 5 条调一次合并去重（合并失败丢弃本批新偏好、保留旧偏好）；
 * 背景满 10 条保留旧背景、丢弃新背景并把 needsCleanup 留给人手整理（页面已有提示）。
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
        // 开关关闭：不读对话、不调模型（关的是自动记忆，档案与旧记忆都不受影响）
        if (!memorySettingService.isEnabled(userId)) {
            log.debug("自动记忆已关闭，跳过抽取：userId={} sessionId={}", userId, sessionId);
            return;
        }
        List<DialogueTurn> turns = sessionActivityPort.recentDialogue(sessionId, DIALOGUE_LIMIT);
        if (turns == null || turns.isEmpty()) {
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

        MemoryExtractionResult result = memoryExtractor.extract(dialogueText(turns));
        if (result == null || result.isEmpty()) {
            log.info("本轮未抽到可落库的记忆：sessionId={}", sessionId);
            return;
        }
        savePreferences(userId, result.preferences());
        saveBackgrounds(userId, result.backgrounds());
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

    // ---------------- 偏好 ----------------

    private void savePreferences(String userId, List<PreferenceDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        try {
            List<Memory> existing = activeMemories(userId, MemoryType.PREFERENCE);
            Set<String> existingKeys = new HashSet<>();
            for (Memory row : existing) {
                existingKeys.add(key(row.getMkey(), row.getMvalue()));
            }
            List<PreferenceDraft> fresh = new ArrayList<>();
            for (PreferenceDraft draft : drafts) {
                if (existingKeys.add(key(draft.key(), draft.value()))) {
                    fresh.add(draft);
                }
            }
            if (fresh.isEmpty()) {
                return;
            }
            int max = preferenceMax();
            if (existing.size() + fresh.size() <= max) {
                for (PreferenceDraft draft : fresh) {
                    Memory saved = insert(userId, MemoryType.PREFERENCE, draft.key(), draft.value(),
                            draft.quote(), draft.confidence(), null);
                    audit(saved, userId, MemoryOp.ADD, null, draft.value());
                }
                return;
            }
            mergePreferences(userId, existing, fresh, max);
        } catch (RuntimeException e) {
            log.warn("偏好落库失败（丢弃本批）：userId={}｜原因={}", userId, e.getMessage());
        }
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
            merged = memoryExtractor.merge(valuesOf(existing), valuesOfDrafts(fresh));
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

    private List<String> valuesOf(List<Memory> rows) {
        return rows.stream().map(Memory::getMvalue).toList();
    }

    private List<String> valuesOfDrafts(List<PreferenceDraft> drafts) {
        return drafts.stream().map(PreferenceDraft::value).toList();
    }

    /** 合并后的值若能对上原条目，沿用它的原话；对不上就串起旧偏好原话（仍可追溯） */
    private String bestQuote(String value, List<Memory> existing, List<PreferenceDraft> fresh) {
        for (Memory row : existing) {
            if (value.equals(row.getMvalue()) && row.getSourceQuote() != null) {
                return row.getSourceQuote();
            }
        }
        for (PreferenceDraft draft : fresh) {
            if (value.equals(draft.value())) {
                return draft.quote();
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
