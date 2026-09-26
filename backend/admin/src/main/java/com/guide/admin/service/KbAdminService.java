package com.guide.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.guide.admin.dto.KbAdminDTO;
import com.guide.async.entity.IngestTask;
import com.guide.async.service.IngestPipeline;
import com.guide.async.service.IngestTaskService;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.kb.entity.Dept;
import com.guide.kb.entity.KbChunk;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.enums.DocStatus;
import com.guide.kb.service.DeptService;
import com.guide.kb.service.KbDocService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库管理端编排（链路 B）：把 kb（文档与切片）与 async（任务表与流水线）拼成管理端要看的形状。
 *
 * <p>编排放在 admin 是本项目的既定分工：**admin 是启动器、只做编排**，
 * 业务规则各归其模块——"一个文档一个科室"归 kb、"同一文档同时最多一个 running 任务"归 async。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbAdminService {

    /** 单次列表最多返回多少行，防止前端传个大数把库拖垮 */
    private static final int MAX_PAGE_SIZE = 100;

    private final KbDocService kbDocService;
    private final DeptService deptService;
    private final IngestPipeline ingestPipeline;
    private final IngestTaskService ingestTaskService;

    /**
     * 上传并开始入库。**同步落 MinIO 与建文档，异步入库**——上传接口不能等解析完才返回。
     */
    public KbAdminDTO.IngestVO upload(MultipartFile file, String deptId, String title) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.UPLOAD_EMPTY_FILE);
        }
        String originalName = file.getOriginalFilename();
        KbDoc doc;
        try {
            doc = kbDocService.create(deptId, title, originalName,
                    file.getInputStream(), file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
        String taskId = ingestPipeline.start(doc.getId());
        KbAdminDTO.IngestVO vo = new KbAdminDTO.IngestVO();
        vo.setDocId(doc.getId());
        vo.setTaskId(taskId);
        return vo;
    }

    /**
     * 重新入库（reprocess）：**先删旧切片再重建**，用 MinIO 里的原文件。
     *
     * <p>两条硬语义（见 CONTEXT.md「重新入库」）：① 没有"替换文件"这个操作——要换内容就删掉
     * 这份文档重新上传；② 先删旧再重建，所以**失败时这份文档的内容会缺席**，直到人工再点一次。
     * 这是明知的取舍，不要顺手加"失败回滚到旧切片"。
     */
    public KbAdminDTO.IngestVO reprocess(String docId) {
        KbDoc doc = kbDocService.require(docId);
        if (!kbDocService.hasSourceFile(doc)) {
            throw new BizException(ErrorCode.DOC_HAS_NO_SOURCE);
        }
        // 这道检查与 IngestPipeline.start 里那道是**两回事**，不能删：规则本身归 async（那道才是权威），
        // 但这里挡在**删除动作之前**——顺序反了会把一个正在写入的任务的切片删掉，然后自己也起不来
        if (ingestTaskService.hasRunning(docId)) {
            throw new BizException(ErrorCode.DOC_INGESTING);
        }
        kbDocService.deleteChunks(docId);
        kbDocService.markParsing(docId);
        String taskId = ingestPipeline.start(docId);
        log.info("重新入库已提交：docId={} taskId={}", docId, taskId);
        KbAdminDTO.IngestVO vo = new KbAdminDTO.IngestVO();
        vo.setDocId(docId);
        vo.setTaskId(taskId);
        return vo;
    }

    /**
     * 删除文档：**先终止在飞的任务，再走删除补偿**。
     *
     * <p>顺序不能反：终止任务覆盖"还在轮询中"的（省下一次白付的解析费），
     * 删除补偿覆盖"已写入的东西"。反过来则可能在删完数据后，被一个刚跑完的解析任务
     * 把切片又写回来。
     */
    public void delete(String docId) {
        KbDoc doc = kbDocService.require(docId);
        ingestTaskService.terminateRunning(docId, "文档已删除");
        kbDocService.delete(docId);
        log.info("文档已删除并终止在飞任务：docId={} title={}", docId, doc.getTitle());
    }

    /** 文档分页（含最近一次运行的阶段与真实进度量） */
    public KbAdminDTO.DocPageVO page(String deptId, String status, String keyword, long pageNum, long pageSize) {
        IPage<KbDoc> page = kbDocService.page(deptId, parseStatus(status), keyword,
                Math.max(1, pageNum), Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize)));
        List<KbDoc> docs = page.getRecords();
        Map<String, String> deptNames = deptNames();
        Map<String, IngestTask> tasks = latestTasks(docs);

        List<KbAdminDTO.DocVO> records = new ArrayList<>(docs.size());
        for (KbDoc doc : docs) {
            records.add(toVO(doc, deptNames.get(doc.getDeptId()), tasks.get(doc.getId())));
        }
        KbAdminDTO.DocPageVO vo = new KbAdminDTO.DocPageVO();
        vo.setTotal(page.getTotal());
        vo.setRecords(records);
        return vo;
    }

    /** 单次运行的状态（前端上传后按 taskId 轮询进度） */
    public KbAdminDTO.TaskVO task(String taskId) {
        return toTaskVO(ingestTaskService.require(taskId));
    }

    /** 查看切片：切片正文的事实源在 MySQL，pgvector 里那份只是排查用的副本 */
    public List<KbAdminDTO.ChunkVO> chunks(String docId) {
        kbDocService.require(docId);
        List<KbAdminDTO.ChunkVO> result = new ArrayList<>();
        for (KbChunk chunk : kbDocService.listChunks(docId)) {
            KbAdminDTO.ChunkVO vo = new KbAdminDTO.ChunkVO();
            vo.setId(chunk.getId());
            vo.setTitle(chunk.getTitle());
            vo.setContent(chunk.getContent());
            vo.setSeq(chunk.getSeq());
            result.add(vo);
        }
        return result;
    }

    /** 上传表单的科室选项（含停用科室——停用只在导诊入口生效，不影响知识维护） */
    public List<KbAdminDTO.DeptOptionVO> depts() {
        Map<String, Long> counts = kbDocService.countByDept();
        List<KbAdminDTO.DeptOptionVO> options = new ArrayList<>();
        for (Dept dept : deptService.listAll()) {
            KbAdminDTO.DeptOptionVO vo = new KbAdminDTO.DeptOptionVO();
            vo.setId(dept.getId());
            vo.setName(dept.getName());
            vo.setEnabled(dept.getEnabled());
            vo.setDocCount(counts.getOrDefault(dept.getId(), 0L));
            options.add(vo);
        }
        return options;
    }

    // ---------- 内部 ----------

    private Map<String, IngestTask> latestTasks(List<KbDoc> docs) {
        if (docs.isEmpty()) {
            return Map.of();
        }
        List<String> docIds = docs.stream().map(KbDoc::getId).toList();
        return ingestTaskService.listByDocs(docIds).stream()
                .collect(Collectors.toMap(IngestTask::getDocId, Function.identity(),
                        // 列表按创建时间倒序，保留第一条即"最近一次"（失败原因看的就是它）
                        (first, second) -> first));
    }

    private Map<String, String> deptNames() {
        return deptService.listAll().stream()
                .collect(Collectors.toMap(Dept::getId, Dept::getName, (a, b) -> a, HashMap::new));
    }

    private KbAdminDTO.DocVO toVO(KbDoc doc, String deptName, IngestTask task) {
        KbAdminDTO.DocVO vo = new KbAdminDTO.DocVO();
        vo.setId(doc.getId());
        vo.setTitle(doc.getTitle());
        vo.setDeptId(doc.getDeptId());
        vo.setDeptName(deptName);
        vo.setStatus(doc.getStatus() == null ? null : doc.getStatus().getCode());
        vo.setFailReason(doc.getFailReason());
        vo.setFailType(doc.getFailType() == null ? null : doc.getFailType().getCode());
        vo.setReprocessable(kbDocService.hasSourceFile(doc));
        vo.setChunkTotal(doc.getChunkTotal());
        vo.setChunkDone(doc.getChunkDone());
        vo.setCreatedAt(doc.getCreatedAt());
        vo.setTask(toTaskVO(task));
        return vo;
    }

    private KbAdminDTO.TaskVO toTaskVO(IngestTask task) {
        if (task == null) {
            return null;
        }
        KbAdminDTO.TaskVO vo = new KbAdminDTO.TaskVO();
        vo.setTaskId(task.getId());
        vo.setStage(task.getStage() == null ? null : task.getStage().getCode());
        vo.setStatus(task.getStatus() == null ? null : task.getStatus().getCode());
        vo.setTotal(task.getTotal());
        vo.setDone(task.getDone());
        vo.setError(task.getError());
        return vo;
    }

    /** 前端传的是小写编码值，**不能用 valueOf**（那是匹配大写常量名）——按 code 遍历匹配 */
    private DocStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        for (DocStatus value : DocStatus.values()) {
            if (value.getCode().equalsIgnoreCase(status)) {
                return value;
            }
        }
        return null;
    }
}
