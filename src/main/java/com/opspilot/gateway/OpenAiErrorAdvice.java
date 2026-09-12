package com.opspilot.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * /v1 面的错误形状按 OpenAI 规范输出 {"error":{message,type,code}}——标准客户端
 * （LobeChat/SDK）解析不了其它形状。@Order(0) 必须高于全局 advice；
 * 注：advice 不能写成 OpenAiController 嵌套类（嵌套类不参与组件扫描，实测踩过）。
 * 直写 response（同 GlobalExceptionHandler 的 406 教训）：/v1 端点 produces 也是
 * text/event-stream，返回 ResponseEntity 会被内容协商吞成空 body——advice 宣称的
 * "标准形状"对带 SSE Accept 的客户端必须同样兑现（QA P2-3 的完整闭环）。
 */
@RestControllerAdvice(assignableTypes = OpenAiController.class)
@Order(0)
public class OpenAiErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(OpenAiErrorAdvice.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final com.opspilot.metrics.AuditService audit;

    public OpenAiErrorAdvice(com.opspilot.metrics.AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public void status(ResponseStatusException e, HttpServletResponse resp) throws IOException {
        int code = e.getStatusCode().value();
        write(resp, code, e.getReason() == null ? "error" : e.getReason(),
                code == 429 ? "rate_limit_error" : "invalid_request_error");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void unreadable(HttpMessageNotReadableException e, HttpServletResponse resp) throws IOException {
        log.warn("openai body parse failed", e); // cause 细节只进日志，响应保持卫生
        write(resp, 400, "Malformed JSON request body / 请求体解析失败", "invalid_request_error");
    }

    /** QA 第五轮 P1：Content-Type 异常（缺失/text/plain）发生在参数解析、仍归属本 controller 的
     *  advice——不分支会落 unexpected 的 500 server_error，SDK 重试逻辑会误判为服务端故障。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void mediaType(HttpMediaTypeNotSupportedException e, HttpServletResponse resp) throws IOException {
        audit.logInvalid(null, "/v1/chat/completions", "Content-Type 不被支持: " + e.getContentType());
        write(resp, 415, "Unsupported Content-Type (use application/json) / 请用 application/json",
                "invalid_request_error");
    }

    @ExceptionHandler(Exception.class)
    public void unexpected(Exception e, HttpServletResponse resp) throws IOException {
        log.warn("openai unhandled", e); // 屏蔽 JVM 文案，与全局卫生标准一致
        write(resp, 500, "服务端内部错误", "server_error");
    }

    /** OpenAI 规范：code 是机器码或 null——把 HTTP 状态码字符串塞入属语义漂移（QA P3），
     *  400/500 缺省；401 invalid_api_key（JwtAuthFilter）与 429 rate_limit_error 各自专属路径携带。 */
    private void write(HttpServletResponse resp, int status, String message, String type) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(mapper.writeValueAsString(Map.of(
                "error", Map.of("message", message, "type", type))));
    }
}
