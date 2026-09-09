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
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        OpsPilotProperties props = new OpsPilotProperties(
                null, null, null, new OpsPilotProperties.Jwt(SECRET, 3600), null, null, null, null);
        store = mock(UserStore.class);
        filter = new JwtAuthFilter(new JwtService(props), store);
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
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/search");
        if (bearer != null) req.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
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
}
