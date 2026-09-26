package com.guide.llm.client;

import com.aliyun.docmind_api20220711.Client;
import com.aliyun.docmind_api20220711.models.GetDocParserResultRequest;
import com.aliyun.docmind_api20220711.models.GetDocParserResultResponse;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusRequest;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponse;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobAdvanceRequest;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.guide.common.model.LayoutBlock;
import com.guide.llm.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 阿里云 DocumentMind（文档智能）客户端：pdf/docx/png 的**唯一**解析路径。
 *
 * <p>三个只有真跑才会发现的坑，代码里都钉住了（详见 {@code .claude/skills/docmind-api}）：
 * <ol>
 *   <li>{@code processing} 是 **0–100**，不是 0–1——按 0–1 判完成会在第一次轮询就"完成"</li>
 *   <li>必须设 {@code needHeaderFooter=false}——否则每页页眉都会被识别成 {@code type=title}
 *       混进正文最前面，而标题块是切分边界：每页页眉都开一个新 chunk，文档被切成一堆碎片</li>
 *   <li>文件要**传流不传 URL**——URL 要求对服务端公网可达，而本项目的 MinIO 在局域网</li>
 * </ol>
 */
@Slf4j
@Component
public class DocMindParseModel implements DocParseModel {

    /** 轮询封顶，防止服务端状态异常导致无限翻页 */
    private static final int MAX_PAGES = 200;

    private final LlmProperties properties;

    /** 客户端懒建：密钥可能到运行期才配上，启动时不该因为缺 Key 直接失败 */
    private volatile Client client;

    public DocMindParseModel(LlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public String submit(InputStream in, String fileName, String extension) {
        requireKey();
        try {
            SubmitDocParserJobAdvanceRequest request = new SubmitDocParserJobAdvanceRequest()
                    .setFileUrlObject(in)
                    .setFileName(fileName)
                    .setFileNameExtension(extension)
                    // 见类注释 ②：不设它，页眉会变成标题块，把文档切碎
                    .setNeedHeaderFooter(false);
            SubmitDocParserJobResponse response = client().submitDocParserJobAdvance(request, runtimeOptions());
            String jobId = response.getBody() == null || response.getBody().getData() == null
                    ? null : response.getBody().getData().getId();
            if (!StringUtils.hasText(jobId)) {
                throw classify("提交解析任务失败", null, response.getBody() == null ? null : response.getBody().getMessage());
            }
            log.info("已提交文档解析：fileName={} jobId={}", fileName, jobId);
            return jobId;
        } catch (DocParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocParseException(DocParseException.Kind.DEPENDENCY, "解析服务暂时不可用，请稍后重试", e);
        }
    }

    @Override
    public ParseStatus status(String jobId) {
        requireKey();
        try {
            QueryDocParserStatusResponse response = client()
                    .queryDocParserStatus(new QueryDocParserStatusRequest().setId(jobId));
            var data = response.getBody() == null ? null : response.getBody().getData();
            if (data == null) {
                // 拿不到状态按"仍在跑"处理：任务号是有效的，只是这一次没读到
                return new ParseStatus(ParseState.RUNNING, 0, "状态未知");
            }
            String status = data.getStatus() == null ? "" : data.getStatus().toLowerCase(Locale.ROOT);
            // processing 是 0–100（见类注释 ①）
            int processing = data.getProcessing() == null ? 0 : Math.round(data.getProcessing());
            if ("success".equals(status)) {
                return new ParseStatus(ParseState.SUCCESS, 100, "解析完成");
            }
            if ("failed".equals(status) || "fail".equals(status)) {
                // 异步任务的失败详情不在 data 里（data 只有进度与计数），但响应体外层有 code/message。
                // 拿得到详情就按详情分类——加密件/损坏件必须落到 fatal，否则管理端会拿到一个
                // 重跑一百遍也没用的「重新处理」按钮；拿不到就退回笼统的可重试说法
                String code = response.getBody().getCode();
                String detail = response.getBody().getMessage();
                if (StringUtils.hasText(detail) || StringUtils.hasText(code)) {
                    DocParseException failure = classify("解析任务失败", code, detail);
                    return new ParseStatus(ParseState.FAILED, processing, failure.getHumanReason(), failure.getKind());
                }
                return new ParseStatus(ParseState.FAILED, processing, "解析服务返回失败，请稍后重试");
            }
            return new ParseStatus(ParseState.RUNNING, Math.min(99, processing), status);
        } catch (Exception e) {
            throw new DocParseException(DocParseException.Kind.DEPENDENCY, "查询解析进度失败，请稍后重试", e);
        }
    }

    /**
     * 拉全部版面块。**读接口，反复拉不再计费**——所以宁可多拉几次也不要重新提交解析。
     *
     * <p>分页按**块偏移**推进（{@code layoutNum} = 已取到的块数）。服务端的分页语义没有实测过，
     * 所以做了两道防御：一是每页开得很大（默认 500 块，多数文档一次拿完），二是下一页与
     * 上一页首块相同时立即停止并记 WARN——万一 {@code layoutNum} 其实是页码，
     * 最多是少拿后面的块并留下日志，不会原地打转。
     */
    @Override
    public List<LayoutBlock> fetchBlocks(String jobId) {
        requireKey();
        int step = Math.max(1, properties.getDocmind().getLayoutStepSize());
        List<LayoutBlock> all = new ArrayList<>();
        int declaredTotal = -1;
        String lastFirstBlock = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Page current = fetchPage(jobId, all.size(), step);
            if (current.blocks().isEmpty()) {
                break;
            }
            String first = current.blocks().get(0).text();
            if (first.equals(lastFirstBlock)) {
                log.warn("取版面块出现重复页，提前结束（layoutNum 语义可能与预期不同）：jobId={} 已取 {} 块", jobId, all.size());
                break;
            }
            lastFirstBlock = first;
            declaredTotal = current.total();
            all.addAll(current.blocks());
            // 拿到声明的总数就按它判完；拿不到就按"这一页没满"判完
            if (declaredTotal >= 0 ? all.size() >= declaredTotal : current.blocks().size() < step) {
                break;
            }
        }
        if (declaredTotal >= 0 && all.size() < declaredTotal) {
            log.warn("版面块可能没取全：jobId={} 取到 {} 块，服务端声明 {} 块", jobId, all.size(), declaredTotal);
        }
        log.info("版面块拉取完成：jobId={} 共 {} 块", jobId, all.size());
        return all;
    }

    private Page fetchPage(String jobId, int offset, int step) {
        try {
            GetDocParserResultResponse response = client().getDocParserResult(new GetDocParserResultRequest()
                    .setId(jobId)
                    .setLayoutNum(offset)
                    .setLayoutStepSize(step));
            var data = response.getBody() == null ? null : response.getBody().getData();
            return new Page(DocMindLayoutReader.readBlocks(data), DocMindLayoutReader.totalBlocks(data));
        } catch (Exception e) {
            throw new DocParseException(DocParseException.Kind.DEPENDENCY, "读取解析结果失败，请稍后重试", e);
        }
    }

    /** 一页版面块 + 服务端声明的总块数（拿不到为 -1） */
    private record Page(List<LayoutBlock> blocks, int total) {
    }

    // ---------- 内部 ----------

    private Client client() {
        Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    LlmProperties.Docmind config = properties.getDocmind();
                    try {
                        local = new Client(new Config()
                                .setAccessKeyId(config.getAccessKeyId())
                                .setAccessKeySecret(config.getAccessKeySecret())
                                .setRegionId(config.getRegionId())
                                .setEndpoint(config.getEndpoint())
                                .setReadTimeout(config.getTimeoutMs())
                                .setConnectTimeout(30_000));
                    } catch (Exception e) {
                        throw new DocParseException(DocParseException.Kind.DEPENDENCY, "解析服务初始化失败，请检查配置", e);
                    }
                    client = local;
                }
            }
        }
        return local;
    }

    private RuntimeOptions runtimeOptions() {
        return new RuntimeOptions()
                .setReadTimeout(properties.getDocmind().getTimeoutMs())
                .setConnectTimeout(30_000);
    }

    /**
     * 失败分类：**再跑一次可能就好了** → 依赖故障；输入本身有问题 → 不可重试。
     *
     * <p>关键字表是启发式的（上游错误码没有逐个实测过），所以偏向"可重试"——
     * 「重新处理」是**给人用的决策入口**，人看一眼原因就分得清是加密件还是服务端抖动；
     * 而判成 fatal 会把文档卡死在只能删了重传的死路上。
     */
    private DocParseException classify(String what, String code, String message) {
        String text = ((code == null ? "" : code) + " " + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        boolean inputProblem = text.contains("password") || text.contains("encrypt") || text.contains("corrupt")
                || text.contains("损坏") || text.contains("加密") || text.contains("密码")
                || text.contains("format") || text.contains("格式") || text.contains("invalid file");
        String human = inputProblem
                ? "文件无法打开，请确认不是加密件或损坏文件"
                : "解析服务暂时不可用，请稍后重试";
        log.warn("{}：code={} message={}", what, code, message);
        return new DocParseException(inputProblem ? DocParseException.Kind.INPUT : DocParseException.Kind.DEPENDENCY, human);
    }

    private void requireKey() {
        if (!StringUtils.hasText(properties.getDocmind().getAccessKeyId())
                || !StringUtils.hasText(properties.getDocmind().getAccessKeySecret())) {
            throw new DocParseException(DocParseException.Kind.DEPENDENCY,
                    "文档解析服务未配置，请在 application-local.yml 填写 AccessKey");
        }
    }
}
