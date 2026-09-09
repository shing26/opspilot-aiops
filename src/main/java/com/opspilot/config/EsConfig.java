package com.opspilot.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EsConfig {

    @Bean
    public RestClient esRestClient(OpsPilotProperties props) {
        HttpHost host = HttpHost.create(props.es().uri());
        org.elasticsearch.client.RestClientBuilder builder = RestClient.builder(host);
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
