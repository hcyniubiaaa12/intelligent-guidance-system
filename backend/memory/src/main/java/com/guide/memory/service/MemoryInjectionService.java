package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.port.MemoryRecallPort;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.support.StickyTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 每轮对话的记忆注入装配（chat 调用，rag 只吃结果）。
 *
 * <p>为什么单独一类而不扩 {@link MemoryQueryService}：控制面（{@code GET /api/memory}）
 * 要的是「开关 + 偏好 + 背景 + 候选 + 上限」的完整视图，注入面只要「偏好 + 召回端口」，
 * 两者口径不同、生命周期也不同（一个随页面打开，一个随每轮对话）。混在一起会让控制面
 * 的字段悄悄变成对话热路径的负担。
 *
 * <p>偏好全量注入（{@link #loadPreferences}），稳定背景只给一个**按需召回端口**——
 * 端口实例捕获「用户 + 会话」，由 rag 在算出 query 向量后回调；
 * sticky 的 2 轮状态随之落在 memory 侧，rag 仍然不感知 userId。
 */
@Service
@RequiredArgsConstructor
public class MemoryInjectionService {

    private final MemoryMapper memoryMapper;
    private final MemorySettingService memorySettingService;
    private final SysConfigService sysConfigService;
    private final MemoryVectorStore vectorStore;
    private final StickyTracker stickyTracker;

    /**
     * 本轮要注入的记忆。
     *
     * @param preferences 偏好值（按 observedAt 降序、最多 {@code memory.preference.max} 条）；空列表即无偏好段
     * @param recallPort  背景召回端口；开关关闭时为 {@code null}
     */
    public record Injection(List<String> preferences, MemoryRecallPort recallPort) {
    }

    public Injection forTurn(String userId, String sessionId) {
        // 关闭自动记忆：偏好与背景都不注入，召回端口也不构造（档案完全不受影响，由 chat 另行读取）
        if (!memorySettingService.isEnabled(userId)) {
            return new Injection(List.of(), null);
        }
        return new Injection(loadPreferences(userId), new MemoryRecallService(
                userId, sessionId, memoryMapper, vectorStore, memorySettingService,
                sysConfigService, stickyTracker));
    }

    /**
     * 只读**当前用户的生效偏好**：SQL 是第一道闸，服务侧再显式校验一次
     * （与 {@link MemoryQueryService} 同一口径，防止上层查询被改坏或 mock 绕开条件）。
     */
    private List<String> loadPreferences(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int max = sysConfigService.getInt(SysConfigService.KEY_MEMORY_PREFERENCE_MAX,
                SysConfigService.DEFAULT_MEMORY_PREFERENCE_MAX);
        List<Memory> rows = memoryMapper.selectList(Wrappers.<Memory>lambdaQuery()
                .eq(Memory::getUserId, userId)
                .eq(Memory::getType, MemoryType.PREFERENCE)
                .eq(Memory::getStatus, MemoryStatus.ACTIVE)
                .orderByDesc(Memory::getObservedAt));
        if (rows == null || rows.isEmpty() || max <= 0) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> visiblePreference(row, userId))
                .sorted(Comparator.comparing(Memory::getObservedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(max)
                .map(row -> row.getMvalue().strip())
                .toList();
    }

    private boolean visiblePreference(Memory row, String userId) {
        return row != null
                && userId.equals(row.getUserId())
                && row.getType() == MemoryType.PREFERENCE
                && row.getStatus() == MemoryStatus.ACTIVE
                && (row.getDeleted() == null || row.getDeleted() == 0)
                && row.getMvalue() != null
                && !row.getMvalue().isBlank();
    }

}
