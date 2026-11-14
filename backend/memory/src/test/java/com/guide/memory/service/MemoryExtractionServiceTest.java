package com.guide.memory.service;

import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.event.TurnCompletedEvent;
import com.guide.common.port.SessionActivityPort;
import com.guide.common.port.SessionActivityPort.DialogueTurn;
import com.guide.common.port.SessionActivityPort.IdleSession;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryExtractor.MergedPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抽取编排（service 层）：开关、规则预筛、配额、落库与审计、满额分支、失败降级。
 *
 * <p>模型调用与解析被 mock 掉——那是 {@code MemoryExtractorTest} 的职责；这里只钉
 * 「什么时候该调模型、调完写什么、写不进去时怎么收场」。
 */
class MemoryExtractionServiceTest {

    private static final String USER = "u1";
    private static final String SESSION = "s1";

    private MemorySettingService memorySettingService;
    private SessionActivityPort sessionActivityPort;
    private MemoryExtractor memoryExtractor;
    private MemoryMapper memoryMapper;
    private MemoryLogService memoryLogService;
    private SysConfigService sysConfigService;
    private MemoryVectorIndexer vectorIndexer;
    private ProfileCandidateService profileCandidateService;
    private MemoryExtractionService service;
    private final AtomicInteger idSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        memorySettingService = mock(MemorySettingService.class);
        sessionActivityPort = mock(SessionActivityPort.class);
        memoryExtractor = mock(MemoryExtractor.class);
        memoryMapper = mock(MemoryMapper.class);
        memoryLogService = mock(MemoryLogService.class);
        sysConfigService = mock(SysConfigService.class);
        vectorIndexer = mock(MemoryVectorIndexer.class);
        profileCandidateService = mock(ProfileCandidateService.class);
        service = new MemoryExtractionService(memorySettingService, sessionActivityPort, memoryExtractor,
                memoryMapper, memoryLogService, sysConfigService, vectorIndexer, profileCandidateService);

        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(sessionActivityPort.recentDialogue(SESSION, MemoryExtractionService.DIALOGUE_LIMIT))
                .thenReturn(List.of(
                        new DialogueTurn("user", "我有2型糖尿病，希望你讲慢一点", LocalDateTime.now()),
                        new DialogueTurn("ai", "请说说哪里不舒服", LocalDateTime.now())));
        when(sessionActivityPort.tryIncrementExtractCount(SESSION, 3)).thenReturn(true);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_EXTRACT_SESSION_MAX,
                SysConfigService.DEFAULT_MEMORY_EXTRACT_SESSION_MAX)).thenReturn(3);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_PREFERENCE_MAX,
                SysConfigService.DEFAULT_MEMORY_PREFERENCE_MAX)).thenReturn(5);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_BACKGROUND_MAX,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_MAX)).thenReturn(10);
        when(memoryMapper.insert(any(Memory.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Memory.class).setId("m" + idSeq.incrementAndGet());
            return 1;
        });
        when(memoryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("开关关闭：不读对话、不申领配额、不调模型（关的是自动记忆，不是档案）")
    void switchOffSkipsEverything() {
        when(memorySettingService.isEnabled(USER)).thenReturn(false);

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(sessionActivityPort, never()).recentDialogue(any(), anyInt());
        verify(sessionActivityPort, never()).tryIncrementExtractCount(any(), anyInt());
        verify(memoryExtractor, never()).extract(any());
    }

    @Test
    @DisplayName("规则预筛未命中：不申领配额、不调模型（「你好」不值得一次模型调用）")
    void prefilterMissSkipsModel() {
        when(sessionActivityPort.recentDialogue(SESSION, MemoryExtractionService.DIALOGUE_LIMIT))
                .thenReturn(List.of(new DialogueTurn("user", "你好", LocalDateTime.now())));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(sessionActivityPort, never()).tryIncrementExtractCount(any(), anyInt());
        verify(memoryExtractor, never()).extract(any());
    }

    @Test
    @DisplayName("每会话最多 3 次：原子递增返回 false 时直接返回，不再调模型")
    void sessionQuotaExhaustedSkipsModel() {
        when(sessionActivityPort.tryIncrementExtractCount(SESSION, 3)).thenReturn(false);

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryExtractor, never()).extract(any());
    }

    @Test
    @DisplayName("合法抽取落库：偏好/背景各按其类型写入，带原话、置信度、观察时间与 retrievalText，并写审计")
    void persistsValidResultWithAudit() {
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")),
                List.of(background("慢病", "2型糖尿病", "health", "我有2型糖尿病", "0.95", null))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper, times(2)).insert(inserted.capture());
        Memory preference = inserted.getAllValues().get(0);
        assertThat(preference.getType()).isEqualTo(MemoryType.PREFERENCE);
        assertThat(preference.getMkey()).isEqualTo("回答方式");
        assertThat(preference.getMvalue()).isEqualTo("讲慢一点");
        assertThat(preference.getSourceQuote()).isEqualTo("我希望你讲慢一点");
        assertThat(preference.getConfidence()).isEqualByComparingTo("0.9");
        assertThat(preference.getRetrievalText()).isEqualTo("讲慢一点");
        assertThat(preference.getObservedAt()).isNotNull();
        assertThat(preference.getUserId()).isEqualTo(USER);

        Memory background = inserted.getAllValues().get(1);
        assertThat(background.getType()).isEqualTo(MemoryType.BACKGROUND_HEALTH);
        assertThat(background.getMvalue()).isEqualTo("2型糖尿病");

        verify(memoryLogService).append("m1", USER, MemoryOp.ADD, null, "讲慢一点", MemoryActor.EXTRACT);
        verify(memoryLogService).append("m2", USER, MemoryOp.ADD, null, "2型糖尿病", MemoryActor.EXTRACT);
        // 只给稳定背景建向量：偏好永远全量注入，不需要按需召回
        verify(vectorIndexer).index(background);
        verify(vectorIndexer, never()).index(preference);
    }

    @Test
    @DisplayName("背景 type=other 落 background_other；validTo 已过则状态直接置 expired")
    void backgroundTypeAndExpiry() {
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(),
                List.of(background("生活", "长期夜班", "other", "我一直上夜班", "0.9", null),
                        background("用药", "二甲双胍", "health", "我长期吃二甲双胍", "0.9",
                                LocalDate.now().minusDays(1)))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues().get(0).getType()).isEqualTo(MemoryType.BACKGROUND_OTHER);
        assertThat(inserted.getAllValues().get(0).getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(inserted.getAllValues().get(1).getStatus()).isEqualTo(MemoryStatus.EXPIRED);
    }

    @Test
    @DisplayName("已在库里的同 (key,value) 不重复写：抽取模型重复输出也不产生重复记忆")
    void skipsExistingDuplicates() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(existing("old", MemoryType.PREFERENCE,
                "回答方式", "讲慢一点", "0.9")));
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryLogService, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("偏好满额：调一次合并提示词，合并结果替换旧偏好，逐条写 merge 审计")
    void preferenceFullTriggersMerge() {
        List<Memory> existing = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            existing.add(existing("old" + i, MemoryType.PREFERENCE, "偏好" + i, "值" + i, "0.8"));
        }
        when(memoryMapper.selectList(any())).thenReturn(existing);
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));
        when(memoryExtractor.merge(any(), any())).thenReturn(List.of(
                new MergedPreference("偏好0", "值0"),
                new MergedPreference("偏好1", "值1"),
                new MergedPreference("偏好2", "值2"),
                new MergedPreference("偏好3", "值3"),
                new MergedPreference("回答方式", "讲慢一点")));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> incoming = ArgumentCaptor.forClass(List.class);
        verify(memoryExtractor).merge(any(), incoming.capture());
        assertThat(incoming.getValue()).containsExactly("讲慢一点");

        // 5 条合并结果写入 + 5 条旧偏好逻辑删除
        verify(memoryMapper, times(5)).insert(any(Memory.class));
        verify(memoryMapper, times(5)).deleteById(any(String.class));
        // 旧行逐条留痕、新行也留痕
        verify(memoryLogService, times(10)).append(any(), eq(USER), eq(MemoryOp.MERGE),
                any(), any(), eq(MemoryActor.EXTRACT));
    }

    @Test
    @DisplayName("合并失败：丢弃本批新偏好、保留旧偏好——不插不删，患者记忆不被模型抖动清空")
    void mergeFailureKeepsOldPreferences() {
        List<Memory> existing = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            existing.add(existing("old" + i, MemoryType.PREFERENCE, "偏好" + i, "值" + i, "0.8"));
        }
        when(memoryMapper.selectList(any())).thenReturn(existing);
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));
        when(memoryExtractor.merge(any(), any())).thenThrow(new IllegalStateException("合并输出不可解析"));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryMapper, never()).deleteById(any(String.class));
        verify(memoryLogService, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("档案候选只走候选表：不写 user_memory、不建向量（未确认的二手信息绝不进注入路径）")
    void profileCandidatesNeverBecomeMemories() {
        ProfileCandidateDraft candidate = new ProfileCandidateDraft(
                "allergy", "青霉素", null, "我青霉素过敏", new BigDecimal("0.95"));
        when(memoryExtractor.extract(any())).thenReturn(
                new MemoryExtractionResult(List.of(), List.of(), List.of(candidate)));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(profileCandidateService).recordCandidates(USER, List.of(candidate));
        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(vectorIndexer, never()).index(any());
        verify(memoryLogService, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("稳定背景满额：保留旧背景、丢弃新背景（页面 needsCleanup 提示患者整理）")
    void backgroundFullDiscardsIncoming() {
        List<Memory> existing = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            existing.add(existing("old" + i, MemoryType.BACKGROUND_HEALTH, "背景" + i, "值" + i, "0.8"));
        }
        when(memoryMapper.selectList(any())).thenReturn(existing);
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "糖尿病", "health", "我有糖尿病", "0.9", null))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryMapper, never()).deleteById(any(String.class));
    }

    @Test
    @DisplayName("模型/落库异常只记日志：不上抛、不重试（抽取是旁路）")
    void failuresOnlyWarn() {
        when(memoryExtractor.extract(any())).thenThrow(new RuntimeException("upstream 502"));
        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();

        resetExtractor();
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));
        when(memoryMapper.insert(any(Memory.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();
        // 不重试：第二次调用不会再调一次模型
        verify(memoryExtractor, times(1)).extract(any());
    }

    @Test
    @DisplayName("空闲兜底跳过规则预筛：最后一句是「你好」也照常抽取（补上轮次入口漏掉的会话）")
    void idleExtractionSkipsPrefilter() {
        when(sessionActivityPort.recentDialogue(SESSION, MemoryExtractionService.DIALOGUE_LIMIT))
                .thenReturn(List.of(new DialogueTurn("user", "你好", LocalDateTime.now())));
        when(memoryExtractor.extract(any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));

        service.extractIdleSession(new IdleSession(SESSION, USER));

        verify(memoryExtractor).extract(any());
        verify(memoryMapper).insert(any(Memory.class));
    }

    private void resetExtractor() {
        org.mockito.Mockito.reset(memoryExtractor);
    }

    private PreferenceDraft pref(String key, String value, String quote, String confidence) {
        return new PreferenceDraft(key, value, quote, new BigDecimal(confidence));
    }

    private BackgroundDraft background(String key, String value, String type, String quote,
                                       String confidence, LocalDate validTo) {
        return new BackgroundDraft(key, value, type, quote, new BigDecimal(confidence), validTo);
    }

    private Memory existing(String id, MemoryType type, String key, String value, String confidence) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId(USER);
        memory.setType(type);
        memory.setMkey(key);
        memory.setMvalue(value);
        memory.setConfidence(new BigDecimal(confidence));
        memory.setObservedAt(LocalDateTime.now());
        memory.setStatus(MemoryStatus.ACTIVE);
        return memory;
    }
}
