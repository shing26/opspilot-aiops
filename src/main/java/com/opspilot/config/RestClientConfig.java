package com.opspilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient dashScopeRestClient(OpsPilotProperties props) {
        return RestClient.builder()
                .baseUrl(props.dashscope().baseUrl())
                .defaultHeader("Authorization", "Bearer " + props.dashscope().apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
