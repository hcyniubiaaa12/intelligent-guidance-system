package com.guide.async.service;

import com.guide.async.entity.IngestTask;
import com.guide.async.enums.IngestStage;
import com.guide.async.enums.IngestStatus;
import com.guide.auth.service.SysConfigService;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.model.LayoutBlock;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.enums.DocFailType;
import com.guide.kb.parse.ParseSession;
import com.guide.kb.service.DocIngestService;
import com.guide.kb.service.KbDocService;
import com.guide.kb.split.SplitValidationException;
import com.guide.llm.client.DocParseException;
import com.guide.llm.client.DocParseModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两段式编排的行为约束：阶段推进、失败分类与失败人话。
 *
 * <p>线程池用"原地执行"的桩——测的是编排逻辑，不是并发。
 */
class IngestPipelineTest {

    private static final String DOC_ID = "1001";
    private static final String TASK_ID = "9001";
    private static final String JOB_ID = "docmind-job-1";

    private IngestTaskService taskService;
    private KbDocService kbDocService;
    private DocIngestService docIngestService;
    private SysConfigService sysConfigService;
    private IngestPipeline pipeline;

    @BeforeEach
    void setUp() {
        taskService = mock(IngestTaskService.class);
        kbDocService = mock(KbDocService.class);
        docIngestService = mock(DocIngestService.class);
        sysConfigService = mock(SysConfigService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        // 原地执行：整条流水线在一次调用里跑完，便于断言
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        pipeline = new IngestPipeline(taskService, kbDocService, docIngestService, sysConfigService, executor);
        when(kbDocService.exists(DOC_ID)).thenReturn(true);
        when(kbDocService.require(DOC_ID)).thenReturn(doc());
        when(kbDocService.hasSourceFile(any())).thenReturn(true);
        when(taskService.create(DOC_ID)).thenReturn(task(IngestStage.PARSE, IngestStatus.RUNNING));
        // 第二段重入时按 taskId 取任务号（重启后接着跑靠的就是它）
        IngestTask persisted = task(IngestStage.SPLIT, IngestStatus.RUNNING);
        persisted.setExternalJobId(JOB_ID);
        when(taskService.require(TASK_ID)).thenReturn(persisted);
    }

    @Test
    @DisplayName("本地格式：解析是瞬时的，直接进第二段并收敛为完成")
    void localFormatRunsBothStagesInline() {
        when(docIngestService.beginParse(DOC_ID)).thenReturn(new ParseSession.Parsed(blocks()));
        stubSplitAndIndex(3);

        String taskId = pipeline.start(DOC_ID);

        assertThat(taskId).isEqualTo(TASK_ID);
        verify(taskService, never()).markSubmitted(anyString(), anyString());
        verify(taskService).startEmbed(TASK_ID, 3);
        verify(taskService).updateEmbedProgress(TASK_ID, 1);
        verify(taskService).updateEmbedProgress(TASK_ID, 3);
        verify(taskService).success(TASK_ID);
        verify(kbDocService).markDone(DOC_ID, 3);
    }

    @Test
    @DisplayName("外部格式：只提交，任务号落库，等轮询")
    void externalFormatOnlySubmits() {
        when(docIngestService.beginParse(DOC_ID)).thenReturn(new ParseSession.Submitted(JOB_ID));

        pipeline.start(DOC_ID);

        verify(taskService).markSubmitted(TASK_ID, JOB_ID);
        verify(docIngestService, never()).splitAndIndex(anyString(), any(), any(), any());
        verify(kbDocService, never()).markDone(anyString(), anyInt());
    }

    @Test
    @DisplayName("失败分类：输入有问题 → fatal（不给「重新处理」按钮）")
    void inputFailureIsFatal() {
        when(docIngestService.beginParse(DOC_ID)).thenThrow(
                new DocParseException(DocParseException.Kind.INPUT, "文件无法打开，请确认不是加密件或损坏文件"));

        pipeline.start(DOC_ID);

        verify(taskService).fail(eq(TASK_ID), eq("文件无法打开，请确认不是加密件或损坏文件"));
        verify(kbDocService).markFailed(DOC_ID, "文件无法打开，请确认不是加密件或损坏文件", DocFailType.FATAL);
    }

    @Test
    @DisplayName("失败分类：拿到结果但不合格 → retryable，不是 done + 0 片")
    void splitValidationFailureIsRetryable() {
        when(docIngestService.beginParse(DOC_ID))
                .thenThrow(new SplitValidationException("切分结果未通过校验：拼接后与原文不一致（原文 800 字，切分拼回 640 字），请重新处理"));

        pipeline.start(DOC_ID);

        ArgumentCaptor<DocFailType> type = ArgumentCaptor.forClass(DocFailType.class);
        verify(kbDocService).markFailed(eq(DOC_ID), anyString(), type.capture());
        assertThat(type.getValue()).isEqualTo(DocFailType.RETRYABLE);
    }

    @Test
    @DisplayName("处理中被删的文档：不写任何东西，任务标失败说清原因")
    void deletedDocStopsBeforeWriting() {
        when(kbDocService.exists(DOC_ID)).thenReturn(false);

        pipeline.start(DOC_ID);

        verify(docIngestService, never()).beginParse(anyString());
        verify(kbDocService).markFailed(eq(DOC_ID), eq("文档已删除"), eq(DocFailType.RETRYABLE));
    }

    @Test
    @DisplayName("同一文档同时最多一个 running 任务；没有原文件的（回流容器）不给重新处理")
    void rejectsConcurrentAndSourcelessRuns() {
        when(kbDocService.hasSourceFile(any())).thenReturn(true);
        when(taskService.hasRunning(DOC_ID)).thenReturn(true);
        assertThatThrownBy(() -> pipeline.start(DOC_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.DOC_INGESTING.getCode());

        when(taskService.hasRunning(DOC_ID)).thenReturn(false);
        when(kbDocService.hasSourceFile(any())).thenReturn(false);
        assertThatThrownBy(() -> pipeline.start(DOC_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.DOC_HAS_NO_SOURCE.getCode());

        // 恢复默认，避免影响后续断言（本条只验证两个拒绝分支各自的错误码）
        when(kbDocService.hasSourceFile(any())).thenReturn(true);
    }

    @Test
    @DisplayName("轮询：仍在跑 → 写真实百分比（外部服务给的量），不做任何推算")
    void pollingWritesRealPercentage() {
        IngestTask running = task(IngestStage.PARSE, IngestStatus.RUNNING);
        running.setExternalJobId(JOB_ID);
        stubScan(List.of(running));
        when(docIngestService.pollParse(JOB_ID)).thenReturn(
                new DocParseModel.ParseStatus(DocParseModel.ParseState.RUNNING, 37, "processing"));

        pipeline.pollRunning();

        verify(taskService).updateParseProgress(TASK_ID, 37);
        verify(taskService, never()).moveToSplit(anyString());
    }

    @Test
    @DisplayName("轮询：解析完成 → 推进阶段并投回线程池做第二段")
    void pollingResumesOnSuccess() {
        IngestTask running = task(IngestStage.PARSE, IngestStatus.RUNNING);
        running.setExternalJobId(JOB_ID);
        stubScan(List.of(running));
        when(docIngestService.pollParse(JOB_ID)).thenReturn(
                new DocParseModel.ParseStatus(DocParseModel.ParseState.SUCCESS, 100, "解析完成"));
        when(docIngestService.loadBlocks(DOC_ID, JOB_ID)).thenReturn(blocks());
        stubSplitAndIndex(2);

        pipeline.pollRunning();

        // 轮询推一次阶段（让扫描不再挑中它，避免第二段被重复投递），第二段起手再推一次（幂等）
        verify(taskService, atLeastOnce()).moveToSplit(TASK_ID);
        verify(docIngestService).loadBlocks(DOC_ID, JOB_ID);
        verify(kbDocService).markDone(DOC_ID, 2);
    }

    @Test
    @DisplayName("轮询：等待超时 → failed(retryable)，原因说清等了多久")
    void pollingTimesOut() {
        IngestTask running = task(IngestStage.PARSE, IngestStatus.RUNNING);
        running.setExternalJobId(JOB_ID);
        running.setCreatedAt(LocalDateTime.now().minusHours(2));
        stubScan(List.of(running));

        pipeline.pollRunning();

        verify(taskService).fail(eq(TASK_ID), eq("解析超时（已等待 30 分钟），请重新处理"));
        verify(kbDocService).markFailed(eq(DOC_ID), anyString(), eq(DocFailType.RETRYABLE));
        verify(docIngestService, never()).pollParse(anyString());
    }

    @Test
    @DisplayName("轮询间隔节流：距上次轮询不足间隔就不问外部服务（省一次计费请求）")
    void pollingRespectsInterval() {
        IngestTask running = task(IngestStage.PARSE, IngestStatus.RUNNING);
        running.setExternalJobId(JOB_ID);
        running.setUpdatedAt(LocalDateTime.now());
        stubScan(List.of(running));

        pipeline.pollRunning();

        verify(docIngestService, never()).pollParse(anyString());
    }

    @Test
    @DisplayName("卡住的 split/embed 任务被判中断：否则它既跑不完、又因「同时最多一个」而重跑不了")
    void staleTasksAreFailedSoTheyCanBeReprocessed() {
        IngestTask stuck = task(IngestStage.EMBED, IngestStatus.RUNNING);
        stuck.setUpdatedAt(LocalDateTime.now().minusHours(2));
        stubScan(List.of(stuck));

        pipeline.pollRunning();

        verify(taskService).fail(TASK_ID, "入库中断（服务重启或超时），请重新处理");
        verify(docIngestService, never()).pollParse(anyString());
    }

    @Test
    @DisplayName("提交途中退出的任务（parse 但没有任务号）同样算卡住——它既不可轮询，也不是 split/embed，最容易漏")
    void staleParseWithoutJobIdIsAlsoFailed() {
        IngestTask orphan = task(IngestStage.PARSE, IngestStatus.RUNNING);
        orphan.setExternalJobId(null);
        orphan.setUpdatedAt(LocalDateTime.now().minusHours(2));
        stubScan(List.of(orphan));

        pipeline.pollRunning();

        verify(taskService).fail(TASK_ID, "入库中断（服务重启或超时），请重新处理");
    }

    @Test
    @DisplayName("刚起手、还没来得及提交的任务不算卡住（updated_at 是刚刚）")
    void freshTaskIsNotJudgedStale() {
        IngestTask fresh = task(IngestStage.PARSE, IngestStatus.RUNNING);
        fresh.setExternalJobId(null);
        fresh.setUpdatedAt(LocalDateTime.now());
        stubScan(List.of(fresh));

        pipeline.pollRunning();

        verify(taskService, never()).fail(anyString(), anyString());
    }

    @Test
    @DisplayName("外部解析失败：分类由解析层给——加密件落 fatal，抖动落 retryable")
    void parseFailureKeepsItsClassification() {
        IngestTask running = task(IngestStage.PARSE, IngestStatus.RUNNING);
        running.setExternalJobId(JOB_ID);
        stubScan(List.of(running));
        when(docIngestService.pollParse(JOB_ID)).thenReturn(new DocParseModel.ParseStatus(
                DocParseModel.ParseState.FAILED, 0, "文件无法打开，请确认不是加密件或损坏文件",
                DocParseException.Kind.INPUT));

        pipeline.pollRunning();

        verify(kbDocService).markFailed(DOC_ID, "文件无法打开，请确认不是加密件或损坏文件", DocFailType.FATAL);
    }

    // ---------- 桩 ----------

    /** 让 splitAndIndex 按给定总数回调 onTotal 与逐批 onProgress，并返回总数 */
    private void stubSplitAndIndex(int chunkTotal) {
        when(docIngestService.splitAndIndex(eq(DOC_ID), any(), any(), any())).thenAnswer(invocation -> {
            IntConsumer onTotal = invocation.getArgument(2);
            IntConsumer onProgress = invocation.getArgument(3);
            onTotal.accept(chunkTotal);
            for (int i = 1; i <= chunkTotal; i++) {
                onProgress.accept(i);
            }
            return chunkTotal;
        });
    }

    private void stubScan(List<IngestTask> tasks) {
        when(sysConfigService.getInt(eq(SysConfigService.KEY_INGEST_POLL_INTERVAL_SECONDS), anyInt()))
                .thenReturn(SysConfigService.DEFAULT_INGEST_POLL_INTERVAL_SECONDS);
        when(sysConfigService.getInt(eq(SysConfigService.KEY_INGEST_PARSE_TIMEOUT_MINUTES), anyInt()))
                .thenReturn(SysConfigService.DEFAULT_INGEST_PARSE_TIMEOUT_MINUTES);
        when(taskService.listRunningTasks(anyInt())).thenReturn(tasks);
    }

    private IngestTask task(IngestStage stage, IngestStatus status) {
        IngestTask task = new IngestTask();
        task.setId(TASK_ID);
        task.setDocId(DOC_ID);
        task.setStage(stage);
        task.setStatus(status);
        // 刚创建的任务没有 updated_at（MyBatis 的自动填充不在单测里跑），节流判定按"没问过"处理
        task.setUpdatedAt(null);
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }

    private KbDoc doc() {
        KbDoc doc = new KbDoc();
        doc.setId(DOC_ID);
        doc.setDeptId("dept-1");
        doc.setTitle("心血管内科分诊知识");
        doc.setFileUrl("kb/" + DOC_ID + "/心内科.txt");
        return doc;
    }

    private List<LayoutBlock> blocks() {
        return List.of(LayoutBlock.title("标题", null), LayoutBlock.text("正文", null));
    }
}
