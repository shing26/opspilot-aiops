package com.opspilot.metrics;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求级关联标识（H2，生产就绪度 2026-09-12）：8 位短 id 注入 MDC，
 * 让 app.log 的每行日志（logback %X{request_id}）与 audit.jsonl 的审计行
 * （AuditService.writeEvent 读取 MDC）能用同一 id 关联——"审计发现异常→回查生成链"不再断链。
 * 编排异步段（虚拟线程任务）由 ChatOrchestrator.submit 显式携带——MDC 是 thread-local，
 * 不跨线程传播，必须在任务内重挂。
 * 命名注记：不能叫 RequestContextFilter——与 Boot WebMvcAutoConfiguration 内置同名 bean 冲突
 * （BeanDefinitionOverrideException，实测启动失败）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // 先于 JwtAuthFilter（@Order(0)），守卫拒绝日志同样带 id
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String KEY = "request_id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(KEY, UUID.randomUUID().toString().substring(0, 8));
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(KEY);   // Tomcat 线程池复用，必须清理防串号
        }
    }
}
