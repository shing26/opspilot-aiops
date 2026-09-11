package com.opspilot.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.metrics.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一错误响应：结构化 code/message，屏蔽内部异常文案。
 * 直写 response 而非返回 ResponseEntity（QA 小周 P2-1）：SSE 端点 produces=text/event-stream
 * 时 Map 无法按客户端 Accept 协商 → HttpMediaTypeNotAcceptableException 吞掉错误体，
 * 用户只见到裸 400 空 body。直写字节绕开内容协商，保证错误文案必达；400 同步留痕
 * （QA P1-2：改请求如坠迷雾且攻击面不可查）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void onValidation(MethodArgumentNotValidException e, HttpServletRequest req,
                             HttpServletResponse resp) throws IOException {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        UserContext u = JwtAuthFilter.from(req);
        audit.logInvalid(u, req.getRequestURI(), msg);
        writeJson(resp, 400, Map.of("code", "INVALID_REQUEST", "message", msg));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public void onStatus(ResponseStatusException e, HttpServletResponse resp) throws IOException {
        int status = e.getStatusCode().value();
        // 401 由 JwtAuthFilter 短路产生、不经 advice（/v1 面另有 OpenAI 形状）；
        // 403 平台门禁已由 AdminController 落审计，此处只管呈现。
        writeJson(resp, status, Map.of("code", "HTTP_" + status,
                "message", e.getReason() == null ? "error" : e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public void onOther(Exception e, HttpServletResponse resp) throws IOException {
        // 对外屏蔽细节，对内必须留栈——否则 500 是诊断黑洞（本仓踩过：/v1 首测 500 无线索）
        log.warn("unhandled exception", e);
        writeJson(resp, 500, Map.of("code", "INTERNAL_ERROR", "message", "服务内部错误"));
    }

    private void writeJson(HttpServletResponse resp, int status, Map<String, Object> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(mapper.writeValueAsString(body));
    }
}
