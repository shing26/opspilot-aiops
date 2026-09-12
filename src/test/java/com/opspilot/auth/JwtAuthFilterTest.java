package com.opspilot.auth;

import com.opspilot.config.OpsPilotProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P0/P1/P2 防线黑盒锁：claim 卫生（level>=1、tenant 非空/空白/≤64）→
 * 账号存在性与禁用（查主库）→ token_ver 吊销版本 → 权限维度以 DB 为准（旧 token 不残留高权限）。
 */
class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-secret-at-least-32-bytes-long!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private UserStore store;
    private com.opspilot.metrics.AuditService audit;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        OpsPilotProperties props = new OpsPilotProperties(
                null, null, null, new OpsPilotProperties.Jwt(SECRET, 3600), null, null, null, null);
        store = mock(UserStore.class);
        audit = mock(com.opspilot.metrics.AuditService.class);
        filter = new JwtAuthFilter(new JwtService(props), store, audit);
    }

    private static UserStore.User active(String sub, String tenant, int level) {
        return new UserStore.User(sub, tenant, level, "sre", false, 1);
    }

    /** 手工签任意 claim 组合（红队面）。 */
    private static String token(Integer level, String tenant, Integer tver) {
        var b = Jwts.builder().subject("sre-test").claim("role", "sre")
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (level != null) b.claim("auth_level", level);
        if (tenant != null) b.claim("tenant_id", tenant);
        if (tver != null) b.claim("tver", tver);
        return b.signWith(KEY).compact();
    }

    private MockHttpServletResponse dispatch(String bearer, MockFilterChain chain) throws Exception {
        return dispatchOn("POST", "/api/v1/copilot/search", bearer, chain);
    }

    private MockHttpServletResponse dispatchOn(String method, String path, String bearer,
                                               MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (bearer != null) req.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    /** OpenAI 兼容面（/v1）守卫：OPTIONS 预检放行（否则浏览器端客户端死在预检），其余同 JWT。 */
    @Test
    void optionsPreflightOnV1PassesWithoutCredential() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = dispatchOn("OPTIONS", "/v1/chat/completions", null, chain);
        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest(), "CORS 预检必须放行给 DispatcherServlet 处理");
    }

    @Test
    void v1EndpointsRequireCredential() throws Exception {
        MockFilterChain c1 = new MockFilterChain();
        assertEquals(401, dispatchOn("POST", "/v1/chat/completions", null, c1).getStatus());
        MockFilterChain c2 = new MockFilterChain();
        assertEquals(401, dispatchOn("GET", "/v1/models", null, c2).getStatus(),
                "模型列表同样受守卫（无 Key 不暴露模型面）");
        assertNull(c1.getRequest());
        assertNull(c2.getRequest());
    }

    @Test
    void validTokenWithActiveUserIsAdmitted() throws Exception {
        when(store.find("sre-test")).thenReturn(active("sre-test", "tenant-demo", 1));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = dispatch(token(1, "tenant-demo", 1), chain);
        assertEquals(200, resp.getStatus());
        UserContext ctx = (UserContext) chain.getRequest().getAttribute(UserContext.REQUEST_ATTR);
        assertEquals(1, ctx.authLevel());
        assertEquals("tenant-demo", ctx.tenantId());
    }

    @Test
    void authLevelBoundariesRejected401() throws Exception {
        when(store.find("sre-test")).thenReturn(active("sre-test", "tenant-demo", 1));
        for (Integer lvl : new Integer[]{null, 0, -2}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse resp = dispatch(token(lvl, "tenant-demo", 1), chain);
            assertEquals(401, resp.getStatus(), "level=" + lvl + " 必须 401");
            assertNull(chain.getRequest());
        }
    }

    @Test
    void tenantBoundariesRejected401() throws Exception {
        when(store.find("sre-test")).thenReturn(active("sre-test", "tenant-demo", 1));
        for (String t : new String[]{null, "", "   ", "t".repeat(65)}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse resp = dispatch(token(1, t, 1), chain);
            assertEquals(401, resp.getStatus(), "tenant=[" + t + "] 必须 401");
            assertNull(chain.getRequest(), "tenant 非法不得进入下游");
        }
    }

    @Test
    void unknownUserRejectedEvenWithValidClaims() throws Exception {
        when(store.find("sre-test")).thenReturn(null);
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, dispatch(token(1, "tenant-demo", 1), chain).getStatus());
        assertNull(chain.getRequest(), "无账号记录的签名 token 不得放行");
    }

    @Test
    void disabledUserRejected401() throws Exception {
        when(store.find("sre-test")).thenReturn(
                new UserStore.User("sre-test", "tenant-demo", 1, "sre", true, 1));
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, dispatch(token(1, "tenant-demo", 1), chain).getStatus());
        assertNull(chain.getRequest(), "禁用账号的存量 token 即时失效");
    }

    @Test
    void staleTokenVersionRejected401() throws Exception {
        when(store.find("sre-test")).thenReturn(
                new UserStore.User("sre-test", "tenant-demo", 1, "sre", false, 7));
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, dispatch(token(1, "tenant-demo", 1), chain).getStatus(),
                "token_ver 落后=已被全局吊销");
        assertNull(chain.getRequest());
    }

    @Test
    void permissionDimensionsComeFromDbNotClaims() throws Exception {
        // token 里签的是 level 3 / tenant-old，但 DB 真实为 level 1 / tenant-internal
        when(store.find("sre-test")).thenReturn(active("sre-test", "tenant-internal", 1));
        MockFilterChain chain = new MockFilterChain();
        assertEquals(200, dispatch(token(3, "tenant-old", 1), chain).getStatus());
        UserContext ctx = (UserContext) chain.getRequest().getAttribute(UserContext.REQUEST_ATTR);
        assertEquals(1, ctx.authLevel(), "降级后旧高等级 token 不得残留权限（DB 为唯一真相）");
        assertEquals("tenant-internal", ctx.tenantId());
    }

    /**
     * P2-3（QA 台账 2026-09-11）：/v1 面的 401 必须是 OpenAI 标准错误形状——filter 层短路
     * 不经过 OpenAiErrorAdvice，Spring 默认体（含 path 回显）会让标准客户端解析不了 token 过期。
     */
    @Test
    void v1DeniedReturnsOpenAiErrorShape() throws Exception {
        MockHttpServletResponse resp = dispatchOn("POST", "/v1/chat/completions", null, new MockFilterChain());
        assertEquals(401, resp.getStatus());
        String body = resp.getContentAsString();
        assertTrue(body.contains("\"error\""), "OpenAI 错误形状缺失: " + body);
        assertTrue(body.contains("\"authentication_error\""), "type 字段不符: " + body);
        assertTrue(body.contains("\"invalid_api_key\""), "code 字段不符: " + body);
        assertFalse(body.contains("\"path\""), "不得回显内部路径: " + body);
        assertTrue(resp.getContentType().startsWith("application/json"),
                "内容类型应为 JSON，实际 " + resp.getContentType());
    }

    /** 同形状对"凭证无效"分支（过期/吊销/篡改）也成立——LobeChat 用户 token 24h 过期即走此路。 */
    @Test
    void v1InvalidTokenAlsoOpenAiShape() throws Exception {
        when(store.find("sre-test")).thenReturn(null);
        MockHttpServletResponse resp = dispatchOn("GET", "/v1/models",
                token(1, "tenant-demo", 1), new MockFilterChain());
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"invalid_api_key\""));
    }

    /** /api 面维持原样（sendError 语义），本修复只收敛 /v1 形状。 */
    @Test
    void apiSurfaceKeepsPlainUnauthorized() throws Exception {
        MockHttpServletResponse resp = dispatch(null, new MockFilterChain());
        assertEquals(401, resp.getStatus());
        assertEquals("missing bearer token", resp.getErrorMessage());
    }

    /** QA 第五轮 P3：RFC 7235 scheme 大小写不敏感 + 多空白容忍；裸值/Basic 仍拒。 */
    @Test
    void bearerSchemeCaseInsensitiveAndWhitespaceTolerant() throws Exception {
        when(store.find("sre-test")).thenReturn(active("sre-test", "tenant-demo", 1));
        String jwt = token(1, "tenant-demo", 1);
        for (String h : new String[]{"bearer " + jwt, "Bearer  " + jwt}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse resp = dispatchHeader("POST", "/api/v1/copilot/search", h, null, chain);
            assertEquals(200, resp.getStatus(), "RFC7235 变体应放行: " + h.substring(0, 12) + "…");
            assertNotNull(chain.getRequest());
        }
        assertEquals(401, dispatchHeader("POST", "/api/v1/copilot/search", jwt, null,
                new MockFilterChain()).getStatus(), "裸值（无 scheme）拒绝");
        assertEquals(401, dispatchHeader("POST", "/api/v1/copilot/search", "Basic abc", null,
                new MockFilterChain()).getStatus(), "Basic scheme 拒绝");
    }

    /** QA 第五轮 P2：/v1 401 由 filter 短路（早于 CORS 处理器），白名单 Origin 必须回显 ACAO。 */
    @Test
    void v1UnauthorizedEchoesAllowlistedOrigin() throws Exception {
        MockHttpServletResponse ok = dispatchHeader("POST", "/v1/chat/completions", null,
                "http://localhost:3210", new MockFilterChain());
        assertEquals(401, ok.getStatus());
        assertEquals("http://localhost:3210", ok.getHeader("Access-Control-Allow-Origin"),
                "浏览器端要能读到 401 错误体而非笼统 fetch 失败");
        MockHttpServletResponse evil = dispatchHeader("POST", "/v1/chat/completions", null,
                "http://evil.example", new MockFilterChain());
        assertNull(evil.getHeader("Access-Control-Allow-Origin"), "白名单外不回显");
    }

    private MockHttpServletResponse dispatchHeader(String method, String path, String authHeader,
                                                   String origin, MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (authHeader != null) req.addHeader("Authorization", authHeader);
        if (origin != null) req.addHeader("Origin", origin);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    /** QA P1-2：守卫拒绝必须留痕且原因可辨（sub 仅在 token 可解析时携带，不编造身份）。 */
    @Test
    void denialLeavesDiscriminatingAuditTrail() throws Exception {
        when(store.find("sre-test")).thenReturn(active("sre-test", "tenant-demo", 1));
        dispatch(token(0, "tenant-demo", 1), new MockFilterChain());
        verify(audit).logAuthDenied("sre-test", "/api/v1/copilot/search", "denied", "level_invalid");

        dispatchOn("POST", "/v1/chat/completions", null, new MockFilterChain());
        verify(audit).logAuthDenied(null, "/v1/chat/completions", "denied", "missing_bearer");

        when(store.find("sre-test")).thenReturn(null);
        dispatch(token(1, "tenant-demo", 1), new MockFilterChain());
        verify(audit).logAuthDenied("sre-test", "/api/v1/copilot/search", "denied", "unknown_or_revoked");
    }
}
