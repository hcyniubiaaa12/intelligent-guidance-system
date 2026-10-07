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
 * <p><b>逐条标注</b>：归因写在每条导诊记录（{@code root_cause}）上，审核时展开代表样本逐条设置。
 * 归因与 approve / 驳回是两个独立动作，互不代劳：驳回的桶也要归因（「患者挂错」要从看板 miss
 * 口径里排除），标了归因不改桶状态。每次修改追加一条 {@code root_cause_log}，不覆盖。
 *
 * <p>为什么没有「桶级一键套用」（2026-07-11 移除）：它会把桶内**每条**记录整份覆盖成同一组根因，
 * 包括管理员已经逐条改过的——同方向一个桶里的样本未必是同一个成因（例如"主诉太含糊"与
 * "患者描述与推荐科室不符"可以同时存在于一个方向桶里），一刀切等于用统计口径覆盖事实判断。
 * 归因本来就是给人判断的，宁可多花两次点击。
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
    private final ObjectMapper objectMapper;

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
