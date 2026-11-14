package com.guide.memory.listener;

import com.guide.common.event.TurnCompletedEvent;
import com.guide.memory.config.MemoryExecutorConfig;
import com.guide.memory.service.MemoryExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/**
 * 轮次完成 → 异步抽取。
 *
 * <p>监听器本身只在**事件发布线程**（导诊线程的 finally）里做一次提交，真正的读对话与模型调用
 * 在 {@code memoryExecutor} 上跑——所以事件发布不会因为抽取慢而卡住本轮 SSE。
 *
 * <p>线程池拒绝（队列满）只记 WARN 丢弃：抽取是旁路，丢一轮不影响对话，也不需要重试
 * （空闲兜底与后续轮次还有机会）。
 */
@Slf4j
@Component
public class MemoryExtractionListener {

    private final MemoryExtractionService memoryExtractionService;
    private final ThreadPoolTaskExecutor memoryExecutor;

    public MemoryExtractionListener(MemoryExtractionService memoryExtractionService,
                                    @Qualifier(MemoryExecutorConfig.MEMORY_EXECUTOR)
                                    ThreadPoolTaskExecutor memoryExecutor) {
        this.memoryExtractionService = memoryExtractionService;
        this.memoryExecutor = memoryExecutor;
    }

    @EventListener
    public void onTurnCompleted(TurnCompletedEvent event) {
        if (event == null || event.sessionId() == null) {
            return;
        }
        try {
            memoryExecutor.execute(() -> memoryExtractionService.onTurnCompleted(event));
        } catch (RejectedExecutionException e) {
            log.warn("记忆抽取线程池已满，丢弃本轮抽取：sessionId={}｜原因={}",
                    event.sessionId(), e.getMessage());
        }
    }
}
