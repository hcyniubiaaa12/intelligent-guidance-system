package com.guide.feedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.exception.BizException;
import com.guide.feedback.entity.ClusterBucket;
import com.guide.feedback.entity.RootCause;
import com.guide.feedback.mapper.RootCauseLogMapper;
import com.guide.feedback.mapper.RootCauseMapper;
import org.junit.jupiter.api.BeforeEach;
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

/** 根因：桶级套用按桶取记录，字典外的 key 拒绝，修改追加审计日志。 */
@ExtendWith(MockitoExtension.class)
class RootCauseServiceTest {

    @Mock
    private RootCauseMapper rootCauseMapper;
    @Mock
    private RootCauseLogMapper rootCauseLogMapper;
    @Mock
    private GuideRecordMapper guideRecordMapper;
    @Mock
    private ReviewService reviewService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RootCauseService rootCauseService;

    @BeforeEach
    void setUp() {
        // ObjectMapper 是 @Spy，@InjectMocks 会把它注入
    }

    @Test
    @DisplayName("桶级套用写入桶内全部记录，并各留一条审计")
    void applyWritesEveryMember() {
        GuideRecord first = record("rec-1");
        GuideRecord second = record("rec-2");
        when(reviewService.requireBucket("bucket-1")).thenReturn(new ClusterBucket());
        when(reviewService.members("bucket-1")).thenReturn(List.of(first, second));
        when(rootCauseMapper.selectOne(any())).thenReturn(null);

        int affected = rootCauseService.applyToBucket("bucket-1",
                List.of("chunk_broken", "chunk_broken", "retrieval_fail"), "admin-1");

        assertThat(affected).isEqualTo(2);
        ArgumentCaptor<RootCause> causes = ArgumentCaptor.forClass(RootCause.class);
        verify(rootCauseMapper, org.mockito.Mockito.times(2)).insert(causes.capture());
        assertThat(causes.getAllValues())
                .allSatisfy(cause -> assertThat(cause.getCauses())
                        .isEqualTo("[\"chunk_broken\",\"retrieval_fail\"]"))
                .extracting(RootCause::getRecordId)
                .containsExactly("rec-1", "rec-2");
        verify(rootCauseLogMapper, org.mockito.Mockito.times(2)).insert(any());
    }

    @Test
    @DisplayName("字典外的 key 拒绝，不写库")
    void unknownKeyRejected() {
        assertThatThrownBy(() -> rootCauseService.applyToBucket("bucket-1",
                List.of("symptom_ambiguous"), "admin-1"))
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
