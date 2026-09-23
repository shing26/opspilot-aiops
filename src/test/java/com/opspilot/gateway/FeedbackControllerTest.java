package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.gateway.dto.FeedbackRequest;
import com.opspilot.metrics.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 反馈端点的三态锁：认证与密级防线不得被绕过，合法反馈必须原样落审计。
 *
 * 为什么这三条值得锁：反馈是"人 → 系统"的唯一人工信号入口，而它同时是"复盘→知识回灌"
 * 在案债务的前置。若它静默失效（无痕、或被未认证者灌入），那条债务链会断在这里而无人察觉。
 */
class FeedbackControllerTest {

    private final AuditService audit = mock(AuditService.class);
    private final FeedbackController controller = new FeedbackController(audit);

    private static MockHttpServletRequest withUser(int level) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR, new UserContext("u", "sre", level, "tenant-demo"));
        return req;
    }

    /** 无身份属性 = filter 未参与（绕过路径）：必须 403，且不得写任何审计行。 */
    @Test
    void unauthenticatedIsForbiddenWithoutAuditing() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.feedback(new FeedbackRequest("fp-1", "down", null),
                        new MockHttpServletRequest()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(audit);
    }

    @Test
    void zeroAndNegativeLevelsAreForbiddenWithoutAuditing() {
        for (int level : new int[]{0, -1}) {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> controller.feedback(new FeedbackRequest("fp-1", "down", null), withUser(level)),
                    "level=" + level + " 必须被 Controller 兜底拒绝");
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }
        verifyNoInteractions(audit);
    }

    @Test
    void validFeedbackIsAuditedWithIdentityAndVerdict() {
        var resp = controller.feedback(new FeedbackRequest("fp-abc", "down", "答案里的错误码是编的"),
                withUser(2));
        assertEquals(Boolean.TRUE, resp.get("ok"));
        verify(audit).logFeedback(argThat(u -> "tenant-demo".equals(u.tenantId()) && u.authLevel() == 2),
                eq("fp-abc"), eq("down"), eq("答案里的错误码是编的"));
    }

    /** 未知 fingerprint 不是错误：系统不认识它也不该崩——反馈的价值在"收下"，不在"先校验"。 */
    @Test
    void unknownFingerprintIsAcceptedNotRejected() {
        var resp = controller.feedback(new FeedbackRequest("00000000000000000000000000000000", "up", null),
                withUser(1));
        assertEquals(Boolean.TRUE, resp.get("ok"));
        verify(audit).logFeedback(any(), eq("00000000000000000000000000000000"), eq("up"), isNull());
    }
}
