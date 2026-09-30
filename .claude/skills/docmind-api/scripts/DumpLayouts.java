import com.aliyun.docmind_api20220711.Client;
import com.aliyun.docmind_api20220711.models.GetDocParserResultRequest;
import com.aliyun.docmind_api20220711.models.GetDocParserResultResponse;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把某个 DocumentMind 任务的全部版面块拉下来写成 JSON（**只读接口，不重复计费**）。
 *
 * <p>用来回答"这份文档的每个块到底是什么类型"——探针的 report.txt 有 40000 字符上限
 * 装不下整篇，要看全量就得用这个。
 *
 * <p>用法：`./dump-layouts.sh <jobId> <输出文件>`，任务号从
 * `SELECT external_job_id FROM ingest_task WHERE doc_id='...'` 拿。
 */
public class DumpLayouts {

    public static void main(String[] args) throws Exception {
        String jobId = args[0];
        Path out = Path.of(args[1]);
        Client client = new Client(new Config()
                .setAccessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
                .setAccessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
                .setRegionId(System.getenv().getOrDefault("DOCMIND_REGION", "cn-hangzhou"))
                .setEndpoint(System.getenv().getOrDefault("DOCMIND_ENDPOINT",
                        "docmind-api.cn-hangzhou.aliyuncs.com")));

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        ArrayNode all = mapper.createArrayNode();
        int step = 50;
        int batches = 0;
        for (int offset = 0; offset < 5000; offset += step) {
            GetDocParserResultResponse response = client.getDocParserResult(
                    new GetDocParserResultRequest().setId(jobId).setLayoutNum(offset).setLayoutStepSize(step));
            String json = mapper.writeValueAsString(response.getBody());
            JsonNode layouts = mapper.readTree(json).path("data").path("layouts");
            if (!layouts.isArray() || layouts.isEmpty()) {
                break;
            }
            batches++;
            layouts.forEach(all::add);
            if (layouts.size() < step) {
                break;
            }
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("jobId", jobId);
        root.put("batches", batches);
        root.set("layouts", all);
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        System.out.println("[dump-layouts] 块数=" + all.size() + " 批次=" + batches + " → " + out.toAbsolutePath());
    }
}
