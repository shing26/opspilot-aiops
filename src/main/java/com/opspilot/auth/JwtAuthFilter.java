package com.opspilot.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.opspilot.config.OpsPilotProperties;

/**
 * 鉴权拦截器：解析 Bearer JWT，注入 UserContext。
 * /api/v1/copilot/** 与 /api/v1/admin/** 均需有效凭证；auth_level claim 必须存在且 >=1、
 * tenant_id 必须非空非空白且 ≤64 字符（任一非法一律 401——检索双条件硬过滤、L1 key、
 * L2 回放都以 (tenant, auth_level) 为维度，入口保证二者真实存在）。
 * admin 状态变更端点在 Controller 层另有 auth_level>=3 门禁。
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
        // 仅放行健康检查等公开路径；copilot 与 admin 均需有效凭证
        boolean guarded = path.startsWith("/api/v1/copilot") || path.startsWith("/api/v1/admin");
        if (!guarded) {
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
            Integer level = claims.get("auth_level", Integer.class);
            if (level == null || level < 1) {
                // 下限校验：auth_level 缺失或 <1 视为非法凭证，防止 level-0 token 提权
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid token");
                return;
            }
            String tenant = claims.get("tenant_id", String.class);
            if (tenant == null || tenant.isBlank() || tenant.length() > 64) {
                // P1 租户显式化：tenant 缺失/空/超长一律拒绝——检索双条件过滤、L1 key、
                // L2 回放都以 tenant 为硬维度，入口必须保证它真实存在
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid token");
                return;
            }
            UserContext ctx = new UserContext(
                    claims.getSubject(),
                    claims.get("role", String.class),
                    level,
                    tenant);
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
}
