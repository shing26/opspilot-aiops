package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.resilience.DegradationStateMachine.Level;
import java.util.List;

/**
 * 聊天编排的事件出口（Sink 抽象）：编排逻辑（缓存/风暴收敛/降级/检索/生成）与
 * 线格式解耦——SSE 自定义事件帧（SseChatSink → SseEvents）与 OpenAI chunk 帧
 * （OpenAiChatSink）是同一编排的两种投递方式。新增协议面 = 新增一个 sink，
 * 编排链路（含 L1/L2/Single-Flight/熔断/审计）零复制零漂移。
 */
public interface ChatSink {

    /** 编排元信息（指纹/缓存命中/降级态/快路径/检索耗时）。OpenAI 帧无对应位可不落。 */
    void meta(String fp, String cacheHit, Level level, boolean fastPath, boolean deduplicated, long retrievalMs);

    /** 增量文本帧。 */
    void delta(String token);

    /** 完整答案分片下发（缓存回放/SOP 直出场景，保留打字机体验）。 */
    void streamInChunks(String answer);

    /** 正常收尾：t0/firstTokenNano 供 TTFT 口径（first_token，A3-6 锁死）；refs 溯源。 */
    void done(long t0, long firstTokenNano, List<AnswerPayload.Ref> refs);

    /** 异常收尾：sink 自行决定错误呈现并完成流（SSE 走 error 事件；OpenAI 走提示文本+stop+[DONE]）。 */
    void error(Throwable t);
}
