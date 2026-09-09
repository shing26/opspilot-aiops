package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.resilience.DegradationStateMachine.Level;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件原语（meta/delta/done）：从 CopilotController 抽出（ADR-0004 单链路的线格式层）。
 * 目的：Controller 只因编排变化，事件帧格式只因协议变化——拆开 Divergent Change 的两个轴。
 * 口径约定：done.ttft_ms 恒为 first_token 口径（调用方传入首个 delta 的时刻），
 * 由 acceptance_a3 A3-6 双源断言锁死。
 */
@Component
public class SseEvents {

    private static final Logger log = LoggerFactory.getLogger(SseEvents.class);

    public void emitMeta(SseEmitter emitter, String fp, String cacheHit, Level level,
                         boolean fastPath, boolean deduplicated, long retrievalMs) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fingerprint", fp);
        meta.put("cache_hit", cacheHit);
        meta.put("degradation_level", level.name());
        meta.put("fast_path", fastPath);
        meta.put("deduplicated", deduplicated);
        meta.put("retrieval_ms", retrievalMs);
        trySend(emitter, "meta", meta);
    }

    /** 完整答案分片下发，保留打字机流式体验（缓存回放/降级直出用）。 */
    public void streamInChunks(SseEmitter emitter, String answer) {
        for (int i = 0; i < answer.length(); i += 48) {
            emitDelta(emitter, answer.substring(i, Math.min(i + 48, answer.length())));
        }
    }

    public void emitDelta(SseEmitter emitter, String token) {
        trySend(emitter, "delta", Map.of("token", token));
    }

    public void emitDone(SseEmitter emitter, long t0, long firstTokenNano, List<AnswerPayload.Ref> refs) {
        long ttftMs = (firstTokenNano - t0) / 1_000_000;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("ttft_ms", ttftMs);
        done.put("refs", refs);
        trySend(emitter, "done", done);
        emitter.complete();
    }

    public void trySend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed (client gone): {}", e.getMessage());
        }
    }
}
