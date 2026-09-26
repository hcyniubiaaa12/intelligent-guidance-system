package com.guide.async.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 离线入库线程池：文档解析提交、切分、向量化入库都用它，**与在线导诊的 SSE 线程池隔离**
 * （CLAUDE.md 依赖规范：async 线程池与在线导诊线程隔离，不共用）。
 *
 * <p>为什么必须隔离：入库一份文档要花几十秒到几分钟（嵌入是网络调用、逐批写三处存储），
 * 共用池子的话，管理员传一份大文档就能把患者的导诊对话堵在队列里——
 * 而这两件事的优先级根本不在一个量级上。
 *
 * <p>拒绝策略选 AbortPolicy（与 chat 侧同理）：队列满时明确抛给调用方，
 * 由 {@code IngestPipeline} 把任务标 failed 并说清原因，而不是让上传请求线程
 * 悄悄同步跑完整个入库。
 *
 * <p>{@code @EnableScheduling} 挂在这里：定时轮询（{@code IngestPollingJob}）是离线侧自己的事，
 * 由本模块开启，不去动启动器 admin。
 */
@Configuration
@EnableScheduling
public class IngestExecutorConfig {

    public static final String INGEST_EXECUTOR = "ingestExecutor";

    @Bean(name = INGEST_EXECUTOR)
    public ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
