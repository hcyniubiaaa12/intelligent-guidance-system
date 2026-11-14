package com.guide.memory.job;

import com.guide.memory.service.MemoryMigrationService;
import com.guide.memory.service.MemoryMigrationService.MigrationSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时执行一次存量档案迁移（旧慢病 → 稳定背景记忆）。
 *
 * <p><b>默认开、可关</b>：{@code guide.memory.migration.enabled=false} 可禁用。
 * 迁移幂等（按物理行判存在），每次启动都跑不会产生重复记忆，也能接住"迁移期间新增的老档案"。
 *
 * <p><b>绝不影响启动</b>：任何异常只记 WARN 吞掉。迁移是数据整理，不是应用能启动的前提；
 * 让服务因为一条坏档案起不来，代价远大于"这次没迁完，下次再来"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "guide.memory.migration.enabled", havingValue = "true", matchIfMissing = true)
public class MemoryMigrationRunner implements ApplicationRunner {

    private final MemoryMigrationService memoryMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            MigrationSummary summary = memoryMigrationService.migrateAll();
            if (summary.profiles() > 0) {
                log.info("存量档案迁移完成：扫描 {} 份，新写入 {} 条背景记忆",
                        summary.profiles(), summary.inserted());
            }
        } catch (RuntimeException e) {
            log.warn("存量档案迁移中断（不影响启动，下次启动重试）：{}", e.getMessage(), e);
        }
    }
}
