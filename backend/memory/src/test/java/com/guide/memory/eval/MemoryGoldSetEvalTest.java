package com.guide.memory.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.auth.service.SysConfigService;
import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.DeepSeekChatModel;
import com.guide.llm.config.LlmProperties;
import com.guide.llm.support.HttpJson;
import com.guide.memory.dto.MemoryExtractionResult;
import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.dto.MemoryExtractionResult.ProfileCandidateDraft;
import com.guide.memory.service.MemoryExtractor;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 长期记忆抽取的 gold set 评估（默认不跑，避免常规测试产生模型费用）。
 *
 * <p><b>怎么开</b>：设置 {@code MEMORY_EVAL=true} 与 DeepSeek Key（环境变量
 * {@code DEEPSEEK_API_KEY}，或 {@code MEMORY_EVAL_CONFIG} 指向本地 application-local.yml）。
 * 复现命令见 {@code docs/memory-gold-set.md}。
 *
 * <p><b>它测什么</b>：逐条调用生产同一条链路 {@link MemoryExtractor#extract(String)}
 * （模型 + 两道纯函数校验），再按 gold set 的期望算精确率 / 召回率 / 空样本准确率 /
 * 候选月份准确率 / 背景类型准确率。它不是单元测试——指标低不代表构建失败，
 * 输出是给提示词迭代与阈值校准用的诊断信息。
 *
 * <p><b>为什么不用 Spring 上下文</b>：评估只需要「真实模型 + prompts.yml + 配置默认值」，
 * 起整个 Spring 会把数据库/Redis/ES 都拖进来。这里手工装配，和纯单测同一思路。
 */
@EnabledIfEnvironmentVariable(named = "MEMORY_EVAL", matches = "true")
class MemoryGoldSetEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("gold set：逐条抽取并输出精确率/召回率（诊断用，不因指标低而失败）")
    void evaluate() throws Exception {
        Path root = repoRoot();
        Path goldSet = pathFromEnv("MEMORY_EVAL_GOLD_SET",
                root.resolve("docs/memory-gold-set.jsonl"));
        List<JsonNode> cases = loadCases(goldSet);
        int limit = intFromEnv("MEMORY_EVAL_LIMIT", cases.size());
        cases = cases.subList(0, Math.min(limit, cases.size()));

        PromptProperties prompts = loadPrompts(root);
        LlmProperties llm = loadLlm(root);
        ChatModel chat = new DeepSeekChatModel(llm, new HttpJson(new ObjectMapper(), llm));
        SysConfigService sysConfig = mock(SysConfigService.class);
        when(sysConfig.getDouble(SysConfigService.KEY_MEMORY_EXTRACT_CONFIDENCE_MIN,
                SysConfigService.DEFAULT_MEMORY_EXTRACT_CONFIDENCE_MIN))
                .thenReturn(SysConfigService.DEFAULT_MEMORY_EXTRACT_CONFIDENCE_MIN);
        MemoryExtractor extractor = new MemoryExtractor(chat, prompts, sysConfig);

        Metrics total = new Metrics("总体");
        Map<String, Metrics> byCategory = new TreeMap<>();
        int emptyCases = 0;
        int emptyPass = 0;
        int monthExpected = 0;
        int monthHit = 0;
        int typeExpected = 0;
        int typeHit = 0;
        List<String> failures = new ArrayList<>();

        long start = System.currentTimeMillis();
        for (JsonNode node : cases) {
            String id = text(node, "id");
            String category = text(node, "category");
            String dialogue = text(node, "dialogue");
            JsonNode expect = node.path("expect");

            MemoryExtractionResult actual = extractor.extract(dialogue);
            Metrics metrics = byCategory.computeIfAbsent(category, Metrics::new);
            CaseResult result = score(expect, actual);
            merge(total, result);
            merge(metrics, result);

            if (isEmptyCase(expect)) {
                emptyCases++;
                if (result.tp == 0 && result.fp == 0 && result.fn == 0) {
                    emptyPass++;
                }
            }
            monthExpected += result.monthExpected;
            monthHit += result.monthHit;
            typeExpected += result.typeExpected;
            typeHit += result.typeHit;
            if (result.fp > 0 || result.fn > 0) {
                failures.add(failureLine(id, category, result));
            }
        }
        long cost = System.currentTimeMillis() - start;

        printReport(cases.size(), cost, total, byCategory, emptyPass, emptyCases,
                monthHit, monthExpected, typeHit, typeExpected, failures);
        assertThat(cases).isNotEmpty();
    }

    // ---------------- 打分 ----------------

    private CaseResult score(JsonNode expect, MemoryExtractionResult actual) {
        CaseResult result = new CaseResult();

        List<Expect> expectedPreferences = expectList(expect, "preferences");
        List<PreferenceDraft> actualPreferences = actual.preferences();
        matchValues(expectedPreferences, actualPreferences.stream()
                .map(p -> new Actual(p.value(), null, null)).toList(), result);

        List<Expect> expectedBackgrounds = expectList(expect, "background");
        List<BackgroundDraft> actualBackgrounds = actual.backgrounds();
        matchBackgrounds(expectedBackgrounds, actualBackgrounds, result);

        List<Expect> expectedCandidates = expectList(expect, "candidates");
        List<ProfileCandidateDraft> actualCandidates = actual.candidates();
        matchCandidates(expectedCandidates, actualCandidates, result);

        return result;
    }

    private void matchValues(List<Expect> expected, List<Actual> actual, CaseResult result) {
        boolean[] used = new boolean[actual.size()];
        for (Expect e : expected) {
            int hit = indexOfValue(e, actual, used);
            if (hit >= 0) {
                used[hit] = true;
                result.tp++;
            } else {
                result.fn++;
                result.missing.add(e.value);
            }
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!used[i]) {
                result.fp++;
                result.extra.add(actual.get(i).value);
            }
        }
    }

    private void matchBackgrounds(List<Expect> expected, List<BackgroundDraft> actual,
                                  CaseResult result) {
        boolean[] used = new boolean[actual.size()];
        for (Expect e : expected) {
            int hit = -1;
            for (int i = 0; i < actual.size() && hit < 0; i++) {
                if (!used[i] && valueMatches(e, actual.get(i).value())) {
                    hit = i;
                }
            }
            if (hit >= 0) {
                used[hit] = true;
                result.tp++;
                if (e.type != null && !e.type.isBlank()) {
                    result.typeExpected++;
                    if (e.type.equalsIgnoreCase(Objects.toString(actual.get(hit).type(), ""))) {
                        result.typeHit++;
                    }
                }
            } else {
                result.fn++;
                result.missing.add(e.value);
            }
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!used[i]) {
                result.fp++;
                result.extra.add(actual.get(i).value());
            }
        }
    }

    private void matchCandidates(List<Expect> expected, List<ProfileCandidateDraft> actual,
                                 CaseResult result) {
        boolean[] used = new boolean[actual.size()];
        for (Expect e : expected) {
            int hit = -1;
            for (int i = 0; i < actual.size() && hit < 0; i++) {
                ProfileCandidateDraft a = actual.get(i);
                if (!used[i]
                        && e.field != null
                        && e.field.equalsIgnoreCase(a.field())
                        && valueMatches(e, a.value())) {
                    hit = i;
                }
            }
            if (hit >= 0) {
                used[hit] = true;
                result.tp++;
                if (e.occurredOn != null && !e.occurredOn.isBlank()) {
                    result.monthExpected++;
                    if (e.occurredOn.equals(Objects.toString(actual.get(hit).occurredOn(), ""))) {
                        result.monthHit++;
                    }
                }
            } else {
                result.fn++;
                result.missing.add(e.field + ":" + e.value);
            }
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!used[i]) {
                result.fp++;
                result.extra.add(actual.get(i).field() + ":" + actual.get(i).value());
            }
        }
    }

    private int indexOfValue(Expect expected, List<Actual> actual, boolean[] used) {
        for (int i = 0; i < actual.size(); i++) {
            if (!used[i] && valueMatches(expected, actual.get(i).value)) {
                return i;
            }
        }
        return -1;
    }

    private boolean valueMatches(Expect expected, String actualValue) {
        String actual = normalize(actualValue);
        if (actual.isEmpty()) {
            return false;
        }
        if (actual.equals(normalize(expected.value))) {
            return true;
        }
        for (String alias : expected.aliases) {
            if (actual.equals(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private void merge(Metrics metrics, CaseResult result) {
        metrics.tp += result.tp;
        metrics.fp += result.fp;
        metrics.fn += result.fn;
    }

    // ---------------- 报告 ----------------

    private void printReport(int cases, long costMs, Metrics total, Map<String, Metrics> byCategory,
                             int emptyPass, int emptyCases, int monthHit, int monthExpected,
                             int typeHit, int typeExpected, List<String> failures) {
        StringBuilder out = new StringBuilder();
        out.append("\n===== 长期记忆 gold set 评估 =====\n");
        out.append("样本：").append(cases).append(" 条｜耗时 ").append(costMs).append(" ms\n");
        out.append(String.format(Locale.ROOT, "总体：TP=%d FP=%d FN=%d｜P=%.3f R=%.3f F1=%.3f%n",
                total.tp, total.fp, total.fn, total.precision(), total.recall(), total.f1()));
        out.append("空样本准确率：").append(emptyPass).append('/').append(emptyCases)
                .append(emptyCases == 0 ? "（无空样本）" : "").append('\n');
        out.append("候选月份准确率：").append(monthHit).append('/').append(monthExpected).append('\n');
        out.append("背景类型准确率：").append(typeHit).append('/').append(typeExpected).append('\n');
        out.append("\n分类别：\n");
        for (Metrics m : byCategory.values()) {
            out.append(String.format(Locale.ROOT,
                    "  %-12s TP=%3d FP=%3d FN=%3d｜P=%.3f R=%.3f F1=%.3f%n",
                    m.name, m.tp, m.fp, m.fn, m.precision(), m.recall(), m.f1()));
        }
        if (!failures.isEmpty()) {
            out.append("\n逐条差异（最多 30 条）：\n");
            failures.stream().limit(30).forEach(line -> out.append("  ").append(line).append('\n'));
        }
        // 控制台可能不是 UTF-8；评估结果同时写 target 下的文件，便于 Windows 下读取。
        System.out.println(out);
        try {
            Path target = Paths.get("target", "memory-gold-set-eval.txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写不出文件不影响评估本身
        }
    }

    private String failureLine(String id, String category, CaseResult result) {
        return String.format(Locale.ROOT, "%s[%s] 漏抽=%s｜多抽=%s",
                id, category, result.missing, result.extra);
    }

    // ---------------- 数据/配置加载 ----------------

    private List<JsonNode> loadCases(Path file) throws Exception {
        assertThat(Files.exists(file)).as("gold set 不存在：%s", file).isTrue();
        List<JsonNode> cases = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            cases.add(MAPPER.readTree(line));
        }
        return cases;
    }

    private PromptProperties loadPrompts(Path root) throws Exception {
        Path file = root.resolve("backend/admin/src/main/resources/prompts.yml");
        assertThat(Files.exists(file)).as("prompts.yml 不存在：%s", file).isTrue();
        Map<String, Object> yaml;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            yaml = new Yaml().load(reader);
        }
        Map<String, Object> prompts = map(yaml.get("prompts"));
        Map<String, Object> extract = map(prompts.get("memory-extract"));
        String system = string(extract.get("system"));
        String userTemplate = string(extract.get("user-template"));
        assertThat(system).as("prompts.memory-extract.system").isNotBlank();
        assertThat(userTemplate).as("prompts.memory-extract.user-template").isNotBlank();

        PromptProperties properties = new PromptProperties();
        properties.getMemoryExtract().setSystem(system);
        properties.getMemoryExtract().setUserTemplate(userTemplate);
        return properties;
    }

    private LlmProperties loadLlm(Path root) throws Exception {
        String envKey = System.getenv("DEEPSEEK_API_KEY");
        String apiKey = envKey == null ? "" : envKey.trim();
        String baseUrl = envOr("MEMORY_EVAL_BASE_URL", "https://api.deepseek.com");
        String model = envOr("MEMORY_EVAL_MODEL", "deepseek-chat");

        Path config = configPath(root);
        if (config != null && Files.exists(config)) {
            Map<String, Object> yaml;
            try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                yaml = new Yaml().load(reader);
            }
            Map<String, Object> llm = map(map(yaml.get("llm")).get("deepseek"));
            if (apiKey.isBlank()) {
                apiKey = string(llm.get("api-key")).trim();
            }
            if (System.getenv("MEMORY_EVAL_BASE_URL") == null) {
                String configured = string(llm.get("base-url")).trim();
                if (!configured.isEmpty()) {
                    baseUrl = configured;
                }
            }
            if (System.getenv("MEMORY_EVAL_MODEL") == null) {
                String configured = string(llm.get("model")).trim();
                if (!configured.isEmpty()) {
                    model = configured;
                }
            }
        }
        if (apiKey.isBlank() || apiKey.contains("${")) {
            throw new IllegalStateException(
                    "未找到可用的 DeepSeek Key：请设置 DEEPSEEK_API_KEY，或把 MEMORY_EVAL_CONFIG "
                            + "指向含 llm.deepseek.api-key 的 application-local.yml（不要提交密钥）");
        }
        LlmProperties properties = new LlmProperties();
        properties.getDeepseek().setApiKey(apiKey);
        properties.getDeepseek().setBaseUrl(baseUrl);
        properties.getDeepseek().setModel(model);
        properties.getDeepseek().setTemperature(0.3);
        return properties;
    }

    private Path configPath(Path root) {
        String env = System.getenv("MEMORY_EVAL_CONFIG");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        Path local = root.resolve("backend/admin/src/main/resources/application-local.yml");
        return Files.exists(local) ? local : null;
    }

    private Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("docs/memory-gold-set.jsonl"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（以 docs/memory-gold-set.jsonl 为锚点）");
    }

    private Path pathFromEnv(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Paths.get(value);
    }

    private int intFromEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private List<Expect> expectList(JsonNode parent, String field) {
        List<Expect> result = new ArrayList<>();
        for (JsonNode node : parent.path(field)) {
            Expect expect = new Expect();
            expect.value = text(node, "value");
            expect.field = text(node, "field");
            expect.occurredOn = text(node, "occurredOn");
            expect.type = text(node, "type");
            for (JsonNode alias : node.path("aliases")) {
                if (alias.isTextual() && !alias.asText().isBlank()) {
                    expect.aliases.add(alias.asText());
                }
            }
            if (!expect.value.isBlank()) {
                result.add(expect);
            }
        }
        return result;
    }

    private boolean isEmptyCase(JsonNode expect) {
        return expect.path("preferences").isEmpty()
                && expect.path("background").isEmpty()
                && expect.path("candidates").isEmpty();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    // ---------------- 内部类型 ----------------

    private static final class Expect {
        private String value;
        private String field;
        private String occurredOn;
        private String type;
        private final List<String> aliases = new ArrayList<>();
    }

    private record Actual(String value, String field, String occurredOn) {
    }

    private static final class CaseResult {
        private int tp;
        private int fp;
        private int fn;
        private int monthExpected;
        private int monthHit;
        private int typeExpected;
        private int typeHit;
        private final List<String> missing = new ArrayList<>();
        private final List<String> extra = new ArrayList<>();
    }

    private static final class Metrics {
        private final String name;
        private int tp;
        private int fp;
        private int fn;

        private Metrics(String name) {
            this.name = name;
        }

        private double precision() {
            return tp + fp == 0 ? 1d : (double) tp / (tp + fp);
        }

        private double recall() {
            return tp + fn == 0 ? 1d : (double) tp / (tp + fn);
        }

        private double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0d : 2 * p * r / (p + r);
        }
    }
}
