package com.opspilot.config;

import java.util.Arrays;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /v1（OpenAI 兼容面）CORS：浏览器型客户端（LobeChat Web 模式等）从 localhost 任意端口
 * 直连网关属跨源，需放行预检与真实请求。白名单三条：环回（localhost/127.0.0.1 任意端口）
 * + 容器内服务名 `http://gateway:`（compose 形态下网关自身/同网服务的 origin）。
 * 刻意不开 `*`——网关凭证是 JWT，恶意网页所在 origin 不应能探测凭据面。
 *
 * 注意口径（QA 台账 P2 遗留项已按此更正）：这不是"仅环回"，第三条是容器网络内的服务名——
 * 它只在内网 compose 里可解析，对外不可达，但**描述上不能含糊**："仅环回"是不准确的。
 *
 * **2026-09-28 修一处真缺陷（探索性验收实测）**：本类曾把 `"http://localhost:"` 这类**前缀**
 * 直接喂给 Spring 的 `allowedOriginPatterns(...)`，而那个 API 是 **glob 匹配**（通配符是 `*`，
 * 没有 `*` 即按字面量精确匹配）——于是任何真实 origin 都不命中：`Origin: http://localhost:3000`
 * 的预检返回 **403 Invalid CORS request**，而同一份白名单前缀经 `isAllowedOrigin()`（startsWith）
 * 判定却是"允许"。**同一行代码里两套判据互相矛盾**，后果是任何非 8081 端口的本地 web 客户端根本接不上，
 * 而"浏览器客户端可零适配直连"正是 /v1 面的卖点。
 *
 * 修法不是把两处都改对，而是**让它们不可能再分叉**：glob patterns 是权威形态（Spring 真正消费的那个），
 * 前缀集合由它**派生**；`allowedHeaders` 同时补上 `X-Trace-Id`（2026-09-28 新增的调用方关联键——
 * 它同样属于"浏览器要发的头"，漏了它跨源请求照样被预检拒掉）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 权威白名单（唯一形态定义）：glob 形态，Spring 直接消费。 */
    private static final String[] ALLOWED_ORIGIN_PATTERNS =
            {"http://localhost:*", "http://127.0.0.1:*", "http://gateway:*"};

    /** 浏览器可发的请求头白名单：Authorization（身份）与 Content-Type 是业务必需，X-Trace-Id 是调用方关联键。 */
    private static final String[] ALLOWED_HEADERS = {"Authorization", "Content-Type", "X-Trace-Id"};

    /** 前缀形态**由上面那份常量派生**——两套判据同源，改一处即同步（防再次分叉）。 */
    private static final String[] ALLOWED_ORIGIN_PREFIXES = Arrays.stream(ALLOWED_ORIGIN_PATTERNS)
            .map(p -> p.endsWith("*") ? p.substring(0, p.length() - 1) : p)
            .toArray(String[]::new);

    /**
     * 与 addCorsMappings 同源的白名单判定（前缀语义）。
     * 供 JwtAuthFilter 的 /v1 401 短路路径复用（filter 在 CORS 处理器之前，不主动回显 ACAO
     * 时浏览器端读不到 401 错误体——QA 第五轮 P2）。
     */
    public static boolean isAllowedOrigin(String origin) {
        if (origin == null) return false;
        for (String prefix : ALLOWED_ORIGIN_PREFIXES) {
            if (origin.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 供测试断言"权威常量与派生常量同源且形态正确"（防回归到"前缀喂给 glob"那版）。 */
    static String[] allowedOriginPatterns() {
        return ALLOWED_ORIGIN_PATTERNS.clone();
    }

    /** 供测试断言请求头白名单（漏 X-Trace-Id 会让带该头的跨源请求被预检拒掉）。 */
    static String[] allowedHeaders() {
        return ALLOWED_HEADERS.clone();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders(ALLOWED_HEADERS)
                .maxAge(3600);
    }
}
