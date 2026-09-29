package com.guide.feedback.service;

import com.guide.auth.service.SysConfigService;
import com.guide.chat.entity.ChatMessage;
import com.guide.chat.entity.GuideRecord;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.mapper.ChatMessageMapper;
import com.guide.chat.mapper.GuideRecordMapper;
import com.guide.common.util.PgVectorUtil;
import com.guide.feedback.entity.ClusterBucket;
import com.guide.feedback.enums.BucketStatus;
import com.guide.feedback.mapper.ClusterBucketMapper;
import com.guide.feedback.mapper.ReviewTaskMapper;
import com.guide.llm.client.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单条记录归桶的四条路径：精确命中、语义命中、开新桶、达阈值升级。
 * 无主诉的记录标记跳过，终态桶只累加。
 *
 * <p>这里直接调 {@link ClusteringService#clusterOne}——扫描与循环在
 * {@code AggregationScheduler}（另有其测试）。扫描口径（低置信度不进聚合）在联调里实测：
 * 低置信度与已命中 Top3 的记录跑完归桶仍是 {@code aggregated=0}。
 */
@ExtendWith(MockitoExtension.class)
class ClusteringServiceTest {

    private static final float[] VECTOR = {0.1f, 0.2f};

    @Mock
    private GuideRecordMapper guideRecordMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private ClusterBucketMapper clusterBucketMapper;
    @Mock
    private ReviewTaskMapper reviewTaskMapper;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private PgVectorUtil pgVectorUtil;
    @Mock
    private SysConfigService sysConfigService;

    @InjectMocks
    private ClusteringService clusteringService;

    private GuideRecord record;

    @BeforeEach
    void setUp() {
        record = new GuideRecord();
        record.setId("rec-1");
        record.setSessionId("session-1");
        record.setRecDeptId("dept-rec");
        record.setActualDeptId("dept-actual");
        record.setTop3Hit(0);
        record.setLowConfidence(0);
        record.setAggregated(0);
    }

    @Test
    @DisplayName("精确归桶：同方向同键的桶计数 +1，记录记下所属桶")
    void exactMatchIncrements() {
        ClusterBucket bucket = bucket(2, BucketStatus.MONITORING);
        when(chatMessageMapper.selectOne(any())).thenReturn(message("头疼，发热"));
        when(clusterBucketMapper.selectOne(any())).thenReturn(bucket);
        when(sysConfigService.getInt(ClusteringService.KEY_UPGRADE_COUNT,
                ClusteringService.DEFAULT_UPGRADE_COUNT)).thenReturn(3);

        assertThat(clusteringService.clusterOne(record)).isTrue();

        assertThat(bucket.getCount()).isEqualTo(3);
        assertThat(bucket.getStatus()).isEqualTo(BucketStatus.PENDING);
        verify(reviewTaskMapper).insert(any());
        ArgumentCaptor<GuideRecord> marked = ArgumentCaptor.forClass(GuideRecord.class);
        verify(guideRecordMapper).updateById(marked.capture());
        assertThat(marked.getValue().getAggregated()).isEqualTo(1);
        assertThat(marked.getValue().getBucketId()).isEqualTo("bucket-1");
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    @DisplayName("语义归桶：精确未命中且相似度达阈值时归入，不另开桶")
    void semanticMatchJoinsExisting() {
        ClusterBucket bucket = bucket(1, BucketStatus.MONITORING);
        when(chatMessageMapper.selectOne(any())).thenReturn(message("头痛发热"));
        when(clusterBucketMapper.selectOne(any())).thenReturn(null);
        when(embeddingModel.embed("头痛发热")).thenReturn(VECTOR);
        when(pgVectorUtil.searchBucketByDirection("dept-rec", "dept-actual", VECTOR))
                .thenReturn(new PgVectorUtil.BucketHit("bucket-1", 0.9));
        when(sysConfigService.getDouble(SysConfigService.KEY_CLUSTER_BUCKET_THRESHOLD,
                ClusteringService.DEFAULT_SIMILARITY)).thenReturn(0.85);
        when(clusterBucketMapper.selectById("bucket-1")).thenReturn(bucket);
        when(sysConfigService.getInt(anyString(), eq(ClusteringService.DEFAULT_UPGRADE_COUNT))).thenReturn(3);

        clusteringService.clusterOne(record);

        assertThat(bucket.getCount()).isEqualTo(2);
        assertThat(bucket.getStatus()).isEqualTo(BucketStatus.MONITORING);
        verify(clusterBucketMapper, never()).insert(any(ClusterBucket.class));
        verify(reviewTaskMapper, never()).insert(any());
    }

    @Test
    @DisplayName("相似度低于阈值：开新桶，锚点向量写入 pgvector")
    void belowThresholdOpensBucket() {
        when(chatMessageMapper.selectOne(any())).thenReturn(message("腹痛"));
        when(clusterBucketMapper.selectOne(any())).thenReturn(null);
        when(embeddingModel.embed("腹痛")).thenReturn(VECTOR);
        when(pgVectorUtil.searchBucketByDirection(anyString(), anyString(), any()))
                .thenReturn(new PgVectorUtil.BucketHit("other", 0.5));
        when(sysConfigService.getDouble(anyString(), eq(ClusteringService.DEFAULT_SIMILARITY))).thenReturn(0.85);
        when(sysConfigService.getInt(eq(ClusteringService.KEY_UPGRADE_COUNT),
                eq(ClusteringService.DEFAULT_UPGRADE_COUNT))).thenReturn(3);
        when(clusterBucketMapper.insert(any(ClusterBucket.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ClusterBucket.class).setId("bucket-new");
            return 1;
        });

        clusteringService.clusterOne(record);

        ArgumentCaptor<ClusterBucket> created = ArgumentCaptor.forClass(ClusterBucket.class);
        verify(clusterBucketMapper).insert(created.capture());
        assertThat(created.getValue().getExactKey()).isEqualTo("腹痛");
        assertThat(created.getValue().getCount()).isEqualTo(1);
        assertThat(created.getValue().getStatus()).isEqualTo(BucketStatus.MONITORING);
        verify(pgVectorUtil).upsertBucketVector("bucket-new", "dept-rec", "dept-actual", VECTOR);
    }

    @Test
    @DisplayName("没有患者消息：不归桶，但标记已处理，避免每小时重试")
    void missingSymptomIsMarkedSkipped() {
        when(chatMessageMapper.selectOne(any())).thenReturn(null);

        assertThat(clusteringService.clusterOne(record)).isFalse();

        verify(clusterBucketMapper, never()).insert(any(ClusterBucket.class));
        ArgumentCaptor<GuideRecord> marked = ArgumentCaptor.forClass(GuideRecord.class);
        verify(guideRecordMapper).updateById(marked.capture());
        assertThat(marked.getValue().getAggregated()).isEqualTo(1);
        assertThat(marked.getValue().getBucketId()).isNull();
    }

    @Test
    @DisplayName("终态桶的新样本只累加，不自动回到待审")
    void terminalBucketStaysTerminal() {
        ClusterBucket bucket = bucket(5, BucketStatus.APPROVED);
        when(chatMessageMapper.selectOne(any())).thenReturn(message("头痛"));
        when(clusterBucketMapper.selectOne(any())).thenReturn(bucket);

        clusteringService.clusterOne(record);

        assertThat(bucket.getCount()).isEqualTo(6);
        assertThat(bucket.getStatus()).isEqualTo(BucketStatus.APPROVED);
        verify(reviewTaskMapper, never()).insert(any());
    }

    @Test
    @DisplayName("归一化：去口语词、标点、量词与空白")
    void normalizationStripsFiller() {
        assertThat(ClusteringService.normalizeSymptom("头疼，发热了啊")).isEqualTo("头疼发热");
        assertThat(ClusteringService.normalizeSymptom("腹痛 3 次")).isEqualTo("腹痛3");
        assertThat(ClusteringService.normalizeSymptom("  ")).isEmpty();
    }

    private ClusterBucket bucket(int count, BucketStatus status) {
        ClusterBucket bucket = new ClusterBucket();
        bucket.setId("bucket-1");
        bucket.setRecDeptId("dept-rec");
        bucket.setActualDeptId("dept-actual");
        bucket.setExactKey("头疼发热");
        bucket.setCount(count);
        bucket.setStatus(status);
        return bucket;
    }

    private ChatMessage message(String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId("session-1");
        message.setRole(MessageRole.USER);
        message.setContent(content);
        return message;
    }
}
