package com.opspilot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登录面错误形状的回归锁（2026-09-28，探索性验收实测：`POST /api/v1/auth/login` 错口令返回的是
 * `application/problem+json`（`{type,title,status,detail,instance}`）——全仓**第三种**错误形状，
 * 既非文档声明的 `{code,message}`，也非 /v1 的 OpenAI 信封，还回显 `instance` 路径）。
 *
 * 根因：两个 `@ExceptionHandler` 返回 `ResponseStatusException` 对象，被 Spring 按 ErrorResponse 渲染，
 * 绕过了统一形状。修法是直写 `ErrorBodies`。本测试**直接调 handler 方法**（不启 MVC 栈）锁形状。
 */
class AuthControllerTest {

    /** 只测错误形状，不经 AuthService（两个 handler 不碰 auth 字段）。 */
    private final AuthController controller = new AuthController(null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void badCredentialsUseCodeMessageShapeNotProblemJson() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.bad(resp);

        assertEquals(401, resp.getStatus());
        assertEquals("application/json;charset=UTF-8", resp.getContentType(),
                "不得是 application/problem+json");
        JsonNode body = mapper.readTree(resp.getContentAsString());
        assertEquals("UNAUTHORIZED", body.get("code").asText());
        assertEquals("invalid username or password", body.get("message").asText());
        assertFalse(body.has("type") || body.has("title") || body.has("instance"),
                "不得落 ErrorResponse 的字段（type/title/instance）：" + body);
    }

    @Test
    void loginLockedUsesSameShape() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.locked(resp);

        assertEquals(429, resp.getStatus());
        JsonNode body = mapper.readTree(resp.getContentAsString());
        assertEquals("TOO_MANY_REQUESTS", body.get("code").asText());
        assertEquals("temporarily locked", body.get("message").asText());
    }
}
