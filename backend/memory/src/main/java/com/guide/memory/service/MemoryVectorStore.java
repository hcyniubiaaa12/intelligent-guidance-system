package com.guide.memory.service;

import com.guide.common.config.PgVectorConfig;
import com.guide.memory.enums.MemoryType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆向量表（pgvector {@code user_memory_vec}）读写。
 *
 * <p><b>为什么不走 {@code PgVectorUtil}</b>：那个工具类是 chunk / 聚类锚点的领域（kb 与 feedback 的
 * 表结构、列与检索口径都绑死在那里）。记忆向量只有 4 列、每用户 ≤10 行、口径完全不同，
 * 塞进同一个类只会让两边的改动互相牵连。这里直接注入 pgvector 专用 {@link JdbcTemplate}
 * （与 kb/feedback 同一份连接池配置，不再注册第二个 DataSource —— 那会让 MySQL 主数据源自动配置退让）。
 *
 * <p><b>不建 HNSW</b>：每用户 ≤10 行，{@code (user_id, type)} 过滤后顺序扫描足够；
 * 向量索引反而会在小表上引入不必要的写放大。
 *
 * <p><b>只管向量，不管事实</b>：{@code user_memory} 在 MySQL 是权威源，召回后仍要回表校验
 * 用户、状态与有效期；这里返回的 id 只是候选。
 */
@Component
public class MemoryVectorStore {

    private final JdbcTemplate jdbcTemplate;

    public MemoryVectorStore(@Qualifier(PgVectorConfig.PG_VECTOR_JDBC_TEMPLATE) JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写入/覆盖一条记忆向量（编辑后重算走同一条 upsert；时间戳用于回填任务识别新鲜度） */
    public void upsert(String memoryId, String userId, String type, float[] embedding) {
        jdbcTemplate.update("""
                INSERT INTO user_memory_vec (memory_id, user_id, type, embedding, updated_at)
                VALUES (?, ?, ?, CAST(? AS vector), ?)
                ON CONFLICT (memory_id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    type = EXCLUDED.type,
                    embedding = EXCLUDED.embedding,
                    updated_at = EXCLUDED.updated_at
                """, memoryId, userId, type, toLiteral(embedding), LocalDateTime.now());
    }

    /** 删除向量（记忆被患者删除 / 一键清空时的补偿；不存在的行静默无操作） */
    public void delete(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM user_memory_vec WHERE memory_id = ?", memoryId);
    }

    /**
     * 按当前用户召回两类背景记忆的 top-K。
     *
     * <p>偏好不向量化，SQL 侧也再挡一次：即便写入侧出了偏差，偏好也不会被召回。
     * 返回顺序按余弦距离升序（相似度高在前）。
     */
    public List<ScoredMemory> search(String userId, float[] queryVector, int topK) {
        if (userId == null || userId.isBlank() || queryVector == null
                || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        String literal = toLiteral(queryVector);
        return jdbcTemplate.query("""
                        SELECT memory_id, 1 - (embedding <=> CAST(? AS vector)) AS score
                        FROM user_memory_vec
                        WHERE user_id = ? AND type IN (?, ?)
                        ORDER BY embedding <=> CAST(? AS vector)
                        LIMIT ?
                        """,
                (rs, rowNum) -> new ScoredMemory(rs.getString("memory_id"), rs.getDouble("score")),
                literal, userId,
                MemoryType.BACKGROUND_HEALTH.getCode(), MemoryType.BACKGROUND_OTHER.getCode(),
                literal, topK);
    }

    /**
     * 批量读取向量行的写入时间（回填任务用：缺行 = 没有向量，时间早于记忆 updated_at = 过期）。
     *
     * @return memoryId → 向量写入时间；不存在的 id 不在 map 里
     */
    public Map<String, LocalDateTime> vectorUpdatedAt(Collection<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(memoryIds.size(), "?"));
        List<VectorRow> rows = jdbcTemplate.query(
                "SELECT memory_id, updated_at FROM user_memory_vec WHERE memory_id IN (" + placeholders + ")",
                (rs, rowNum) -> new VectorRow(rs.getString("memory_id"),
                        toLocalDateTime(rs.getTimestamp("updated_at"))),
                memoryIds.toArray());
        Map<String, LocalDateTime> result = new HashMap<>();
        for (VectorRow row : rows) {
            result.put(row.memoryId(), row.updatedAt());
        }
        return result;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /** 向量字面量：pgvector 接受 '[0.1,0.2,...]' 文本形式 */
    private String toLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("向量为空，无法写入 pgvector");
        }
        StringBuilder sb = new StringBuilder(embedding.length * 8).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    /** 召回候选：只有记忆 id 与相似度，事实字段回 MySQL 读 */
    public record ScoredMemory(String memoryId, double score) {
    }

    private record VectorRow(String memoryId, LocalDateTime updatedAt) {
    }
}
