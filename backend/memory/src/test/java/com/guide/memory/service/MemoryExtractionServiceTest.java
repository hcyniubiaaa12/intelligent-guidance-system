package com.guide.memory.service;

import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.event.TurnCompletedEvent;
import com.guide.common.port.SessionActivityPort;
import com.guide.common.port.SessionActivityPort.DialogueTurn;
import com.guide.common.port.SessionActivityPort.IdleSession;
import com.guide.memory.MpTableInfoTestSupport;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryDedupAction;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryExtractor.MergedPreference;
import com.guide.memory.service.MemoryExtractor.PreferenceItem;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** LambdaUpdateWrapper 的 set 会立即解析列名：纯单测里得先手工建 TableInfo 缓存 */
    @BeforeAll
    static void initTableInfo() {
        MpTableInfoTestSupport.init(Memory.class);
    }

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
        // 默认视为命中并改了一行；「并发置失效 ⇒ 0 行」由专门用例覆盖
        when(memoryMapper.update(any(), any())).thenReturn(1);
        when(memoryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("偏好改口 action=update：原地改旧行（id 不变），审计记 update（old→new），不插新行")
    void updatePreferenceInPlace() {
        Memory old = existing("old1", MemoryType.PREFERENCE, "回答方式", "讲慢一点", "0.8");
        old.setSourceQuote("我希望你讲慢一点");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("语速", "语速要慢", "你讲话不要太慢了", "0.9",
                        MemoryDedupAction.UPDATE, 1)), List.of()));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        // 不插新行：改口是「这一条变了」，不是「多了一条」
        verify(memoryMapper, never()).insert(any(Memory.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Memory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(memoryMapper).update(isNull(), captor.capture());
        // 用显式列更新（不是 updateById 整实体回写），且只命中目标行（id + 归属 + 生效中）
        assertThat(captor.getValue().getSqlSet()).contains("mvalue", "source_quote", "confidence", "observed_at");
        // 先取 sqlSegment 才会把 eq 的参数值落进 paramNameValuePairs
        assertThat(captor.getValue().getSqlSegment()).contains("id", "user_id", "status");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("语速要慢", "你讲话不要太慢了", "old1", USER);

        verify(memoryLogService).append("old1", USER, MemoryOp.UPDATE,
                "讲慢一点", "语速要慢", MemoryActor.EXTRACT);
        verify(memoryLogService, never()).append(any(), any(), eq(MemoryOp.ADD), any(), any(), any());
        // 偏好不向量化：改口不触发 retrieval_text 刷新，也不重算向量
        verify(vectorIndexer, never()).refreshBackgroundValue(any(), any(), any());
        verify(vectorIndexer, never()).index(any(Memory.class));
    }

    @Test
    @DisplayName("背景改口 action=update：原地改旧行（id 不变）、同一语句刷 retrieval_text，再重算向量，不插新行")
    void updateBackgroundInPlaceRefreshesVector() {
        Memory old = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.8");
        old.setSourceQuote("我有2型糖尿病");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "2型糖尿病，控制良好", "health",
                        "2型糖尿病，控制良好", "0.9", null, MemoryDedupAction.UPDATE, 1))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Memory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(memoryMapper).update(isNull(), captor.capture());
        // 内容列 + retrieval_text + updated_at 同一条语句落库：向量文本不能与值分家
        assertThat(captor.getValue().getSqlSet()).contains("mkey", "mvalue", "source_quote",
                "confidence", "observed_at", "updated_at", "retrieval_text");
        // 只命中目标行（id + 归属 + 生效中）
        assertThat(captor.getValue().getSqlSegment()).contains("id", "user_id", "status");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("2型糖尿病，控制良好", "b1", USER);

        verify(memoryLogService).append("b1", USER, MemoryOp.UPDATE,
                "2型糖尿病", "2型糖尿病，控制良好", MemoryActor.EXTRACT);
        // 向量走共用口径（与患者手动编辑同一处），不是各写一遍；
        // 没有扩写时 retrieval_text 回退新值（绝不让它停留在旧说法的文本上）
        verify(vectorIndexer).refreshBackgroundValue(eq(old), eq("2型糖尿病，控制良好"),
                eq("2型糖尿病，控制良好"), any(LocalDateTime.class));
        verify(vectorIndexer, never()).index(any(Memory.class));
    }

    @Test
    @DisplayName("背景带检索扩展文本落库：retrieval_text 用扩写，mvalue 仍是患者说的值")
    void persistsBackgroundRetrievalText() {
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "2型糖尿病", "health", "我有2型糖尿病", "0.95", null,
                        "2型糖尿病；糖尿病、血糖高；我血糖高要注意什么",
                        MemoryDedupAction.NEW, null))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMvalue()).isEqualTo("2型糖尿病");
        assertThat(inserted.getValue().getRetrievalText())
                .isEqualTo("2型糖尿病；糖尿病、血糖高；我血糖高要注意什么");
    }

    @Test
    @DisplayName("背景改口带新扩写：retrieval_text 写新扩写，向量按它重算（不能停留在旧说法上）")
    void updateBackgroundWritesNewRetrievalText() {
        Memory old = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "糖尿病", "health", "我有糖尿病", "0.9", null,
                        "糖尿病；血糖高、血糖病；我血糖高要注意什么",
                        MemoryDedupAction.UPDATE, 1))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Memory>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(memoryMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("糖尿病；血糖高、血糖病；我血糖高要注意什么");
        verify(vectorIndexer).refreshBackgroundValue(eq(old), eq("糖尿病"),
                eq("糖尿病；血糖高、血糖病；我血糖高要注意什么"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("偏好更新命中 0 行（目标已被并发置为失效）：不写 update 审计，本条降级为新增")
    void preferenceUpdateHitsNoRowFallsBackToNew() {
        Memory old = existing("old1", MemoryType.PREFERENCE, "回答方式", "讲慢一点", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("语速", "语速要慢", "你讲话不要太慢了", "0.9",
                        MemoryDedupAction.UPDATE, 1)), List.of()));
        // 判重之后目标行被并发置为 expired：UPDATE 命中 0 行，不能当成功
        when(memoryMapper.update(any(), any())).thenReturn(0);

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryLogService, never()).append(any(), any(), eq(MemoryOp.UPDATE), any(), any(), any());
        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMvalue()).isEqualTo("语速要慢");
        verify(memoryLogService).append("m1", USER, MemoryOp.ADD, null, "语速要慢", MemoryActor.EXTRACT);
    }

    @Test
    @DisplayName("背景更新命中 0 行：不写 update 审计、不动向量，本条降级为新增")
    void backgroundUpdateHitsNoRowFallsBackToNew() {
        Memory old = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "2型糖尿病，控制良好", "health",
                        "2型糖尿病，控制良好", "0.9", null, MemoryDedupAction.UPDATE, 1))));
        when(memoryMapper.update(any(), any())).thenReturn(0);

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryLogService, never()).append(any(), any(), eq(MemoryOp.UPDATE), any(), any(), any());
        verify(vectorIndexer, never()).refreshBackgroundValue(any(), any(), any());
        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMvalue()).isEqualTo("2型糖尿病，控制良好");
        verify(vectorIndexer).index(inserted.getValue());
    }

    @Test
    @DisplayName("偏好更新成功但审计写失败：只记日志，不再插入一条（同一事实不能两行）")
    void preferenceAuditFailureDoesNotDuplicate() {
        Memory old = existing("old1", MemoryType.PREFERENCE, "回答方式", "讲慢一点", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("语速", "语速要慢", "你讲话不要太慢了", "0.9",
                        MemoryDedupAction.UPDATE, 1)), List.of()));
        doThrow(new RuntimeException("audit down"))
                .when(memoryLogService).append(any(), any(), any(), any(), any(), any());

        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();

        // 是否降级为新增只由 UPDATE 的决定：审计失败不触发第二次写入
        verify(memoryMapper, never()).insert(any(Memory.class));
    }

    @Test
    @DisplayName("背景更新成功但审计写失败：仍不插新行，向量照常重算")
    void backgroundAuditFailureDoesNotDuplicate() {
        Memory old = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "2型糖尿病，控制良好", "health",
                        "2型糖尿病，控制良好", "0.9", null, MemoryDedupAction.UPDATE, 1))));
        doThrow(new RuntimeException("audit down"))
                .when(memoryLogService).append(any(), any(), any(), any(), any(), any());

        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(vectorIndexer).refreshBackgroundValue(eq(old), eq("2型糖尿病，控制良好"),
                eq("2型糖尿病，控制良好"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("背景 action=skip：零写入、零审计、不动向量（值没变却刷新观察时间会让它跳到列表最前）")
    void skipBackgroundWritesNothing() {
        Memory old = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "2型糖尿病", "health", "我有2型糖尿病", "0.9",
                        null, MemoryDedupAction.SKIP, 1))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryMapper, never()).update(any(), any());
        verify(memoryLogService, never()).append(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(vectorIndexer);
    }

    @Test
    @DisplayName("背景满额时仍能改口：update 不增条数，不被满额逻辑误杀")
    void backgroundFullStillAllowsUpdate() {
        List<Memory> existing = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            existing.add(existing("old" + i, MemoryType.BACKGROUND_HEALTH, "背景" + i, "值" + i, "0.8"));
        }
        when(memoryMapper.selectList(any())).thenReturn(existing);
        // remembered 顺序 = 10 条背景，target=3 指的是 old2
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "糖尿病，控制良好", "health",
                        "我糖尿病控制得不错", "0.9", null, MemoryDedupAction.UPDATE, 3))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryLogService).append("old2", USER, MemoryOp.UPDATE,
                "值2", "糖尿病，控制良好", MemoryActor.EXTRACT);
        verify(vectorIndexer).refreshBackgroundValue(eq(existing.get(2)), eq("糖尿病，控制良好"),
                eq("糖尿病，控制良好"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("背景原地更新失败：本条改为新增落库，不丢草稿")
    void backgroundUpdateFailureFallsBackToNew() {
        Memory old = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "2型糖尿病，控制良好", "health",
                        "2型糖尿病，控制良好", "0.9", null, MemoryDedupAction.UPDATE, 1))));
        // 原地更新这一步炸了（DB 抖动）：这一批不能跟着丢，退回新增
        when(memoryMapper.update(any(), any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();

        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMvalue()).isEqualTo("2型糖尿病，控制良好");
        verify(vectorIndexer).index(inserted.getValue());
    }

    @Test
    @DisplayName("偏好 action=skip：零写入、零审计（值没变却刷新观察时间会让它跳到列表最前）")
    void skipPreferenceWritesNothing() {
        Memory old = existing("old1", MemoryType.PREFERENCE, "回答方式", "讲慢一点", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9",
                        MemoryDedupAction.SKIP, 1)), List.of()));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryMapper, never()).update(any(), any());
        verify(memoryLogService, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("偏好 action=new：照既有路径插入并记 add 审计")
    void newPreferenceInserts() {
        Memory old = existing("old1", MemoryType.PREFERENCE, "回答方式", "讲慢一点", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("称呼", "叫我老王", "叫我老王就行", "0.9", MemoryDedupAction.NEW, null)),
                List.of()));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMvalue()).isEqualTo("叫我老王");
        verify(memoryLogService).append("m1", USER, MemoryOp.ADD, null, "叫我老王", MemoryActor.EXTRACT);
    }

    @Test
    @DisplayName("target 指向另一类的行：降级为新增，不改那条背景（偏好与背景永远不是同一件事）")
    void updateTargetOfOtherTypeFallsBackToNew() {
        Memory background = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.9");
        when(memoryMapper.selectList(any())).thenReturn(List.of(background));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("语速", "语速要慢", "你讲话不要太慢了", "0.9",
                        MemoryDedupAction.UPDATE, 1)), List.of()));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper).insert(any(Memory.class));
        verify(memoryMapper, never()).update(any(), any());
        verify(memoryLogService).append("m1", USER, MemoryOp.ADD, null, "语速要慢", MemoryActor.EXTRACT);
    }

    @Test
    @DisplayName("target 的序号按「送进提示词的那份清单」算：偏好排在第 2 位时 target=2 改的是它")
    void targetFollowsPromptOrderNotPerTypeQuery() {
        Memory first = existing("b1", MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", "0.9");
        Memory second = existing("p1", MemoryType.PREFERENCE, "回答方式", "讲慢一点", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(first, second));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("语速", "语速要慢", "你讲话不要太慢了", "0.9",
                        MemoryDedupAction.UPDATE, 2)), List.of()));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryLogService).append("p1", USER, MemoryOp.UPDATE,
                "讲慢一点", "语速要慢", MemoryActor.EXTRACT);
        verify(memoryMapper, never()).insert(any(Memory.class));
    }

    @Test
    @DisplayName("判重分派过程中的异常：本批按「全部新增」落库，一条不丢")
    void dedupFailureFallsBackToNew() {
        Memory old = existing("old1", MemoryType.PREFERENCE, "回答方式", "讲慢一点", "0.8");
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("语速", "语速要慢", "你讲话不要太慢了", "0.9", MemoryDedupAction.UPDATE, 1)),
                List.of()));
        // 原地更新这一步炸了（DB 抖动）：这一批不能跟着丢，退回「全部当新增」
        when(memoryMapper.update(any(), any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();

        ArgumentCaptor<Memory> inserted = ArgumentCaptor.forClass(Memory.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMvalue()).isEqualTo("语速要慢");
    }

    @Test
    @DisplayName("开关关闭：不申领配额、不调模型（仍读对话以处理患者更正，那不是自动记忆）")
    void switchOffSkipsExtraction() {
        when(memorySettingService.isEnabled(USER)).thenReturn(false);

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(sessionActivityPort).recentDialogue(SESSION, MemoryExtractionService.DIALOGUE_LIMIT);
        verify(sessionActivityPort, never()).tryIncrementExtractCount(any(), anyInt());
        verify(memoryExtractor, never()).extract(any(), any());
    }

    @Test
    @DisplayName("患者更正「说错了我没有2型糖尿病」：旧背景标记失效、删向量、写审计")
    void retractsCorrectedBackground() {
        Memory row = new Memory();
        row.setId("m9");
        row.setUserId(USER);
        row.setType(MemoryType.BACKGROUND_HEALTH);
        row.setMkey("慢病");
        row.setMvalue("2型糖尿病");
        row.setStatus(MemoryStatus.ACTIVE);
        when(memoryMapper.selectList(any())).thenReturn(List.of(row));
        when(sessionActivityPort.recentDialogue(SESSION, MemoryExtractionService.DIALOGUE_LIMIT))
                .thenReturn(List.of(new DialogueTurn("user", "说错了我没有2型糖尿病", LocalDateTime.now())));
        when(memoryExtractor.extract(any(), any()))
                .thenReturn(new MemoryExtractionResult(List.of(), List.of(), List.of()));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        assertThat(row.getStatus()).isEqualTo(MemoryStatus.EXPIRED);
        assertThat(row.getValidTo()).isEqualTo(LocalDate.now());
        verify(memoryMapper).updateById(row);
        verify(vectorIndexer).delete("m9");
        verify(memoryLogService).append("m9", USER, MemoryOp.DELETE,
                "2型糖尿病", null, MemoryActor.EXTRACT);
    }

    @Test
    @DisplayName("规则预筛未命中：不申领配额、不调模型（「你好」不值得一次模型调用）")
    void prefilterMissSkipsModel() {
        when(sessionActivityPort.recentDialogue(SESSION, MemoryExtractionService.DIALOGUE_LIMIT))
                .thenReturn(List.of(new DialogueTurn("user", "你好", LocalDateTime.now())));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(sessionActivityPort, never()).tryIncrementExtractCount(any(), anyInt());
        verify(memoryExtractor, never()).extract(any(), any());
    }

    @Test
    @DisplayName("每会话最多 3 次：原子递增返回 false 时直接返回，不再调模型")
    void sessionQuotaExhaustedSkipsModel() {
        when(sessionActivityPort.tryIncrementExtractCount(SESSION, 3)).thenReturn(false);

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryExtractor, never()).extract(any(), any());
    }

    @Test
    @DisplayName("合法抽取落库：偏好/背景各按其类型写入，带原话、置信度、观察时间与 retrievalText，并写审计")
    void persistsValidResultWithAudit() {
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
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
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
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
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
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
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));
        when(memoryExtractor.merge(any(), any())).thenReturn(List.of(
                new MergedPreference("偏好0", "值0"),
                new MergedPreference("偏好1", "值1"),
                new MergedPreference("偏好2", "值2"),
                new MergedPreference("偏好3", "值3"),
                new MergedPreference("回答方式", "讲慢一点")));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PreferenceItem>> existingItem = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PreferenceItem>> incoming = ArgumentCaptor.forClass(List.class);
        verify(memoryExtractor).merge(existingItem.capture(), incoming.capture());
        // 新偏好确实进了合并输入，且带着 key——模型不再需要凭空编 key
        assertThat(incoming.getValue()).extracting(PreferenceItem::value).containsExactly("讲慢一点");
        assertThat(incoming.getValue()).extracting(PreferenceItem::key).containsExactly("回答方式");
        assertThat(existingItem.getValue()).extracting(PreferenceItem::key)
                .containsExactly("偏好0", "偏好1", "偏好2", "偏好3", "偏好4");
        assertThat(existingItem.getValue()).extracting(PreferenceItem::value)
                .containsExactly("值0", "值1", "值2", "值3", "值4");

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
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
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
        when(memoryExtractor.extract(any(), any())).thenReturn(
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
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(), List.of(background("慢病", "糖尿病", "health", "我有糖尿病", "0.9", null))));

        service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION));

        verify(memoryMapper, never()).insert(any(Memory.class));
        verify(memoryMapper, never()).deleteById(any(String.class));
    }

    @Test
    @DisplayName("模型/落库异常只记日志：不上抛、不重试（抽取是旁路）")
    void failuresOnlyWarn() {
        when(memoryExtractor.extract(any(), any())).thenThrow(new RuntimeException("upstream 502"));
        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();

        resetExtractor();
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));
        when(memoryMapper.insert(any(Memory.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.onTurnCompleted(new TurnCompletedEvent(USER, SESSION)))
                .doesNotThrowAnyException();
        // 不重试：第二次调用不会再调一次模型
        verify(memoryExtractor, times(1)).extract(any(), any());
    }

    @Test
    @DisplayName("空闲兜底跳过规则预筛：最后一句是「你好」也照常抽取（补上轮次入口漏掉的会话）")
    void idleExtractionSkipsPrefilter() {
        when(sessionActivityPort.recentDialogue(SESSION, MemoryExtractionService.DIALOGUE_LIMIT))
                .thenReturn(List.of(new DialogueTurn("user", "你好", LocalDateTime.now())));
        when(memoryExtractor.extract(any(), any())).thenReturn(new MemoryExtractionResult(
                List.of(pref("回答方式", "讲慢一点", "我希望你讲慢一点", "0.9")), List.of()));

        service.extractIdleSession(new IdleSession(SESSION, USER));

        verify(memoryExtractor).extract(any(), any());
        verify(memoryMapper).insert(any(Memory.class));
    }

    private void resetExtractor() {
        org.mockito.Mockito.reset(memoryExtractor);
    }

    private PreferenceDraft pref(String key, String value, String quote, String confidence) {
        return new PreferenceDraft(key, value, quote, new BigDecimal(confidence));
    }

    private PreferenceDraft pref(String key, String value, String quote, String confidence,
                                 MemoryDedupAction action, Integer target) {
        return new PreferenceDraft(key, value, quote, new BigDecimal(confidence), action, target);
    }

    private BackgroundDraft background(String key, String value, String type, String quote,
                                       String confidence, LocalDate validTo) {
        return new BackgroundDraft(key, value, type, quote, new BigDecimal(confidence), validTo);
    }

    private BackgroundDraft background(String key, String value, String type, String quote,
                                       String confidence, LocalDate validTo,
                                       MemoryDedupAction action, Integer target) {
        return new BackgroundDraft(key, value, type, quote, new BigDecimal(confidence), validTo,
                action, target);
    }

    private BackgroundDraft background(String key, String value, String type, String quote,
                                       String confidence, LocalDate validTo,
                                       String retrievalText, MemoryDedupAction action, Integer target) {
        return new BackgroundDraft(key, value, type, quote, new BigDecimal(confidence), validTo,
                retrievalText, action, target);
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
