package com.guide.memory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 记忆抽取线程池：与在线导诊（{@code chatSseExecutor}）、离线入库（{@code ingestExecutor}）隔离，
 * 三者不共用——抽取慢或积压不能挤占患者当前这轮回答，也不能拖慢文档入库。
 *
 * <p>队列满用 {@code AbortPolicy}：抽取是旁路，丢掉一轮远好过让事件发布线程（导诊的 finally）
 * 在这里等；拒绝由 {@code MemoryExtractionListener} 捕获并记 WARN。CallerRunsPolicy 会让
 * 导诊线程同步跑一次模型抽取，那正是「抽取不影响对话」要避免的。
 */
@Configuration
public class MemoryExecutorConfig {

    public static final String MEMORY_EXECUTOR = "memoryExecutor";
    public static final String MEMORY_ENRICH_EXECUTOR = "memoryEnrichExecutor";

    @Bean(name = MEMORY_EXECUTOR)
    public ThreadPoolTaskExecutor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("memory-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 记忆扩写专用单线程池：与抽取池隔离。
     *
     * <p>为什么不能共用 {@link #memoryExecutor()}：扩写是一次完整的 chat 调用（可达数十秒），
     * 患者连续改两条就会占满抽取池的两个核心线程；抽取每轮完成与空闲兜底都靠它，
     * 被一个「只影响 embedding 文本」的附属功能饿住不值得。单线程足够——改口是低频动作。
     * 队列满同样 AbortPolicy，由 {@code MemoryEnricher} 捕获并 WARN（扩写是旁路）。
     */
    @Bean(name = MEMORY_ENRICH_EXECUTOR)
    public ThreadPoolTaskExecutor memoryEnrichExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("memory-enrich-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
