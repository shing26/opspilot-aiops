package com.opspilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /v1（OpenAI 兼容面）CORS：浏览器型客户端（LobeChat Web 模式等）从 localhost 任意端口
 * 直连网关属跨源，需放行预检与真实请求。仅允许环回 origin 模式（localhost/127.0.0.1 任意端口），
 * 不开 *——网关凭证是 JWT，恶意网页所在 origin 不应能携 cookie 之外的凭据面探测。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }
}
