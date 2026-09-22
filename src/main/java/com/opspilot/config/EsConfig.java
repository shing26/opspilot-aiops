package com.opspilot.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 出站客户端。
 *
 * <p>B3（2026-09-21 补）：此前只设了认证，没有 connect/socket 超时——ES 挂住时
 * 检索线程会一直等。这里补上，取值来自 {@code opspilot.es.*}。
 *
 * <p>与 {@code RestClientConfig} 同样的理由：超时对象抽成 {@link #requestConfig} 以便单测，
 * 否则"写了超时"只能靠 grep 证明。
 */
@Configuration
public class EsConfig {

    /**
     * 把 {@code opspilot.es.*} 的超时写进给定的 builder。
     * <p>生产路径（下面的 {@code setRequestConfigCallback}）与 {@code OutboundTimeoutTest}
     * 走的是<b>同一个函数</b>——否则测试验的就不是生产那条路，等于没验。
     */
    static RequestConfig.Builder applyTimeouts(RequestConfig.Builder builder, OpsPilotProperties.Es es) {
        return builder.setConnectTimeout(es.connectTimeoutMs()).setSocketTimeout(es.readTimeoutMs());
    }

    /** 供测试直接取一份成品配置；生产路径用 {@link #applyTimeouts}。 */
    static RequestConfig requestConfig(OpsPilotProperties.Es es) {
        return applyTimeouts(RequestConfig.custom(), es).build();
    }

    @Bean
    public RestClient esRestClient(OpsPilotProperties props) {
        HttpHost host = HttpHost.create(props.es().uri());
        org.elasticsearch.client.RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(rc -> applyTimeouts(rc, props.es()));
        // P3：xpack security 形态下 Basic 认证；口令空保持无认证（CI/本地）
        String pass = props.es().password();
        if (pass != null && !pass.isBlank()) {
            org.apache.http.impl.client.BasicCredentialsProvider creds =
                    new org.apache.http.impl.client.BasicCredentialsProvider();
            creds.setCredentials(
                    new org.apache.http.auth.AuthScope(host.getHostName(), host.getPort()),
                    new org.apache.http.auth.UsernamePasswordCredentials(props.es().username(), pass));
            builder = builder.setHttpClientConfigCallback(c -> c.setDefaultCredentialsProvider(creds));
        }
        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
