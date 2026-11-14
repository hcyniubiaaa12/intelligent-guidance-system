package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.memory.dto.MemoryDTO;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 记忆控制面只读查询（{@code GET /api/memory}）。
 *
 * <p>口径：
 * <ul>
 *   <li><b>只返回当前用户</b>：SQL 按 user_id 过滤，服务侧再显式校验一次——
 *       越权风险不能只押在一处。</li>
 *   <li><b>只返回生效项</b>：{@code deleted=0} 且 {@code status=active}；过期记忆仍留在库里，
 *       但不进列表（页面看到的就是系统现在会用的）。</li>
 *   <li><b>偏好与背景分开</b>：{@code preference} 进偏好；两类 {@code background_*} 都进背景。</li>
 *   <li><b>排序降序</b>：SQL 已按 observed_at 降序，服务侧再兜一次——纯单测与换实现时仍成立。</li>
 *   <li><b>needsCleanup</b>：背景条数达到上限即为 true（满额交患者整理，不静默淘汰）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MemoryQueryService {

    private final MemoryMapper memoryMapper;
    private final MemorySettingService memorySettingService;
    private final SysConfigService sysConfigService;

    public MemoryDTO.MemoryVO getMemory(String userId) {
        int preferenceMax = preferenceMax();
        int backgroundMax = backgroundMax();

        List<Memory> rows = memoryMapper.selectList(Wrappers.<Memory>lambdaQuery()
                .eq(Memory::getUserId, userId)
                .eq(Memory::getStatus, MemoryStatus.ACTIVE)
                .orderByDesc(Memory::getObservedAt));

        List<MemoryDTO.MemoryItemVO> preferences = new ArrayList<>();
        List<MemoryDTO.MemoryItemVO> backgrounds = new ArrayList<>();
        if (rows != null) {
            for (Memory row : rows) {
                if (!visible(row, userId)) {
                    continue;
                }
                if (row.getType() == MemoryType.PREFERENCE) {
                    preferences.add(toItem(row));
                } else if (row.getType() != null) {
                    backgrounds.add(toItem(row));
                }
            }
        }

        Comparator<MemoryDTO.MemoryItemVO> byObservedDesc = Comparator.comparing(
                MemoryDTO.MemoryItemVO::getObservedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        preferences.sort(byObservedDesc);
        backgrounds.sort(byObservedDesc);

        MemoryDTO.Limits limits = new MemoryDTO.Limits();
        limits.setPreferenceMax(preferenceMax);
        limits.setBackgroundMax(backgroundMax);

        MemoryDTO.MemoryVO vo = new MemoryDTO.MemoryVO();
        vo.setMemoryEnabled(memorySettingService.isEnabled(userId));
        vo.setPreferences(preferences);
        vo.setBackgrounds(backgrounds);
        vo.setLimits(limits);
        vo.setNeedsCleanup(backgrounds.size() >= backgroundMax);
        return vo;
    }

    /**
     * 服务侧显式过滤（第二道闸）：当前用户、未删除、生效中。
     * SQL 已经是第一道，这里防的是「上层查询被改坏」与「mock/换实现绕开条件」。
     */
    private boolean visible(Memory row, String userId) {
        return row != null
                && userId != null
                && userId.equals(row.getUserId())
                && (row.getDeleted() == null || row.getDeleted() == 0)
                && row.getStatus() == MemoryStatus.ACTIVE;
    }

    private MemoryDTO.MemoryItemVO toItem(Memory row) {
        MemoryDTO.MemoryItemVO item = new MemoryDTO.MemoryItemVO();
        item.setId(row.getId());
        item.setKey(row.getMkey());
        item.setValue(row.getMvalue());
        item.setSourceQuote(row.getSourceQuote());
        item.setObservedAt(row.getObservedAt());
        item.setStatus(row.getStatus() == null ? null : row.getStatus().getCode());
        return item;
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
