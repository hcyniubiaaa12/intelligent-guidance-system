package com.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 配置：chunk 全文检索（BM25）+ 关键词通道的术语加分。
 * 真实密码放 application-local.yml。写入方：kb（chunk 索引唯一写入口）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchProperties {

    /** 集群地址，多个用逗号分隔 */
    private String uris;

    private String username;

    private String password;

    /** chunk 索引名 */
    private String chunkIndex;
}
