package com.opspilot.gateway;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * /v1 面的错误形状按 OpenAI 规范输出 {"error":{message,type,code}}——标准客户端
 * （LobeChat/SDK）解析不了其它形状。@Order(0) 必须高于全局 advice；
 * 注：advice 不能写成 OpenAiController 嵌套类（嵌套类不参与组件扫描，实测踩过）。
 */
@RestControllerAdvice(assignableTypes = OpenAiController.class)
@Order(0)
public class OpenAiErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(OpenAiErrorAdvice.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        return body(code, e.getReason() == null ? "error" : e.getReason(),
                code == 429 ? "rate_limit_error" : "invalid_request_error");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        log.warn("openai body parse failed", e); // cause 细节只进日志，响应保持卫生
        return body(400, "请求体解析失败", "invalid_request_error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return body(500, "服务端内部错误", "server_error"); // 屏蔽 JVM 内部文案，与全局卫生标准一致
    }

    private static ResponseEntity<Map<String, Object>> body(int code, String message, String type) {
        return ResponseEntity.status(code).body(Map.of(
                "error", Map.of("message", message, "type", type, "code", String.valueOf(code))));
    }
}
