package com.opspilot.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 鉴权拦截器（P2 起接 H2 账号主库）：解析 Bearer JWT 后做三层校验，注入 UserContext。
 * ① claim 卫生：auth_level 存在且 >=1、tenant_id 非空非空白且 ≤64（防畸形签名的早期拒绝）；
 * ② 用户存在且未禁用（每请求查 H2，µs 级）——被禁用/删除的账号其存量未过期 token 即时失效；
 * ③ 吊销版本：token 的 tver 必须等于用户当前 token_ver（改版本=全局吊销该用户所有 token）。
 * 权限维度（tenant/auth_level/role）以 **DB 为唯一真相**，token claim 仅作定位与卫生检查——
 * 降级用户不可能凭旧 token 维持高等级。/api/v1/copilot|admin/** 受此守卫；/api/v1/auth/login 免凭证
 * （由 AuthService 限流保护）。admin 端点在 Controller 层另有平台门禁（role=platform ∧ level≥3）。
 * 拒绝呈现分面：/v1（OpenAI 兼容面）直写标准 error JSON（filter 短路不经 advice，
 * Spring 默认体标准客户端解析不了——QA P2-3）；其余面维持 sendError。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserStore users;
    private final com.opspilot.metrics.AuditService audit;

    public JwtAuthFilter(JwtService jwt, UserStore users, com.opspilot.metrics.AuditService audit) {
        this.jwt = jwt;
        this.users = users;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // CORS 预检不带 Authorization 头，必须放行（真实请求随后仍受守卫）；否则 LobeChat 等
        // 浏览器端客户端在 OPTIONS 预检即被 401 掐死
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, resp);
            return;
        }
        // 仅放行健康检查等公开路径；copilot、admin 与 /v1（OpenAI 兼容面）均需有效凭证
        boolean guarded = path.startsWith("/api/v1/copilot") || path.startsWith("/api/v1/admin")
                || path.startsWith("/v1");
        if (!guarded) {
            chain.doFilter(req, resp);
            return;
        }
        String header = req.getHeader("Authorization");
        String token = bearerToken(header);
        if (token == null) {
            deny(req, resp, path, "missing bearer token",
                    "No API key provided. Use your OpsPilot JWT as the API key.",
                    null, "missing_bearer");
            return;
        }
        String sub = null;
        try {
            var claims = jwt.parse(token);
            sub = claims.getSubject();
            Integer level = claims.get("auth_level", Integer.class);
            if (level == null || level < 1) {
                // 下限校验：auth_level 缺失或 <1 视为非法凭证，防止 level-0 token 提权
                deny(req, resp, path, "invalid token", "Invalid API key.", sub, "level_invalid");
                return;
            }
            String tenant = claims.get("tenant_id", String.class);
            if (tenant == null || tenant.isBlank() || tenant.length() > 64) {
                // P1 租户显式化：缺失/空白/超长一律拒绝（检索双条件过滤与缓存 key 的硬维度）
                deny(req, resp, path, "invalid token", "Invalid API key.", sub, "tenant_invalid");
                return;
            }
            UserStore.User u = users.find(sub);
            Integer tver = claims.get("tver", Integer.class);
            if (u == null || u.disabled() || tver == null || u.tokenVer() != tver.intValue()) {
                // ②③：无此账号/已禁用/版本不匹配 → 吊销生效（不信任 token 内旧权限）
                deny(req, resp, path, "invalid token", "Invalid API key.", sub, "unknown_or_revoked");
                return;
            }
            UserContext ctx = new UserContext(u.sub(), u.role(), u.authLevel(), u.tenant());
            req.setAttribute(UserContext.REQUEST_ATTR, ctx);
            chain.doFilter(req, resp);
        } catch (Exception e) {
            deny(req, resp, path, "invalid token", "Invalid API key.", sub, "parse_failed");
        }
    }

    /**
     * 401 呈现按面分叉：/v1 直写 OpenAI 标准 error JSON（不回显 path），其余维持容器 sendError。
     * 全分支留痕（QA P1-2）：守卫拒绝是攻击探测唯一可观测面，ev=auth outcome=denied。
     * /v1 的 401 由 filter 短路（早于 CORS 处理器），Origin 命中白名单时须主动回显 ACAO，
     * 否则浏览器端读不到错误详情（QA 第五轮 P2）。
     */
    private void deny(HttpServletRequest req, HttpServletResponse resp, String path,
                      String legacyReason, String openAiMessage, String sub, String auditReason)
            throws IOException {
        audit.logAuthDenied(sub, path, "denied", auditReason);
        if (path.startsWith("/v1")) {
            String origin = req.getHeader("Origin");
            if (com.opspilot.config.CorsConfig.isAllowedOrigin(origin)) {
                resp.setHeader("Access-Control-Allow-Origin", origin);
            }
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":{\"message\":\"" + openAiMessage
                    + "\",\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}");
            return;
        }
        resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, legacyReason);
    }

    /**
     * RFC 7235：scheme 大小写不敏感、冒号后多空白容忍（"bearer  x" 合法）；
     * 无 scheme 裸值 / Basic / 空 token 一律 null=missing。
     */
    private static String bearerToken(String header) {
        if (header == null) return null;
        String h = header.trim();
        int sp = h.indexOf(' ');
        if (sp <= 0 || !"bearer".equalsIgnoreCase(h.substring(0, sp))) return null;
        String token = h.substring(sp + 1).trim();
        return token.isEmpty() ? null : token;
    }

    /** 供 Controller 便捷取用。 */
    public static UserContext from(HttpServletRequest req) {
        return (UserContext) req.getAttribute(UserContext.REQUEST_ATTR);
    }
}
