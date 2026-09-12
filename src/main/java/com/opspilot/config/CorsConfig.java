package com.opspilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /v1（OpenAI 兼容面）CORS：浏览器型客户端（LobeChat Web 模式等）从 localhost 任意端口
 * 直连网关属跨源，需放行预检与真实请求。仅允许环回 origin 模式（localhost/127.0.0.1 任意端口），
 * 不开 *——网关凭证是 JWT，恶意网页所在 origin 不应能携 cookie 之外的凭据面探测。
 * isAllowedOrigin 供 JwtAuthFilter 的 /v1 401 短路路径复用（filter 在 CORS 处理器之前，
 * 不主动回显 ACAO 时浏览器端读不到 401 错误体——QA 第五轮 P2）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final String[] ALLOWED_ORIGIN_PREFIXES =
            {"http://localhost:", "http://127.0.0.1:", "http://gateway:"};

    /** 与 addCorsMappings 同源的白名单判定（前缀式 patterns 的静态等价）。 */
    public static boolean isAllowedOrigin(String origin) {
        if (origin == null) return false;
        for (String prefix : ALLOWED_ORIGIN_PREFIXES) {
            if (origin.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns(ALLOWED_ORIGIN_PREFIXES)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }
}
