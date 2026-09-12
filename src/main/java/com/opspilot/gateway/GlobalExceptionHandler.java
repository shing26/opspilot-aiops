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
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误响应：结构化 code/message，屏蔽内部异常文案。
 * 直写 response 而非返回 ResponseEntity（QA 小周 P2-1）：SSE 端点 produces=text/event-stream
 * 时 Map 无法按客户端 Accept 协商 → HttpMediaTypeNotAcceptableException 吞掉错误体。
 * 直写字节绕开内容协商，保证错误文案必达；400 类同步留痕（QA P1-2）。
 *
 * 错误分层（QA 第五轮 P1）：客户端错误绝不能落 500 兜底——参数类型错/方法错/媒体类型错/
 * 未知静态资源各有专属分支；且 /v1 面（OpenAI 协议）的错误必须保持 {"error":{...}} 形状
 * （方法面与协商异常发生在进 controller 之前，OpenAiErrorAdvice 覆盖不到，由本类分流）。
 * 404/405 不落审计（扫描噪音）；400 类落 ev=invalid。
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
        write(req, resp, 400, "INVALID_REQUEST", msg);
    }

    /** 查询/路径参数类型错（如 since=abc、limit=1e18）：曾落 500 兜底污染 5xx SLO（QA P1）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public void onTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req,
                               HttpServletResponse resp) throws IOException {
        String need = e.getRequiredType() == null ? "正确类型" : e.getRequiredType().getSimpleName();
        String msg = "参数 " + e.getName() + " 非法（需 " + need + " / parameter '" + e.getName()
                + "' must be of type " + need + "）";
        audit.logInvalid(JwtAuthFilter.from(req), req.getRequestURI(), msg);
        write(req, resp, 400, "INVALID_REQUEST", msg);
    }

    /** POST-only 端点被打 GET 等：曾落 500 且无 Allow 头。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void onMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest req,
                                     HttpServletResponse resp) throws IOException {
        var supported = e.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            resp.setHeader("Allow", supported.stream().map(HttpMethod::name)
                    .sorted().collect(java.util.stream.Collectors.joining(", ")));
        }
        write(req, resp, 405, "HTTP_405", "请求方法不被支持（method not supported: " + e.getMethod() + "）");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void onMediaTypeNotSupported(HttpMediaTypeNotSupportedException e, HttpServletRequest req,
                                        HttpServletResponse resp) throws IOException {
        String msg = "Content-Type 不被支持（" + e.getContentType() + "），请用 application/json";
        audit.logInvalid(JwtAuthFilter.from(req), req.getRequestURI(), msg);
        write(req, resp, 415, "HTTP_415", msg);
    }

    /** 未知路径/favicon：曾落 500（每次开页面板控制台必报错）。404 不落审计（防扫描噪音）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public void onNoResource(NoResourceFoundException e, HttpServletRequest req,
                             HttpServletResponse resp) throws IOException {
        write(req, resp, 404, "NOT_FOUND",
                "资源不存在（no resource: " + req.getMethod() + " " + req.getRequestURI() + "）");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public void onStatus(ResponseStatusException e, HttpServletRequest req,
                         HttpServletResponse resp) throws IOException {
        int status = e.getStatusCode().value();
        write(req, resp, status, "HTTP_" + status,
                e.getReason() == null ? "error" : e.getReason());
    }

    @ExceptionHandler(Exception.class)
    public void onOther(Exception e, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 对外屏蔽细节，对内必须留栈——否则 500 是诊断黑洞（本仓踩过：/v1 首测 500 无线索）
        log.warn("unhandled exception", e);
        write(req, resp, 500, "INTERNAL_ERROR", "服务内部错误");
    }

    /**
     * 形状分流：/v1 面（OpenAI 协议）一律 {"error":{message,type}}（code 缺省=规范允许的 null；
     * 401 invalid_api_key / 429 rate_limit_error 由各自专属路径携带），其余面维持 {code,message}。
     */
    private void write(HttpServletRequest req, HttpServletResponse resp, int status,
                       String code, String message) throws IOException {
        if (req.getRequestURI().startsWith("/v1")) {
            String type = status >= 500 ? "server_error" : "invalid_request_error";
            writeRaw(resp, status, mapper.writeValueAsString(Map.of(
                    "error", Map.of("message", message, "type", type))));
            return;
        }
        writeRaw(resp, status, mapper.writeValueAsString(Map.of("code", code, "message", message)));
    }

    private void writeRaw(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(json);
    }
}
