package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.resilience.DegradationStateMachine.Level;
import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** ChatSink 的 SSE 实现：全部帧格式委托 SseEvents（原 /chat/stream 协议零变化）。 */
public class SseChatSink implements ChatSink {

    private final SseEmitter emitter;
    private final SseEvents events;

    public SseChatSink(SseEmitter emitter, SseEvents events) {
        this.emitter = emitter;
        this.events = events;
    }

    @Override
    public void meta(String fp, String cacheHit, Level level, boolean fastPath,
                     boolean deduplicated, long retrievalMs) {
        events.emitMeta(emitter, fp, cacheHit, level, fastPath, deduplicated, retrievalMs);
    }

    @Override
    public void delta(String token) {
        events.emitDelta(emitter, token);
    }

    @Override
    public void streamInChunks(String answer) {
        events.streamInChunks(emitter, answer);
    }

    @Override
    public void done(long t0, long firstTokenNano, List<AnswerPayload.Ref> refs) {
        events.emitDone(emitter, t0, firstTokenNano, refs);
    }

    @Override
    public void error(Throwable t) {
        events.trySend(emitter, "error",
                Map.of("code", "PIPELINE_ERROR", "message", "排障链路异常，请重试或联系值班 SRE"));
        emitter.complete();
    }
}
