package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.gateway.dto.ChatRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * OpenAI 兼容面（/v1，ADR-0007）：任何标准 OpenAI 客户端（LobeChat/Dify/SDK）可直连本排障网关。
 * 身份模型=「API Key 即 OpsPilot JWT」：/v1 在 JwtAuthFilter 守卫内，租户/密级/吊销/配额/审计
 * 经同一 ChatOrchestrator 全量穿透——这不是第二套系统，是同一编排的第二个协议面。
 * 有状态性声明：无状态单轮语义（取最后一条 user 消息，忽略 system/历史——检索按单 query
 * 指纹设计，多轮请由客户端把上下文并入单条消息），取舍见 ADR-0007。
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    private static final String DEFAULT_MODEL = "opspilot";

    /** OpenAI chat.completions 请求体（仅取所需字段，未知字段忽略）。 */
    public record Msg(String role, String content) {}
    public record ChatCompletionsReq(String model, List<Msg> messages, boolean stream) {}

    private final ChatOrchestrator orchestrator;

    public OpenAiController(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletions(@RequestBody ChatCompletionsReq req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        if (!req.stream()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "本端点仅提供流式（stream=true）补全");
        }
        String query = lastUserMessage(req.messages());
        orchestrator.precheck(user);
        String model = sanitizeModel(req.model());
        // 300s：覆盖 live 全链路（embed 双路 + rerank + LLM 长答案），比 SSE 原生面留更宽余量
        SseEmitter emitter = new SseEmitter(300_000L);
        orchestrator.submit(new ChatRequest(query, "manual", "", "prod"),
                user, new OpenAiChatSink(emitter, model), "openai");
        return emitter;
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of("object", "list",
                "data", List.of(Map.of(
                        "id", DEFAULT_MODEL,
                        "object", "model",
                        "owned_by", "opspilot-gateway")));
    }

    private static String lastUserMessage(List<Msg> messages) {
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Msg m = messages.get(i);
                if (m != null && "user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                    return m.content();
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 中缺少非空的 user 消息");
    }

    private static String sanitizeModel(String model) {
        if (model == null || model.isBlank()) return DEFAULT_MODEL;
        return model.length() > 64 ? model.substring(0, 64) : model;
    }

    // 错误形状由独立 OpenAiErrorAdvice 处理（嵌套 advice 不被组件扫描，曾踩）
}
