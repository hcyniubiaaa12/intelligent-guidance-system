package com.guide.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.auth.service.SysConfigService;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.util.PgVectorUtil;
import com.guide.feedback.entity.ClusterBucket;
import com.guide.feedback.entity.ReviewTask;
import com.guide.feedback.enums.BucketStatus;
import com.guide.feedback.enums.ReviewStatus;
import com.guide.feedback.mapper.ClusterBucketMapper;
import com.guide.feedback.mapper.ReviewTaskMapper;
import com.guide.llm.client.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 聚合归桶（链路 C）。
 *
 * <p>两级：精确归桶（症状归一化后比对 {@code exact_key}）→ 语义归桶（同方向锚点向量的余弦相似度）。
 * 聚合是召回机制（宁可混、不能漏），质量闸门在人工审核。
 *
 * <p>只收全错记录：{@code actual_dept} 非空、{@code top3_hit=0}、{@code low_confidence=0}。
 * 低置信度进盲区榜，不进聚合。处理一条、标记一条，并记下所属桶——同方向可以有多个桶，
 * 审核页按桶取记录，不能按方向反推。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusteringService {

    /** 升级计数阈值：top3 未命中默认 3 次。演示可临时调低，与相似度阈值不是同一个键 */
    public static final String KEY_UPGRADE_COUNT = "cluster.upgrade.count";
    public static final int DEFAULT_UPGRADE_COUNT = 3;
    public static final double DEFAULT_SIMILARITY = 0.85;

    /** 口语停用词：固定规则，不进 sys_config（精确未命中还有语义归桶兜底） */
    private static final Pattern FILLER = Pattern.compile("[的了啊呀呢吧嘛哦哈嗯]");
    /** 标点与量词：\\p{P} 覆盖中英文标点，避免在源码里写中文标点字面量 */
    private static final Pattern PUNCT_AND_MEASURE = Pattern.compile("[\\p{P}\\p{S}个次下点些许]");

    private final GuideRecordMapper guideRecordMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ClusterBucketMapper clusterBucketMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final EmbeddingModel embeddingModel;
    private final PgVectorUtil pgVectorUtil;
    private final SysConfigService sysConfigService;

    /**
     * 扫描未聚合的全错记录，逐条归桶。
     *
     * @return 成功归桶的记录数（单条失败不拖垮整批，下次还会再扫到它）
     */
    public int aggregateNewRecords() {
        List<GuideRecord> records = guideRecordMapper.selectList(Wrappers.<GuideRecord>lambdaQuery()
                .eq(GuideRecord::getAggregated, 0)
                .isNotNull(GuideRecord::getActualDeptId)
                .eq(GuideRecord::getTop3Hit, 0)
                .eq(GuideRecord::getLowConfidence, 0));
        if (records.isEmpty()) {
            log.info("无新增全错记录需要聚合");
            return 0;
        }
        log.info("开始聚合 {} 条新增全错记录", records.size());
        int processed = 0;
        for (GuideRecord record : records) {
            try {
                if (clusterOne(record)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // 单条失败不标 aggregated：下次扫描还会再看到它，不会静默丢失
                log.error("归桶失败，记录 ID: {}", record.getId(), e);
            }
        }
        log.info("聚合完成，成功处理 {} / {} 条记录", processed, records.size());
        return processed;
    }

    /**
     * 一条记录的归桶与标记同事务：中途失败整条回滚，记录仍是 aggregated=0。
     *
     * @return 是否真的归进了桶。抽不出主诉时标已处理并返回 false——主诉不会自己长出来，
     *         不标记的话这条记录会每小时被重新扫到、每小时打一条警告
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean clusterOne(GuideRecord record) {
        String symptom = extractSymptom(record.getSessionId());
        if (symptom.isBlank()) {
            log.warn("记录 {} 无法提取症状，会话 {} 没有患者消息，标记跳过",
                    record.getId(), record.getSessionId());
            markAggregated(record.getId(), null);
            return false;
        }
        String exactKey = normalizeSymptom(symptom);
        String recDeptId = record.getRecDeptId();
        String actualDeptId = record.getActualDeptId();

        ClusterBucket bucket = findExactBucket(recDeptId, actualDeptId, exactKey);
        if (bucket == null) {
            float[] embedding = embeddingModel.embed(symptom);
            bucket = findSemanticBucket(recDeptId, actualDeptId, embedding);
            if (bucket == null) {
                bucket = createNewBucket(recDeptId, actualDeptId, symptom, exactKey, embedding);
            } else {
                increment(bucket);
            }
        } else {
            increment(bucket);
        }
        markAggregated(record.getId(), bucket.getId());
        return true;
    }

    /** 主诉 = 该会话第一条患者消息（与就诊记录页「首句」同一口径） */
    String extractSymptom(String sessionId) {
        ChatMessage first = chatMessageMapper.selectOne(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, MessageRole.USER)
                .orderByAsc(ChatMessage::getCreatedAt)
                .last("LIMIT 1"));
        return first == null || first.getContent() == null ? "" : first.getContent().strip();
    }

    /**
     * 精确归桶键：去空白、去口语词、去标点与量词、转小写。
     * 规则写死——精确未命中还有语义归桶兜底，不值得为它做一套可配置词典。
     */
    static String normalizeSymptom(String symptom) {
        if (symptom == null || symptom.isBlank()) {
            return "";
        }
        String normalized = FILLER.matcher(symptom).replaceAll("");
        normalized = PUNCT_AND_MEASURE.matcher(normalized).replaceAll("");
        return normalized.replaceAll("\\s+", "").toLowerCase();
    }

    private ClusterBucket findExactBucket(String recDeptId, String actualDeptId, String exactKey) {
        if (exactKey.isBlank()) {
            return null;
        }
        return clusterBucketMapper.selectOne(Wrappers.<ClusterBucket>lambdaQuery()
                .eq(ClusterBucket::getRecDeptId, recDeptId)
                .eq(ClusterBucket::getActualDeptId, actualDeptId)
                .eq(ClusterBucket::getExactKey, exactKey)
                .last("LIMIT 1"));
    }

    private ClusterBucket findSemanticBucket(String recDeptId, String actualDeptId, float[] embedding) {
        PgVectorUtil.BucketHit hit = pgVectorUtil.searchBucketByDirection(recDeptId, actualDeptId, embedding);
        double threshold = sysConfigService.getDouble(
                SysConfigService.KEY_CLUSTER_BUCKET_THRESHOLD, DEFAULT_SIMILARITY);
        if (hit == null || hit.score() < threshold) {
            return null;
        }
        return clusterBucketMapper.selectById(hit.bucketId());
    }

    /**
     * 计数 +1。只有 monitoring 达阈值才转 pending 并生成审核任务；
     * 终态桶只累加，绝不自动重新升级（修正重审是人工动作）。
     */
    private void increment(ClusterBucket bucket) {
        bucket.setCount((bucket.getCount() == null ? 0 : bucket.getCount()) + 1);
        promoteIfDue(bucket);
    }

    /** 开新桶也走这里：阈值调成 1 时，第一样本就该进待审，不能等下一条 */
    private void promoteIfDue(ClusterBucket bucket) {
        int threshold = sysConfigService.getInt(KEY_UPGRADE_COUNT, DEFAULT_UPGRADE_COUNT);
        int count = bucket.getCount() == null ? 0 : bucket.getCount();
        if (bucket.getStatus() == BucketStatus.MONITORING && count >= threshold) {
            bucket.setStatus(BucketStatus.PENDING);
            clusterBucketMapper.updateById(bucket);
            createReviewTask(bucket.getId());
            log.info("桶 {} 达到升级阈值 {}，转 pending", bucket.getId(), threshold);
            return;
        }
        clusterBucketMapper.updateById(bucket);
    }

    private ClusterBucket createNewBucket(String recDeptId, String actualDeptId,
                                          String anchorText, String exactKey, float[] embedding) {
        ClusterBucket bucket = new ClusterBucket();
        bucket.setRecDeptId(recDeptId);
        bucket.setActualDeptId(actualDeptId);
        bucket.setAnchorText(truncate(anchorText, 512));
        bucket.setExactKey(truncate(exactKey, 255));
        bucket.setCount(1);
        bucket.setStatus(BucketStatus.MONITORING);
        clusterBucketMapper.insert(bucket);
        // 锚点向量归 feedback 直写（明确例外：kb 的唯一写入口只管 RAG 语料）
        pgVectorUtil.upsertBucketVector(bucket.getId(), recDeptId, actualDeptId, embedding);
        promoteIfDue(bucket);
        return bucket;
    }

    private void createReviewTask(String bucketId) {
        ReviewTask existing = reviewTaskMapper.selectOne(Wrappers.<ReviewTask>lambdaQuery()
                .eq(ReviewTask::getBucketId, bucketId)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        ReviewTask task = new ReviewTask();
        task.setBucketId(bucketId);
        task.setStatus(ReviewStatus.PENDING);
        reviewTaskMapper.insert(task);
    }

    private void markAggregated(String recordId, String bucketId) {
        GuideRecord update = new GuideRecord();
        update.setId(recordId);
        update.setAggregated(1);
        update.setBucketId(bucketId);
        guideRecordMapper.updateById(update);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
