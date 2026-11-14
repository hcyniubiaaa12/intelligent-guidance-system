package com.guide.memory.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryVectorIndexer;
import com.guide.memory.service.MemoryVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 记忆向量回填：扫描生效中的稳定背景，把**缺向量**或**向量比记忆旧**的行重算一遍。
 *
 * <p>为什么需要它：向量写入是旁路（embedding 抖动、pgvector 短暂不可用都只记 WARN），
 * 以及工单 12 的旧档案迁移会直接插 MySQL 行。没有回填，这些记忆就永远不会被召回——
 * 患者说过的话系统"记住了"却用不上，比没记住更难解释。
 *
 * <p><b>不是「重算全部」</b>：换 embedding 模型属于运维任务（spec 明确不做自动化）；
 * 这里只补空洞与编辑后的陈旧向量，正常行不会被反复送去付费 embedding。
 *
 * <p>单条失败只记 WARN 继续——回填是后台整理，不该因为一条坏行让整批停摆。
 * {@code @EnableScheduling} 由 async 模块全局提供，这里不重复开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryVectorBackfillJob {

    /** 单次扫描上限：回填是慢活，分批慢慢补，避免一轮把线程/模型配额打满 */
    static final int SCAN_LIMIT = 500;

    private final MemoryMapper memoryMapper;
    private final MemoryVectorStore vectorStore;
    private final MemoryVectorIndexer indexer;

    @Scheduled(fixedDelayString = "${guide.memory.backfill-interval-ms:600000}", initialDelay = 60_000)
    public void backfill() {
        List<Memory> rows = activeBackgrounds();
        if (rows.isEmpty()) {
            return;
        }
        List<String> ids = rows.stream().map(Memory::getId).filter(id -> id != null && !id.isBlank()).toList();
        Map<String, LocalDateTime> vectorTimes;
        try {
            vectorTimes = vectorStore.vectorUpdatedAt(ids);
        } catch (RuntimeException e) {
            log.warn("读取记忆向量时间失败，本轮回填跳过（下一轮继续）：{}", e.getMessage());
            return;
        }

        int reindexed = 0;
        for (Memory row : rows) {
            if (!needsIndex(row, vectorTimes.get(row.getId()))) {
                continue;
            }
            try {
                if (indexer.index(row)) {
                    reindexed++;
                }
            } catch (RuntimeException e) {
                // 索引器自身已吞异常；这里是最后一道保险，坏行不能中断整批
                log.warn("记忆向量回填失败（继续下一条）：memoryId={}｜原因={}", row.getId(), e.getMessage());
            }
        }
        if (reindexed > 0) {
            log.info("记忆向量回填：重算 {} / 扫描 {} 条", reindexed, rows.size());
        }
    }

    private List<Memory> activeBackgrounds() {
        try {
            List<Memory> rows = memoryMapper.selectList(Wrappers.<Memory>lambdaQuery()
                    .eq(Memory::getStatus, MemoryStatus.ACTIVE)
                    .in(Memory::getType, List.of(MemoryType.BACKGROUND_HEALTH, MemoryType.BACKGROUND_OTHER))
                    .orderByAsc(Memory::getUpdatedAt)
                    // 按最旧的先补；LIMIT 写死在 SQL 尾部，回填任务不需要分页游标
                    .last("LIMIT " + SCAN_LIMIT));
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            log.warn("扫描待回填记忆失败（下一轮继续）：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 缺向量 ⇒ 补；向量行比记忆旧 ⇒ 重算（编辑过 mvalue/retrieval_text）。
     * 记忆没有 updated_at 时只在缺向量时补——没有依据就不重复付费。
     */
    private boolean needsIndex(Memory memory, LocalDateTime vectorUpdatedAt) {
        if (vectorUpdatedAt == null) {
            return true;
        }
        return memory.getUpdatedAt() != null && vectorUpdatedAt.isBefore(memory.getUpdatedAt());
    }
}
