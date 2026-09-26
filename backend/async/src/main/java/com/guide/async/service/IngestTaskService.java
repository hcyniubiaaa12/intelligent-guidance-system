package com.guide.async.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.async.entity.IngestTask;
import com.guide.async.enums.IngestStage;
import com.guide.async.enums.IngestStatus;
import com.guide.async.mapper.IngestTaskMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库任务表（`ingest_task`）：**每一次运行**的记录（首次 + 历次重新入库）。
 * 与 `kb_doc`（文档的当前态，一个文档一行）是两件事，不要合并——
 * 合并会让重试覆盖掉上一次的失败原因，"重试过几次、上次为什么失败"就再也答不上来。
 *
 * <p>表担着四件事：**阶段**（parse/split/embed 在向量表里是零行，只有它能表示）、
 * **进度计数**、**失败留痕**、**异步句柄**（上传接口不等解析完，把 taskId 交前端轮询）。
 *
 * <p>进度计数按阶段复用 `total` / `done`：parse 阶段 `total=100 / done=processing`
 * （外部服务给的真百分比），embed 阶段 `total=切片数 / done=已写入数`；
 * split 阶段没有进度——**切出多少片是切分的结果，解析阶段不可能知道**，
 * 任何形如「15 / 38 · 解析中」的显示都是编出来的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestTaskService {

    /** parse 阶段的进度是 0–100 的百分比（外部服务的 processing 就是这个量纲） */
    private static final int PARSE_PROGRESS_TOTAL = 100;

    private final IngestTaskMapper taskMapper;

    /** 开一次运行：stage=parse、running，进度按外部服务的百分比口径初始化 */
    public IngestTask create(String docId) {
        IngestTask task = new IngestTask();
        task.setDocId(docId);
        task.setStage(IngestStage.PARSE);
        task.setStatus(IngestStatus.RUNNING);
        task.setTotal(PARSE_PROGRESS_TOTAL);
        task.setDone(0);
        taskMapper.insert(task);
        return task;
    }

    public IngestTask require(String taskId) {
        IngestTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.INGEST_TASK_NOT_FOUND);
        }
        return task;
    }

    /**
     * 该文档有没有正在跑的任务。
     *
     * <p>这条比「以 taskId 为键的幂等」更强，而后者管不到它：两条任务各自的 taskId 不同、
     * 各自都"幂等"，但合起来不幂等。放任两条并跑，按「先删旧再重建」的语义会交错删除与写入，
     * 最终库里是哪一套取决于谁最后写完——而且**同一份文档付两次解析费**。
     */
    public boolean hasRunning(String docId) {
        return taskMapper.selectCount(Wrappers.<IngestTask>lambdaQuery()
                .eq(IngestTask::getDocId, docId)
                .eq(IngestTask::getStatus, IngestStatus.RUNNING)) > 0;
    }

    /** 该文档最近一次运行（列表展示"正在做什么、上次为什么失败"读它） */
    public IngestTask latest(String docId) {
        List<IngestTask> tasks = taskMapper.selectList(Wrappers.<IngestTask>lambdaQuery()
                .eq(IngestTask::getDocId, docId)
                .orderByDesc(IngestTask::getCreatedAt)
                .last("LIMIT 1"));
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    /** 批量取最近一次运行（列表页按页取一次，不在循环里回表） */
    public List<IngestTask> listByDocs(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(Wrappers.<IngestTask>lambdaQuery()
                .in(IngestTask::getDocId, docIds)
                .orderByDesc(IngestTask::getCreatedAt));
    }

    /**
     * 扫**所有**仍在跑的任务，最久没动的排前面。
     *
     * <p>刻意不按阶段分开查：`parse` 且**没有任务号**的任务（提交途中进程退出、或本地格式
     * 解析时退出）不在"可轮询"之列，但它同样卡着——只查 parse+jobId 的话，这类任务会永远停在
     * `running`：文档卡在「解析中」跑不完，又因为「同一文档同时最多一个 running 任务」
     * 而重跑被拒（5005），最后只能删了重传。**扫描与判定都要覆盖它**。
     *
     * <p>按 `updated_at` 升序：进度每写一次就刷新它，所以"最久没动"的正是最该被处理的，
     * 活跃任务自然沉到队尾，不会被一批新任务挤掉名额。
     */
    public List<IngestTask> listRunningTasks(int limit) {
        return taskMapper.selectList(Wrappers.<IngestTask>lambdaQuery()
                .eq(IngestTask::getStatus, IngestStatus.RUNNING)
                .orderByAsc(IngestTask::getUpdatedAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    // ---------- 阶段推进 ----------

    /** 外部解析已提交：记下任务号——重启后接着轮询靠的就是它（这个值推不出来，丢了就真接不上） */
    public void markSubmitted(String taskId, String externalJobId) {
        update(taskId, task -> {
            task.setExternalJobId(externalJobId);
            task.setStage(IngestStage.PARSE);
            task.setTotal(PARSE_PROGRESS_TOTAL);
            task.setDone(0);
        });
    }

    /** 解析进度：外部服务给的真百分比，不推算 */
    public void updateParseProgress(String taskId, int processing) {
        update(taskId, task -> {
            task.setTotal(PARSE_PROGRESS_TOTAL);
            task.setDone(Math.max(0, Math.min(PARSE_PROGRESS_TOTAL, processing)));
        });
    }

    /**
     * 进入切分：这一步没有进度可显示（本地瞬时），把计数器清空，免得页面显示上一阶段的数字。
     *
     * <p>**幂等**：轮询到解析完成时推一次（让扫描不再挑中它，这是"不重复投递第二段"的判据），
     * 第二段起手再推一次（本地格式直接进第二段、以及重入时都要）。已在切分阶段就不重复写库。
     */
    public void moveToSplit(String taskId) {
        IngestTask task = require(taskId);
        if (task.getStage() == IngestStage.SPLIT) {
            return;
        }
        task.setStage(IngestStage.SPLIT);
        task.setTotal(null);
        task.setDone(null);
        taskMapper.updateById(task);
    }

    /** 进入写入：进度口径切换成「已写入 x / 共 y 片」 */
    public void startEmbed(String taskId, int chunkTotal) {
        update(taskId, task -> {
            task.setStage(IngestStage.EMBED);
            task.setTotal(chunkTotal);
            task.setDone(0);
        });
    }

    public void updateEmbedProgress(String taskId, int done) {
        update(taskId, task -> task.setDone(done));
    }

    public void success(String taskId) {
        update(taskId, task -> {
            task.setStage(IngestStage.DONE);
            task.setStatus(IngestStatus.SUCCESS);
            task.setError(null);
        });
    }

    public void fail(String taskId, String reason) {
        update(taskId, task -> {
            task.setStatus(IngestStatus.FAILED);
            task.setError(reason);
        });
    }

    /**
     * 终止该文档在飞的任务（删除文档时调用）。
     *
     * <p>置 `failed` 而不是 `success`：success 会让人以为处理完成了，而它并没有。
     * 定时任务扫到 failed 后自然跳过。
     *
     * @return 终止了几条
     */
    public int terminateRunning(String docId, String reason) {
        List<IngestTask> running = taskMapper.selectList(Wrappers.<IngestTask>lambdaQuery()
                .eq(IngestTask::getDocId, docId)
                .eq(IngestTask::getStatus, IngestStatus.RUNNING));
        for (IngestTask task : running) {
            task.setStatus(IngestStatus.FAILED);
            task.setError(reason);
            taskMapper.updateById(task);
        }
        if (!running.isEmpty()) {
            log.info("已终止在飞任务：docId={} 条数={} 原因={}", docId, running.size(), reason);
        }
        return running.size();
    }

    /**
     * 时间戳兜底写入。**不用 LambdaUpdateWrapper**：`Update.set` 调用当刻就解析列名，
     * 纯单测里没有 Spring 注册 TableInfo 会直接抛（见进度.md 记的那个坑）。
     */
    private void update(String taskId, java.util.function.Consumer<IngestTask> mutator) {
        IngestTask task = require(taskId);
        mutator.accept(task);
        taskMapper.updateById(task);
    }

    /** 超时判定的时间点（早于它说明这份任务已经等太久了） */
    public static LocalDateTime staleBefore(int minutes) {
        return LocalDateTime.now().minus(Duration.ofMinutes(Math.max(1, minutes)));
    }
}
