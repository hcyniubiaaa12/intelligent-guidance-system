package com.guide.kb.service;

import com.guide.auth.service.SysConfigService;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.common.model.LayoutBlock;
import com.guide.common.util.MinioUtil;
import com.guide.kb.dto.ChunkInput;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.parse.DocFormat;
import com.guide.kb.parse.HtmlLayoutParser;
import com.guide.kb.parse.LayoutParser;
import com.guide.kb.parse.ParseSession;
import com.guide.kb.parse.PlainTextLayoutParser;
import com.guide.kb.split.DocSplitter;
import com.guide.kb.split.SplitParams;
import com.guide.kb.split.SplitValidationException;
import com.guide.llm.client.DocParseException;
import com.guide.llm.client.DocParseModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 解析与切分的编排（链路 B 的领域逻辑，回流合成 chunk 复用同一套切分）。
 *
 * <p>格式分流在这里落地：`pdf`/`docx`/`png` 走 DocumentMind（唯一路径，**不降级**）；
 * `txt`/`md` 原生读；`html` 走 Tika——**Tika 不得接 pdf/docx**，那条刚被否掉的降级路径
 * 会顺着依赖慢慢爬回来（依赖层面也做了限制：kb 只引了 Tika 的 html 模块）。
 *
 * <p>两段式编排留出的接口就是这三个方法：{@link #beginParse}（起手）、
 * {@link #loadBlocks}（取块，重启后重入也走它）、{@link #splitAndIndex}（切分入库）。
 * 线程池、任务表、轮询都在 async，这里不碰。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocIngestService {

    /**
     * 入库批大小：批间回调进度，也是为了失败时不必把整篇重来（重来走「先删旧再重建」）。
     *
     * <p>包内可见：术语抽取（{@link TermExtractor}）复用同一个数值；批大小**只有这一处定义**。
     */
    static final int INDEX_BATCH_SIZE = 20;

    /** 产出异常的判据：全文抽出不足这么多字，基本可以断定是解析没干活（扫描件/加密件） */
    private static final int MIN_USABLE_CHARS = 20;

    private final KbDocService kbDocService;
    private final DocSplitter docSplitter;
    private final ChunkIndexService chunkIndexService;
    private final PlainTextLayoutParser plainTextLayoutParser;
    private final HtmlLayoutParser htmlLayoutParser;
    private final DocParseModel docParseModel;
    private final MinioUtil minioUtil;
    private final SysConfigService sysConfigService;
    private final TermExtractor termExtractor;
    private final MedicalTermService medicalTermService;

    // ---------- 第一段：起手 ----------

    /**
     * 起手解析：外部格式只提交（拿到任务号就结束，不让线程池干等），本地格式直接解出来。
     */
    public ParseSession beginParse(String docId) {
        KbDoc doc = kbDocService.require(docId);
        DocFormat format = requireFormat(doc);
        if (!format.external()) {
            return new ParseSession.Parsed(parseLocally(doc, format));
        }
        InputStream in = minioUtil.download(doc.getFileUrl());
        try {
            String jobId = docParseModel.submit(in, DocFormat.baseName(doc.getFileUrl()), format.getExtension());
            return new ParseSession.Submitted(jobId);
        } finally {
            closeQuietly(in, docId);
        }
    }

    /**
     * 轮询外部解析进度。放在 kb 而不是让 async 直接调 llm：**问 DocumentMind 状态**属于
     * 解析这件事的领域知识（processing 是 0–100、success 是小写状态值这些都在这一层），
     * async 只管"什么时候问、问完怎么推进任务表"。
     */
    public DocParseModel.ParseStatus pollParse(String externalJobId) {
        return docParseModel.status(externalJobId);
    }

    // ---------- 第二段：取块 ----------

    /**
     * 取版面块。**外部格式必须带任务号**——重新解析要重新付费，所以宁可失败也不偷偷重来；
     * 本地格式反过来，重解一次不花钱，直接重来即可（这正是它能撑住"重启后接着跑"的原因）。
     */
    public List<LayoutBlock> loadBlocks(String docId, String externalJobId) {
        KbDoc doc = kbDocService.require(docId);
        DocFormat format = requireFormat(doc);
        List<LayoutBlock> blocks;
        if (format.external()) {
            if (!StringUtils.hasText(externalJobId)) {
                throw new DocParseException(DocParseException.Kind.DEPENDENCY,
                        "解析任务号丢失，无法取回解析结果，请重新处理", null);
            }
            blocks = docParseModel.fetchBlocks(externalJobId);
        } else {
            blocks = parseLocally(doc, format);
        }
        return requireUsableBlocks(blocks, doc, format);
    }

    // ---------- 第三段：切分 + 入库 ----------

    /**
     * 切分并分批入库。
     *
     * <p>两个回调分别对应两件**不同的事实**，不能合成一个：{@code onTotal} 是**切分的结果**
     * （切完才知道会有多少片），{@code onProgress} 是**已经写进去的**。
     * 进度要显示真实量，就不能拿推算出来的总数去凑一根连续的进度条。
     *
     * @param onTotal    切分完成、开始写入前回调一次：切片总数
     * @param onProgress 每批入库后回调：已写入的切片数
     * @return 切片总数
     */
    public int splitAndIndex(String docId, List<LayoutBlock> blocks, IntConsumer onTotal, IntConsumer onProgress) {
        KbDoc doc = kbDocService.require(docId);
        SplitParams params = SplitParams.from(sysConfigService);
        List<ChunkInput> chunks = docSplitter.split(blocks, params, doc.getTitle());
        if (chunks.isEmpty()) {
            throw new SplitValidationException("切分结果为空：这份文档没有可用的文字内容，请检查原件");
        }
        // 抽取在切分之后、第一批写入之前：ES 一次写入即带 terms，不走"先写空再更新"的二次写路径。
        // 耗时计入 split 阶段（不为旁路新增 ingest_task 阶段——那会立刻产生"抽取失败要不要改阶段"的无解问题）
        List<ChunkInput> indexed = extractTerms(docId, chunks);
        onTotal.accept(indexed.size());
        int done = 0;
        for (int from = 0; from < indexed.size(); from += INDEX_BATCH_SIZE) {
            // 防线①**每批复查一次**：写入是分批的，中途被删的文档不能再往下写。
            // 只在起手查一次的话，删掉文档之后剩下的批照样写三处——那些切片在 MySQL 里活着、
            // 检索也命中，而文档行已经没了，页面上再也找不到它们，也没地方清理
            requireDocAlive(docId);
            List<ChunkInput> batch = indexed.subList(from, Math.min(from + INDEX_BATCH_SIZE, indexed.size()));
            chunkIndexService.indexChunks(docId, doc.getDeptId(), batch);
            done += batch.size();
            onProgress.accept(done);
        }
        log.info("切分入库完成：docId={} 块 {} → 切片 {} 条（目标 {} 字 / 上限 {} 字）",
                docId, blocks.size(), indexed.size(), params.targetLength(), params.maxLength());
        return indexed.size();
    }

    /**
     * 入库即抽取：把切好的片送进抽取器，抽到的词合并进白名单候选，切片带着术语返回。
     *
     * <p><b>整段是旁路</b>：任何失败只 WARN、不上抛。模型抖动、词库写库失败都不能让一份已经
     * 解析完的文档白费（DocumentMind 按次计费）。两层兜底——抽取器内部已逐批吞掉失败，
     * 这里再兜一层防"抽取器本身抛"与"合并写库失败"。
     */
    private List<ChunkInput> extractTerms(String docId, List<ChunkInput> chunks) {
        try {
            TermExtractor.ExtractionResult result = termExtractor.extract(docId, chunks);
            try {
                medicalTermService.mergeExtracted(docId, result.terms());
            } catch (RuntimeException e) {
                log.warn("抽取术语写白名单失败（不影响入库，ES 仍带术语）：docId={} {}", docId, e.getMessage());
            }
            return result.chunks();
        } catch (RuntimeException e) {
            log.warn("术语抽取失败，切片照常入库但本次不带术语：docId={} {}", docId, e.getMessage());
            return chunks;
        }
    }

    // ---------- 内部 ----------

    private List<LayoutBlock> parseLocally(KbDoc doc, DocFormat format) {
        LayoutParser parser = switch (format.getRoute()) {
            case TIKA -> htmlLayoutParser;
            case LOCAL_TEXT -> plainTextLayoutParser;
            // 外部格式不走这里：留个明确的分支，将来有人误加格式时会立刻炸在编译期/启动期
            case DOCMIND -> throw new IllegalStateException("外部解析格式不应走本地解析：" + format);
        };
        InputStream in = minioUtil.download(doc.getFileUrl());
        try {
            return parser.parse(in, DocFormat.baseName(doc.getFileUrl()));
        } finally {
            closeQuietly(in, doc.getId());
        }
    }

    /**
     * 关流失败只记一条日志：**不能把它变成一次假失败**——解析已经拿到了结果，
     * 因为关闭一个连接出错就把整份文档判 failed，管理员会拿着一条莫名其妙的失败原因
     * 去查原件，而原件什么毛病都没有。
     */
    private void closeQuietly(InputStream in, String docId) {
        try {
            in.close();
        } catch (IOException e) {
            log.warn("关闭原文流失败（不影响解析结果）：docId={} {}", docId, e.getMessage());
        }
    }

    /**
     * 产出校验（解析这一侧的）：拿到结果但不合格 → **retryable**，不是 done + 0 片。
     *
     * <p>人话里带数字判据（几页、多少字），它们是可行动的——管理员看一眼就知道该去检查原件。
     */
    private List<LayoutBlock> requireUsableBlocks(List<LayoutBlock> blocks, KbDoc doc, DocFormat format) {
        int chars = blocks.stream().mapToInt(b -> b.text() == null ? 0 : b.text().length()).sum();
        if (chars >= MIN_USABLE_CHARS) {
            return blocks;
        }
        int pages = blocks.stream().mapToInt(b -> b.pageNum() == null ? 0 : b.pageNum()).max().orElse(0);
        String scope = pages > 0 ? pages + " 页" : "该文档";
        String reason = "解析产出异常：" + scope + "仅抽出 " + chars + " 字，请检查原件";
        log.warn("解析产出异常：docId={} 格式={} 块数={} 字数={}", doc.getId(), format.getExtension(), blocks.size(), chars);
        throw new DocParseException(DocParseException.Kind.DEPENDENCY, reason, null);
    }

    /** 防线①：处理中被删的文档不再写向量与 ES（写入分批进行，所以每批都要问一次） */
    private void requireDocAlive(String docId) {
        if (!kbDocService.exists(docId)) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND.getCode(), "文档已删除");
        }
    }

    private DocFormat requireFormat(KbDoc doc) {
        DocFormat format = DocFormat.fromFileName(doc.getFileUrl());
        if (format == null) {
            // 上传时就该拦下的（白名单），走到这里说明库里的数据被改过或迁移过
            throw new DocParseException(DocParseException.Kind.INPUT, DocFormat.unsupportedMessage(), null);
        }
        return format;
    }

}
