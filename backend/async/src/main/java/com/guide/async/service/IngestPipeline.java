package com.guide.async.service;

import com.guide.async.config.IngestExecutorConfig;
import com.guide.async.entity.IngestTask;
import com.guide.async.enums.IngestStage;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 入库流水线的**两段式编排**：解析是外部服务、一份件可能几分钟，不能让线程池干等。
 *
 * <ol>
 *   <li>线程池只做「**提交**」——拿到外部任务号就结束，把它记进 {@code ingest_task.external_job_id}</li>
 *   <li>{@link #pollRunning} 定时扫 `running 且 stage=parse` 的任务去**轮询**，
 *       完成后**投回线程池**做「读结果 → 切分 → 入库」</li>
 * </ol>
 *
 * <p>为什么不一路 sleep 轮询到底：① 一个文档占一个线程数分钟，批量上传时后面的干等；
 * ② **JVM 重启会丢掉正在轮询的任务**——线程死了，任务永远停在 running，变成没人管的僵尸。
 * 而"权威状态 + 可恢复"正是任务表存在的**全部理由**。
 *
 * <p>本地格式（txt/md/html）不绕这一圈：解析是瞬时的，起手就把块拿到手，直接进第二段。
 */
@Slf4j
@Service
public class IngestPipeline {

    /** 一次扫描最多处理多少个待轮询任务（避免一个扫描周期跑太久） */
    private static final int POLL_BATCH = 20;

    private final IngestTaskService taskService;
    private final KbDocService kbDocService;
    private final DocIngestService docIngestService;
    private final SysConfigService sysConfigService;
    private final ThreadPoolTaskExecutor executor;

    public IngestPipeline(IngestTaskService taskService,
                          KbDocService kbDocService,
                          DocIngestService docIngestService,
                          SysConfigService sysConfigService,
                          @Qualifier(IngestExecutorConfig.INGEST_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.taskService = taskService;
        this.kbDocService = kbDocService;
        this.docIngestService = docIngestService;
        this.sysConfigService = sysConfigService;
        this.executor = executor;
    }

    /**
     * 起一次入库：建任务 + 投线程池。
     *
     * @return taskId（上传接口立刻返回它，前端据此轮询进度）
     */
    public String start(String docId) {
        KbDoc doc = kbDocService.require(docId);
        if (!kbDocService.hasSourceFile(doc)) {
            throw new BizException(ErrorCode.DOC_HAS_NO_SOURCE);
        }
        if (taskService.hasRunning(docId)) {
            throw new BizException(ErrorCode.DOC_INGESTING);
        }
        IngestTask task = taskService.create(docId);
        dispatchSubmit(IngestRun.of(task));
        return task.getId();
    }

    // ---------- 第一段：提交 ----------

    /** 投线程池做提交；线程池拒绝（队列满）时把任务标失败，不把异常丢给上传接口的调用方 */
    private void dispatchSubmit(IngestRun run) {
        try {
            executor.execute(() -> submitStage(run));
        } catch (RuntimeException e) {
            log.warn("入库线程池拒绝新任务：{} {}", run, e.getMessage());
            fail(run, "当前入库任务过多，请稍后重新处理", DocFailType.RETRYABLE);
        }
    }

    private void submitStage(IngestRun run) {
        try {
            // 防线①的早退：文档在处理中被删了就不必再解析（那次解析是按量计费的）
            requireDocAlive(run.docId());
            ParseSession session = docIngestService.beginParse(run.docId());
            if (session instanceof ParseSession.Submitted submitted) {
                taskService.markSubmitted(run.taskId(), submitted.jobId());
                log.info("解析已提交，等待轮询：{} jobId={}", run, submitted.jobId());
                return;
            }
            // 本地格式解析是瞬时的：块已在手上，直接进第二段（这一段自己会推进阶段）
            resumeStage(run, ((ParseSession.Parsed) session).blocks());
        } catch (Exception e) {
            fail(run, reasonOf(e), failTypeOf(e));
        }
    }

    // ---------- 轮询 ----------

    /**
     * 扫描所有仍在跑的任务（定时任务调用），两种处理：
     * <ul>
     *   <li>**外部解析中**（parse 且已有任务号）→ 问一次外部状态、推进进度，完成了投回线程池做第二段</li>
     *   <li>**其余**（无任务号的 parse、split、embed）→ 没有外部进度可问，只能按"太久没动"判中断</li>
     * </ul>
     *
     * <p>第二类是必须的：进程在入库中途退出，线程没了，任务永远停在 `running`——不判中断的话
     * 这份文档既跑不完、又因为「同一文档同时最多一个 running 任务」而重跑不了，是个死胡同。
     * 无任务号的 parse 尤其容易漏（它既不是"可轮询"的，也不是 split/embed）。
     */
    public void pollRunning() {
        int interval = sysConfigService.getInt(SysConfigService.KEY_INGEST_POLL_INTERVAL_SECONDS,
                SysConfigService.DEFAULT_INGEST_POLL_INTERVAL_SECONDS);
        int timeoutMinutes = sysConfigService.getInt(SysConfigService.KEY_INGEST_PARSE_TIMEOUT_MINUTES,
                SysConfigService.DEFAULT_INGEST_PARSE_TIMEOUT_MINUTES);

        for (IngestTask task : taskService.listRunningTasks(POLL_BATCH)) {
            IngestRun run = IngestRun.of(task);
            try {
                if (task.getStage() == IngestStage.PARSE && StringUtils.hasText(task.getExternalJobId())) {
                    pollOne(task, run, interval, timeoutMinutes);
                } else if (isStale(task, timeoutMinutes)) {
                    log.warn("任务长时间无进展，判定中断：taskId={} stage={} jobId={}",
                            run.taskId(), task.getStage(), task.getExternalJobId());
                    fail(run, "入库中断（服务重启或超时），请重新处理", DocFailType.RETRYABLE);
                }
            } catch (Exception e) {
                // 单次轮询失败（网络抖动这类）不判死：只记一条，下一轮再来。
                // 真正的兜底是超时判定——连续失败到最后会得到一条说清原因的 failed，
                // 而"一次网络抖动就把文档判失败"会让管理员白重新处理一遍（还白付一次解析费）
                log.warn("轮询任务失败（下一轮重试）：{} {}", run, e.getMessage());
            }
        }
    }

    private void pollOne(IngestTask task, IngestRun run, int intervalSeconds, int timeoutMinutes) {
        if (isTooSoon(task, intervalSeconds)) {
            return;
        }
        if (isTimedOut(task, timeoutMinutes)) {
            log.warn("解析超时：{} jobId={} 已等 {} 分钟", run, task.getExternalJobId(), timeoutMinutes);
            fail(run, "解析超时（已等待 " + timeoutMinutes + " 分钟），请重新处理", DocFailType.RETRYABLE);
            return;
        }
        DocParseModel.ParseStatus status = docIngestService.pollParse(task.getExternalJobId());
        if (status.running()) {
            taskService.updateParseProgress(run.taskId(), status.processing());
            return;
        }
        if (status.failed()) {
            // 失败分类由解析层给：加密件/损坏件是**输入问题**（fatal，重跑也没用），
            // 服务端抖动是依赖问题（retryable）。这里不再一律当 retryable
            fail(run, status.message(), failTypeOf(status.failureKind()));
            return;
        }
        if (status.succeeded()) {
            taskService.moveToSplit(run.taskId());
            // 第二段投回线程池：读结果（读接口，不重复计费）→ 切分 → 入库
            dispatchResume(run);
        }
    }

    /** 长时间没动过（进度每写一次就刷新 updated_at，所以它等价于"距上次有动静多久"） */
    private boolean isStale(IngestTask task, int timeoutMinutes) {
        LocalDateTime updated = task.getUpdatedAt();
        return updated != null && updated.isBefore(IngestTaskService.staleBefore(timeoutMinutes));
    }

    /** 按配置的轮询间隔节流：一次扫描不应把同一任务连问多遍（updated_at 就是上次问的时间） */
    private boolean isTooSoon(IngestTask task, int intervalSeconds) {
        LocalDateTime last = task.getUpdatedAt();
        return last != null && last.isAfter(LocalDateTime.now().minusSeconds(Math.max(1, intervalSeconds)));
    }

    private boolean isTimedOut(IngestTask task, int timeoutMinutes) {
        LocalDateTime created = task.getCreatedAt();
        return created != null && created.isBefore(IngestTaskService.staleBefore(timeoutMinutes));
    }

    // ---------- 第二段：取结果 → 切分 → 入库 ----------

    private void dispatchResume(IngestRun run) {
        try {
            executor.execute(() -> resumeStage(run, null));
        } catch (RuntimeException e) {
            log.warn("入库线程池拒绝续跑：{} {}", run, e.getMessage());
            fail(run, "当前入库任务过多，请稍后重新处理", DocFailType.RETRYABLE);
        }
    }

    /**
     * 第二段：取块 → 切分 → 分批入库 → 收敛状态。
     *
     * <p>{@code blocks} 为 null 时自己去取（外部完成 / 重启后重入都走这条），
     * 所以这一段是**可重入的**：只依赖 docId 与任务号。
     */
    private void resumeStage(IngestRun run, List<LayoutBlock> blocks) {
        try {
            // 防线①：投回线程池后可能已经排在队列里一会儿了，写库前再确认一次文档还在
            requireDocAlive(run.docId());
            List<LayoutBlock> ready = blocks != null ? blocks
                    : docIngestService.loadBlocks(run.docId(), jobIdOf(run));
            taskService.moveToSplit(run.taskId());

            // 切片总数要等切完才知道，所以「写入 x / 共 y」的 y 由 onTotal 给，不推算
            WriteProgress progress = new WriteProgress();
            docIngestService.splitAndIndex(run.docId(), ready,
                    total -> {
                        progress.total = total;
                        taskService.startEmbed(run.taskId(), total);
                        kbDocService.markChunkProgress(run.docId(), total, 0);
                    },
                    done -> {
                        taskService.updateEmbedProgress(run.taskId(), done);
                        kbDocService.markChunkProgress(run.docId(), progress.total, done);
                    });

            taskService.success(run.taskId());
            kbDocService.markDone(run.docId(), progress.total);
            log.info("文档入库完成：docId={} 切片 {} 条", run.docId(), progress.total);
        } catch (Exception e) {
            fail(run, reasonOf(e), failTypeOf(e));
        }
    }

    // ---------- 失败留痕 ----------

    /**
     * 三个字段、三种读者：{@code fail_type} 给**程序**（决定「重新处理」按钮给不给）、
     * {@code fail_reason} 给**要动手的人**（说清下一步）、日志原文给**要排查的人**。
     *
     * <p>分类判据压成一句「再跑一次可能就好了」；偏向可重试——判成 fatal 会把文档
     * 卡死在只能删了重传的死路上，而「重新处理」本来就是给人用的决策入口。
     */
    private void fail(IngestRun run, String reason, DocFailType failType) {
        log.warn("入库失败：{} 类型={} 原因={}", run, failType, reason);
        taskService.fail(run.taskId(), reason);
        try {
            kbDocService.markFailed(run.docId(), reason, failType);
        } catch (RuntimeException e) {
            // 文档被删掉了（失败本就可能因此而来），标记失败失败不算新问题
            log.warn("标记文档失败状态时出错（文档可能已删除）：docId={} {}", run.docId(), e.getMessage());
        }
    }

    private String reasonOf(Exception e) {
        if (e instanceof DocParseException parse) {
            return parse.getHumanReason();
        }
        if (e instanceof SplitValidationException split) {
            return split.getMessage();
        }
        if (e instanceof BizException biz) {
            return biz.getMessage();
        }
        // 非受控异常：原文只进日志，人话另给一句
        log.error("入库出现未预期异常", e);
        return "入库过程出现异常，请重新处理";
    }

    private DocFailType failTypeOf(Exception e) {
        // 「拿到结果但不合格」也是 retryable：失败发生在**依赖的返回值**上，是依赖的问题不是输入的问题
        return e instanceof DocParseException parse
                ? failTypeOf(parse.getKind())
                : DocFailType.RETRYABLE;
    }

    /** 输入本身有问题 → 不可重试；其余一律可重试（宁可多给按钮，也不要把文档卡死在只能删了重传的死路） */
    private DocFailType failTypeOf(DocParseException.Kind kind) {
        return kind == DocParseException.Kind.INPUT ? DocFailType.FATAL : DocFailType.RETRYABLE;
    }

    // ---------- 内部 ----------

    /**
     * 防线①：入库校验——处理中被删的文档不再写向量与 ES。
     * 拦得住"写脏数据"，拦不住"白跑一趟"（终止在飞任务才管那件事，见删除动作）。
     */
    private void requireDocAlive(String docId) {
        if (!kbDocService.exists(docId)) {
            // 用 (code, message) 构造而不是 (ErrorCode, detail)：后者的消息是"文档不存在: 文档已删除"，
            // 拼接出来的句子会直接成为管理端看到的失败原因
            throw new BizException(ErrorCode.DOC_NOT_FOUND.getCode(), "文档已删除");
        }
    }

    private String jobIdOf(IngestRun run) {
        IngestTask task = taskService.require(run.taskId());
        return task.getExternalJobId();
    }

    /**
     * 一次运行的标识：任务号 + 文档 id。
     *
     * <p>这两个值从建任务到收敛全程成对出现（提交、轮询、续跑、失败留痕都要它们），
     * 绑成一个类型，免得每个方法都摊开传两个 String——同型参数多了迟早会串位。
     */
    private record IngestRun(String taskId, String docId) {

        static IngestRun of(IngestTask task) {
            return new IngestRun(task.getId(), task.getDocId());
        }

        @Override
        public String toString() {
            return "taskId=" + taskId + " docId=" + docId;
        }
    }

    /**
     * 写入进度的可变持有者：**切出多少片是切分的结果**，只有切完才知道，
     * 而每批写入的回调当场就需要它来拼「写入 x / 共 y」。
     */
    private static final class WriteProgress {
        private int total;
    }
}
