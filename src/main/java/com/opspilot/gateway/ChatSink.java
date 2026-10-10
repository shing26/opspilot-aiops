package com.opspilot.gateway;

import com.opspilot.action.Action;
import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.resilience.DegradationState.Level;
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

    /**
     * 正常收尾：t0/firstTokenNano 供 TTFT 口径（first_token，A3-6 锁死）；refs 溯源；
     * actions 只读行动契约（ADRs 0017，随载荷进缓存，回放路径同样有）。
     */
    void done(long t0, long firstTokenNano, List<AnswerPayload.Ref> refs, List<Action> actions);

    /**
     * 异常收尾：sink 自行决定错误呈现并完成流（SSE 走 error 事件；OpenAI 走提示文本+stop+[DONE]）。
     *
     * {@code refs}/{@code actions} 是**异常前已经到手**的检索与契约成果（ADRs 0017）：
     * 生成侧失败不该连坐把"引用了什么、能做什么"一起吞掉——那是本系统与纯生成式问答的
     * 唯一区别。无成果时传空表，帧形态与历史一致。
     */
    void error(Throwable t, List<AnswerPayload.Ref> refs, List<Action> actions);
}
