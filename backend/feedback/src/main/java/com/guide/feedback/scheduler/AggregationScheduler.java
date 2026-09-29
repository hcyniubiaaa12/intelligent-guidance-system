package com.guide.feedback.scheduler;

import com.guide.chat.entity.GuideRecord;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.feedback.service.ClusteringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合归桶定时任务（链路 C）：每小时整点扫描全错记录。
 *
 * <p>单机部署，进程内互斥就够了——上一次还没跑完时，后来的触发直接跳过，
 * 最多再等一小时。不把运行标记写进 {@code sys_config}：那张表是业务口径，
 * 而且它有 60 秒缓存，写进去也读不到刚写的值。
 *
 * <p>管理端另有「立即聚合」入口（{@link #aggregateNow()}）：整点任务没法用来演示与排查，
 * 但它和定时任务共用同一把锁——并发跑两遍会把同一条还没标记的记录算两遍，桶计数就虚了，
 * 而桶计数正是升级到待审的判据。
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
            runAggregation("定时");
        } catch (RuntimeException e) {
            log.error("聚合归桶任务失败", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 手动触发一次聚合（管理端「立即聚合」）。异常直接抛给调用方——
     * 人工点的操作要当场知道失败原因，不能只留在日志里。
     *
     * @return 成功归桶的记录数
     * @throws BizException 已有聚合在跑（6004）
     */
    public int aggregateNow() {
        if (!running.compareAndSet(false, true)) {
            throw new BizException(ErrorCode.AGGREGATE_RUNNING);
        }
        try {
            return runAggregation("手动");
        } finally {
            running.set(false);
        }
    }

    /**
     * 调用前必须已持有 running 锁。
     *
     * <p>循环放这里而不是 {@code ClusteringService} 里：归桶要逐条独立事务，
     * service 内部直调自己的方法会绕开 AOP 代理、事务失效，失败的那条就会留下
     * 「桶计数加了、记录没标记」的半截状态。跨 bean 调用才走代理。
     */
    private int runAggregation(String trigger) {
        List<GuideRecord> records = clusteringService.scanPending();
        if (records.isEmpty()) {
            log.info("聚合归桶任务结束（{}）：没有新的全错记录", trigger);
            return 0;
        }
        log.info("聚合归桶任务开始（{}）：{} 条新增全错记录", trigger, records.size());
        int processed = 0;
        for (GuideRecord record : records) {
            try {
                if (clusteringService.clusterOne(record)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // 单条失败整条回滚，记录仍是 aggregated=0，下次扫描还会再看到它，不会静默丢失
                log.error("归桶失败，记录 ID: {}", record.getId(), e);
            }
        }
        log.info("聚合归桶任务完成（{}）：成功处理 {} / {} 条记录", trigger, processed, records.size());
        return processed;
    }
}
