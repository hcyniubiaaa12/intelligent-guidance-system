package com.guide.memory.service;

import com.guide.auth.service.HealthProfileService;
import com.guide.auth.support.LegacyProfile;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存量档案迁移：旧慢病 → 稳定背景记忆，格式转换委托 auth，幂等、单条失败不中断。
 *
 * <p>测的是**迁移的可观察结果**（写了哪些记忆、审计 actor、是否补向量），
 * 不测 SQL 细节。旧列是否被改写由 {@code HealthProfileServiceTest} 钉住。
 */
class MemoryMigrationServiceTest {

    private static final String USER = "u1";
    private static final String SOURCE_PREFIX = "来自旧档案：";

    private HealthProfileService healthProfileService;
    private MemoryMapper memoryMapper;
    private MemoryLogService memoryLogService;
    private MemoryVectorIndexer vectorIndexer;
    private MemoryMigrationService service;
    private final AtomicInteger idSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        healthProfileService = mock(HealthProfileService.class);
        memoryMapper = mock(MemoryMapper.class);
        memoryLogService = mock(MemoryLogService.class);
        vectorIndexer = mock(MemoryVectorIndexer.class);

        when(memoryMapper.countIncludingDeleted(anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(0);
        when(memoryMapper.insert(any(Memory.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Memory.class).setId("m" + idSeq.incrementAndGet());
            return 1;
        });
        when(healthProfileService.rewriteMedicationTagsAsObjects(anyString())).thenReturn(false);

        service = new MemoryMigrationService(healthProfileService, memoryMapper,
                memoryLogService, vectorIndexer, Runnable::run);
    }

    private LegacyProfile profile(List<String> tags, String other) {
        return new LegacyProfile(USER, tags, other, null, null);
    }

    private List<Memory> capturedInserts(int expected) {
        ArgumentCaptor<Memory> captor = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper, times(expected)).insert(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("空档案不产记忆，但仍做用药格式检查（格式转换与旧慢病无关）")
    void emptyProfileWritesNothing() {
        int inserted = service.migrateProfile(profile(List.of(), null));

        assertThat(inserted).isZero();
        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryLogService, never()).append(anyString(), anyString(), any(), any(), any(), any());
        verify(healthProfileService).rewriteMedicationTagsAsObjects(USER);
    }

    @Test
    @DisplayName("仅慢病：迁成背景记忆，出处写明「来自旧档案」，审计 actor=migration，并补向量")
    void chronicTagBecomesBackgroundMemory() {
        int inserted = service.migrateProfile(profile(List.of("2型糖尿病"), null));

        assertThat(inserted).isEqualTo(1);
        Memory memory = capturedInserts(1).get(0);
        assertThat(memory.getUserId()).isEqualTo(USER);
        assertThat(memory.getType()).isEqualTo(MemoryType.BACKGROUND_HEALTH);
        assertThat(memory.getMkey()).isEqualTo("慢病");
        assertThat(memory.getMvalue()).isEqualTo("2型糖尿病");
        assertThat(memory.getRetrievalText()).isEqualTo("2型糖尿病");
        assertThat(memory.getSourceQuote()).isEqualTo(SOURCE_PREFIX + "2型糖尿病");
        assertThat(memory.getConfidence()).isNull();
        assertThat(memory.getObservedAt()).isNotNull();
        assertThat(memory.getStatus()).isEqualTo(MemoryStatus.ACTIVE);

        verify(memoryLogService).append(memory.getId(), USER, MemoryOp.ADD,
                null, "2型糖尿病", MemoryActor.MIGRATION);
        verify(vectorIndexer).index(memory);
    }

    @Test
    @DisplayName("慢病 + 用药 + 过敏：只迁慢病，用药/过敏留在档案字段（不复制成记忆）")
    void onlyChronicHistoryIsMigrated() {
        LegacyProfile legacy = new LegacyProfile(USER, List.of("高血压"), null,
                "[\"二甲双胍\"]", "[\"青霉素类\"]");

        service.migrateProfile(legacy);

        Memory memory = capturedInserts(1).get(0);
        assertThat(memory.getMvalue()).isEqualTo("高血压");
        assertThat(memory.getMvalue()).doesNotContain("二甲双胍", "青霉素类");
    }

    @Test
    @DisplayName("重复执行不产生重复记忆：物理存在即跳过，审计与向量也只写一次")
    void repeatedMigrationIsIdempotent() {
        when(memoryMapper.countIncludingDeleted(anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(0, 1);

        service.migrateProfile(profile(List.of("2型糖尿病"), null));
        service.migrateProfile(profile(List.of("2型糖尿病"), null));

        verify(memoryMapper, times(1)).insert(any(Memory.class));
        verify(memoryLogService, times(1)).append(anyString(), anyString(), eq(MemoryOp.ADD),
                isNull(), eq("2型糖尿病"), eq(MemoryActor.MIGRATION));
        verify(vectorIndexer, times(1)).index(any(Memory.class));
    }

    @Test
    @DisplayName("historyOther 按 、，,；; 切分：每段一条记忆，mkey=旧档案")
    void historyOtherIsSplitIntoSegments() {
        service.migrateProfile(profile(List.of(), "高血压、糖尿病，长期夜班；吸烟"));

        List<Memory> memories = capturedInserts(4);
        assertThat(memories).extracting(Memory::getMvalue)
                .containsExactly("高血压", "糖尿病", "长期夜班", "吸烟");
        assertThat(memories).allSatisfy(memory -> {
            assertThat(memory.getMkey()).isEqualTo("旧档案");
            assertThat(memory.getSourceQuote()).isEqualTo(SOURCE_PREFIX + memory.getMvalue());
        });
    }

    @Test
    @DisplayName("超长文本段硬切成 ≤60 字的多条，不丢字")
    void overlongTextSegmentIsSplitWithoutLoss() {
        String longText = "糖".repeat(70);

        service.migrateProfile(profile(List.of(), longText));

        List<Memory> memories = capturedInserts(2);
        assertThat(memories).allSatisfy(memory -> assertThat(memory.getMvalue().length()).isLessThanOrEqualTo(60));
        assertThat(String.join("", memories.stream().map(Memory::getMvalue).toList()))
                .isEqualTo(longText);
    }

    @Test
    @DisplayName("单条失败只 WARN 继续：前一个候选抛错，后一个照常写入")
    void singleFailureDoesNotStopRemainingCandidates() {
        when(memoryMapper.countIncludingDeleted(anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(0);

        int inserted = service.migrateProfile(profile(List.of("高血压", "糖尿病"), null));

        assertThat(inserted).isEqualTo(1);
        assertThat(capturedInserts(1).get(0).getMvalue()).isEqualTo("糖尿病");
    }

    @Test
    @DisplayName("向量线程池拒绝：记忆仍已落库，迁移不抛错（回填任务兜底）")
    void rejectedVectorTaskDoesNotFailMigration() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("full");
        };
        service = new MemoryMigrationService(healthProfileService, memoryMapper,
                memoryLogService, vectorIndexer, rejecting);

        assertThatCode(() -> service.migrateProfile(profile(List.of("高血压"), null)))
                .doesNotThrowAnyException();
        verify(memoryMapper).insert(any(Memory.class));
        verify(vectorIndexer, never()).index(any(Memory.class));
    }

    @Test
    @DisplayName("批量迁移：逐份档案处理并汇总扫描数与写入数")
    void migrateAllIteratesProfiles() {
        when(healthProfileService.listLegacyProfiles(anyInt(), isNull()))
                .thenReturn(List.of(profile(List.of("高血压"), null),
                        new LegacyProfile("u2", List.of(), "长期夜班", null, null)));

        MemoryMigrationService.MigrationSummary summary = service.migrateAll();

        assertThat(summary.profiles()).isEqualTo(2);
        assertThat(summary.inserted()).isEqualTo(2);
        verify(healthProfileService).rewriteMedicationTagsAsObjects("u2");
    }

    @Test
    @DisplayName("某一用户迁移失败不影响后续：批量扫描继续")
    void migrateAllContinuesAfterProfileFailure() {
        when(healthProfileService.listLegacyProfiles(anyInt(), isNull()))
                .thenReturn(List.of(profile(List.of("高血压"), null),
                        new LegacyProfile("u2", List.of("糖尿病"), null, null, null)));
        when(healthProfileService.rewriteMedicationTagsAsObjects("u1"))
                .thenThrow(new RuntimeException("boom"));

        MemoryMigrationService.MigrationSummary summary = service.migrateAll();

        assertThat(summary.profiles()).isEqualTo(2);
        assertThat(summary.inserted()).isEqualTo(1);
        verify(healthProfileService).rewriteMedicationTagsAsObjects("u2");
    }

    @Test
    @DisplayName("重复文本段只在本次迁移内产出一条（避免同批自撞）")
    void duplicateSegmentsAreDeduplicated() {
        service.migrateProfile(profile(List.of(), "高血压、高血压"));

        List<Memory> memories = capturedInserts(1);
        assertThat(memories.get(0).getMvalue()).isEqualTo("高血压");
    }

    @Test
    @DisplayName("空标签 / 空文本段被忽略，不写空记忆")
    void blankValuesAreIgnored() {
        service.migrateProfile(profile(List.of("  ", ""), " 、 ， "));

        verify(memoryMapper, never()).insert(any(Memory.class));
    }
}
