package com.guide.feedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.exception.BizException;
import com.guide.feedback.entity.RootCause;
import com.guide.feedback.mapper.RootCauseLogMapper;
import com.guide.feedback.mapper.RootCauseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 根因：字典外的 key 拒绝、记录不存在即报错、修改追加审计并记下改前的值。 */
@ExtendWith(MockitoExtension.class)
class RootCauseServiceTest {

    @Mock
    private RootCauseMapper rootCauseMapper;
    @Mock
    private RootCauseLogMapper rootCauseLogMapper;
    @Mock
    private GuideRecordMapper guideRecordMapper;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RootCauseService rootCauseService;

    @Test
    @DisplayName("字典外的 key 拒绝，不写库")
    void unknownKeyRejected() {
        when(guideRecordMapper.selectById("rec-1")).thenReturn(record("rec-1"));

        assertThatThrownBy(() -> rootCauseService.updateSingle(
                "rec-1", List.of("symptom_ambiguous"), "admin-1"))
                .isInstanceOf(BizException.class);

        verify(rootCauseMapper, never()).insert(any(RootCause.class));
    }

    @Test
    @DisplayName("逐条覆盖：记录不存在时报 RECORD_NOT_FOUND")
    void missingRecordRejected() {
        when(guideRecordMapper.selectById("rec-1")).thenReturn(null);

        assertThatThrownBy(() -> rootCauseService.updateSingle("rec-1", List.of(), "admin-1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("去重并按字典序归一：重复 key 只留一个")
    void duplicateKeysDeduped() {
        when(guideRecordMapper.selectById("rec-1")).thenReturn(record("rec-1"));
        when(rootCauseMapper.selectOne(any())).thenReturn(null);

        rootCauseService.updateSingle("rec-1", List.of("chunk_broken", "chunk_broken"), "admin-1");

        ArgumentCaptor<RootCause> saved = ArgumentCaptor.forClass(RootCause.class);
        verify(rootCauseMapper).insert(saved.capture());
        assertThat(saved.getValue().getCauses()).isEqualTo("[\"chunk_broken\"]");
    }

    @Test
    @DisplayName("更新已有根因时，审计日志记下修改前的值")
    void updateKeepsBefore() {
        RootCause existing = new RootCause();
        existing.setRecordId("rec-1");
        existing.setCauses("[\"parse_missed\"]");
        when(guideRecordMapper.selectById("rec-1")).thenReturn(record("rec-1"));
        when(rootCauseMapper.selectOne(any())).thenReturn(existing);

        rootCauseService.updateSingle("rec-1", List.of("patient_wrong"), "admin-1");

        ArgumentCaptor<com.guide.feedback.entity.RootCauseLog> audit =
                ArgumentCaptor.forClass(com.guide.feedback.entity.RootCauseLog.class);
        verify(rootCauseLogMapper).insert(audit.capture());
        assertThat(audit.getValue().getCausesBefore()).isEqualTo("[\"parse_missed\"]");
        assertThat(audit.getValue().getCausesAfter()).isEqualTo("[\"patient_wrong\"]");
        assertThat(audit.getValue().getUpdatedBy()).isEqualTo("admin-1");
    }

    private GuideRecord record(String id) {
        GuideRecord record = new GuideRecord();
        record.setId(id);
        return record;
    }
}