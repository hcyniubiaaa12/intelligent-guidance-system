package com.guide.feedback.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.common.config.PromptProperties;
import com.guide.common.exception.BizException;
import com.guide.feedback.entity.ClusterBucket;
import com.guide.feedback.entity.ReviewTask;
import com.guide.feedback.enums.BucketStatus;
import com.guide.feedback.enums.ReviewStatus;
import com.guide.feedback.mapper.ClusterBucketMapper;
import com.guide.feedback.mapper.ReviewTaskMapper;
import com.guide.kb.entity.Dept;
import com.guide.kb.entity.DeptMapping;
import com.guide.kb.entity.KbChunk;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.enums.MappingSource;
import com.guide.kb.mapper.DeptMappingMapper;
import com.guide.kb.service.ChunkIndexService;
import com.guide.kb.service.DeptService;
import com.guide.kb.service.KbDocService;
import com.guide.llm.client.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** approve 的两样产物：台账同键并集、合成 chunk 走 kb；终态才能修正重审。 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private ClusterBucketMapper clusterBucketMapper;
    @Mock
    private ReviewTaskMapper reviewTaskMapper;
    @Mock
    private DeptMappingMapper deptMappingMapper;
    @Mock
    private DeptService deptService;
    @Mock
    private KbDocService kbDocService;
    @Mock
    private ChunkIndexService chunkIndexService;
    @Mock
    private ChatModel chatModel;
    @Spy
    private PromptProperties prompts = new PromptProperties();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ApprovalService approvalService;

    private ClusterBucket bucket;

    @BeforeEach
    void setUp() {
        PromptProperties.Feedback feedback = new PromptProperties.Feedback();
        feedback.setSystem("系统");
        feedback.setUserTemplate("{symptom}|{mainDept}|{crossDepts}");
        feedback.setFallbackTemplate("出现【{symptom}】症状时，应首先考虑【{mainDept}】{crossDepts}。");
        prompts.setFeedback(feedback);

        bucket = new ClusterBucket();
        bucket.setId("bucket-1");
        bucket.setRecDeptId("dept-rec");
        bucket.setActualDeptId("dept-actual");
        bucket.setAnchorText("头痛发热");
        bucket.setStatus(BucketStatus.PENDING);
    }

    @Test
    @DisplayName("LLM 失败时用模板兜底，交叉科室写进鉴别句")
    void previewFallsBackToTemplate() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("超时"));

        String text = approvalService.preview("头痛", "神经内科", List.of("感染科"));

        assertThat(text).isEqualTo("出现【头痛】症状时，应首先考虑【神经内科】，需与感染科进行鉴别。");
    }

    @Test
    @DisplayName("没有交叉科室时模板句不带「需与」")
    void previewWithoutCrossDept() {
        when(chatModel.chat(anyList())).thenReturn("  ");

        String text = approvalService.preview("头痛", "神经内科", List.of());

        assertThat(text).doesNotContain("需与");
    }

    @Test
    @DisplayName("approve：同键台账取并集，桶转 approved，合成 chunk 入库")
    void approveMergesMappingAndIndexesChunk() {
        DeptMapping existing = new DeptMapping();
        existing.setSymptom("头痛发热");
        existing.setMainDeptId("dept-main");
        existing.setCrossDeptIds("[\"dept-a\"]");
        KbDoc container = new KbDoc();
        container.setId("doc-1");
        when(clusterBucketMapper.selectById("bucket-1")).thenReturn(bucket);
        when(deptService.getById("dept-main")).thenReturn(dept("dept-main", "神经内科"));
        when(deptService.getById("dept-b")).thenReturn(dept("dept-b", "感染科"));
        when(deptMappingMapper.selectByKeyIncludingDeleted("头痛发热", "dept-main")).thenReturn(existing);
        when(kbDocService.ensureFeedbackContainer("dept-main", "神经内科")).thenReturn(container);
        when(chunkIndexService.indexSyntheticChunk(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new KbChunk());
        when(kbDocService.listChunks("doc-1")).thenReturn(List.of(new KbChunk()));
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);

        approvalService.approve("bucket-1", "头痛发热", "dept-main",
                List.of("dept-b", "dept-main"), "定稿文本", "admin-1");

        ArgumentCaptor<DeptMapping> mapping = ArgumentCaptor.forClass(DeptMapping.class);
        verify(deptMappingMapper).updateById(mapping.capture());
        assertThat(mapping.getValue().getCrossDeptIds()).contains("dept-a").contains("dept-b");
        assertThat(mapping.getValue().getSource()).isEqualTo(MappingSource.FEEDBACK);
        assertThat(bucket.getStatus()).isEqualTo(BucketStatus.APPROVED);
        verify(chunkIndexService).indexSyntheticChunk(eq("doc-1"), eq("dept-main"),
                eq("回流 · bucket-1 · 头痛发热"), eq("定稿文本"));
    }

    @Test
    @DisplayName("非待审的桶不能 approve")
    void approveRejectsTerminalBucket() {
        bucket.setStatus(BucketStatus.APPROVED);
        when(clusterBucketMapper.selectById("bucket-1")).thenReturn(bucket);

        assertThatThrownBy(() -> approvalService.approve("bucket-1", "头痛发热", "dept-main",
                List.of(), "文本", "admin-1"))
                .isInstanceOf(BizException.class);

        verify(deptMappingMapper, never()).insert(any(DeptMapping.class));
    }

    @Test
    @DisplayName("主科室不存在时拒绝，不写台账")
    void approveRejectsUnknownDept() {
        when(clusterBucketMapper.selectById("bucket-1")).thenReturn(bucket);
        when(deptService.getById("missing")).thenReturn(null);

        assertThatThrownBy(() -> approvalService.approve("bucket-1", "头痛发热", "missing",
                List.of(), "文本", "admin-1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("修正重审：approved 桶撤掉台账与合成 chunk，审核痕迹清空，回到 pending")
    void reReviewRevokesPreviousOutput() {
        bucket.setStatus(BucketStatus.APPROVED);
        ReviewTask task = new ReviewTask();
        task.setId("task-1");
        task.setBucketId("bucket-1");
        task.setStatus(ReviewStatus.DONE);
        task.setMainDeptId("dept-main");
        KbDoc container = new KbDoc();
        container.setId("doc-1");
        KbChunk chunk = new KbChunk();
        chunk.setId("chunk-1");
        chunk.setTitle("回流 · bucket-1 · 头痛发热");
        when(clusterBucketMapper.selectById("bucket-1")).thenReturn(bucket);
        when(reviewTaskMapper.selectOne(any())).thenReturn(task);
        when(kbDocService.findFeedbackContainer("dept-main")).thenReturn(container);
        when(kbDocService.listChunks("doc-1")).thenReturn(List.of(chunk), List.of());

        approvalService.reReview("bucket-1");

        verify(deptMappingMapper).delete(any());
        verify(chunkIndexService).deleteChunk("chunk-1");
        // 走自定义 SQL 而不是 updateById：三个字段要写成 null，updateById 会跳过它们
        verify(reviewTaskMapper).reopen("task-1");
        assertThat(bucket.getStatus()).isEqualTo(BucketStatus.PENDING);
    }

    @Test
    @DisplayName("监控中的桶不能修正重审")
    void reReviewRejectsMonitoring() {
        bucket.setStatus(BucketStatus.MONITORING);
        when(clusterBucketMapper.selectById("bucket-1")).thenReturn(bucket);

        assertThatThrownBy(() -> approvalService.reReview("bucket-1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("交叉科室预填 = 桶方向减去主科室")
    void prefillDropsMainDept() {
        List<String> cross = approvalService.prefillCrossDepts(bucket, "dept-actual");

        assertThat(cross).containsExactly("dept-rec");
    }

    @Test
    @DisplayName("驳回只改状态，不写台账")
    void rejectClosesWithoutMapping() {
        when(clusterBucketMapper.selectById("bucket-1")).thenReturn(bucket);
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);

        approvalService.reject("bucket-1", "admin-1");

        ArgumentCaptor<ClusterBucket> update = ArgumentCaptor.forClass(ClusterBucket.class);
        verify(clusterBucketMapper).updateById(update.capture());
        assertThat(update.getValue().getStatus()).isEqualTo(BucketStatus.REJECTED);
        verify(deptMappingMapper, never()).insert(any(DeptMapping.class));
        verify(chunkIndexService, never()).indexSyntheticChunk(anyString(), anyString(), anyString(), anyString());
    }

    private Dept dept(String id, String name) {
        Dept dept = new Dept();
        dept.setId(id);
        dept.setName(name);
        dept.setEnabled(1);
        return dept;
    }
}
