package com.opspilot.config;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORS 白名单的回归锁（2026-09-28，探索性验收抓到的真缺陷）。
 *
 * 现象：`Origin: http://localhost:3000` 的预检返回 **403 Invalid CORS request**，
 * 而类注释与 OPS 都宣称"环回任意端口放行"。根因是权威常量写成了前缀形态
 * （`"http://localhost:"`，带冒号无通配）却喂给 Spring 的 `allowedOriginPatterns`
 * ——那个 API 是 **glob 匹配**，无 `*` 即按字面量精确匹配，于是任何真实 origin 都不命中；
 * 而 `isAllowedOrigin()`（startsWith）认为它合法 ⇒ **同一份白名单两套判据互相矛盾**。
 *
 * 本测试用 **Spring 自己的匹配器**钉住行为（不是钉我们的前缀函数）——它才是运行时真正消费白名单的那个。
 */
class CorsConfigTest {

    private static CorsConfiguration cfg() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of(CorsConfig.allowedOriginPatterns()));
        return c;
    }

    /** 核心锁：Spring 的真实匹配器必须接受环回任意端口（修前此处返回 null=拒绝）。 */
    @Test
    void springMatcherAcceptsLoopbackOnAnyPort() {
        assertEquals("http://localhost:3000", cfg().checkOrigin("http://localhost:3000"),
                "环回任意端口必须被 Spring 匹配器接受——403 那版就是死在这里");
        assertEquals("http://127.0.0.1:5173", cfg().checkOrigin("http://127.0.0.1:5173"));
        assertEquals("http://gateway:8081", cfg().checkOrigin("http://gateway:8081"));
    }

    /** 反向：非白名单 origin 必须仍被拒（不许为了修 403 而放开成 `*`）。 */
    @Test
    void springMatcherStillRejectsForeignOrigins() {
        assertNull(cfg().checkOrigin("http://evil.example.com"));
        assertNull(cfg().checkOrigin("https://localhost.evil.example.com"));
    }

    /** 权威形态必须是 glob：任何一条不带 `*` 都会退化成字面量匹配（修前的 bug 形态）。 */
    @Test
    void authoritativePatternsAreGlobForm() {
        for (String p : CorsConfig.allowedOriginPatterns()) {
            assertTrue(p.endsWith(":*"), "白名单必须写成 glob（如 http://localhost:*），实际: " + p);
        }
    }

    /** 派生判据与权威常量同源：前缀集合必须能从前缀形态对应上（防两套判据再次分叉）。 */
    @Test
    void prefixJudgeAgreesWithPatterns() {
        assertTrue(CorsConfig.isAllowedOrigin("http://localhost:3000"));
        assertTrue(CorsConfig.isAllowedOrigin("http://127.0.0.1:5173"));
        assertTrue(CorsConfig.isAllowedOrigin("http://gateway:9000"));
        assertFalse(CorsConfig.isAllowedOrigin("http://evil.example.com"));
        assertFalse(CorsConfig.isAllowedOrigin(null), "null origin 必须拒（无 Origin 头 ≠ 允许）");
        for (String p : CorsConfig.allowedOriginPatterns()) {
            String prefix = p.substring(0, p.length() - 1);
            assertTrue(CorsConfig.isAllowedOrigin(prefix + "12345"),
                    "每条 glob 对应的 origin 都必须被前缀判据接受: " + p);
        }
    }

    /** 浏览器客户端要发的头必须在白名单里——漏了它跨源请求照样被预检拒掉（X-Trace-Id 是 2026-09-28 新增的）。 */
    @Test
    void browserSendableHeadersAreAllowed() {
        var headers = Arrays.asList(CorsConfig.allowedHeaders());
        assertTrue(headers.contains("Authorization") && headers.contains("Content-Type"));
        assertTrue(headers.contains("X-Trace-Id"),
                "调用方关联键要能被浏览器发出，否则带它的跨源请求会被预检拒: " + headers);
    }
}
