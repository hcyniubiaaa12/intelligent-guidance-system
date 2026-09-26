package com.guide.async.job;

import com.guide.async.service.IngestPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 入库轮询的**扫描节拍**（每 5 秒扫一次任务表）。
 *
 * <p>为什么扫描节拍是固定的、而"多久问一次外部服务"走 `sys_config`：
 * {@code @Scheduled} 的固定间隔在启动时就定死了，改不了；而**每个任务距上次轮询多久**
 * 是运行期可判的（靠任务的 {@code updated_at}）。于是节拍只当心跳，真正的轮询间隔
 * （`ingest.poll.interval.seconds`）与总超时（`ingest.parse.timeout.minutes`）
 * 仍然可配、且改完立刻生效——这也让"扫描"与"问外部服务"解耦：
 * 扫得勤只多几次数据库查询，不会多发一次计费的请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestPollingJob {

    private final IngestPipeline ingestPipeline;

    @Scheduled(fixedDelayString = "${guide.ingest.scan-interval-ms:5000}", initialDelay = 10_000)
    public void scan() {
        try {
            ingestPipeline.pollRunning();
        } catch (Exception e) {
            // 定时任务抛异常会让后续调度继续跑，但这里兜一手，避免日志里堆满堆栈
            log.warn("入库轮询扫描出错：{}", e.getMessage());
        }
    }
}
