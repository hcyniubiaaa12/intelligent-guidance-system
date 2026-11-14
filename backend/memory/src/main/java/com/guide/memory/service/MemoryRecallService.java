package com.guide.memory.service;

import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.model.MemoryHit;
import com.guide.common.port.MemoryRecallPort;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryVectorStore.ScoredMemory;
import com.guide.memory.support.StickyTracker;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *   <li>向量分数低于阈值 ⇒ 不出现（未命中即完全不可见，不做索引兜底）；</li>
 *   <li>回表校验：本人、active、未删除、未过期、两类背景（偏好在 SQL 与这里都挡）；</li>
 *   <li>否定句：当前消息说「我没有糖尿病」时跳过该条——否则模型会拿一句已被否认的背景作答。</li>
 * </ol>
 *
 * <p><b>sticky 2 轮</b>：向量本轮没再命中、但上一轮命中过的背景，接下来两轮继续注入
 * （患者改用「这个病」时背景不能突然消失）。sticky 项不刷新计数，否则永远不会过期。
 */
@Slf4j
public class MemoryRecallService implements MemoryRecallPort {

    /** 否定词：出现在背景值之前（或与值重叠）即认为当前这句否定了它 */
    private static final List<String> NEGATIONS = List.of(
            "没有", "不是", "不过敏", "没做过", "没吃", "未", "没");
    /** 否定词的观察窗口：值出现位置往前多少个字符 */
    private static final int NEGATION_WINDOW = 12;

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
        Set<String> currentHitIds = Set.copyOf(scores.keySet());

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
        LocalDate today = LocalDate.now();
        List<MemoryHit> hits = new ArrayList<>();
        for (Memory row : rows) {
            if (!visible(row, today) || negated(query, row.getMvalue())) {
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

    /**
     * 当前消息是否否定了这条背景：值出现在 query 里，且值之前 12 字内（窗口含值本身）有否定词。
     * 含值本身是为了让「不过敏」这种否定的词形能命中——它的否定词与值连在一起。
     */
    static boolean negated(String query, String value) {
        if (query == null || query.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT);
        String v = value.toLowerCase(Locale.ROOT);
        int index = q.indexOf(v);
        while (index >= 0) {
            int from = Math.max(0, index - NEGATION_WINDOW);
            int to = Math.min(q.length(), index + v.length());
            String window = q.substring(from, to);
            for (String negation : NEGATIONS) {
                if (window.contains(negation)) {
                    return true;
                }
            }
            index = q.indexOf(v, index + 1);
        }
        return false;
    }
}
