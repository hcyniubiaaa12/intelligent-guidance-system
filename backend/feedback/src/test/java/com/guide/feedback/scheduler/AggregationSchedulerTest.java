package com.guide.feedback.scheduler;

import com.guide.chat.entity.GuideRecord;
import com.guide.feedback.service.ClusteringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聚合任务的驱动逻辑：逐条归桶、单条失败不拖垮整批。
 *
 * <p>为什么循环在调度器而不在 {@code ClusteringService}：归桶要逐条独立事务，
 * service 内部直调自己的方法会绕开 AOP 代理。这里断言的是循环本身的行为，
 * 事务生效与否靠「跨 bean 调用」这个结构保证（联调里实测过：一条记录 INSERT 报错后
 * 桶计数没有虚增）。
 */
@ExtendWith(MockitoExtension.class)
class AggregationSchedulerTest {

    @Mock
    private ClusteringService clusteringService;

    @InjectMocks
    private AggregationScheduler scheduler;

    @Test
    @DisplayName("没有待归桶记录：不逐条调用，返回 0")
    void emptyScanDoesNothing() {
        when(clusteringService.scanPending()).thenReturn(List.of());

        assertThat(scheduler.aggregateNow()).isZero();

        verify(clusteringService, never()).clusterOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("单条归桶抛异常：其余记录照常处理，失败的计不进成功数")
    void oneBadRecordDoesNotStopTheBatch() {
        GuideRecord bad = record("rec-bad");
        GuideRecord good = record("rec-good");
        when(clusteringService.scanPending()).thenReturn(List.of(bad, good));
        when(clusteringService.clusterOne(bad)).thenThrow(new RuntimeException("INSERT 撞了"));
        when(clusteringService.clusterOne(good)).thenReturn(true);

        assertThat(scheduler.aggregateNow()).isEqualTo(1);
    }

    @Test
    @DisplayName("抽不出主诉的记录（clusterOne 返回 false）不计入成功数")
    void skippedRecordIsNotCounted() {
        GuideRecord skipped = record("rec-skipped");
        when(clusteringService.scanPending()).thenReturn(List.of(skipped));
        when(clusteringService.clusterOne(skipped)).thenReturn(false);

        assertThat(scheduler.aggregateNow()).isZero();
    }

    private GuideRecord record(String id) {
        GuideRecord record = new GuideRecord();
        record.setId(id);
        return record;
    }
}
