package com.guide.llm.service;

import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import com.guide.llm.client.EmbeddingModel;
import com.guide.llm.client.RerankModel;
import com.guide.llm.config.LlmProperties;
import com.guide.llm.dto.LlmDiagDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 适配层自检（管理端 LLM 配置页）：模型接入信息 + 三路连通性探测。
 *
 * <p>为什么放在 llm 而不是 admin：探测要用三个模型客户端与 {@link LlmProperties}，
 * 那是适配层自己的知识；业务侧只该拿到「能不能用」的结论。
 *
 * <p>探测是**人工触发的同步调用**（管理端点一下跑一次），发的是最小请求（一个词、两条候选），
 * 不走 SSE 线程池——避免占用在线导诊的线程资源。
 *
 * <p>失败原因一律经 {@link #sanitize} 处理：上游响应体里可能回带请求头或 Key 片段，
 * 直出会把密钥送到管理端页面，故先按配置里的密钥做替换再截断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmDiagnosticService {

    /** 探测用最小输入：能验证链路通即可，尽量少花 token */
    private static final String PROBE_TEXT = "连通性测试";

    /** 重排探测的两条候选：内容无所谓，只要有一对可比较的文本 */
    private static final List<String> PROBE_DOCS = List.of("劳力性胸闷胸痛的识别与鉴别", "皮疹与瘙痒的鉴别");

    /** 失败原因展示上限（超出截断，防止一串上游响应体糊满页面） */
    private static final int DETAIL_MAX = 160;

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final RerankModel rerankModel;
    private final LlmProperties properties;

    /** 模型接入信息（模型名与地址可读，密钥只回是否已配） */
    public LlmDiagDTO.ModelInfo modelInfo() {
        LlmDiagDTO.ModelInfo info = new LlmDiagDTO.ModelInfo();
        info.setChatModel(properties.getDeepseek().getModel());
        info.setChatBaseUrl(properties.getDeepseek().getBaseUrl());
        info.setChatKeySet(configured(properties.getDeepseek().getApiKey()));
        info.setEmbeddingModel(properties.getDashscope().getEmbeddingModel());
        info.setEmbeddingDim(properties.getDashscope().getEmbeddingDimension());
        info.setEmbeddingKeySet(configured(properties.getDashscope().getApiKey()));
        info.setRerankModel(properties.getDashscope().getRerankModel());
        info.setRerankBaseUrl(properties.getDashscope().getBaseUrl());
        info.setRerankKeySet(configured(properties.getDashscope().getApiKey()));
        return info;
    }

    /** 依次探测三路模型；每路各自兜异常，一路失败不影响其余两路的结论 */
    public List<LlmDiagDTO.ProbeResult> probeAll() {
        List<LlmDiagDTO.ProbeResult> results = new ArrayList<>(3);
        results.add(probe("chat", "对话模型（DeepSeek）", this::probeChat));
        results.add(probe("embedding", "向量模型（text-embedding）", this::probeEmbedding));
        results.add(probe("rerank", "重排模型（rerank）", this::probeRerank));
        return results;
    }

    private String probeChat() {
        String reply = chatModel.chat(List.of(ChatMsg.user(PROBE_TEXT)));
        if (reply == null || reply.isBlank()) {
            throw new IllegalStateException("模型返回空内容");
        }
        return "正常，返回 " + reply.length() + " 字";
    }

    private String probeEmbedding() {
        float[] vector = embeddingModel.embed(PROBE_TEXT);
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("未返回向量");
        }
        int expected = properties.getDashscope().getEmbeddingDimension() == null
                ? 0 : properties.getDashscope().getEmbeddingDimension();
        if (expected > 0 && vector.length != expected) {
            throw new IllegalStateException("返回维度 " + vector.length + "，与配置 " + expected + " 不一致（换模型需重建向量）");
        }
        return "正常，向量维度 " + vector.length + "，与配置一致";
    }

    private String probeRerank() {
        List<RerankModel.RerankHit> hits = rerankModel.rerank(PROBE_TEXT, PROBE_DOCS, PROBE_DOCS.size());
        if (hits == null || hits.isEmpty()) {
            throw new IllegalStateException("未返回排序结果");
        }
        double top = hits.stream().mapToDouble(RerankModel.RerankHit::score).max().orElse(0d);
        return "正常，返回 " + hits.size() + " 条，最高分 " + String.format("%.4f", top);
    }

    /** 单路探测：计时 + 异常兜底，任何异常都转成 ok=false 的结果而不是抛出去 */
    private LlmDiagDTO.ProbeResult probe(String kind, String name, ProbeAction action) {
        long start = System.currentTimeMillis();
        LlmDiagDTO.ProbeResult result = new LlmDiagDTO.ProbeResult();
        result.setKind(kind);
        result.setName(name);
        try {
            String detail = action.run();
            result.setOk(true);
            result.setDetail(detail);
        } catch (Exception e) {
            log.warn("LLM 连通性探测失败：{} → {}", kind, e.getMessage());
            result.setOk(false);
            result.setDetail(sanitize(e));
        }
        result.setLatencyMs(System.currentTimeMillis() - start);
        return result;
    }

    /** 失败原因脱敏：抹掉配置里的密钥原文，再截断 */
    private String sanitize(Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        message = mask(message, properties.getDeepseek().getApiKey());
        message = mask(message, properties.getDashscope().getApiKey());
        return message.length() <= DETAIL_MAX ? message : message.substring(0, DETAIL_MAX) + "…";
    }

    private static String mask(String text, String secret) {
        if (secret == null || secret.isBlank()) {
            return text;
        }
        return text.replace(secret, "******");
    }

    private static boolean configured(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 探测动作：返回成功摘要，失败直接抛异常 */
    @FunctionalInterface
    private interface ProbeAction {
        String run();
    }
}
