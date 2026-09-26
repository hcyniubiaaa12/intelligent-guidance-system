package com.guide.kb.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.util.EsChunkUtil;
import com.guide.common.util.MinioUtil;
import com.guide.common.util.PgVectorUtil;
import com.guide.kb.entity.KbChunk;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.enums.DocFailType;
import com.guide.kb.enums.DocStatus;
import com.guide.kb.mapper.KbChunkMapper;
import com.guide.kb.mapper.KbDocMapper;
import com.guide.kb.parse.DocFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识文档服务（链路 B）：文档的**当前态**在这里（`kb_doc`，一个文档一行）；
 * 每一次运行在任务表（`ingest_task`，见 async 模块）。列表展示读前者，重试与排查读后者。
 *
 * <p>删除走**删除补偿**：先删 ES → 再删向量 → 再删元数据 → 再删 MinIO 文件。
 * 顺序不是仪式——反过来的话，删到一半失败就会留下「元数据没了、ES 还留着」，
 * 检索会命中已删内容而回填丢弃，表现为莫名其妙地少召回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocService {

    /** 内建标记串：种子语料与回流合成 chunk 都不是 MinIO 里的文件，不能去 MinIO 删 */
    private static final String SEED_PREFIX = "seed://";
    private static final String FEEDBACK_PREFIX = "feedback://";

    private final KbDocMapper kbDocMapper;
    private final KbChunkMapper kbChunkMapper;
    private final DeptService deptService;
    private final EsChunkUtil esChunkUtil;
    private final PgVectorUtil pgVectorUtil;
    private final MinioUtil minioUtil;

    /**
     * 建文档：**先落 MinIO，再落库**。
     *
     * <p>对象键是 {@code kb/{docId}/{原始文件名}}——带上原文件名是为了两件事：
     * 分流按扩展名做（名字改错就走错解析器，代价是一条留痕可重试的失败记录），
     * 以及排查时一眼看得出这是哪份件。
     *
     * @param originalName 原始文件名（含扩展名）
     */
    public KbDoc create(String deptId, String title, String originalName,
                        InputStream content, long size, String contentType) {
        if (deptService.getById(deptId) == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        DocFormat format = DocFormat.fromFileName(originalName);
        if (format == null) {
            throw new BizException(ErrorCode.UPLOAD_FORMAT_UNSUPPORTED.getCode(), DocFormat.unsupportedMessage());
        }
        String docId = IdWorker.getIdStr();
        String objectKey = "kb/" + docId + "/" + DocFormat.baseName(originalName);
        minioUtil.upload(objectKey, content, size, contentType);

        KbDoc doc = new KbDoc();
        doc.setId(docId);
        doc.setDeptId(deptId);
        doc.setTitle(title == null || title.isBlank() ? DocFormat.baseName(originalName) : title.strip());
        doc.setFileUrl(objectKey);
        doc.setStatus(DocStatus.PARSING);
        kbDocMapper.insert(doc);
        log.info("文档已建：docId={} deptId={} title={} 格式={}", docId, deptId, doc.getTitle(), format.getExtension());
        return doc;
    }

    public KbDoc require(String docId) {
        KbDoc doc = docId == null ? null : kbDocMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND);
        }
        return doc;
    }

    /** 文档是否还在（删除后为 false）：入库流水线写完前用它做防线①的校验 */
    public boolean exists(String docId) {
        return docId != null && kbDocMapper.selectById(docId) != null;
    }

    public IPage<KbDoc> page(String deptId, DocStatus status, String keyword, long pageNum, long pageSize) {
        return kbDocMapper.selectPage(new Page<>(pageNum, pageSize), Wrappers.<KbDoc>lambdaQuery()
                .eq(deptId != null && !deptId.isBlank(), KbDoc::getDeptId, deptId)
                .eq(status != null, KbDoc::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), KbDoc::getTitle, keyword)
                .orderByDesc(KbDoc::getCreatedAt));
    }

    /** 该文档的切片（管理端「查看切片」）：按 seq 排，正文以 MySQL 为准 */
    public List<KbChunk> listChunks(String docId) {
        return kbChunkMapper.selectList(Wrappers.<KbChunk>lambdaQuery()
                .eq(KbChunk::getDocId, docId)
                .orderByAsc(KbChunk::getSeq));
    }

    /** 每个科室的文档数（上传表单的科室选项要显示"这个科室下有几份"） */
    public Map<String, Long> countByDept() {
        return kbDocMapper.selectList(Wrappers.<KbDoc>lambdaQuery()
                        .select(KbDoc::getDeptId))
                .stream()
                .collect(Collectors.groupingBy(KbDoc::getDeptId, Collectors.counting()));
    }

    // ---------- 状态机 ----------

    /** 重新处理开始时重置：进度与失败原因都清掉，否则页面上还挂着上一次的失败 */
    public void markParsing(String docId) {
        update(docId, doc -> {
            doc.setStatus(DocStatus.PARSING);
            doc.setFailReason(null);
            doc.setFailType(null);
            doc.setChunkTotal(null);
            doc.setChunkDone(null);
        });
    }

    /** 全部切片写入成功（三处都写完）才置完成 */
    public void markDone(String docId, int chunkTotal) {
        update(docId, doc -> {
            doc.setStatus(DocStatus.DONE);
            doc.setFailReason(null);
            doc.setFailType(null);
            doc.setChunkTotal(chunkTotal);
            doc.setChunkDone(chunkTotal);
        });
    }

    public void markFailed(String docId, String reason, DocFailType failType) {
        update(docId, doc -> {
            doc.setStatus(DocStatus.FAILED);
            doc.setFailReason(reason);
            doc.setFailType(failType);
        });
    }

    /** 写入进度（管理端列表直接读 kb_doc，进度也让它看得见） */
    public void markChunkProgress(String docId, int total, int done) {
        update(docId, doc -> {
            doc.setChunkTotal(total);
            doc.setChunkDone(done);
        });
    }

    private void update(String docId, java.util.function.Consumer<KbDoc> mutator) {
        KbDoc doc = require(docId);
        mutator.accept(doc);
        kbDocMapper.updateById(doc);
    }

    // ---------- 删除与重新处理 ----------

    /**
     * 删除文档（删除补偿的完整顺序）：先删 ES → 再删向量 → 再删元数据 → 再删 MinIO 文件。
     *
     * <p>注意：调用方**要先终止该文档在飞的任务**（async 层），否则一次解析跑完才发现文档没了
     * ——防线①（入库前查文档是否仍有效）拦得住写脏数据，拦不住那次**按量计费的解析**白跑一趟。
     *
     * <p><b>为什么整段仍然裹在事务里</b>（看着与"顺序保证"矛盾，其实不是）：
     * 顺序本身是按"失败时倒向哪一边"选的——ES/向量先删，万一停在中间，留下的是
     * 「MySQL 有、检索没有」，表现为"这份文档召回不出来"，看得见；反过来留下的是
     * 「检索有、MySQL 没有」，那才是**静默**少召回。
     * 而事务在这里的作用是**让这个动作可以重来**：末段（MinIO）失败时 MySQL 一并回滚，
     * 文档行还在，管理员再点一次删除即可收敛（ES 与向量的删除都是幂等的）。
     * 若去掉事务，元数据会先落地删除——文档行没了，MinIO 里的对象就再也没有入口清理，
     * 变成永远查不出来、也删不掉的残留。
     *
     * <p>已知缺口：失败到重试之间那段窗口里，库里是"文档在、切片在、检索副本没了"的半拉状态，
     * 页面上看不出来（没有"待重试"标记的载体）。见 进度.md 待办。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KbDoc doc = require(docId);
        deleteChunks(docId);
        if (isMinioObject(doc.getFileUrl())) {
            minioUtil.remove(doc.getFileUrl());
        }
        kbDocMapper.deleteById(docId);
        log.info("文档已删除：docId={} title={}", docId, doc.getTitle());
    }

    /**
     * 只删切片（重新入库的「先删旧再重建」用）：ES → 向量 → MySQL 元数据。
     *
     * <p>**先删旧再重建**是明确的取舍：失败时这份文档的内容会缺席，直到人工再点一次。
     * 不要顺手加"失败回滚到旧切片"——那要先写新再删旧，而两份产物在同一张表里
     * 只能靠 id 区分，回滚路径本身就成了新的故障源。
     */
    public void deleteChunks(String docId) {
        List<String> chunkIds = kbChunkMapper.selectList(Wrappers.<KbChunk>lambdaQuery()
                        .eq(KbChunk::getDocId, docId))
                .stream().map(KbChunk::getId).toList();
        if (chunkIds.isEmpty()) {
            return;
        }
        esChunkUtil.deleteChunks(chunkIds);
        pgVectorUtil.deleteChunkVectorsByDoc(docId);
        kbChunkMapper.delete(Wrappers.<KbChunk>lambdaQuery().eq(KbChunk::getDocId, docId));
        log.info("旧切片已清理：docId={} 切片 {} 条", docId, chunkIds.size());
    }

    /** 该文档有没有原文件可重跑（回流容器与种子语料没有） */
    public boolean hasSourceFile(KbDoc doc) {
        String url = doc.getFileUrl();
        return isMinioObject(url);
    }

    private boolean isMinioObject(String fileUrl) {
        return fileUrl != null && !fileUrl.isBlank()
                && !fileUrl.startsWith(SEED_PREFIX) && !fileUrl.startsWith(FEEDBACK_PREFIX);
    }
}
