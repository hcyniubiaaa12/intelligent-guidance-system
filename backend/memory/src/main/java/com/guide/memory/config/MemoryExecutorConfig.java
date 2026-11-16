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
}
