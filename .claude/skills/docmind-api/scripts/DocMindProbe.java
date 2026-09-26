package probe;

import com.aliyun.docmind_api20220711.Client;
import com.aliyun.docmind_api20220711.models.GetDocParserResultRequest;
import com.aliyun.docmind_api20220711.models.GetDocParserResultResponse;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusRequest;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponse;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobAdvanceRequest;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DocumentMind 探针 —— 复验工具，不是测试。
 *
 * <p>回答四个问题：① {@code OutputFormat} 收哪些值；② 默认输出什么形态、签名多久过期；
 * ③ 版面块里有没有类型/层级/坐标/表格；④ 分页取版面块怎么调。
 *
 * <p><b>不重复计费</b>：设了 {@code PROBE_JOB_ID} 就跳过提交，直接读那个任务的结果——
 * {@code getDocParserResult} / {@code queryDocParserStatus} 都是读接口，反复拉不再花钱。
 * 调试时先看手上有没有现成的任务号，不要动辄重新提交。
 *
 * <p><b>报告落盘 UTF-8</b>：Windows 控制台会把中文压成乱码，完整报告写到
 * {@code PROBE_OUT/report.txt}——<b>看那个文件，不要看控制台</b>。
 *
 * <p>环境变量：
 * <pre>
 *   ALIBABA_CLOUD_ACCESS_KEY_ID      必填
 *   ALIBABA_CLOUD_ACCESS_KEY_SECRET  必填
 *   PROBE_SAMPLE                     样本文件绝对路径（未设 PROBE_JOB_ID 时必填）
 *   PROBE_OUT                        输出目录，默认 target/probe-out
 *   PROBE_JOB_ID                     可选，复用已有任务号（跳过提交、不计费）
 *   PROBE_REGION / PROBE_ENDPOINT    可选，默认 cn-hangzhou / docmind-api.cn-hangzhou.aliyuncs.com
 * </pre>
 *
 * <p>怎么跑见同目录的 {@code run-probe.sh}。
 */
public class DocMindProbe {

    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 5_000L;
    private static final int DUMP_LIMIT = 40_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 报告既打控制台也落盘：控制台给进度，文件给正文（UTF-8，不被 Windows 控制台糟蹋） */
    private static final StringBuilder REPORT = new StringBuilder();

    public static void main(String[] args) throws Exception {
        String ak = require("ALIBABA_CLOUD_ACCESS_KEY_ID");
        String sk = require("ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        Path outDir = Path.of(env("PROBE_OUT", "target/probe-out"));
        Files.createDirectories(outDir);
        String presetJobId = System.getenv("PROBE_JOB_ID");

        try {
            Client client = new Client(new Config()
                    .setAccessKeyId(ak)
                    .setAccessKeySecret(sk)
                    .setRegionId(env("PROBE_REGION", "cn-hangzhou"))
                    .setEndpoint(env("PROBE_ENDPOINT", "docmind-api.cn-hangzhou.aliyuncs.com"))
                    .setReadTimeout(60_000)
                    .setConnectTimeout(30_000));

            String jobId = presetJobId;
            if (jobId == null || jobId.isBlank()) {
                Path sample = Path.of(require("PROBE_SAMPLE"));
                say("[probe] 样本 = " + sample + "（" + Files.size(sample) + " 字节）");
                jobId = submit(client, sample);
            } else {
                say("[probe] 复用已有任务号（跳过提交，不再计费）= " + jobId);
            }
            say("[probe] 任务号 = " + jobId);

            QueryDocParserStatusResponse status = poll(client, jobId);
            say("\n========== ① 状态查询完整返回 ==========");
            say(json(status.getBody()));
            dumpResultFiles(status, outDir);
            dumpLayouts(client, jobId);
        } finally {
            Path reportFile = outDir.resolve("report.txt");
            Files.writeString(reportFile, REPORT.toString(), StandardCharsets.UTF_8);
            System.out.println("[probe] 报告已落盘（看这个文件，别看控制台）：" + reportFile.toAbsolutePath());
        }
    }

    // ---------- 提交（先试 OutputFormat，失败退回默认）----------

    private static String submit(Client client, Path sample) throws Exception {
        for (List<String> formats : List.of(List.of("markdown", "json"), List.<String>of())) {
            String label = formats.isEmpty() ? "（默认，不传 OutputFormat）" : formats.toString();
            try (InputStream in = Files.newInputStream(sample)) {
                SubmitDocParserJobAdvanceRequest req = new SubmitDocParserJobAdvanceRequest()
                        .setFileUrlObject(in)                       // 传流，不传 URL
                        .setFileName(sample.getFileName().toString())
                        .setFileNameExtension(extension(sample))
                        .setNeedHeaderFooter(false);                // 不设的话页眉会变成标题块
                if (!formats.isEmpty()) {
                    req.setOutputFormat(formats);
                }
                SubmitDocParserJobResponse resp = client.submitDocParserJobAdvance(req, new RuntimeOptions());
                say("[probe] 提交 outputFormat=" + label + " → code="
                        + resp.getBody().getCode() + " message=" + resp.getBody().getMessage());
                if (resp.getBody().getData() != null && resp.getBody().getData().getId() != null) {
                    return resp.getBody().getData().getId();
                }
            } catch (Exception e) {
                // 服务端通常会在这里回一份「合法取值」的提示，正是我们要的信息
                say("[probe] 提交 outputFormat=" + label + " 抛错：" + e.getMessage());
            }
        }
        throw new IllegalStateException("两次提交都没拿到任务号，看上面的服务端报错");
    }

    // ---------- 轮询（processing 是 0–100，不是 0–1）----------

    private static QueryDocParserStatusResponse poll(Client client, String jobId) throws Exception {
        String lastStatus = null;
        QueryDocParserStatusResponse last = null;
        for (int i = 1; i <= MAX_POLLS; i++) {
            last = client.queryDocParserStatus(new QueryDocParserStatusRequest().setId(jobId));
            var data = last.getBody() == null ? null : last.getBody().getData();
            if (data == null) {
                Thread.sleep(POLL_INTERVAL_MS);
                continue;
            }
            if (!String.valueOf(data.getStatus()).equals(lastStatus)) {
                lastStatus = data.getStatus();
                say("[probe] 第 " + i + " 次轮询，状态变为 = " + lastStatus);
            }
            say("[probe]   processing=" + data.getProcessing()
                    + " 页数估算=" + data.getPageCountEstimate()
                    + " 段落数=" + data.getParagraphCount()
                    + " 表格数=" + data.getTableCount()
                    + " 图片数=" + data.getImageCount()
                    + " tokens=" + data.getTokens()
                    + " 已成功解析=" + data.getNumberOfSuccessfulParsing()
                    + " 输出块数=" + (data.getOutputFormatResult() == null
                            ? 0 : data.getOutputFormatResult().size()));
            boolean finished = data.getOutputFormatResult() != null
                    && !data.getOutputFormatResult().isEmpty()
                    && data.getProcessing() != null && data.getProcessing() >= 100f;
            if (finished) {
                say("[probe] 判定完成（processing=100 且已给出输出）");
                return last;
            }
            if ("success".equalsIgnoreCase(data.getStatus()) || "failed".equalsIgnoreCase(data.getStatus())) {
                return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        say("[probe] 轮询到上限仍未完成，按当前状态继续");
        return last;
    }

    // ---------- 下载结果文件 ----------

    private static void dumpResultFiles(QueryDocParserStatusResponse status, Path outDir) throws Exception {
        var data = status.getBody() == null ? null : status.getBody().getData();
        if (data == null || data.getOutputFormatResult() == null) {
            say("[probe] 没有 outputFormatResult，跳过下载");
            return;
        }
        int i = 0;
        for (var fmt : data.getOutputFormatResult()) {
            i++;
            String ext = fmt.getOutputType() == null ? "bin" : fmt.getOutputType();
            say("\n========== ② 输出 " + i + "：outputType=" + ext
                    + "，pages=" + (fmt.getPages() == null ? 0 : fmt.getPages().size()) + " ==========");
            say("outputFileUrl = " + fmt.getOutputFileUrl());
            if (fmt.getOutputFileUrl() == null) {
                continue;
            }
            long now = System.currentTimeMillis() / 1000L;
            say("（现在 = " + now + "，链接自带的 Expires 见上面的 URL 参数，相减就是剩余有效期）");
            HttpResponse<byte[]> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(fmt.getOutputFileUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            say("HTTP " + res.statusCode() + "，下载 " + res.body().length + " 字节");
            Path saved = outDir.resolve("result-" + i + "." + ext);
            Files.write(saved, res.body());
            say("已存到 " + saved.toAbsolutePath());
            say("---- 内容全文 ----");
            say(new String(res.body(), StandardCharsets.UTF_8));
        }
    }

    // ---------- 分页拉版面块 ----------

    private static void dumpLayouts(Client client, String jobId) throws Exception {
        say("\n========== ③ getDocParserResult（layoutNum=0, layoutStepSize=50）==========");
        try {
            GetDocParserResultResponse resp = client.getDocParserResult(
                    new GetDocParserResultRequest().setId(jobId).setLayoutNum(0).setLayoutStepSize(50));
            say(json(resp.getBody()));
        } catch (Exception e) {
            say("[probe] 取版面块失败：" + e.getMessage());
        }
    }

    // ---------- 工具 ----------

    private static void say(String s) {
        REPORT.append(s).append('\n');
        System.out.println(s);
    }

    private static String json(Object o) throws Exception {
        String s = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        return s.length() <= DUMP_LIMIT ? s : s.substring(0, DUMP_LIMIT) + "\n…（已截断，共 " + s.length() + " 字符）";
    }

    private static String extension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量：" + key);
        }
        return v;
    }
}
