package com.opspilot.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.opspilot.config.OpsPilotProperties;

/**
 * 鉴权拦截器：解析 Bearer JWT，注入 UserContext。
 * /api/v1/admin/** 与 /actuator 放行（仅本机演示）；/api/v1/copilot/** 强制凭证。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final SecretKey key;

    public JwtAuthFilter(OpsPilotProperties props) {
        String secret = props.jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 环境变量未设置（凭据只从环境读取，见 .env.example）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/v1/copilot")) {
            chain.doFilter(req, resp);
            return;
        }
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing bearer token");
            return;
        }
        try {
            var claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(header.substring(7)).getPayload();
            UserContext ctx = new UserContext(
                    claims.getSubject(),
                    claims.get("role", String.class),
                    claims.get("auth_level", Integer.class),
                    claims.get("tenant_id", String.class));
            req.setAttribute(UserContext.REQUEST_ATTR, ctx);
            chain.doFilter(req, resp);
        } catch (Exception e) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid token");
        }
    }

    /** 供 Controller 便捷取用。 */
    public static UserContext from(HttpServletRequest req) {
        return (UserContext) req.getAttribute(UserContext.REQUEST_ATTR);
    }

    static List<String> roles() {
        return List.of("sre", "dev", "manager");
    }
}
