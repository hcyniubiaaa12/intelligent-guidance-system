package com.guide.memory.service;

import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.support.StickyTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 每轮记忆注入装配（chat 侧唯一入口）。
 *
 * <p>钉住三件事：开关关闭时**不读库、不构造端口**（档案由 chat 另行读取，完全不受影响）；
 * 开关开启时只读当前用户的**生效偏好**；条数上限来自 {@code sys_config}，按 observedAt 降序截断。
 */
class MemoryInjectionServiceTest {

    private static final String USER = "u1";
    private static final String SESSION = "s1";

    private MemoryMapper memoryMapper;
    private MemorySettingService memorySettingService;
    private SysConfigService sysConfigService;
    private MemoryVectorStore vectorStore;
    private MemoryInjectionService service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(MemoryMapper.class);
        memorySettingService = mock(MemorySettingService.class);
        sysConfigService = mock(SysConfigService.class);
        vectorStore = mock(MemoryVectorStore.class);
        when(sysConfigService.getInt(anyString(), anyInt()))
                .thenReturn(SysConfigService.DEFAULT_MEMORY_PREFERENCE_MAX);
        service = new MemoryInjectionService(memoryMapper, memorySettingService, sysConfigService,
                vectorStore, new StickyTracker());
    }

    @Test
    @DisplayName("开关关闭：不读记忆、不构造召回端口（档案照常由 chat 注入）")
    void disabledReturnsEmptyAndNullPort() {
        when(memorySettingService.isEnabled(USER)).thenReturn(false);

        MemoryInjectionService.Injection injection = service.forTurn(USER, SESSION);

        assertThat(injection.preferences()).isEmpty();
        assertThat(injection.recallPort()).isNull();
        verifyNoInteractions(memoryMapper);
    }

    @Test
    @DisplayName("开关开启但无记忆：偏好为空、端口存在且本轮无命中")
    void enabledWithoutMemoriesReturnsEmptyPreferencesAndPort() {
        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(vectorStore.search(eq(USER), any(), anyInt())).thenReturn(List.of());

        MemoryInjectionService.Injection injection = service.forTurn(USER, SESSION);

        assertThat(injection.preferences()).isEmpty();
        assertThat(injection.recallPort()).isInstanceOf(MemoryRecallService.class);
        assertThat(injection.recallPort().recall("胸闷", new float[]{0.1f})).isEmpty();
    }

    @Test
    @DisplayName("只读当前用户的生效偏好：背景、过期、已删除、他人记忆都不注入")
    void onlyActivePreferencesOfCurrentUser() {
        LocalDateTime now = LocalDateTime.now();
        List<Memory> rows = List.of(
                memory("m1", USER, MemoryType.PREFERENCE, MemoryStatus.ACTIVE, "讲慢一点", now, 0),
                memory("m2", USER, MemoryType.BACKGROUND_HEALTH, MemoryStatus.ACTIVE, "2型糖尿病", now, 0),
                memory("m3", USER, MemoryType.PREFERENCE, MemoryStatus.EXPIRED, "过期偏好", now, 0),
                memory("m4", USER, MemoryType.PREFERENCE, MemoryStatus.ACTIVE, "已删除偏好", now, 1),
                memory("m5", "u2", MemoryType.PREFERENCE, MemoryStatus.ACTIVE, "别人的偏好", now, 0));
        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(memoryMapper.selectList(any())).thenReturn(rows);

        MemoryInjectionService.Injection injection = service.forTurn(USER, SESSION);

        assertThat(injection.preferences()).containsExactly("讲慢一点");
        assertThat(injection.recallPort()).isNotNull();
    }

    @Test
    @DisplayName("按 observedAt 降序截断：默认最多 5 条")
    void preferencesSortedDescAndCappedAtFive() {
        LocalDateTime now = LocalDateTime.now();
        List<Memory> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(memory("m" + i, USER, MemoryType.PREFERENCE, MemoryStatus.ACTIVE,
                    "偏好" + i, now.minusHours(i), 0));
        }
        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(memoryMapper.selectList(any())).thenReturn(rows);

        MemoryInjectionService.Injection injection = service.forTurn(USER, SESSION);

        assertThat(injection.preferences())
                .containsExactly("偏好0", "偏好1", "偏好2", "偏好3", "偏好4");
    }

    @Test
    @DisplayName("上限读 sys_config：调小后立即按新上限注入")
    void capComesFromSysConfig() {
        LocalDateTime now = LocalDateTime.now();
        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_PREFERENCE_MAX,
                SysConfigService.DEFAULT_MEMORY_PREFERENCE_MAX)).thenReturn(2);
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.PREFERENCE, MemoryStatus.ACTIVE, "偏好一", now, 0),
                memory("m2", USER, MemoryType.PREFERENCE, MemoryStatus.ACTIVE, "偏好二", now.minusHours(1), 0),
                memory("m3", USER, MemoryType.PREFERENCE, MemoryStatus.ACTIVE, "偏好三", now.minusHours(2), 0)));

        MemoryInjectionService.Injection injection = service.forTurn(USER, SESSION);

        assertThat(injection.preferences()).containsExactly("偏好一", "偏好二");
    }

    private Memory memory(String id, String userId, MemoryType type, MemoryStatus status,
                          String value, LocalDateTime observedAt, int deleted) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId(userId);
        memory.setType(type);
        memory.setStatus(status);
        memory.setMkey("键");
        memory.setMvalue(value);
        memory.setObservedAt(observedAt);
        memory.setDeleted(deleted);
        return memory;
    }
}
