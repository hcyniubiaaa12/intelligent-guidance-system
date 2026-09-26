package com.guide.kb.migration;

import com.guide.common.util.PgVectorUtil;
import com.guide.kb.entity.KbChunk;
import com.guide.kb.mapper.KbChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 存量向量回填（一次性迁移，需显式开启 {@code guide.backfill.chunk-vec-payload.enabled=true}）。
 *
 * <p>背景：{@code kb_chunk_vec} 的 doc_id / title / content 是后加的三列
 * （见 scripts/sql/migration_20260926_kb_chunk_vec_payload_step1.sql），老库已写入的向量行
 * 没有这三个值，step2 的 NOT NULL 约束会因此加不上。
 *
 * <p>为什么用应用侧脚本而不是纯 SQL：跨库不能 JOIN，只能「读 MySQL → 批量 UPDATE pgvector」。
 *
 * <p>幂等：只挑 doc_id 为空的行回填，跑完即空转。MySQL 里查不到的 chunk_id 是**孤儿向量**
 * （MySQL 已回滚、向量已落库），它们本来就进不了 Prompt，**这里只报告不删除**——
 * 删不删由人决定，日志里给出可直接执行的 SQL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "guide.backfill.chunk-vec-payload.enabled", havingValue = "true")
public class ChunkVecPayloadBackfill implements ApplicationRunner {

    private final KbChunkMapper chunkMapper;
    private final PgVectorUtil pgVectorUtil;

    @Override
    public void run(ApplicationArguments args) {
        List<String> pending = pgVectorUtil.listChunkIdsWithoutPayload();
        if (pending.isEmpty()) {
            log.info("存量向量回填：没有待回填的行，跳过");
            return;
        }
        Map<String, KbChunk> chunks = chunkMapper.selectBatchIds(pending).stream()
                .collect(Collectors.toMap(KbChunk::getId, Function.identity()));

        List<PgVectorUtil.ChunkPayload> payloads = new ArrayList<>(pending.size());
        List<String> orphans = new ArrayList<>();
        for (String chunkId : pending) {
            KbChunk chunk = chunks.get(chunkId);
            if (chunk == null) {
                orphans.add(chunkId);
                continue;
            }
            payloads.add(new PgVectorUtil.ChunkPayload(
                    chunkId, chunk.getDocId(), chunk.getTitle(), chunk.getContent()));
        }

        int updated = pgVectorUtil.backfillChunkPayload(payloads);
        log.info("存量向量回填完成：待补 {} 条，已回填 {} 条", pending.size(), updated);
        if (!orphans.isEmpty()) {
            log.warn("发现 {} 条孤儿向量（MySQL 里已无对应切片），未删除。确认后执行："
                    + "DELETE FROM kb_chunk_vec WHERE chunk_id IN (...)；chunkIds={}", orphans.size(), orphans);
        }
    }
}
