package com.guide.common.util;

import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.model.ChunkHit;
import com.guide.common.config.PgVectorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * pgvector 读写工具（管语义）。
 * 写入方：kb（chunk 向量，唯一 RAG 语料入口）、feedback（聚类锚点向量）；
 * 读取方：rag（向量召回）、feedback（语义归桶）。
 * 表结构见《数据库设计.md》4.1：kb_chunk_vec / cluster_bucket_vec，余弦距离 HNSW 索引。
 */
@Slf4j
@Component
public class PgVectorUtil {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorUtil(@Qualifier(PgVectorConfig.PG_VECTOR_JDBC_TEMPLATE) JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写入/覆盖 chunk 向量（回流重入、重新入库走同一条 upsert） */
    public void upsertChunkVector(ChunkVector vector) {
        jdbcTemplate.update("""
                INSERT INTO kb_chunk_vec (chunk_id, dept_id, doc_id, title, content, embedding)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector))
                ON CONFLICT (chunk_id) DO UPDATE SET
                    dept_id = EXCLUDED.dept_id,
                    doc_id = EXCLUDED.doc_id,
                    title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding
                """, vector.chunkId(), vector.deptId(), vector.docId(), vector.title(), vector.content(),
                toLiteral(vector.embedding()));
    }

    /** 删除 chunk 向量（删除补偿：先删 ES → 再删向量 → 再删元数据） */
    public void deleteChunkVector(String chunkId) {
        jdbcTemplate.update("DELETE FROM kb_chunk_vec WHERE chunk_id = ?", chunkId);
    }

    /**
     * 批量删除向量（写时补偿：ES 或向量库写失败时，回删本次已写入的向量）。
     * pgvector 与 ES 都不在 MySQL 事务里，MySQL 回滚不会带走它们，必须主动回删。
     *
     * @return 实际删除行数
     */
    public int deleteChunkVectors(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        return jdbcTemplate.update("DELETE FROM kb_chunk_vec WHERE chunk_id IN (" + placeholders + ")",
                chunkIds.toArray());
    }

    /**
     * 按文档批量删除向量（删文档 / 重新入库「先删旧切片」用）。
     * 走 {@code doc_id} 索引——行自证归属正是为这一刻加的列。
     *
     * @return 实际删除行数
     */
    public int deleteChunkVectorsByDoc(String docId) {
        return jdbcTemplate.update("DELETE FROM kb_chunk_vec WHERE doc_id = ?", docId);
    }

    /** 列出正文副本为空的行（迁移前写入的向量），供一次性回填定位待补行 */
    public List<String> listChunkIdsWithoutPayload() {
        return jdbcTemplate.queryForList("SELECT chunk_id FROM kb_chunk_vec WHERE doc_id IS NULL", String.class);
    }

    /**
     * 回填归属与正文副本（一次性迁移用：老库的向量行没有这三列，embedding 不动）。
     *
     * @return 实际更新行数
     */
    public int backfillChunkPayload(List<ChunkPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return 0;
        }
        int[][] affected = jdbcTemplate.batchUpdate(
                "UPDATE kb_chunk_vec SET doc_id = ?, title = ?, content = ? WHERE chunk_id = ?",
                payloads, payloads.size(), (ps, payload) -> {
                    ps.setString(1, payload.docId());
                    ps.setString(2, payload.title());
                    ps.setString(3, payload.content());
                    ps.setString(4, payload.chunkId());
                });
        int updated = 0;
        for (int[] batch : affected) {
            for (int rows : batch) {
                updated += Math.max(rows, 0);
            }
        }
        return updated;
    }

    /**
     * 向量召回：余弦相似度 Top-K。停用科室不做过滤——chunk 留库可召回，
     * 但绝不入推荐（科室校验在 chat 层按 enabled 拦截，见链路 A 对齐点）。
     *
     * <p>只返回 id / 科室 / 分数：切片正文在 MySQL（管事实），跨库不能 JOIN，
     * 由 rag 层融合后经 ChunkTextProvider 端口批量回填标题与正文。
     */
    public List<ChunkHit> searchChunks(float[] queryVector, int topK) {
        String literal = toLiteral(queryVector);
        return jdbcTemplate.query("""
                        SELECT chunk_id, dept_id, 1 - (embedding <=> CAST(? AS vector)) AS score
                        FROM kb_chunk_vec
                        ORDER BY embedding <=> CAST(? AS vector)
                        LIMIT ?
                        """,
                (rs, rowNum) -> new ChunkHit(
                        rs.getString("chunk_id"),
                        rs.getString("dept_id"),
                        null,
                        null,
                        rs.getDouble("score")),
                literal, literal, topK);
    }

    /** 写入聚类锚点向量（feedback 直写，开新桶时调用） */
    public void upsertBucketVector(String bucketId, String recDeptId, String actualDeptId, float[] embedding) {
        jdbcTemplate.update("""
                INSERT INTO cluster_bucket_vec (bucket_id, rec_dept_id, actual_dept_id, embedding)
                VALUES (?, ?, ?, CAST(? AS vector))
                ON CONFLICT (bucket_id) DO UPDATE SET embedding = EXCLUDED.embedding
                """, bucketId, recDeptId, actualDeptId, toLiteral(embedding));
    }

    /**
     * 语义归桶：在【同方向】候选桶内找余弦相似度最高者（方向精确字段先锁死，歧义空间小）。
     *
     * @return 命中的桶 id 与相似度；无候选桶返回 null
     */
    public BucketHit searchBucketByDirection(String recDeptId, String actualDeptId, float[] queryVector) {
        List<BucketHit> hits = jdbcTemplate.query("""
                        SELECT bucket_id, 1 - (embedding <=> CAST(? AS vector)) AS score
                        FROM cluster_bucket_vec
                        WHERE rec_dept_id = ? AND actual_dept_id = ?
                        ORDER BY embedding <=> CAST(? AS vector)
                        LIMIT 1
                        """,
                (rs, rowNum) -> new BucketHit(rs.getString("bucket_id"), rs.getDouble("score")),
                toLiteral(queryVector), recDeptId, actualDeptId, toLiteral(queryVector));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 向量字面量：pgvector 接受 '[0.1,0.2,...]' 文本形式 */
    private String toLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "向量为空，无法写入 pgvector");
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

    /** 归桶命中结果 */
    public record BucketHit(String bucketId, double score) {
    }

    /**
     * 待写入的向量行。
     * {@code title} / {@code content} 是 MySQL 切片的**副本，只作人工排查用**——
     * 检索不读它们（{@link #searchChunks} 不 SELECT），事实仍以 MySQL 为准
     * （见《数据库设计.md》4.1）。{@code docId} 用于行自证归属与按文档批量清理。
     */
    public record ChunkVector(String chunkId, String deptId, String docId, String title, String content,
                              float[] embedding) {
    }

    /** 副本回填行（一次性迁移用）：把 MySQL 切片的归属与正文补进已存在的向量行 */
    public record ChunkPayload(String chunkId, String docId, String title, String content) {
    }
}
