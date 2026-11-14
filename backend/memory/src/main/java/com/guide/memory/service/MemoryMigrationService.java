package com.guide.memory.service;

import com.guide.auth.service.HealthProfileService;
import com.guide.auth.support.LegacyProfile;
import com.guide.memory.config.MemoryExecutorConfig;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 存量档案迁移：旧 {@code history_tags} / {@code history_other} → 稳定背景记忆。
 *
 * <p><b>迁什么、不迁什么</b>：只迁旧慢病（标签 + 自由文本）。用药 / 过敏留在档案字段里
 * 继续展示与注入，迁进记忆等于同一事实存两处（spec「同一事实只存一处」）。
 *
 * <p><b>不改旧列</b>：{@code history_*} 原样保留——迁移失败时能回滚，也能解释历史快照。
 * 用药列的字符串数组 → 对象数组由 auth 负责（它拥有那张表），这里只触发。
 *
 * <p><b>幂等</b>：按 {@code (userId, type, mkey, mvalue, sourceQuote)} 判**物理行**存在
 * （{@link MemoryMapper#countIncludingDeleted}）。患者删掉的迁移记忆不会在下次启动时复活。
 *
 * <p><b>旁路</b>：单条候选失败只 WARN 继续；向量写入投递到 {@code memoryExecutor}，
 * 失败 / 线程池拒绝都不回滚记忆，由 {@code MemoryVectorBackfillJob} 补。
 */
@Slf4j
@Service
public class MemoryMigrationService {

    /** 每批读取的档案数：启动期任务，分批避免一次性把大量档案读进内存 */
    static final int BATCH_SIZE = 200;

    /** 记忆值上限（与抽取侧 {@code memory.mvalue ≤60 字} 同一口径） */
    static final int MAX_VALUE_CHARS = 60;

    static final String KEY_CHRONIC = "慢病";
    static final String KEY_LEGACY = "旧档案";
    static final String SOURCE_PREFIX = "来自旧档案：";

    /** 自由文本的分隔符：中英文顿号 / 逗号 / 分号（换行也算自然分段） */
    private static final String SEGMENT_DELIMITERS = "[、，,；;\\r\\n]+";

    private final HealthProfileService healthProfileService;
    private final MemoryMapper memoryMapper;
    private final MemoryLogService memoryLogService;
    private final MemoryVectorIndexer vectorIndexer;
    private final Executor memoryExecutor;

    public MemoryMigrationService(HealthProfileService healthProfileService,
                                  MemoryMapper memoryMapper,
                                  MemoryLogService memoryLogService,
                                  MemoryVectorIndexer vectorIndexer,
                                  @Qualifier(MemoryExecutorConfig.MEMORY_EXECUTOR) Executor memoryExecutor) {
        this.healthProfileService = healthProfileService;
        this.memoryMapper = memoryMapper;
        this.memoryLogService = memoryLogService;
        this.vectorIndexer = vectorIndexer;
        this.memoryExecutor = memoryExecutor;
    }

    /** 迁移结果：扫描档案数 + 新写入记忆数（重复执行时后者为 0） */
    public record MigrationSummary(int profiles, int inserted) {
    }

    /**
     * 扫描全部活跃档案并逐份迁移（启动时调用）。
     *
     * <p>按 {@code user_id} 游标分批：{@link HealthProfileService#listLegacyProfiles(int, String)}
     * 只处理"游标之后"的用户，否则每次重启都只看前 N 份档案。
     */
    public MigrationSummary migrateAll() {
        String cursor = null;
        int profiles = 0;
        int inserted = 0;
        while (true) {
            List<LegacyProfile> batch;
            try {
                batch = healthProfileService.listLegacyProfiles(BATCH_SIZE, cursor);
            } catch (RuntimeException e) {
                log.warn("读取存量档案失败，本批停止（下次启动重试）：{}", e.getMessage());
                break;
            }
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (LegacyProfile profile : batch) {
                profiles++;
                try {
                    inserted += migrateProfile(profile);
                } catch (RuntimeException e) {
                    // 某个用户失败不能拖住其余用户；记忆插入是逐条独立的，没有事务可回滚
                    log.warn("存量档案迁移失败（继续下一个用户）：userId={}｜原因={}",
                            profile == null ? null : profile.userId(), e.getMessage());
                }
            }
            String lastUserId = lastUserId(batch);
            if (batch.size() < BATCH_SIZE || lastUserId == null) {
                break;
            }
            cursor = lastUserId;
        }
        if (profiles > 0) {
            log.info("存量档案迁移：扫描 {} 份档案，新写入 {} 条背景记忆", profiles, inserted);
        }
        return new MigrationSummary(profiles, inserted);
    }

    /**
     * 迁移单个用户的档案，返回新写入的记忆条数（幂等，重复调用不会重复写）。
     *
     * <p>用药格式转换在迁移记忆之前触发；它失败会让这份档案整体跳过
     * （{@link #migrateAll()} 捕获后继续下一个用户），因为"只迁一半"比"下次再来一遍"更难解释。
     */
    public int migrateProfile(LegacyProfile profile) {
        if (profile == null || profile.userId() == null || profile.userId().isBlank()) {
            return 0;
        }
        String userId = profile.userId();
        healthProfileService.rewriteMedicationTagsAsObjects(userId);

        Set<String> seenValues = new LinkedHashSet<>();
        int inserted = 0;
        if (profile.historyTags() != null) {
            for (String tag : profile.historyTags()) {
                inserted += migrateOne(userId, KEY_CHRONIC, capTag(tag), seenValues);
            }
        }
        for (String segment : splitText(profile.historyOther())) {
            inserted += migrateOne(userId, KEY_LEGACY, segment, seenValues);
        }
        return inserted;
    }

    /** 写入一条背景记忆；已存在（含已删除）或空值则跳过 */
    private int migrateOne(String userId, String mkey, String value, Set<String> seenValues) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        // 同一份档案里标签与自由文本重复提到同一事实：只留先出现的那条（标签优先）
        if (!seenValues.add(value)) {
            return 0;
        }
        String sourceQuote = SOURCE_PREFIX + value;
        int existing;
        try {
            existing = memoryMapper.countIncludingDeleted(userId, MemoryType.BACKGROUND_HEALTH.getCode(),
                    mkey, value, sourceQuote);
        } catch (RuntimeException e) {
            log.warn("存量记忆查重失败，跳过本条（继续下一条）：userId={} value={}｜原因={}",
                    userId, value, e.getMessage());
            return 0;
        }
        if (existing > 0) {
            return 0;
        }

        Memory memory = new Memory();
        memory.setUserId(userId);
        memory.setType(MemoryType.BACKGROUND_HEALTH);
        memory.setMkey(mkey);
        memory.setMvalue(value);
        memory.setRetrievalText(value);
        memory.setSourceQuote(sourceQuote);
        memory.setConfidence(null);
        memory.setObservedAt(LocalDateTime.now());
        memory.setStatus(MemoryStatus.ACTIVE);
        try {
            memoryMapper.insert(memory);
        } catch (RuntimeException e) {
            log.warn("存量记忆写入失败，跳过本条（继续下一条）：userId={} value={}｜原因={}",
                    userId, value, e.getMessage());
            return 0;
        }

        audit(memory, userId, value);
        submitIndex(memory);
        return 1;
    }

    private void audit(Memory memory, String userId, String value) {
        if (memory.getId() == null) {
            log.warn("存量记忆已写入但 id 缺失，跳过审计日志：userId={} value={}", userId, value);
            return;
        }
        try {
            memoryLogService.append(memory.getId(), userId, MemoryOp.ADD,
                    null, value, MemoryActor.MIGRATION);
        } catch (RuntimeException e) {
            // 审计失败不回滚记忆：记忆本身是患者可见的事实，日志是留痕
            log.warn("存量迁移审计写入失败（记忆已保留）：memoryId={}｜原因={}",
                    memory.getId(), e.getMessage());
        }
    }

    /**
     * 投递向量补算。线程池满 / 已关闭只 WARN——记忆已在 MySQL 里，
     * {@code MemoryVectorBackfillJob} 会发现缺向量的行。
     */
    private void submitIndex(Memory memory) {
        try {
            memoryExecutor.execute(() -> vectorIndexer.index(memory));
        } catch (RejectedExecutionException e) {
            log.warn("记忆向量线程池已满，迁移记忆暂缺向量（回填任务会补）：memoryId={}",
                    memory.getId());
        } catch (RuntimeException e) {
            log.warn("记忆向量任务提交失败（回填任务会补）：memoryId={}｜原因={}",
                    memory.getId(), e.getMessage());
        }
    }

    private String lastUserId(List<LegacyProfile> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            LegacyProfile profile = batch.get(i);
            if (profile != null && profile.userId() != null && !profile.userId().isBlank()) {
                return profile.userId();
            }
        }
        return null;
    }

    /** 标签去空白；超长时截断并告警（标签是词表受控词，正常不会超长） */
    private String capTag(String tag) {
        if (tag == null) {
            return null;
        }
        String value = tag.trim();
        if (value.length() > MAX_VALUE_CHARS) {
            log.warn("旧慢病标签超过 {} 字，迁移时截断：{}", MAX_VALUE_CHARS, value);
            return value.substring(0, MAX_VALUE_CHARS);
        }
        return value;
    }

    /**
     * 自由文本 → 多个记忆值：先按顿号 / 逗号 / 分号 / 换行切分，再对超长段硬切到 ≤60 字。
     *
     * <p><b>不丢字</b>：超过 60 字的段不是截断而是接着切下一段——旧档案里的生活背景
     * 是患者手填的原文，迁移不该替人删内容。
     */
    private List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : text.split(SEGMENT_DELIMITERS)) {
            String segment = raw.trim();
            if (segment.isEmpty()) {
                continue;
            }
            for (int start = 0; start < segment.length(); start += MAX_VALUE_CHARS) {
                result.add(segment.substring(start, Math.min(segment.length(), start + MAX_VALUE_CHARS)));
            }
        }
        return result;
    }
}
