package com.guide.llm.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * LLM 配置页（管理端）用 DTO：模型接入信息 + 连通性测试结果。
 * 密钥只回「是否已配置」，不回值也不回掩码串——密钥不进库、不出后端。
 */
public final class LlmDiagDTO {

    private LlmDiagDTO() {
    }

    /** 模型接入信息（模型名/地址来自 application.yml，密钥来自 application-local.yml） */
    @Getter
    @Setter
    public static class ModelInfo {
        private String chatModel;
        private String chatBaseUrl;
        private Boolean chatKeySet;
        private String embeddingModel;
        private Integer embeddingDim;
        private Boolean embeddingKeySet;
        private String rerankModel;
        private String rerankBaseUrl;
        private Boolean rerankKeySet;
    }

    /** 单项探测结果 */
    @Getter
    @Setter
    public static class ProbeResult {
        /** chat / embedding / rerank */
        private String kind;
        /** 展示名 */
        private String name;
        private Boolean ok;
        private Long latencyMs;
        /** 成功摘要或失败原因（已对密钥脱敏、已截断） */
        private String detail;
    }
}
