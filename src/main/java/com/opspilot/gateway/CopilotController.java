package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.gateway.dto.ChatRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 排障 Copilot 流式入口（单链路，ADR-0004）。协议面：SSE meta/delta/done（帧格式见 SseEvents）。
 * P 收尾批次起变薄：编排全在 ChatOrchestrator（与 /v1 OpenAI 面共享），本类只做
 * 身份获取、同步配额前置（429 需在 servlet 线程抛）与 sink 装配。
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class CopilotController {

    private final ChatOrchestrator orchestrator;
    private final SseEvents events;

    public CopilotController(ChatOrchestrator orchestrator, SseEvents events) {
        this.orchestrator = orchestrator;
        this.events = events;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@jakarta.validation.Valid @RequestBody ChatRequest req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        orchestrator.precheck(user);   // 配额 429 前置，省下游
        SseEmitter emitter = new SseEmitter(120_000L);
        orchestrator.submit(req, user, new SseChatSink(emitter, events), "sse");
        return emitter;
    }
}
