package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.metrics.AuditService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QA P2-1/P2-2 回归锁（错误形状）：SSE 端点校验失败时，错误文案必须真正到达客户端。
 * 旧实现返回 ResponseEntity<Map>，在 Accept: text/event-stream 下被内容协商吞成
 * 406→裸 400 空 body（小周实测"改请求改到怀疑自己"）；现直写 JSON 字节绕开协商。
 */
class GlobalExceptionHandlerTest {

    private final AuditService audit = mock(AuditService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(audit);

    /** 仅需一个带参方法做 MethodParameter 载体；异常本身只消费 BindingResult。 */
    static void dummyBindingTarget(String query) {}

    private static MethodArgumentNotValidException invalid(String field, String msg) throws Exception {
        Method m = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyBindingTarget", String.class);
        MethodParameter mp = new MethodParameter(m, 0);
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "chatRequest");
        br.addError(new FieldError("chatRequest", field, msg));
        return new MethodArgumentNotValidException(mp, br);
    }

    @Test
    void validationErrorOnSseAcceptStillReachesClientBody() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat/stream");
        req.addHeader("Accept", "text/event-stream");
        req.setAttribute(UserContext.REQUEST_ATTR,
                new UserContext("sre-x", "sre", 1, "tenant-internal"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onValidation(invalid("query", "不能为空"), req, resp);

        assertEquals(400, resp.getStatus());
        assertTrue(resp.getContentType().startsWith("application/json"),
                "直写必须钉死 JSON 内容类型（不随 Accept 协商）: " + resp.getContentType());
        String body = resp.getContentAsString();
        assertTrue(body.contains("query") && body.contains("不能为空"), "文案必达: " + body);
        verify(audit).logInvalid(any(UserContext.class), eq("/api/v1/copilot/chat/stream"), contains("不能为空"));
    }

    @Test
    void statusAndGenericErrorsAreDirectWritten() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/reingest");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        handler.onStatus(new ResponseStatusException(HttpStatus.FORBIDDEN, "需要平台管理员凭证"), r1);
        assertEquals(403, r1.getStatus());
        assertTrue(r1.getContentAsString().contains("HTTP_403"));

        MockHttpServletResponse r2 = new MockHttpServletResponse();
        handler.onOther(new RuntimeException("jdbc leaked // SELECT * FROM users"), r2);
        assertEquals(500, r2.getStatus());
        assertFalse(r2.getContentAsString().contains("SELECT"), "内部细节不得外泄");
        verifyNoInteractions(audit); // onOther 不产 invalid 事件（栈进日志，事件只归 400）
    }
}
