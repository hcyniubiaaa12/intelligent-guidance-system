package com.guide.memory.job;

import com.guide.auth.service.SysConfigService;
import com.guide.common.port.SessionActivityPort;
import com.guide.common.port.SessionActivityPort.IdleSession;
import com.guide.memory.service.MemoryExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 空闲会话兜底抽取：会话静默 30 分钟后，把还没抽过的对话补抽一次。
 *
 * <p>为什么要有它：轮次完成的异步抽取可能被线程池丢掉、也可能因为规则预筛漏掉一句
 * 「我其实有糖尿病」；会话不再有新消息时没有第二个触发点，这条信息就永远进不来。
 * <b>它不依赖会话关闭事件</b>——关闭只在挂号成功时发生，没挂号的患者同样该被记住。
 *
 * <p>配额与轮次入口同一个计数（{@code chat_session.memory_extract_count}），所以兜底不会
 * 把单会话的模型调用推过 3 次。预筛在这一路跳过：它存在的意义正是补预筛没覆盖到的会话。
 *
 * <p>{@code @Scheduled} 由 async 模块的 {@code @EnableScheduling} 全局启用，这里不重复开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryIdleExtractionJob {

    /** 空闲多久算「这一轮结束了」——spec §4 定 30 分钟 */
    static final Duration IDLE_FOR = Duration.ofMinutes(30);
    /** 单次扫描最多处理多少会话（避免一次扫太多把线程池压满） */
    static final int SCAN_LIMIT = 50;

    private final SessionActivityPort sessionActivityPort;
    private final MemoryExtractionService memoryExtractionService;
    private final SysConfigService sysConfigService;

    @Scheduled(fixedDelayString = "${guide.memory.idle-scan-interval-ms:300000}", initialDelay = 60_000)
    public void scan() {
        try {
            int sessionMax = sysConfigService.getInt(SysConfigService.KEY_MEMORY_EXTRACT_SESSION_MAX,
                    SysConfigService.DEFAULT_MEMORY_EXTRACT_SESSION_MAX);
            List<IdleSession> sessions = sessionActivityPort.findIdleSessions(
                    IDLE_FOR, sessionMax, SCAN_LIMIT);
            if (sessions == null || sessions.isEmpty()) {
                return;
            }
            log.info("空闲会话兜底抽取：扫描到 {} 个待处理会话", sessions.size());
            for (IdleSession session : sessions) {
                // 逐个走同一抽取路径；单条失败由 service 内部吞掉，不中断整批
                memoryExtractionService.extractIdleSession(session);
            }
        } catch (RuntimeException e) {
            log.warn("空闲会话扫描失败（下一轮继续）：{}", e.getMessage());
        }
    }
}
