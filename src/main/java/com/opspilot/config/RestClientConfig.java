package com.opspilot.config;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * DashScope 出站客户端（精排 + embedding 共用；LLM 生成走 {@code LlmClient} 自己的 JDK HttpClient）。
 *
 * <p>B3（2026-09-21 补）：此前这里没有 connect/read 超时，全靠底层客户端默认值。
 * {@code RerankClient} 与 {@code EmbeddingClient} 都从这个 bean 取连接，所以这一处
 * 覆盖了那两路的全部出站调用。
 *
 * <p>为什么超时设置单独抽成 {@link #settings} 而不是内联在 {@code builder()} 里：
 * 内联之后唯一能验证它的手段是 grep 源码里有没有 "timeout" 字样——那只能证明写了字，
 * 证明不了值真的流进了请求工厂。抽出来之后 {@code OutboundTimeoutTest} 可以直接断言
 * 配置值 → settings 的传递，且能把"0 或负数被拒绝"也钉住。
 */
@Configuration
public class RestClientConfig {

    /** 把配置里的毫秒值装进 Spring 的请求工厂设置；请求工厂由 Boot 按 classpath 自动探测。 */
    static ClientHttpRequestFactorySettings settings(OpsPilotProperties.DashScope dashscope) {
        return ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(dashscope.connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(dashscope.readTimeoutMs()));
    }

    @Bean
    public RestClient dashScopeRestClient(OpsPilotProperties props) {
        return RestClient.builder()
                .baseUrl(props.dashscope().baseUrl())
                .defaultHeader("Authorization", "Bearer " + props.dashscope().apiKey())
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(ClientHttpRequestFactories.get(settings(props.dashscope())))
                .build();
    }
}
