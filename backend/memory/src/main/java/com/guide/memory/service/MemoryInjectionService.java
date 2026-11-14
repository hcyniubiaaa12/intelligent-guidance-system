package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.port.MemoryRecallPort;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
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
 * <p>07 只接偏好：稳定背景的向量召回在 08，这里先给一个**返回 null 的端口占位**
 * （rag 侧把 null 当「无命中」，不影响检索）。
 * {@code sessionId} 现在不参与读取，但签名先带上——08 的 sticky 2 轮状态按会话落在 memory 侧。
 */
@Service
@RequiredArgsConstructor
public class MemoryInjectionService {

    private final MemoryMapper memoryMapper;
    private final MemorySettingService memorySettingService;
    private final SysConfigService sysConfigService;

    /**
     * 本轮要注入的记忆。
     *
     * @param preferences 偏好值（按 observedAt 降序、最多 {@code memory.preference.max} 条）；空列表即无偏好段
     * @param recallPort  背景召回端口；开关关闭时为 {@code null}，开启时 07 为空实现（返回 null）
     */
    public record Injection(List<String> preferences, MemoryRecallPort recallPort) {
    }

    public Injection forTurn(String userId, String sessionId) {
        // 关闭自动记忆：偏好与背景都不注入，召回端口也不构造（档案完全不受影响，由 chat 另行读取）
        if (!memorySettingService.isEnabled(userId)) {
            return new Injection(List.of(), null);
        }
        return new Injection(loadPreferences(userId), emptyRecallPort());
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

    /**
     * 07 占位：稳定背景召回在 08。返回 null 而不是空列表是刻意的——
     * 它把「还没有实现」与「实现了但本轮没命中」在调试时区分开，rag 两者都按无命中处理。
     */
    private MemoryRecallPort emptyRecallPort() {
        return (query, queryVector) -> null;
    }
}
