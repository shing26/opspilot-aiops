package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** /v1 入口面：OpenAI 请求 → 内部 ChatRequest 映射、流式约束、messages 提取规则。 */
class OpenAiControllerTest {

    private final ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
    private final OpenAiController controller = new OpenAiController(orchestrator);

    private static MockHttpServletRequest withUser() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR,
                new UserContext("sre-full", "sre", 3, "tenant-internal"));
        return req;
    }

    @Test
    void takesLastUserMessageIgnoringSystemAndHistory() {
        controller.chatCompletions(new OpenAiController.ChatCompletionsReq("opspilot",
                List.of(new OpenAiController.Msg("system", "你是个助手"),
                        new OpenAiController.Msg("user", "第一问"),
                        new OpenAiController.Msg("assistant", "答"),
                        new OpenAiController.Msg("user", "50012_DB_TIMEOUT 怎么查")), true),
                withUser());

        var captor = ArgumentCaptor.forClass(com.opspilot.gateway.dto.ChatRequest.class);
        verify(orchestrator).submit(captor.capture(), any(), any(), eq("openai"));
        assertEquals("50012_DB_TIMEOUT 怎么查", captor.getValue().query());
    }

    @Test
    void nonStreamIsRejected() {
        var ex = assertThrows(ResponseStatusException.class, () -> controller.chatCompletions(
                new OpenAiController.ChatCompletionsReq("m",
                        List.of(new OpenAiController.Msg("user", "q")), false), withUser()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void missingUserMessageIs400() {
        for (List<OpenAiController.Msg> msgs : List.<List<OpenAiController.Msg>>of(List.of(),
                List.of(new OpenAiController.Msg("system", "只有 system")))) {
            var ex = assertThrows(ResponseStatusException.class, () -> controller.chatCompletions(
                    new OpenAiController.ChatCompletionsReq("m", msgs, true), withUser()));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        verify(orchestrator, never()).precheck(any());
    }

    @Test
    void quotaPrecheckHappensBeforeSubmit() {
        controller.chatCompletions(new OpenAiController.ChatCompletionsReq(null,
                List.of(new OpenAiController.Msg("user", "q")), true), withUser());
        var inOrder = inOrder(orchestrator);
        inOrder.verify(orchestrator).precheck(any());
        inOrder.verify(orchestrator).submit(any(), any(), any(), anyString());
    }

    @Test
    void modelsAdvertisesOpspilot() {
        var m = controller.models();
        assertEquals("list", m.get("object"));
        @SuppressWarnings("unchecked")
        List<Object> data = (List<Object>) m.get("data");
        assertTrue(String.valueOf(data.get(0)).contains("opspilot"));
    }
}
