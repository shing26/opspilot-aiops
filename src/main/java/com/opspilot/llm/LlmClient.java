package com.opspilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;

/**
 * qwen-plus 流式对话：JDK HttpClient 手写解析 OpenAI 兼容 SSE（无 SDK，DoD 要求无重量级黑盒依赖）。
 * 429 抛 LlmRateLimitedException 供降级状态机熔断。
 */
@Component
public class LlmClient {

    public static class LlmRateLimitedException extends RuntimeException {
        public LlmRateLimitedException() { super("LLM 429 rate limited"); }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpsPilotProperties props;

    public LlmClient(OpsPilotProperties props) {
        this.props = props;
    }

    /** 流式生成：onToken 逐 token 回调，返回完整答案文本。 */
    public String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) {
        if (!props.dashscope().live()) {
            return mockStream(messages, onToken);
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", props.dashscope().llmModel(),
                    "messages", messages,
                    "stream", true,
                    "temperature", 0.2));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.dashscope().baseUrl() + "/compatible-mode/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(props.dashscope().llmTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.dashscope().apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 429) throw new LlmRateLimitedException();
            if (resp.statusCode() != 200) {
                throw new RuntimeException("LLM HTTP " + resp.statusCode());
            }

            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;
                    JsonNode node = mapper.readTree(payload);
                    JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String token = delta.asText();
                        if (!token.isEmpty()) {
                            full.append(token);
                            onToken.accept(token);
                        }
                    }
                }
            }
            return full.toString();
        } catch (LlmRateLimitedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /** Mock 流式：从 user 消息抽取参考上下文 → 组装答案 → 按 24 字符切片回调。 */
    private String mockStream(List<Map<String, String>> messages, Consumer<String> onToken) {
        String user = messages.get(messages.size() - 1).get("content");
        List<String> chunks = new java.util.ArrayList<>();
        int idx = user.indexOf("【参考上下文】");
        if (idx >= 0) {
            String ctx = user.substring(idx);
            for (String part : ctx.split("\\[参考\\d+\\] ")) {
                if (!part.isBlank()) chunks.add(part.trim());
            }
        }
        String answer = MockEngine.answer(chunks);
        for (int i = 0; i < answer.length(); i += 24) {
            String piece = answer.substring(i, Math.min(i + 24, answer.length()));
            onToken.accept(piece);
        }
        return answer;
    }
}
