package com.guide.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.feedback.entity.RootCause;
import com.guide.feedback.entity.RootCauseLog;
import com.guide.feedback.enums.RootCauseKey;
import com.guide.feedback.mapper.RootCauseLogMapper;
import com.guide.feedback.mapper.RootCauseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 根因归因（链路 C）。
 *
 * <p>桶级套用写入桶内每条记录，展开后可逐条覆盖。归因与 approve / 驳回是两个独立动作，
 * 互不代劳：驳回的桶也要归因（「患者挂错」要从看板 miss 口径里排除），标了归因不改桶状态。
 * 每次修改追加一条 {@code root_cause_log}，不覆盖。
 *
 * <p>字典外的 key 直接拒绝。存量值仍不校验（看板对字典外 key 原样显示），
 * 但新写入的必须在 {@link RootCauseKey} 里——否则看板口径会静默漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RootCauseService {

    private final RootCauseMapper rootCauseMapper;
    private final RootCauseLogMapper rootCauseLogMapper;
    private final GuideRecordMapper guideRecordMapper;
    private final ReviewService reviewService;
    private final ObjectMapper objectMapper;

    /**
     * 桶级套用：写入该桶的全部记录。
     *
     * @return 影响的记录数
     */
    @Transactional(rollbackFor = Exception.class)
    public int applyToBucket(String bucketId, List<String> causes, String updatedBy) {
        // 先校验字典：空桶也要拒绝非法 key，不能因为没有记录可写就把错误吞掉
        String causesJson = toJson(normalize(causes));
        if (reviewService.requireBucket(bucketId) == null) {
            throw new BizException(ErrorCode.BUCKET_NOT_FOUND);
        }
        List<GuideRecord> records = reviewService.members(bucketId);
        if (records.isEmpty()) {
            log.warn("桶 {} 内无记录，跳过根因套用", bucketId);
            return 0;
        }
        for (GuideRecord record : records) {
            upsert(record.getId(), causesJson, updatedBy);
        }
        log.info("桶 {} 套用根因完成，影响 {} 条记录", bucketId, records.size());
        return records.size();
    }

    /** 逐条覆盖 */
    @Transactional(rollbackFor = Exception.class)
    public void updateSingle(String recordId, List<String> causes, String updatedBy) {
        GuideRecord record = guideRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ErrorCode.RECORD_NOT_FOUND);
        }
        upsert(recordId, toJson(normalize(causes)), updatedBy);
    }

    /** 去重、去空白，并校验每一项都在字典里 */
    List<String> normalize(List<String> causes) {
        if (causes == null || causes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String cause : causes) {
            if (cause == null || cause.isBlank()) {
                continue;
            }
            String key = cause.trim().toLowerCase();
            if (RootCauseKey.fromKey(key).isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "未知的根因：" + key);
            }
            unique.add(key);
        }
        return new ArrayList<>(unique);
    }

    private void upsert(String recordId, String causesJson, String updatedBy) {
        RootCause existing = rootCauseMapper.selectOne(Wrappers.<RootCause>lambdaQuery()
                .eq(RootCause::getRecordId, recordId)
                .last("LIMIT 1"));
        String before = existing == null ? null : existing.getCauses();
        if (existing == null) {
            RootCause created = new RootCause();
            created.setRecordId(recordId);
            created.setCauses(causesJson);
            created.setUpdatedBy(updatedBy);
            rootCauseMapper.insert(created);
        } else {
            existing.setCauses(causesJson);
            existing.setUpdatedBy(updatedBy);
            rootCauseMapper.updateById(existing);
        }
        RootCauseLog audit = new RootCauseLog();
        audit.setRecordId(recordId);
        audit.setCausesBefore(before);
        audit.setCausesAfter(causesJson);
        audit.setUpdatedBy(updatedBy);
        rootCauseLogMapper.insert(audit);
    }

    private String toJson(List<String> causes) {
        try {
            return objectMapper.writeValueAsString(causes);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "根因序列化失败");
        }
    }
}
