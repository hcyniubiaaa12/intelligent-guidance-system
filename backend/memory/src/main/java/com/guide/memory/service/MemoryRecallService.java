package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.model.MemoryHit;
import com.guide.common.port.MemoryRecallPort;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryVectorStore.ScoredMemory;
import com.guide.memory.support.MemoryNegation;
import com.guide.memory.support.StickyTracker;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 稳定背景的按需召回（rag 通过 {@link MemoryRecallPort} 回调）。
 *
 * <p><b>一个实例只服务一轮</b>：由 {@code MemoryInjectionService.forTurn} 按「用户 + 会话」构造，
 * 捕获 userId / sessionId 后传给 rag。rag 因此仍不感知业务状态，sticky 也能按会话记账。
 *
 * <p><b>复用 query 向量</b>：召回只用 rag 已经算好的 {@code queryVector}，**绝不在这里再调一次 embedding**
 * （那会把首字延迟直接推高一档）。没有向量就按无命中处理。
 *
 * <p><b>四道闸</b>：
 * <ol>
 *   <li>开关关闭 ⇒ 空（连库都不查）；</li>
 *   <li>相关度：向量分数达到阈值，**或**本轮消息在词面上明确提到了这条值（见下）；
 *       两路都不满足才不出现——不做"没命中就全量注入"的索引兜底；</li>
 *   <li>回表校验：本人、active、未删除、未过期、两类背景（偏好在 SQL 与这里都挡）；</li>
 *   <li>否定句：当前消息说「我没有糖尿病」时跳过该条——否则模型会拿一句已被否认的背景作答。</li>
 * </ol>
 *
 * <p><b>词面第二路</b>：短中文里「糖尿病 / 2型糖尿病」这种改写，向量分经常过不了 0.70，
 * 但患者肉眼可见地说到了它。因此向量之外再看词面（完整子串，或足够长的公共子串）。
 * 只比对本人 active 背景（≤10 条），且否定句照样拦——这不是放松相关度，是把"明显相关"
 * 从"语义相似度碰巧够高"里救出来。
 *
 * <p><b>sticky 2 轮</b>：向量本轮没再命中、但上一轮命中过的背景，接下来两轮继续注入
 * （患者改用「这个病」时背景不能突然消失）。sticky 项不刷新计数，否则永远不会过期。
 */
@Slf4j
public class MemoryRecallService implements MemoryRecallPort {

    private final String userId;
    private final String sessionId;
    private final MemoryMapper memoryMapper;
    private final MemoryVectorStore vectorStore;
    private final MemorySettingService memorySettingService;
    private final SysConfigService sysConfigService;
    private final StickyTracker stickyTracker;

    public MemoryRecallService(String userId, String sessionId, MemoryMapper memoryMapper,
                               MemoryVectorStore vectorStore, MemorySettingService memorySettingService,
                               SysConfigService sysConfigService, StickyTracker stickyTracker) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.memoryMapper = memoryMapper;
        this.vectorStore = vectorStore;
        this.memorySettingService = memorySettingService;
        this.sysConfigService = sysConfigService;
        this.stickyTracker = stickyTracker;
    }

    @Override
    public List<MemoryHit> recall(String query, float[] queryVector) {
        if (userId == null || userId.isBlank() || !memorySettingService.isEnabled(userId)) {
            return List.of();
        }
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }

        int topK = sysConfigService.getInt(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_TOP_K,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_RECALL_TOP_K);
        double threshold = sysConfigService.getDouble(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_THRESHOLD,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_RECALL_THRESHOLD);

        Map<String, Double> scores = new LinkedHashMap<>();
        for (ScoredMemory hit : search(queryVector, topK)) {
            if (hit != null && hit.score() >= threshold) {
                scores.put(hit.memoryId(), hit.score());
            }
        }

        LocalDate today = LocalDate.now();
        // 词面补召回：短中文改写（「糖尿病」↔「2型糖尿病」）向量分常常过不了阈值，
        // 但患者明明说到了这条背景。只查本人 active 背景（≤ 10 条），且只在本轮真提到时命中——
        // 这是**词面第二路**，不是"没命中就把所有背景塞进去"的索引兜底。
        if (scores.size() < topK) {
            int room = topK - scores.size();
            for (Memory row : activeBackgrounds()) {
                if (room <= 0) {
                    break;
                }
                if (row == null || row.getId() == null || scores.containsKey(row.getId())
                        || !visible(row, today) || MemoryNegation.negated(query, row.getMvalue())) {
                    continue;
                }
                if (MemoryNegation.mentions(query, row.getMvalue())) {
                    scores.put(row.getId(), threshold);
                    room--;
                }
            }
        }

        Set<String> currentHitIds = new HashSet<>(scores.keySet());

        // sticky 先收集（并推进计数），再合入本轮命中：同一 id 以本轮分数为准；
        // 本轮新命中稍后统一 remember（置 2），sticky-only 项不刷新，否则永远不过期。
        for (StickyTracker.Hit sticky : stickyTracker.collectAndAdvance(sessionId)) {
            scores.putIfAbsent(sticky.memoryId(), sticky.score());
        }
        if (scores.isEmpty()) {
            return List.of();
        }

        List<Memory> rows = loadRows(scores.keySet());
        if (rows.isEmpty()) {
            return List.of();
        }
        List<MemoryHit> hits = new ArrayList<>();
        for (Memory row : rows) {
            if (!visible(row, today) || MemoryNegation.negated(query, row.getMvalue())) {
                continue;
            }
            double score = scores.getOrDefault(row.getId(), 0d);
            hits.add(new MemoryHit(row.getId(), row.getType().getCode(), row.getMkey(),
                    row.getMvalue(), score, row.getSourceQuote(), row.getValidTo()));
            if (currentHitIds.contains(row.getId())) {
                stickyTracker.remember(sessionId, row.getId(), score);
            }
        }
        hits.sort(Comparator.comparingDouble(MemoryHit::score).reversed());
        return hits;
    }

    /** 向量召回是旁路：失败按无命中处理，不能把整轮检索拖垮 */
    private List<ScoredMemory> search(float[] queryVector, int topK) {
        try {
            List<ScoredMemory> hits = vectorStore.search(userId, queryVector, topK);
            return hits == null ? List.of() : hits;
        } catch (RuntimeException e) {
            log.warn("记忆向量召回失败，本轮按无命中处理：userId={}｜原因={}", userId, e.getMessage());
            return List.of();
        }
    }

    private List<Memory> loadRows(Set<String> ids) {
        try {
            List<Memory> rows = memoryMapper.selectBatchIds(ids);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            log.warn("记忆回表失败，本轮按无命中处理：userId={}｜原因={}", userId, e.getMessage());
            return List.of();
        }
    }

    /** 词面补召回的候选集：只取本人 active 背景（≤ memory.background.max 条） */
    private List<Memory> activeBackgrounds() {
        try {
            List<Memory> rows = memoryMapper.selectList(Wrappers.<Memory>lambdaQuery()
                    .eq(Memory::getUserId, userId)
                    .in(Memory::getType, List.of(MemoryType.BACKGROUND_HEALTH, MemoryType.BACKGROUND_OTHER))
                    .eq(Memory::getStatus, MemoryStatus.ACTIVE)
                    .orderByDesc(Memory::getObservedAt));
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            log.warn("背景记忆读取失败，本轮跳过词面召回：userId={}｜原因={}", userId, e.getMessage());
            return List.of();
        }
    }

    private boolean visible(Memory row, LocalDate today) {
        return row != null
                && userId.equals(row.getUserId())
                && isBackground(row.getType())
                && row.getStatus() == MemoryStatus.ACTIVE
                && (row.getDeleted() == null || row.getDeleted() == 0)
                && (row.getValidTo() == null || !row.getValidTo().isBefore(today))
                && row.getMvalue() != null
                && !row.getMvalue().isBlank();
    }

    private boolean isBackground(MemoryType type) {
        return type == MemoryType.BACKGROUND_HEALTH || type == MemoryType.BACKGROUND_OTHER;
    }
}
