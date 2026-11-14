package com.guide.memory.service;

import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.memory.dto.MemoryDTO;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆只读列表（工单 04）：按用户隔离、逻辑删除/过期过滤、空列表、偏好与背景分列表、
 * 上限与 needsCleanup 边界、开关读取。
 *
 * <p>只测服务对外的读行为：给定库里的行（mock 返回），断言页面拿到什么。
 * 查询条件（user_id / status）是 SQL 侧的第一道闸，这里同时钉服务侧的**显式过滤**——
 * 即便上层查询被改坏，也不能把别人的记忆或已删除的行下发出去。
 */
class MemoryQueryServiceTest {

    private static final String USER = "u1";

    private MemoryMapper memoryMapper;
    private MemorySettingService memorySettingService;
    private SysConfigService sysConfigService;
    private MemoryQueryService service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(MemoryMapper.class);
        memorySettingService = mock(MemorySettingService.class);
        sysConfigService = mock(SysConfigService.class);
        service = new MemoryQueryService(memoryMapper, memorySettingService, sysConfigService);

        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_PREFERENCE_MAX,
                SysConfigService.DEFAULT_MEMORY_PREFERENCE_MAX)).thenReturn(5);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_BACKGROUND_MAX,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_MAX)).thenReturn(10);
    }

    @Test
    @DisplayName("空列表：偏好、背景、候选都为空，needsCleanup=false，开关与上限照常下发")
    void emptyList() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        MemoryDTO.MemoryVO vo = service.getMemory(USER);

        assertThat(vo.getPreferences()).isEmpty();
        assertThat(vo.getBackgrounds()).isEmpty();
        assertThat(vo.getCandidates()).isEmpty();
        assertThat(vo.isNeedsCleanup()).isFalse();
        assertThat(vo.isMemoryEnabled()).isTrue();
        assertThat(vo.getLimits().getPreferenceMax()).isEqualTo(5);
        assertThat(vo.getLimits().getBackgroundMax()).isEqualTo(10);
    }

    @Test
    @DisplayName("按用户隔离：查询结果里混入别人的行也不会被下发")
    void isolatesByUser() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.PREFERENCE, "语气", "慢一点", day(2)),
                memory("m2", "u2", MemoryType.PREFERENCE, "语气", "快一点", day(3))));

        MemoryDTO.MemoryVO vo = service.getMemory(USER);

        assertThat(vo.getPreferences()).extracting(MemoryDTO.MemoryItemVO::getId).containsExactly("m1");
    }

    @Test
    @DisplayName("逻辑删除与过期都不展示：只有 deleted=0 且 status=active 的行下发")
    void dropsDeletedAndExpired() {
        Memory deleted = memory("m1", USER, MemoryType.PREFERENCE, "语气", "慢一点", day(1));
        deleted.setDeleted(1);
        Memory expired = memory("m2", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "糖尿病", day(2));
        expired.setStatus(MemoryStatus.EXPIRED);
        Memory active = memory("m3", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "高血压", day(3));

        when(memoryMapper.selectList(any())).thenReturn(List.of(deleted, expired, active));

        MemoryDTO.MemoryVO vo = service.getMemory(USER);

        assertThat(vo.getPreferences()).isEmpty();
        assertThat(vo.getBackgrounds()).extracting(MemoryDTO.MemoryItemVO::getId).containsExactly("m3");
    }

    @Test
    @DisplayName("偏好与两类背景分开：preference 进偏好，background_health/background_other 都进背景")
    void splitsPreferenceAndBackground() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.PREFERENCE, "称呼", "叫我老王", day(1)),
                memory("m2", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", day(2)),
                memory("m3", USER, MemoryType.BACKGROUND_OTHER, "生活", "长期夜班", day(3))));

        MemoryDTO.MemoryVO vo = service.getMemory(USER);

        assertThat(vo.getPreferences()).extracting(MemoryDTO.MemoryItemVO::getId).containsExactly("m1");
        assertThat(vo.getBackgrounds()).extracting(MemoryDTO.MemoryItemVO::getId)
                .containsExactlyInAnyOrder("m2", "m3");
        assertThat(vo.getCandidates()).isEmpty();
    }

    @Test
    @DisplayName("各自按 observedAt 降序：SQL 排序之外，服务侧再兜一次（纯单测钉住）")
    void ordersByObservedAtDesc() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory("p-old", USER, MemoryType.PREFERENCE, "语气", "慢一点", day(1)),
                memory("p-new", USER, MemoryType.PREFERENCE, "称呼", "叫我老王", day(5)),
                memory("p-mid", USER, MemoryType.PREFERENCE, "长度", "短一点", day(3)),
                memory("b-old", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "高血压", day(2)),
                memory("b-new", USER, MemoryType.BACKGROUND_OTHER, "生活", "长期夜班", day(4))));

        MemoryDTO.MemoryVO vo = service.getMemory(USER);

        assertThat(vo.getPreferences()).extracting(MemoryDTO.MemoryItemVO::getId)
                .containsExactly("p-new", "p-mid", "p-old");
        assertThat(vo.getBackgrounds()).extracting(MemoryDTO.MemoryItemVO::getId)
                .containsExactly("b-new", "b-old");
    }

    @Test
    @DisplayName("needsCleanup 边界：背景 9 条（<上限 10）=false，10 条（=上限）=true")
    void needsCleanupBoundary() {
        when(memoryMapper.selectList(any())).thenReturn(backgrounds(9));
        assertThat(service.getMemory(USER).isNeedsCleanup()).isFalse();

        when(memoryMapper.selectList(any())).thenReturn(backgrounds(10));
        assertThat(service.getMemory(USER).isNeedsCleanup()).isTrue();
    }

    @Test
    @DisplayName("关闭自动记忆：memoryEnabled=false，但旧记忆仍可见（关的是抽取与注入，不是删除）")
    void switchOffKeepsMemoriesVisible() {
        when(memorySettingService.isEnabled(USER)).thenReturn(false);
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.PREFERENCE, "语气", "慢一点", day(1))));

        MemoryDTO.MemoryVO vo = service.getMemory(USER);

        assertThat(vo.isMemoryEnabled()).isFalse();
        assertThat(vo.getPreferences()).extracting(MemoryDTO.MemoryItemVO::getId).containsExactly("m1");
    }

    @Test
    @DisplayName("上限从运行时配置读：改配置即改下发值，页面不写死")
    void limitsComeFromConfig() {
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_PREFERENCE_MAX,
                SysConfigService.DEFAULT_MEMORY_PREFERENCE_MAX)).thenReturn(7);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_BACKGROUND_MAX,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_MAX)).thenReturn(12);
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        MemoryDTO.MemoryVO vo = service.getMemory(USER);

        assertThat(vo.getLimits().getPreferenceMax()).isEqualTo(7);
        assertThat(vo.getLimits().getBackgroundMax()).isEqualTo(12);
    }

    @Test
    @DisplayName("单条记忆的字段：键、值、患者原话出处、观察时间、状态都下发")
    void itemCarriesSourceAndTime() {
        Memory row = memory("m1", USER, MemoryType.PREFERENCE, "回答方式", "讲慢一点", day(2));
        row.setSourceQuote("我希望你讲慢一点、温柔一点");
        when(memoryMapper.selectList(any())).thenReturn(List.of(row));

        MemoryDTO.MemoryItemVO item = service.getMemory(USER).getPreferences().get(0);

        assertThat(item.getKey()).isEqualTo("回答方式");
        assertThat(item.getValue()).isEqualTo("讲慢一点");
        assertThat(item.getSourceQuote()).isEqualTo("我希望你讲慢一点、温柔一点");
        assertThat(item.getObservedAt()).isEqualTo(day(2));
        assertThat(item.getStatus()).isEqualTo("active");
    }

    // ---------- 夹具 ----------

    private Memory memory(String id, String userId, MemoryType type, String key, String value,
                          LocalDateTime observedAt) {
        Memory m = new Memory();
        m.setId(id);
        m.setUserId(userId);
        m.setType(type);
        m.setMkey(key);
        m.setMvalue(value);
        m.setObservedAt(observedAt);
        m.setStatus(MemoryStatus.ACTIVE);
        m.setDeleted(0);
        return m;
    }

    private List<Memory> backgrounds(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> memory("b" + i, USER, MemoryType.BACKGROUND_HEALTH,
                        "背景" + i, "值" + i, day(1).plusMinutes(i)))
                .toList();
    }

    private LocalDateTime day(int d) {
        return LocalDateTime.of(2026, 11, d, 10, 0);
    }
}
