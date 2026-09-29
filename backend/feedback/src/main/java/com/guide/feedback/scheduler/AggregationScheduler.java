package com.guide.feedback.scheduler;

import com.guide.feedback.service.ClusteringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合归桶定时任务（链路 C）：每小时整点扫描全错记录。
 *
 * <p>单机部署，进程内互斥就够了——上一次还没跑完时，后来的触发直接跳过，
 * 最多再等一小时。不把运行标记写进 {@code sys_config}：那张表是业务口径，
 * 而且它有 60 秒缓存，写进去也读不到刚写的值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregationScheduler {

    private final ClusteringService clusteringService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "0 0 * * * ?")
    public void aggregate() {
        if (!running.compareAndSet(false, true)) {
            log.warn("聚合归桶任务已在运行，跳过本次触发");
            return;
        }
        try {
            log.info("聚合归桶任务开始");
            int processed = clusteringService.aggregateNewRecords();
            log.info("聚合归桶任务完成，处理 {} 条记录", processed);
        } catch (RuntimeException e) {
            log.error("聚合归桶任务失败", e);
        } finally {
            running.set(false);
        }
    }
}
