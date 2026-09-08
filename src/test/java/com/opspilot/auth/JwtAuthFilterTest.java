package com.opspilot.auth;

import com.opspilot.config.OpsPilotProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 回归：JWT 鉴权对 auth_level 做下限校验 —— claim 缺失或 <1 视为非法凭证 401，
 * 从入口封死「签发 auth_level:0 即提权」的路径。
 */
class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-secret-at-least-32-bytes-long!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtAuthFilter filter = new JwtAuthFilter(
            new OpsPilotProperties(null, null, null, new OpsPilotProperties.Jwt(SECRET),
                    null, null, null, null));

    private static String tokenWithLevel(Integer authLevel) {
        var builder = Jwts.builder().subject("sre-test")
                .claim("role", "sre")
                .claim("tenant_id", "tenant-demo")
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (authLevel != null) builder.claim("auth_level", authLevel);
        return builder.signWith(KEY).compact();
    }

    private MockHttpServletResponse dispatch(String bearer, MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/search");
        if (bearer != null) req.addHeader("Authorization", "Bearer " + bearer);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    @Test
    void validLevelOneTokenIsAdmitted() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = dispatch(tokenWithLevel(1), chain);
        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest(), "合法凭证必须放行至下游");
        UserContext ctx = (UserContext) chain.getRequest()
                .getAttribute(UserContext.REQUEST_ATTR);
        assertEquals(1, ctx.authLevel());
    }

    @Test
    void zeroLevelTokenIsRejected401() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = dispatch(tokenWithLevel(0), chain);
        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest(), "level-0 token 不得进入下游检索链路");
    }

    @Test
    void negativeLevelTokenIsRejected401() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = dispatch(tokenWithLevel(-2), chain);
        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void missingAuthLevelClaimIsRejected401() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = dispatch(tokenWithLevel(null), chain);
        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }
}
