package com.guide.common.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Elasticsearch 客户端（官方 Java API Client）：chunk 全文检索（BM25）+ 关键词通道的术语加分。
 * 地址由 elasticsearch.uris 配置（多个逗号分隔）；未配账号则不加认证头。
 * 连接惰性建立——ES 不可用时应用照常启动，检索侧由 EsChunkUtil 降级处理。
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient esRestClient(ElasticsearchProperties properties) {
        HttpHost[] hosts = parseHosts(properties.getUris());
        RestClientBuilder builder = RestClient.builder(hosts);
        if (StringUtils.hasText(properties.getUsername())) {
            CredentialsProvider provider = new BasicCredentialsProvider();
            provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                    properties.getUsername(), properties.getPassword()));
            builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(provider));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient esRestClient) {
        return new RestClientTransport(esRestClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    private HttpHost[] parseHosts(String uris) {
        if (!StringUtils.hasText(uris)) {
            throw new IllegalStateException("elasticsearch.uris 未配置");
        }
        return java.util.Arrays.stream(uris.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
    }
}
