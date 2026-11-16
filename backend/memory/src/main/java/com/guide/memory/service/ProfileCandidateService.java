package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.dto.HealthProfileDTO;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.service.HealthProfileService;
import com.guide.auth.service.SysConfigService;
import com.guide.auth.support.MedicationEntry;
import com.guide.auth.support.SurgeryEntry;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.memory.dto.MemoryDTO;
import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import com.guide.memory.entity.ProfileCandidate;
import com.guide.memory.enums.CandidateField;
import com.guide.memory.enums.CandidateStatus;
import com.guide.memory.enums.MemoryActor;
import com.guide.memory.enums.MemoryOp;
import com.guide.memory.mapper.ProfileCandidateMapper;
import com.guide.memory.support.CandidateConflict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 档案候选的确认流（工单 09）：落候选 → 患者接受/拒绝 → 写档案或标记 → 过期清理。
 *
 * <p><b>它不属于记忆</b>：候选成功落库也不会进 {@code user_memory}、不产生向量、不进 prompt、
 * 不产生预警。它是「二手信息」，只有患者接受后才由
 * {@link HealthProfileService#appendProfileFact} 写进正式档案——这是链路唯一的安全阀。
 *
 * <p>四条口径：
 * <ul>
 *   <li><b>同一事实只存一处</b>：同值已在档案里不产生候选；已拒绝的值不再提议；
 *       已有 pending 的不重复插入；已过期的不挡（30 天后允许重新提议）。</li>
 *   <li><b>接受前可改值</b>：用患者改后的值写档案，审计同时记录原值与新值。</li>
 *   <li><b>冲突只算不存</b>：档案同类别自由文本出现明确否定、且没正面提到候选值时，
 *       列表下发 conflict=true 并置顶；档案随时可改，存下来的冲突位会立刻过时。</li>
 *   <li><b>越权按不存在</b>：别人的候选与不存在的候选给同一个错误码，不泄露存在性。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileCandidateService {

    /** 与 user_memory 值列宽、候选值输入框上限一致 */
    static final int VALUE_MAX_CHARS = 60;

    private final ProfileCandidateMapper candidateMapper;
    private final HealthProfileService healthProfileService;
    private final MemoryLogService memoryLogService;
    private final SysConfigService sysConfigService;

    // ---------------- 落候选 ----------------

    /**
     * 把校验后的候选草稿写入「待确认」。逐条判定，任一条失败不连累其它条。
     *
     * <p>候选落库失败只记 WARN：抽取是旁路，不能影响对话；患者下次还可以再被提议。
     */
    public void recordCandidates(String userId, List<ProfileCandidateDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        try {
            HealthProfileDTO.ProfileVO profile = healthProfileService.getProfile(userId);
            for (ProfileCandidateDraft draft : drafts) {
                CandidateField field = CandidateField.fromCode(draft.field());
                if (field == null) {
                    continue;
                }
                if (alreadyInProfile(profile, field, draft.value())) {
                    log.debug("候选同值已在档案里，跳过：userId={} field={} value={}",
                            userId, field.getCode(), draft.value());
                    continue;
                }
                List<ProfileCandidate> prior = priorProposals(userId, field, draft.value());
                if (prior.stream().anyMatch(row -> row.getStatus() == CandidateStatus.REJECTED)) {
                    log.debug("候选同值曾被拒绝，不再提议：userId={} field={} value={}",
                            userId, field.getCode(), draft.value());
                    continue;
                }
                if (prior.stream().anyMatch(row -> row.getStatus() == CandidateStatus.PENDING)) {
                    continue;
                }
                if (CandidateConflict.detect(field, draft.value(), otherText(profile, field))) {
                    log.warn("档案候选与档案否定表述冲突，仍进待确认并标记：userId={} field={} value={}",
                            userId, field.getCode(), draft.value());
                }
                insert(userId, field, draft);
            }
        } catch (RuntimeException e) {
            log.warn("档案候选落库失败（丢弃本批）：userId={}｜原因={}", userId, e.getMessage());
        }
    }

    private void insert(String userId, CandidateField field, ProfileCandidateDraft draft) {
        ProfileCandidate row = new ProfileCandidate();
        row.setUserId(userId);
        row.setField(field);
        row.setValue(draft.value());
        row.setOccurredOn(draft.occurredOn());
        row.setSourceQuote(draft.quote());
        row.setConfidence(draft.confidence());
        row.setStatus(CandidateStatus.PENDING);
        candidateMapper.insert(row);
    }

    /** 「同值已在档案里」：标签 / 条目按值精确比对，自由文本按分词包含比对 */
    private boolean alreadyInProfile(HealthProfileDTO.ProfileVO profile, CandidateField field, String value) {
        if (profile == null || value == null || value.isBlank()) {
            return false;
        }
        String fact = value.trim();
        return switch (field) {
            case ALLERGY -> containsValue(profile.getAllergyTags(), fact)
                    || textContains(profile.getAllergyOther(), fact);
            case MEDICATION -> entriesContain(profile.getMedicationEntries(), fact)
                    || textContains(profile.getMedicationOther(), fact);
            case SURGERY -> entriesContain(profile.getSurgeryEntries(), fact)
                    || textContains(profile.getSurgeryOther(), fact);
        };
    }

    private boolean containsValue(List<String> values, String value) {
        return values != null && values.stream().anyMatch(value::equals);
    }

    private boolean entriesContain(List<?> entries, String value) {
        if (entries == null) {
            return false;
        }
        for (Object entry : entries) {
            if (entry instanceof MedicationEntry medication && value.equals(medication.value())) {
                return true;
            }
            if (entry instanceof SurgeryEntry surgery && value.equals(surgery.value())) {
                return true;
            }
        }
        return false;
    }

    private boolean textContains(String text, String value) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String part : text.split("[、,，;；]")) {
            if (part.trim().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同一用户 + 字段 + 值已有的「尚未失效」提议（pending / rejected）。
     * 一次查询取回两种状态：拒绝过的不再提议、还挂着的也不重复插入；
     * 已过的期不在过滤里——30 天后同一事实允许重新被提议。
     */
    private List<ProfileCandidate> priorProposals(String userId, CandidateField field, String value) {
        List<ProfileCandidate> rows = candidateMapper.selectList(Wrappers.<ProfileCandidate>lambdaQuery()
                .eq(ProfileCandidate::getUserId, userId)
                .eq(ProfileCandidate::getField, field)
                .eq(ProfileCandidate::getValue, value)
                .in(ProfileCandidate::getStatus,
                        List.of(CandidateStatus.PENDING, CandidateStatus.REJECTED)));
        return rows == null ? List.of() : rows;
    }

    // ---------------- 列表 ----------------

    /**
     * 「待确认」列表：只含当前用户的 pending 项；冲突项置顶，其余按创建时间降序。
     *
     * <p>服务侧显式过滤用户与状态——SQL 是第一道闸，防止上层查询被改坏后把别人的候选下发出去。
     */
    public List<MemoryDTO.CandidateItemVO> list(String userId) {
        List<ProfileCandidate> rows = candidateMapper.selectList(Wrappers.<ProfileCandidate>lambdaQuery()
                .eq(ProfileCandidate::getUserId, userId)
                .eq(ProfileCandidate::getStatus, CandidateStatus.PENDING)
                .orderByDesc(ProfileCandidate::getCreatedAt));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // SQL 已按 createdAt 降序；服务侧再兜一次——纯单测与换实现时组内顺序仍成立。
        // 复制一份再排序：mock / 上层可能返回不可变列表。
        List<ProfileCandidate> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(ProfileCandidate::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        HealthProfileDTO.ProfileVO profile = healthProfileService.getProfile(userId);
        List<MemoryDTO.CandidateItemVO> items = new ArrayList<>();
        for (ProfileCandidate row : ordered) {
            if (!visible(row, userId)) {
                continue;
            }
            MemoryDTO.CandidateItemVO item = new MemoryDTO.CandidateItemVO();
            item.setId(row.getId());
            item.setField(row.getField() == null ? null : row.getField().getCode());
            item.setFieldLabel(row.getField() == null ? null : row.getField().getLabel());
            item.setValue(row.getValue());
            item.setOccurredOn(row.getOccurredOn());
            item.setSourceQuote(row.getSourceQuote());
            item.setStatus(row.getStatus() == null ? null : row.getStatus().getCode());
            item.setConflict(CandidateConflict.detect(row.getField(), row.getValue(),
                    otherText(profile, row.getField())));
            items.add(item);
        }
        // List.sort 是稳定排序：只按「是否冲突」分组，组内保持 SQL 的 createdAt 降序
        items.sort(Comparator.comparing(MemoryDTO.CandidateItemVO::isConflict).reversed());
        return items;
    }

    private boolean visible(ProfileCandidate row, String userId) {
        return row != null
                && userId != null
                && userId.equals(row.getUserId())
                && (row.getDeleted() == null || row.getDeleted() == 0)
                && row.getStatus() == CandidateStatus.PENDING;
    }

    // ---------------- 接受 / 拒绝 ----------------

    /**
     * 接受一条候选：先按患者改后的值写档案，再标 accepted，最后写 promote 审计。
     *
     * <p>顺序是「先改值再收」：档案写入失败时候选仍是 pending，患者可以重试或拒绝，
     * 不会出现「状态已接受、档案却没写进去」的假成功。
     */
    @Transactional
    public void accept(String userId, String candidateId, String editedValue) {
        ProfileCandidate row = requirePending(userId, candidateId);
        String value = normalizeValue(editedValue == null ? row.getValue() : editedValue);

        healthProfileService.appendProfileFact(userId, toTagType(row.getField()), value, row.getOccurredOn());

        // 状态迁移必须真的发生（并发下可能已被另一处拒绝/接受）：没迁成要回滚档案写入
        if (updateStatus(userId, candidateId, CandidateStatus.ACCEPTED) == 0) {
            throw new BizException(ErrorCode.MEMORY_CANDIDATE_NOT_FOUND);
        }
        // memory_id 列在这里存候选 id：审计的是「这条候选被提升成了档案事实」，不另开表
        memoryLogService.append(candidateId, userId, MemoryOp.PROMOTE,
                row.getValue(), value, MemoryActor.USER);
    }

    /** 拒绝：只标 rejected，不写档案；同一值以后不再提议（落候选时按 status 过滤） */
    @Transactional
    public void reject(String userId, String candidateId) {
        requirePending(userId, candidateId);
        if (updateStatus(userId, candidateId, CandidateStatus.REJECTED) == 0) {
            throw new BizException(ErrorCode.MEMORY_CANDIDATE_NOT_FOUND);
        }
    }

    private ProfileCandidate requirePending(String userId, String candidateId) {
        ProfileCandidate row = (candidateId == null || candidateId.isBlank())
                ? null : candidateMapper.selectById(candidateId);
        if (row == null
                || userId == null
                || !userId.equals(row.getUserId())
                || (row.getDeleted() != null && row.getDeleted() != 0)
                || row.getStatus() != CandidateStatus.PENDING) {
            throw new BizException(ErrorCode.MEMORY_CANDIDATE_NOT_FOUND);
        }
        return row;
    }

    private String normalizeValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "候选内容不能为空");
        }
        if (value.length() > VALUE_MAX_CHARS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "候选内容不能超过 " + VALUE_MAX_CHARS + " 字");
        }
        return value;
    }

    private HealthTagType toTagType(CandidateField field) {
        return switch (field) {
            case ALLERGY -> HealthTagType.ALLERGY;
            case MEDICATION -> HealthTagType.MEDICATION;
            case SURGERY -> HealthTagType.SURGERY;
        };
    }

    /** 条件更新（只从 pending 迁移）：返回影响行数，0 表示并发下已不是 pending */
    private int updateStatus(String userId, String candidateId, CandidateStatus status) {
        return candidateMapper.update(null, Wrappers.<ProfileCandidate>lambdaUpdate()
                .set(ProfileCandidate::getStatus, status)
                .set(ProfileCandidate::getUpdatedAt, LocalDateTime.now())
                .eq(ProfileCandidate::getId, candidateId)
                .eq(ProfileCandidate::getUserId, userId)
                .eq(ProfileCandidate::getStatus, CandidateStatus.PENDING));
    }

    // ---------------- 过期 ----------------

    /**
     * 把超过 {@code memory.candidate.expire.days} 天未处理的 pending 项标 expired。
     *
     * <p>「过期」不是删除：行还在，只是不再展示、不再挡住重新提议（同一事实下次还能被提出来）。
     *
     * @param now 可注入当前时间（测试用固定时刻，避免断言随运行日漂移）
     * @return 本次标记的行数
     */
    @Transactional
    public int expireOverdue(LocalDateTime now) {
        int days = sysConfigService.getInt(SysConfigService.KEY_MEMORY_CANDIDATE_EXPIRE_DAYS,
                SysConfigService.DEFAULT_MEMORY_CANDIDATE_EXPIRE_DAYS);
        LocalDateTime cutoff = now.minusDays(Math.max(0, days));
        int expired = candidateMapper.update(null, Wrappers.<ProfileCandidate>lambdaUpdate()
                .set(ProfileCandidate::getStatus, CandidateStatus.EXPIRED)
                .set(ProfileCandidate::getUpdatedAt, now)
                .eq(ProfileCandidate::getStatus, CandidateStatus.PENDING)
                .lt(ProfileCandidate::getCreatedAt, cutoff));
        if (expired > 0) {
            log.info("档案候选过期清理：{} 条超过 {} 天未处理，已标记 expired", expired, days);
        }
        return expired;
    }

    /** 按字段取档案里**同类别**的自由文本（冲突判定只看它） */
    private String otherText(HealthProfileDTO.ProfileVO profile, CandidateField field) {
        if (profile == null || field == null) {
            return null;
        }
        return switch (field) {
            case ALLERGY -> profile.getAllergyOther();
            case MEDICATION -> profile.getMedicationOther();
            case SURGERY -> profile.getSurgeryOther();
        };
    }
}
