package com.guide.memory.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.SysConfigService;
import com.guide.llm.client.DashScopeEmbeddingModel;
import com.guide.llm.config.LlmProperties;
import com.guide.llm.support.HttpJson;
import com.guide.memory.support.MemoryNegation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 背景召回 gold set 评估（默认不跑，避免常规测试产生模型费用）。
 *
 * <p><b>怎么开</b>：设置 {@code MEMORY_RECALL_EVAL=true} 与 DashScope Key（环境变量
 * {@code DASHSCOPE_API_KEY}，或 {@code backend/admin/src/main/resources/application-local.yml}）。
 * 跑法见 {@code docs/memory-recall-eval.md}。
 *
 * <p><b>它测什么</b>：不连 pgvector、不起 Spring——把背景分别按「mvalue」与「retrievalText」
 * 算向量，用真实 query 向量做余弦，模拟生产召回的两路（向量 top-3 + 词面 mvalue），
 * 对比正样本命中率与负样本误召回率。它不是单元测试：指标低不失败，输出是给提示词与阈值校准用的。
 *
 * <p><b>为什么正样本刻意让 query 不含 mvalue 原文</b>：词面路两轮都一样，只有向量文本变了。
 * 如果 query 是 mvalue 的原文子串，两轮都靠词面命中，看不出扩写到底有没有用。
 */
@EnabledIfEnvironmentVariable(named = "MEMORY_RECALL_EVAL", matches = "true")
class MemoryRecallEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOP_K = 3;

    @Test
    @DisplayName("背景召回：mvalue 基线 vs retrievalText 扩写（正样本命中率 / 负样本误召回率）")
    void evaluate() throws Exception {
        Path root = repoRoot();
        JsonNode rootNode = MAPPER.readTree(
                Files.readString(root.resolve("docs/memory-recall-gold-set.json"), StandardCharsets.UTF_8));
        List<Memory> memories = parseMemories(rootNode.path("memories"));
        List<Case> cases = parseCases(rootNode.path("cases"));

        LlmProperties llm = loadDashScope(root);
        DashScopeEmbeddingModel embedding = new DashScopeEmbeddingModel(
                llm, new HttpJson(new ObjectMapper(), llm));

        List<float[]> baseVectors = embed(embedding, memories.stream().map(Memory::value).toList());
        List<float[]> enrichedVectors = embed(embedding, memories.stream()
                .map(m -> m.retrievalText == null || m.retrievalText.isBlank() ? m.value : m.retrievalText)
                .toList());
        List<float[]> queryVectors = embed(embedding, cases.stream().map(c -> c.query).toList());

        double threshold = SysConfigService.DEFAULT_MEMORY_BACKGROUND_RECALL_THRESHOLD;
        int positive = 0;
        int baseHit = 0;
        int enrichedHit = 0;
        int baseVectorHit = 0;
        int enrichedVectorHit = 0;
        int negative = 0;
        int baseFalse = 0;
        int enrichedFalse = 0;
        List<String> detail = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            List<Integer> base = recall(queryVectors.get(i), c.query, baseVectors, memories, threshold, true);
            List<Integer> enriched = recall(queryVectors.get(i), c.query, enrichedVectors, memories, threshold, true);
            List<Integer> baseOnly = recall(queryVectors.get(i), c.query, baseVectors, memories, threshold, false);
            List<Integer> enrichedOnly = recall(queryVectors.get(i), c.query, enrichedVectors, memories, threshold, false);
            if (c.negative) {
                negative++;
                if (!base.isEmpty()) baseFalse++;
                if (!enriched.isEmpty()) enrichedFalse++;
                detail.add(String.format("[负] %s → 基线 %s / 扩写 %s", c.query,
                        values(base, memories), values(enriched, memories)));
                continue;
            }
            positive++;
            boolean baseOk = hits(base, c.expect, memories);
            boolean enrichedOk = hits(enriched, c.expect, memories);
            if (baseOk) baseHit++;
            if (enrichedOk) enrichedHit++;
            if (hits(baseOnly, c.expect, memories)) baseVectorHit++;
            if (hits(enrichedOnly, c.expect, memories)) enrichedVectorHit++;
            if (baseOk != enrichedOk) {
                detail.add(String.format("[%s] %s → 基线 %s / 扩写 %s", enrichedOk ? "救回" : "变差",
                        c.query, values(base, memories), values(enriched, memories)));
            }
        }

        System.out.println("背景召回 gold set（真实 embedding，阈值 " + threshold + "）");
        System.out.println("正样本（词面+向量）：" + baseHit + "/" + positive + "（mvalue 基线） → "
                + enrichedHit + "/" + positive + "（retrievalText 扩写）");
        // 词面两轮都只比 mvalue；个别近义（「血压偏高」↔「高血压」）词面也会命中。
        // 纯向量这行才是扩写带来的净收益。
        System.out.println("正样本（纯向量）：" + baseVectorHit + "/" + positive + "（mvalue 基线） → "
                + enrichedVectorHit + "/" + positive + "（retrievalText 扩写）");
        System.out.println("负样本误召回：" + baseFalse + "/" + negative + "（基线） → "
                + enrichedFalse + "/" + negative + "（扩写）");
        if (!detail.isEmpty()) {
            System.out.println("逐条差异（词面+向量）：");
            detail.forEach(line -> System.out.println("  " + line));
        }
    }

    /**
     * 模拟生产召回：向量 top-3（过阈值）+（可选）词面 mvalue 补位。
     *
     * <p>词面补位与生产同口径：`mentions` 且**未被当前消息否定**；扩写不参与词面。
     * `lexical=false` 时只看向量，用来把扩写的净收益从「词面本来就能命中」的样本里剥出来。
     */
    private List<Integer> recall(float[] queryVector, String query, List<float[]> vectors,
                                 List<Memory> memories, double threshold, boolean lexical) {
        record Scored(int index, double score) {
        }
        List<Scored> ranked = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            ranked.add(new Scored(i, cosine(queryVector, vectors.get(i))));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Integer> hits = new ArrayList<>();
        for (Scored s : ranked) {
            if (hits.size() >= TOP_K) break;
            if (s.score >= threshold) hits.add(s.index);
        }
        if (lexical) {
            for (int i = 0; i < memories.size() && hits.size() < TOP_K; i++) {
                String value = memories.get(i).value;
                if (!hits.contains(i)
                        && !MemoryNegation.negated(query, value)
                        && MemoryNegation.mentions(query, value)) {
                    hits.add(i);
                }
            }
        }
        return hits;
    }

    private boolean hits(List<Integer> indices, List<String> expect, List<Memory> memories) {
        for (int index : indices) {
            if (expect.contains(memories.get(index).value)) {
                return true;
            }
        }
        return false;
    }

    private String values(List<Integer> indices, List<Memory> memories) {
        return indices.stream().map(i -> memories.get(i).value).toList().toString();
    }

    private List<float[]> embed(DashScopeEmbeddingModel embedding, List<String> texts) {
        List<float[]> vectors = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += 10) {
            int to = Math.min(texts.size(), from + 10);
            vectors.addAll(embedding.embedBatch(texts.subList(from, to)));
        }
        return vectors;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private List<Memory> parseMemories(JsonNode node) {
        List<Memory> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(new Memory(item.path("key").asText(""), item.path("value").asText(""),
                    item.path("retrievalText").asText("")));
        }
        return list;
    }

    private List<Case> parseCases(JsonNode node) {
        List<Case> list = new ArrayList<>();
        for (JsonNode item : node) {
            List<String> expect = new ArrayList<>();
            for (JsonNode e : item.path("expect")) {
                expect.add(e.asText(""));
            }
            list.add(new Case(item.path("query").asText(""), expect, item.path("negative").asBoolean(false)));
        }
        return list;
    }

    private LlmProperties loadDashScope(Path root) throws Exception {
        LlmProperties llm = new LlmProperties();
        String apiKey = env("DASHSCOPE_API_KEY");
        String baseUrl = envOr("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com");
        String model = envOr("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v3");

        Path config = root.resolve("backend/admin/src/main/resources/application-local.yml");
        if (Files.exists(config)) {
            Map<String, Object> yaml;
            try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                yaml = new Yaml().load(reader);
            }
            Map<String, Object> dashscope = map(map(yaml.get("llm")).get("dashscope"));
            if (apiKey.isBlank()) apiKey = resolve(string(dashscope.get("api-key")));
            if (System.getenv("DASHSCOPE_BASE_URL") == null) {
                String configured = resolve(string(dashscope.get("base-url")));
                if (!configured.isEmpty()) baseUrl = configured;
            }
            if (System.getenv("DASHSCOPE_EMBEDDING_MODEL") == null) {
                String configured = resolve(string(dashscope.get("embedding-model")));
                if (!configured.isEmpty()) model = configured;
            }
        }
        if (apiKey.isBlank() || apiKey.contains("${")) {
            throw new IllegalStateException("缺少 DashScope Key：设置 DASHSCOPE_API_KEY 或 application-local.yml");
        }
        llm.getDashscope().setApiKey(apiKey);
        llm.getDashscope().setBaseUrl(baseUrl);
        llm.getDashscope().setEmbeddingModel(model);
        return llm;
    }

    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return Map.of();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    /** 处理 local yml 里的 ${ENV:default} 占位符 */
    private String resolve(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("${") && text.endsWith("}")) {
            String inner = text.substring(2, text.length() - 1);
            int colon = inner.indexOf(':');
            String name = colon < 0 ? inner : inner.substring(0, colon);
            String fallback = colon < 0 ? "" : inner.substring(colon + 1);
            String env = System.getenv(name);
            return env == null || env.isBlank() ? fallback : env;
        }
        return text;
    }

    private String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private String envOr(String name, String fallback) {
        String value = env(name);
        return value.isEmpty() ? fallback : value;
    }

    private Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("docs/memory-recall-gold-set.json"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（以 docs/memory-recall-gold-set.json 为锚点）");
    }

    private record Memory(String key, String value, String retrievalText) {
    }

    private record Case(String query, List<String> expect, boolean negative) {
    }
}
