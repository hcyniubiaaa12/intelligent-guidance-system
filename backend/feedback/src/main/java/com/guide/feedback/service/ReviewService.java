package com.guide.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.feedback.entity.ClusterBucket;
import com.guide.feedback.entity.RootCause;
import com.guide.feedback.enums.BucketStatus;
import com.guide.feedback.mapper.ClusterBucketMapper;
import com.guide.feedback.mapper.RootCauseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审核队列的读侧（链路 C）：待审列表与桶详情。
 *
 * <p>代表样本按 {@code guide_record.bucket_id} 取最近 5 条。同方向可以有多个桶，
 * 按方向取会把别的桶的记录混进来，审核看到的就不是这一桶。
 * 证据直接读 {@code guide_record.evidence} 快照，不重新检索——重新检索拿到的是修复后的结果，
 * 看不出当时为什么错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    /** 代表样本条数：够判断「是不是同一类错误」，又不至于信息过载 */
    static final int REPRESENTATIVE_SAMPLE_SIZE = 5;

    private final ClusterBucketMapper clusterBucketMapper;
    private final GuideRecordMapper guideRecordMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final RootCauseMapper rootCauseMapper;
    private final ObjectMapper objectMapper;

    /** 待审桶，按样本数降序（影响面大的排前面） */
    public Page<ClusterBucket> listPendingBuckets(int pageNum, int pageSize) {
        return clusterBucketMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<ClusterBucket>lambdaQuery()
                        .eq(ClusterBucket::getStatus, BucketStatus.PENDING)
                        .orderByDesc(ClusterBucket::getCount));
    }

    /** 终态桶（approved / rejected / dismissed），修正重审的入口 */
    public Page<ClusterBucket> listTerminalBuckets(int pageNum, int pageSize) {
        return clusterBucketMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<ClusterBucket>lambdaQuery()
                        .in(ClusterBucket::getStatus,
                                BucketStatus.APPROVED, BucketStatus.REJECTED, BucketStatus.DISMISSED)
                        .orderByDesc(ClusterBucket::getUpdatedAt));
    }

    /** 待审桶数量：侧栏徽标用，不带分页 */
    public long countPending() {
        return clusterBucketMapper.selectCount(Wrappers.<ClusterBucket>lambdaQuery()
                .eq(ClusterBucket::getStatus, BucketStatus.PENDING));
    }

    public ClusterBucket requireBucket(String bucketId) {
        return bucketId == null ? null : clusterBucketMapper.selectById(bucketId);
    }

    /**
     * 桶详情。桶不存在返回 {@code null}，由调用方决定错误码。
     */
    public BucketDetail getBucketDetail(String bucketId) {
        ClusterBucket bucket = requireBucket(bucketId);
        if (bucket == null) {
            return null;
        }
        List<GuideRecord> records = members(bucketId);
        List<RepresentativeSample> samples = records.stream().map(this::toSample).toList();
        return new BucketDetail(bucket, samples);
    }

    /** 桶内代表样本：最近 {@link #REPRESENTATIVE_SAMPLE_SIZE} 条，倒序 */
    public List<GuideRecord> members(String bucketId) {
        return guideRecordMapper.selectList(Wrappers.<GuideRecord>lambdaQuery()
                .eq(GuideRecord::getBucketId, bucketId)
                .orderByDesc(GuideRecord::getCreatedAt)
                .last("LIMIT " + REPRESENTATIVE_SAMPLE_SIZE));
    }

    private RepresentativeSample toSample(GuideRecord record) {
        RootCause cause = rootCauseMapper.selectOne(Wrappers.<RootCause>lambdaQuery()
                .eq(RootCause::getRecordId, record.getId())
                .last("LIMIT 1"));
        return new RepresentativeSample(
                record.getId(),
                extractSymptom(record.getSessionId()),
                record.getEvidence(),
                parseCauses(cause == null ? null : cause.getCauses()));
    }

    private String extractSymptom(String sessionId) {
        ChatMessage first = chatMessageMapper.selectOne(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, MessageRole.USER)
                .orderByAsc(ChatMessage::getCreatedAt)
                .last("LIMIT 1"));
        return first == null || first.getContent() == null ? "" : first.getContent();
    }

    private List<String> parseCauses(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("根因 JSON 无法解析，原样忽略：{}", json);
            return List.of();
        }
    }

    /** 桶详情：桶本身 + 最近的代表样本 */
    public record BucketDetail(ClusterBucket bucket, List<RepresentativeSample> samples) {
    }

    /**
     * 代表样本。{@code evidence} 是当时的证据快照（JSON 原文），{@code causes} 是当前根因 key。
     */
    public record RepresentativeSample(String recordId, String symptom, String evidence, List<String> causes) {
    }
}
