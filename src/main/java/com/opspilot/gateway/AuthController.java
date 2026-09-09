package com.opspilot.gateway;

import com.opspilot.auth.AuthService;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 登录端点（P2）：/api/v1/auth/** 不在 JwtAuthFilter 守卫清单内（免凭证），
 * 但受 AuthService 的失败限流保护。口令仅存在于请求体，绝不入日志/响应。
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
    public Map<String, Object> login(@org.springframework.web.bind.annotation.RequestBody
                                     @jakarta.validation.Valid LoginReq req) {
        String token = auth.login(req.username().trim(), req.password());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("token_type", "Bearer");
        return resp;
    }

    @ExceptionHandler(AuthService.BadCredentialsException.class)
    public ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
    }

    @ExceptionHandler(AuthService.LoginLockedException.class)
    public ResponseStatusException locked() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "temporarily locked");
    }
}
