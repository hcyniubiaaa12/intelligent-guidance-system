package com.guide.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 适配层配置：DeepSeek 对话 / 阿里 DashScope embedding + rerank / 阿里 DocumentMind 文档解析。
 * 模型名与 base-url 可配（application.yml），API Key 只放 application-local.yml（密钥不进库、不进仓库）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private Deepseek deepseek = new Deepseek();

    private Dashscope dashscope = new Dashscope();

    private Docmind docmind = new Docmind();

    private Http http = new Http();

    @Data
    public static class Deepseek {
        private String apiKey;
        private String baseUrl;
        private String model;
        /** 生成温度：导诊要稳定可复现，默认偏低 */
        private Double temperature = 0.3;
    }

    @Data
    public static class Dashscope {
        private String apiKey;
        private String baseUrl;
        private String embeddingModel;
        private String rerankModel;
        /** embedding 维度，需与 pgvector 建表维度一致 */
        private Integer embeddingDimension = 1024;
        /** 单次 embedding 请求最大文本数（DashScope 批量上限内保守取值） */
        private Integer embeddingBatchSize = 10;
        /** 单条文本截断长度（字符），防止超模型 token 上限 */
        private Integer embeddingMaxChars = 2000;
    }

    /**
     * 阿里云 DocumentMind（文档智能）：pdf/docx/png 的**唯一**解析路径，不降级。
     * 密钥走环境变量占位符（与探针用的那两个同名），可填进 application-local.yml。
     */
    @Data
    public static class Docmind {
        private String accessKeyId;
        private String accessKeySecret;
        private String regionId = "cn-hangzhou";
        private String endpoint = "docmind-api.cn-hangzhou.aliyuncs.com";
        /**
         * 取版面块时每页拉多少块（服务端分页参数 layoutStepSize）。
         * 开得大是刻意的：服务端分页语义没实测过，一页拿完就绕开了它。
         */
        private Integer layoutStepSize = 500;
        /** 单次调用超时（毫秒）：提交要传文件，给得比普通请求宽 */
        private Integer timeoutMs = 120_000;
    }

    /**
     * HTTP 调用超时（毫秒）。**没有超时的模型调用就是一次无限挂起**——2026-09-29 实测撞上过一次：
     * DeepSeek 流式调用挂住，SSE 一直开着、线程卡在模型调用上不返回也不打错误日志，患者端
     * 只是无限转圈（见 进度.md「可顺手清的小遗留」）。超时后统一按 {@code LLM_CALL_FAILED}(7001)
     * 走既有的失败路径（链路 A 已有 error 事件与降级约定，缺的只是"什么时候放弃"）。
     */
    @Data
    public static class Http {
        /** 建连超时：连不上应当立刻失败，而不是等读取超时 */
        private Integer connectTimeoutMs = 10_000;
        /** 非流式请求整体超时，也是流式"等响应头"的超时 */
        private Integer readTimeoutMs = 60_000;
        /**
         * 流式：两行数据之间允许的最长静默。流式生成时长由模型侧决定，不能给整条流设一个死线，
         * 但**长时间一个字都不吐**就是挂住了——这条才是"无限转圈"的真正解药。
         */
        private Integer streamIdleTimeoutMs = 60_000;
        /** 流式：整条流的总预算（兜底，防止一个字一个字地慢慢磨） */
        private Integer streamTotalTimeoutMs = 300_000;
    }
}
