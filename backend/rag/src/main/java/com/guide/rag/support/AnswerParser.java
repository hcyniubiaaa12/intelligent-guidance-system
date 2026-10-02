package com.guide.rag.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.rag.dto.RagAnswer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模型输出解析（链路 A 第 ⑥ 步）。
 * 协议：自然语言回复在前，结论以分隔符 {@value #MARKER} + JSON 对象收尾，**JSON 必须带 verdict 字段**
 * （`RECOMMEND` / `ASK` / `INFO`）。
 *
 * <p><b>判据是「模型声明了什么」，不是「有没有 JSON」</b>（2026-09-30 改）：此前"没有合法 JSON"
 * 直接等于追问，于是模型漏协议、JSON 畸形、答了资料给不出科室，全被伪装成追问——患者看到一段
 * 回答被贴上「追问」标签，追问轮次还白涨，而且没有任何异常信号可查。现在三种情况一律降级为
 * {@link RagAnswer.Verdict#UNKNOWN}（当普通回答 + 上层 WARN），异常回到异常的样子。
 *
 * <p>容错：JSON 前后多余文字按花括号配对截取；置信度非法（越界/非递减）置 null 并按低置信度分流。
 *
 * <p><b>Top3 一律经 {@link #normalizeTop3} 归一化</b>（2026-10-02）：模型给的候选顺序不保证递减、
 * 也可能把别的科室放在首位，直接照抄会让推荐卡 top1 落到末尾、且顶层 confidence 被安到错误科室上。
 * 归一化后「首位 = 顶层声明的 dept，其后按置信度降序」，前端按下标取 top1 才是可靠的。
 */
@Slf4j
@Component
public class AnswerParser {

    /** 结论分隔标记（Prompt 中约定） */
    public static final String MARKER = "---RESULT---";

    private static final int MAX_TOP3 = 3;

    private final ObjectMapper objectMapper;

    public AnswerParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RagAnswer parse(String modelOutput) {
        if (!StringUtils.hasText(modelOutput)) {
            return RagAnswer.unknown("");
        }
        int markerIndex = modelOutput.indexOf(MARKER);
        if (markerIndex < 0) {
            // 例外：模型只给了 JSON 没给自然语言（历史形态）——JSON 之前的文字才是可见回复
            String json = extractJsonObject(modelOutput);
            if (json == null) {
                log.warn("模型未输出结论分隔符，按未声明处理（当普通回答，不占追问轮次）：{}", abbreviate(modelOutput));
                return RagAnswer.unknown(modelOutput.trim());
            }
            int jsonStart = modelOutput.indexOf(json);
            String visible = jsonStart > 0 ? modelOutput.substring(0, jsonStart).trim() : "";
            return buildAnswer(visible, modelOutput.trim(), json);
        }
        String reply = modelOutput.substring(0, markerIndex).trim();
        String json = extractJsonObject(modelOutput.substring(markerIndex + MARKER.length()));
        if (json == null) {
            log.warn("模型输出了结论分隔符但没有合法 JSON，按未声明处理：{}", abbreviate(modelOutput));
            return RagAnswer.unknown(reply);
        }
        return buildAnswer(reply, reply, json);
    }

    /**
     * @param reply         可见的自然语言回复（分隔符/JSON 之前的部分）
     * @param fallbackReply JSON 不可用时的兜底回复（降级为未声明，至少让患者看到内容而不是空白气泡）
     */
    private RagAnswer buildAnswer(String reply, String fallbackReply, String json) {
        RawResult raw;
        try {
            raw = objectMapper.readValue(json, RawResult.class);
        } catch (Exception e) {
            log.warn("结论 JSON 解析失败，按未声明处理：{}", abbreviate(json));
            return RagAnswer.unknown(fallbackReply);
        }
        if (raw == null) {
            return RagAnswer.unknown(fallbackReply);
        }
        RagAnswer.Verdict declared = declared(raw.verdict());
        if (declared == RagAnswer.Verdict.ASK) {
            return RagAnswer.ask(reply);
        }
        if (declared == RagAnswer.Verdict.INFO) {
            return RagAnswer.info(reply);
        }
        // 走到这里是 RECOMMEND，或模型没声明 verdict（老格式向后兼容）——两者都必须给出 dept；
        // 给不出就不是结论，也不能退回追问，只能降级
        if (!StringUtils.hasText(raw.dept())) {
            log.warn("模型未给出可用科室（verdict={}），按未声明处理：{}", raw.verdict(), abbreviate(json));
            return RagAnswer.unknown(reply);
        }

        // 归一化后再校验：模型给的顺序与「顶层 confidence 属于顶层 dept」都不能直接信
        List<RagAnswer.DeptCandidate> top3 = normalizeTop3(raw.dept().trim(), raw.confidence(), raw.top3());
        String note = raw.note() == null ? "" : raw.note().trim();
        return new RagAnswer(RagAnswer.Verdict.RECOMMEND, reply, top3, note,
                parseCites(raw.cites()), firstConfidence(top3), confidenceValid(top3));
    }

    /**
     * Top3 归一化：把模型可能乱序、可能重复、可能没把推荐科室放在首位的候选列表，
     * 整理成「首位 = 顶层声明的 dept，其后按置信度降序」的稳定顺序。
     *
     * <p><b>为什么必须归一化（2026-10-02）</b>：此前这里直接照抄模型给的数组顺序，于是
     * ① 提示词写了「按置信度递减」但模型偶尔不遵守时，<b>top1 会落到列表末尾</b>，
     * 推荐卡第一根条显示的是备选科室（前端按数组下标取 first，前端无从判断）；
     * ② 更糟的是「以顶层 confidence 为准」那行用 {@code top3.set(0, ...)} 写死了下标 0——
     * 顶层 {@code dept} 是心血管内科、模型却把呼吸内科放在数组第 0 位时，
     * 顶层 confidence 会被安到呼吸内科头上，<b>结论卡的科室名与百分比对不上</b>。
     *
     * <p>归一化规则（都是展示层口径，不改模型给出的数值本身）：
     * <ol>
     *   <li><b>首位锚定 {@code primary}（顶层 dept）</b>：顶层 dept 是结论主体（rec_dept 的来源），
     *       候选数组的排列不能推翻它；它不在候选里时补进来。</li>
     *   <li><b>其余按置信度降序</b>：{@code null} 排最后（模型没给值的不能因为位置靠前就排到真候选前面）；
     *       排序稳定，同值保持模型给出的先后。</li>
     *   <li><b>同名去重</b>：模型偶发把同一科室写两遍，重复项会让推荐卡出现两行同名科室。</li>
     *   <li><b>截断到 {@value #MAX_TOP3} 条</b>。</li>
     * </ol>
     *
     * <p>顶层 confidence 优先落在首位那条（顶层 dept 上）；顶层没给时用候选里自带的值。
     * 两者都没有则该条 confidence 为 null，由 {@link #confidenceValid} 判为不可信、按低置信度分流。
     */
    private List<RagAnswer.DeptCandidate> normalizeTop3(String primary, Double primaryConfidence,
                                                         List<RawDept> rawTop3) {
        // 1) 收集候选：跳过空名项，跳过与首位同名的（首位由 primary 独占），同名只留第一次出现的。
        //    先收全再排序——先截断后排序会把排在数组尾部但置信度更高的候选误删
        List<RagAnswer.DeptCandidate> rest = new ArrayList<>();
        boolean primarySeen = false;
        if (rawTop3 != null) {
            for (RawDept item : rawTop3) {
                if (item == null || !StringUtils.hasText(item.dept())) {
                    continue;
                }
                String dept = item.dept().trim();
                if (dept.equals(primary)) {
                    primarySeen = true;
                    continue;
                }
                if (rest.stream().anyMatch(c -> c.dept().equals(dept))) {
                    continue;
                }
                rest.add(new RagAnswer.DeptCandidate(dept, item.confidence()));
            }
        }

        // 2) 其余按置信度降序；null 垫底。stable：同值保持模型给出的先后，不做无谓的抖动
        rest.sort(Comparator.comparing(RagAnswer.DeptCandidate::confidence,
                Comparator.nullsLast(Comparator.reverseOrder())));

        // 3) 首位锚定顶层 dept：模型没把它放进候选、或放进来了但顶层 confidence 更大，都以顶层为准；
        //    首位占一个名额，其余按序补足 MAX_TOP3
        List<RagAnswer.DeptCandidate> top3 = new ArrayList<>();
        top3.add(new RagAnswer.DeptCandidate(primary, firstConfidence(primary, primaryConfidence, rawTop3, primarySeen)));
        for (RagAnswer.DeptCandidate candidate : rest) {
            if (top3.size() == MAX_TOP3) {
                break;
            }
            top3.add(candidate);
        }
        return top3;
    }

    /**
     * 首位科室的置信度：顶层 confidence 优先（顶层 dept 才是结论主体，两者同源）；
     * 顶层没给时回落到候选数组里那一条自带的值。
     *
     * @param primarySeen primary 是否出现在候选数组中（用于区分「候选里有同名项」与「候选里没有」，
     *                    前者才有回落来源）
     */
    private Double firstConfidence(String primary, Double primaryConfidence, List<RawDept> rawTop3,
                                   boolean primarySeen) {
        if (primaryConfidence != null) {
            return primaryConfidence;
        }
        if (primarySeen && rawTop3 != null) {
            for (RawDept item : rawTop3) {
                if (item != null && primary.equals(item.dept() == null ? null : item.dept().trim())
                        && item.confidence() != null) {
                    return item.confidence();
                }
            }
        }
        return null;
    }

    /** 归一化后首位的置信度（rec_dept 的把握程度；首位无值则为 null） */
    private Double firstConfidence(List<RagAnswer.DeptCandidate> top3) {
        return top3.isEmpty() ? null : top3.get(0).confidence();
    }

    /**
     * verdict 字段解析：缺省或取值不认识 → UNKNOWN（不抛异常：容错靠上层降级 + WARN，
     * 抛异常会让一次"模型没按协议"变成患者侧的失败）。
     * 按取值字符串 switch，**不用 valueOf**（见进度.md 已知坑：枚举反序列化禁用 valueOf）。
     */
    private RagAnswer.Verdict declared(String verdict) {
        if (!StringUtils.hasText(verdict)) {
            return RagAnswer.Verdict.UNKNOWN;
        }
        return switch (verdict.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "RECOMMEND" -> RagAnswer.Verdict.RECOMMEND;
            case "ASK" -> RagAnswer.Verdict.ASK;
            case "INFO" -> RagAnswer.Verdict.INFO;
            default -> RagAnswer.Verdict.UNKNOWN;
        };
    }

    /**
     * 置信度校验（见链路 A 对齐点）：0–1 区间、Top3 单调递减（允许相等）。
     * 不合法时整体置为不可信，由 chat 层置 null 并按低置信度分流（进盲区榜）。
     */
    private boolean confidenceValid(List<RagAnswer.DeptCandidate> top3) {
        Double previous = null;
        for (RagAnswer.DeptCandidate candidate : top3) {
            Double confidence = candidate.confidence();
            if (confidence == null || confidence < 0 || confidence > 1) {
                return false;
            }
            if (previous != null && confidence > previous) {
                return false;
            }
            previous = confidence;
        }
        return true;
    }

    /** cites 宽松解析：支持 [1,2] 与 [{"no":1},{"no":2}] 两种形态 */
    private List<Integer> parseCites(JsonNode cites) {
        List<Integer> result = new ArrayList<>();
        if (cites == null || !cites.isArray()) {
            return result;
        }
        for (JsonNode node : cites) {
            if (node.isInt()) {
                result.add(node.asInt());
            } else if (node.has("no") && node.get("no").isInt()) {
                result.add(node.get("no").asInt());
            } else if (node.isTextual()) {
                try {
                    result.add(Integer.parseInt(node.asText().replaceAll("\\D", "")));
                } catch (NumberFormatException ignored) {
                    // 注号不可解析则忽略该条
                }
            }
        }
        return result;
    }

    /** 花括号配对截取第一个完整 JSON 对象（容忍前后多余文字与 ```json 围栏） */
    private String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String abbreviate(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /** 模型结论原始结构（宽松映射：多余字段忽略、缺失字段置 null） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawResult(String verdict, String dept, Double confidence, List<RawDept> top3,
                             String note, JsonNode cites) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawDept(String dept, Double confidence) {
    }
}
