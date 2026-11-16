package com.guide.memory.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.guide.auth.dto.HealthProfileDTO;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.service.HealthProfileService;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.memory.MpTableInfoTestSupport;
import com.guide.memory.dto.MemoryDTO;
import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import com.guide.memory.entity.ProfileCandidate;
import com.guide.memory.enums.CandidateField;
import com.guide.memory.enums.CandidateStatus;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.mapper.ProfileCandidateMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 档案候选的确认流（工单 09）：落候选、去重、拒绝不再提议、接受写档案、冲突、过期、按用户隔离。
 *
 * <p>只测服务对外可观察的行为：给定候选/档案，断言「写了什么、没写什么、下发什么」。
 * 档案列的追加细节由 {@code HealthProfileServiceTest.appendProfileFact} 覆盖，这里只钉调用契约。
 */
class ProfileCandidateServiceTest {

    private static final String USER = "u1";

    private ProfileCandidateMapper candidateMapper;
    private HealthProfileService healthProfileService;
    private MemoryLogService memoryLogService;
    private SysConfigService sysConfigService;
    private ProfileCandidateService service;

    @BeforeAll
    static void initTableInfo() {
        // expireOverdue / 状态流转走 LambdaUpdateWrapper.set(...)
        MpTableInfoTestSupport.init(ProfileCandidate.class);
    }

    @BeforeEach
    void setUp() {
        candidateMapper = mock(ProfileCandidateMapper.class);
        healthProfileService = mock(HealthProfileService.class);
        memoryLogService = mock(MemoryLogService.class);
        sysConfigService = mock(SysConfigService.class);
        service = new ProfileCandidateService(candidateMapper, healthProfileService,
                memoryLogService, sysConfigService);

        when(healthProfileService.getProfile(USER)).thenReturn(emptyProfile());
        when(candidateMapper.selectList(any())).thenReturn(List.of());
        // 状态迁移默认成功（条件更新影响 1 行）；过期用例会按需覆盖
        when(candidateMapper.update(isNull(), any())).thenReturn(1);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_CANDIDATE_EXPIRE_DAYS,
                SysConfigService.DEFAULT_MEMORY_CANDIDATE_EXPIRE_DAYS)).thenReturn(30);
    }

    // ---------- 落候选 ----------

    @Test
    @DisplayName("新事实落成 pending 候选：字段/值/月份/原话/置信度都写对")
    void recordsPendingCandidate() {
        service.recordCandidates(USER, List.of(
                new ProfileCandidateDraft("allergy", "青霉素", null, "我对青霉素过敏", new BigDecimal("0.9"))));

        ArgumentCaptor<ProfileCandidate> captor = ArgumentCaptor.forClass(ProfileCandidate.class);
        verify(candidateMapper).insert(captor.capture());
        ProfileCandidate saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER);
        assertThat(saved.getField()).isEqualTo(CandidateField.ALLERGY);
        assertThat(saved.getValue()).isEqualTo("青霉素");
        assertThat(saved.getOccurredOn()).isNull();
        assertThat(saved.getSourceQuote()).isEqualTo("我对青霉素过敏");
        assertThat(saved.getConfidence()).isEqualByComparingTo("0.9");
        assertThat(saved.getStatus()).isEqualTo(CandidateStatus.PENDING);
    }

    @Test
    @DisplayName("同值已在档案里：不产生候选（同一事实只存一处）")
    void skipsValueAlreadyInProfile() {
        HealthProfileDTO.ProfileVO profile = emptyProfile();
        profile.setAllergyTags(new ArrayList<>(List.of("青霉素")));
        when(healthProfileService.getProfile(USER)).thenReturn(profile);

        service.recordCandidates(USER, List.of(
                new ProfileCandidateDraft("allergy", "青霉素", null, "我对青霉素过敏", new BigDecimal("0.9"))));

        verify(candidateMapper, never()).insert(any(ProfileCandidate.class));
    }

    @Test
    @DisplayName("同值档案自由文本里已有：也不产生候选")
    void skipsValueAlreadyInProfileFreeText() {
        HealthProfileDTO.ProfileVO profile = emptyProfile();
        profile.setMedicationOther("二甲双胍");
        when(healthProfileService.getProfile(USER)).thenReturn(profile);

        service.recordCandidates(USER, List.of(
                new ProfileCandidateDraft("medication", "二甲双胍", "2024-03", "我长期吃二甲双胍",
                        new BigDecimal("0.9"))));

        verify(candidateMapper, never()).insert(any(ProfileCandidate.class));
    }

    @Test
    @DisplayName("同值曾被拒绝：不再重复提议")
    void skipsRejectedValueForever() {
        when(candidateMapper.selectList(any())).thenReturn(List.of(
                candidate("c-old", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.REJECTED)));

        service.recordCandidates(USER, List.of(
                new ProfileCandidateDraft("allergy", "青霉素", null, "我对青霉素过敏", new BigDecimal("0.9"))));

        verify(candidateMapper, never()).insert(any(ProfileCandidate.class));
    }

    @Test
    @DisplayName("同值已有 pending：不重复插入；已过期的不挡（30 天后允许重新提议）")
    void deduplicatesPendingOnly() {
        when(candidateMapper.selectList(any())).thenReturn(List.of(
                candidate("c-old", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.PENDING)));
        service.recordCandidates(USER, List.of(
                new ProfileCandidateDraft("allergy", "青霉素", null, "我对青霉素过敏", new BigDecimal("0.9"))));
        verify(candidateMapper, never()).insert(any(ProfileCandidate.class));

        org.mockito.Mockito.reset(candidateMapper);
        when(candidateMapper.selectList(any())).thenReturn(List.of(
                candidate("c-expired", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.EXPIRED)));
        service.recordCandidates(USER, List.of(
                new ProfileCandidateDraft("allergy", "青霉素", null, "我对青霉素过敏", new BigDecimal("0.9"))));
        verify(candidateMapper).insert(any(ProfileCandidate.class));
    }

    // ---------- 接受 / 拒绝 ----------

    @Test
    @DisplayName("接受过敏候选：写档案（过敏字段）+ 标 accepted + 写 promote 审计")
    void acceptsAllergyCandidate() {
        ProfileCandidate row = candidate("c1", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.PENDING);
        row.setSourceQuote("我对青霉素过敏");
        when(candidateMapper.selectById("c1")).thenReturn(row);

        service.accept(USER, "c1", null);

        verify(healthProfileService).appendProfileFact(USER, HealthTagType.ALLERGY, "青霉素", null);
        assertStatusUpdatedTo(CandidateStatus.ACCEPTED);
        verify(memoryLogService).append("c1", USER, MemoryOp.PROMOTE, "青霉素", "青霉素", MemoryActor.USER);
    }

    @Test
    @DisplayName("接受用药候选：字段映射为用药，发生月份作为开始月份传下去")
    void acceptsMedicationCandidate() {
        ProfileCandidate row = candidate("c2", USER, CandidateField.MEDICATION, "二甲双胍",
                CandidateStatus.PENDING);
        row.setOccurredOn("2024-03");
        when(candidateMapper.selectById("c2")).thenReturn(row);
        when(healthProfileService.getProfile(USER)).thenReturn(emptyProfile());

        service.accept(USER, "c2", null);

        verify(healthProfileService).appendProfileFact(USER, HealthTagType.MEDICATION, "二甲双胍", "2024-03");
        assertStatusUpdatedTo(CandidateStatus.ACCEPTED);
    }

    @Test
    @DisplayName("接受手术候选：字段映射为手术，发生月份原样传下去")
    void acceptsSurgeryCandidateWithMonth() {
        ProfileCandidate row = candidate("c3", USER, CandidateField.SURGERY, "阑尾切除术",
                CandidateStatus.PENDING);
        row.setOccurredOn("2023-10");
        when(candidateMapper.selectById("c3")).thenReturn(row);

        service.accept(USER, "c3", null);

        verify(healthProfileService).appendProfileFact(USER, HealthTagType.SURGERY, "阑尾切除术", "2023-10");
        assertStatusUpdatedTo(CandidateStatus.ACCEPTED);
    }

    @Test
    @DisplayName("先改再收：用患者改后的值写档案，审计同时记录原值与新值")
    void acceptsWithEditedValue() {
        when(candidateMapper.selectById("c1")).thenReturn(
                candidate("c1", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.PENDING));

        service.accept(USER, "c1", " 青霉素类 ");

        verify(healthProfileService).appendProfileFact(USER, HealthTagType.ALLERGY, "青霉素类", null);
        verify(memoryLogService).append("c1", USER, MemoryOp.PROMOTE, "青霉素", "青霉素类", MemoryActor.USER);
    }

    @Test
    @DisplayName("改后的值为空 / 超 60 字：拒绝，不写档案、不改状态")
    void rejectsInvalidEditedValue() {
        when(candidateMapper.selectById("c1")).thenReturn(
                candidate("c1", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.PENDING));

        assertThatThrownBy(() -> service.accept(USER, "c1", "   "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThatThrownBy(() -> service.accept(USER, "c1", "青".repeat(61)))
                .isInstanceOf(BizException.class);
        verify(healthProfileService, never()).appendProfileFact(anyString(), any(), anyString(), any());
        verify(candidateMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("拒绝：只标 rejected，不写档案")
    void rejectsCandidateWithoutWritingProfile() {
        when(candidateMapper.selectById("c1")).thenReturn(
                candidate("c1", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.PENDING));

        service.reject(USER, "c1");

        verify(healthProfileService, never()).appendProfileFact(anyString(), any(), anyString(), any());
        assertStatusUpdatedTo(CandidateStatus.REJECTED);
    }

    @Test
    @DisplayName("越权 / 非 pending：一律按「待确认项不存在」返回，不泄露存在性、不写档案")
    void rejectsForeignOrNonPendingCandidate() {
        when(candidateMapper.selectById("mine")).thenReturn(
                candidate("mine", "u2", CandidateField.ALLERGY, "青霉素", CandidateStatus.PENDING));
        when(candidateMapper.selectById("done")).thenReturn(
                candidate("done", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.ACCEPTED));

        assertThatThrownBy(() -> service.accept(USER, "mine", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.MEMORY_CANDIDATE_NOT_FOUND.getCode());
        assertThatThrownBy(() -> service.accept(USER, "done", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.MEMORY_CANDIDATE_NOT_FOUND.getCode());
        assertThatThrownBy(() -> service.reject(USER, "missing"))
                .isInstanceOf(BizException.class);

        verify(healthProfileService, never()).appendProfileFact(anyString(), any(), anyString(), any());
        verify(candidateMapper, never()).update(any(), any());
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("列表只下发本人的 pending；冲突项置顶，其余按创建时间降序")
    void listsPendingConflictFirstForCurrentUser() {
        HealthProfileDTO.ProfileVO profile = emptyProfile();
        profile.setAllergyOther("无过敏史");
        when(healthProfileService.getProfile(USER)).thenReturn(profile);
        when(candidateMapper.selectList(any())).thenReturn(List.of(
                candidate("old-normal", USER, CandidateField.MEDICATION, "二甲双胍", CandidateStatus.PENDING,
                        LocalDateTime.of(2026, 11, 10, 9, 0)),
                candidate("new-normal", USER, CandidateField.SURGERY, "阑尾切除术", CandidateStatus.PENDING,
                        LocalDateTime.of(2026, 11, 12, 9, 0)),
                candidate("conflict", USER, CandidateField.ALLERGY, "青霉素", CandidateStatus.PENDING,
                        LocalDateTime.of(2026, 11, 9, 9, 0)),
                candidate("other-user", "u2", CandidateField.ALLERGY, "海鲜", CandidateStatus.PENDING,
                        LocalDateTime.of(2026, 11, 13, 9, 0)),
                candidate("not-pending", USER, CandidateField.ALLERGY, "花粉", CandidateStatus.REJECTED,
                        LocalDateTime.of(2026, 11, 13, 9, 0))));

        List<MemoryDTO.CandidateItemVO> items = service.list(USER);

        assertThat(items).extracting(MemoryDTO.CandidateItemVO::getId)
                .containsExactly("conflict", "new-normal", "old-normal");
        assertThat(items.get(0).isConflict()).isTrue();
        assertThat(items.get(0).getField()).isEqualTo("allergy");
        assertThat(items.get(0).getFieldLabel()).isEqualTo("过敏史");
        assertThat(items.get(0).getSourceQuote()).isEqualTo("我对青霉素过敏");
        assertThat(items.get(1).isConflict()).isFalse();
    }

    // ---------- 过期 ----------

    @Test
    @DisplayName("30 天未处理：把 pending 超期项标 expired，返回影响行数")
    void expiresPendingOlderThanConfiguredDays() {
        when(candidateMapper.update(isNull(), any())).thenReturn(2);
        LocalDateTime now = LocalDateTime.of(2026, 11, 14, 12, 0);

        int expired = service.expireOverdue(now);

        assertThat(expired).isEqualTo(2);
        LambdaUpdateWrapper<ProfileCandidate> wrapper = capturedCandidateUpdate();
        assertThat(wrapper.getSqlSet()).contains("status");
        assertThat(wrapper.getSqlSegment()).contains("status", "created_at");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(CandidateStatus.EXPIRED, now.minusDays(30));
    }

    @Test
    @DisplayName("过期天数从受管配置读：改成 7 天即按 7 天算，不写死")
    void expiryDaysComeFromConfig() {
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_CANDIDATE_EXPIRE_DAYS,
                SysConfigService.DEFAULT_MEMORY_CANDIDATE_EXPIRE_DAYS)).thenReturn(7);
        LocalDateTime now = LocalDateTime.of(2026, 11, 14, 12, 0);

        service.expireOverdue(now);

        assertThat(capturedCandidateUpdate().getParamNameValuePairs().values())
                .contains(now.minusDays(7));
    }

    // ---------- 夹具 ----------

    private void assertStatusUpdatedTo(CandidateStatus expected) {
        LambdaUpdateWrapper<ProfileCandidate> wrapper = capturedCandidateUpdate();
        assertThat(wrapper.getSqlSet()).contains("status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(expected);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ProfileCandidate> capturedCandidateUpdate() {
        ArgumentCaptor<Wrapper<ProfileCandidate>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(candidateMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ProfileCandidate> wrapper =
                (LambdaUpdateWrapper<ProfileCandidate>) captor.getValue();
        // where 侧参数由 getSqlSegment 惰性生成：不先物化，param 表里只看得到 SET 的值
        wrapper.getSqlSet();
        wrapper.getSqlSegment();
        return wrapper;
    }

    private ProfileCandidate candidate(String id, String userId, CandidateField field, String value,
                                       CandidateStatus status) {
        return candidate(id, userId, field, value, status, LocalDateTime.now());
    }

    private ProfileCandidate candidate(String id, String userId, CandidateField field, String value,
                                       CandidateStatus status, LocalDateTime createdAt) {
        ProfileCandidate row = new ProfileCandidate();
        row.setId(id);
        row.setUserId(userId);
        row.setField(field);
        row.setValue(value);
        row.setSourceQuote("我对青霉素过敏");
        row.setStatus(status);
        row.setCreatedAt(createdAt);
        row.setDeleted(0);
        return row;
    }

    private HealthProfileDTO.ProfileVO emptyProfile() {
        HealthProfileDTO.ProfileVO vo = new HealthProfileDTO.ProfileVO();
        vo.setAllergyTags(new ArrayList<>());
        vo.setMedicationEntries(new ArrayList<>());
        vo.setSurgeryEntries(new ArrayList<>());
        return vo;
    }
}
