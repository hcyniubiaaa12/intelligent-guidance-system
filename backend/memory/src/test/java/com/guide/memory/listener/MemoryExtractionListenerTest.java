package com.guide.memory.listener;

import com.guide.common.event.TurnCompletedEvent;
import com.guide.memory.service.MemoryExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 事件监听器的两条约束：把工作丢给独立线程池（不占导诊线程）、线程池拒绝时只丢这一轮。
 */
class MemoryExtractionListenerTest {

    private MemoryExtractionService service;
    private ThreadPoolTaskExecutor executor;
    private MemoryExtractionListener listener;

    @BeforeEach
    void setUp() {
        service = mock(MemoryExtractionService.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        listener = new MemoryExtractionListener(service, executor);
    }

    @Test
    @DisplayName("收到事件：提交到 memoryExecutor，并在任务里调用抽取（不直接在当前线程跑模型）")
    void submitsToExecutor() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        TurnCompletedEvent event = new TurnCompletedEvent("u1", "s1");
        listener.onTurnCompleted(event);

        verify(executor).execute(any(Runnable.class));
        verify(service).onTurnCompleted(event);
    }

    @Test
    @DisplayName("线程池已满：只记 WARN 丢弃，不向发布方（导诊 finally）抛异常")
    void rejectedExecutionDoesNotThrow() {
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onTurnCompleted(new TurnCompletedEvent("u1", "s1")))
                .doesNotThrowAnyException();
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("sessionId 为空的事件不处理（会话尚未落定，没有可抽取对象）")
    void ignoresSessionlessEvent() {
        listener.onTurnCompleted(new TurnCompletedEvent("u1", null));

        verifyNoInteractions(executor);
        verifyNoInteractions(service);
    }
}
