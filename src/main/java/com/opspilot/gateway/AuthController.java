package com.opspilot.gateway;

import com.opspilot.auth.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录端点（P2）：/api/v1/auth/** 不在 JwtAuthFilter 守卫清单内（免凭证），
 * 但受 AuthService 的失败限流保护。口令仅存在于请求体，绝不入日志/响应。
 *
 * <p><b>2026-09-28 修一处错误形状缺陷（探索性验收实测）</b>：两个 {@code @ExceptionHandler}
 * 此前**返回** `ResponseStatusException` 对象，被 Spring 按 ErrorResponse 渲染成
 * `application/problem+json`（`{type,title,status,detail,instance}`）——这是全仓**第三种**错误形状，
 * 既不是文档声明的 `{code,message}`，也不是 `/v1` 的 OpenAI 信封，还会回显 `instance` 路径。
 * 现在改为**直写** `ErrorBodies` 的统一形状（与 `GlobalExceptionHandler` 同源），
 * 于是"错误形状"全仓只剩两种，且由单点定义。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody @jakarta.validation.Valid LoginReq req) {
        String token = auth.login(req.username().trim(), req.password());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("token_type", "Bearer");
        return resp;
    }

    /** 429 不在 Servlet 的 `SC_*` 常量表里（Servlet 6 未定义），本地命名以免出现裸魔数。 */
    private static final int SC_TOO_MANY_REQUESTS = 429;

    /** 口令错/账号不存在统一话术（不区分，避免账号枚举）。 */
    @ExceptionHandler(AuthService.BadCredentialsException.class)
    public void bad(HttpServletResponse resp) throws IOException {
        ErrorBodies.writeApi(resp, HttpServletResponse.SC_UNAUTHORIZED,
                "UNAUTHORIZED", "invalid username or password");
    }

    /** 失败限流锁定（AuthService 侧计数）：429 同样是客户端错，形状与上面一致。 */
    @ExceptionHandler(AuthService.LoginLockedException.class)
    public void locked(HttpServletResponse resp) throws IOException {
        ErrorBodies.writeApi(resp, SC_TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "temporarily locked");
    }
}
