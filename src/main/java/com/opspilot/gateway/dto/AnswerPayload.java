package com.opspilot.gateway.dto;

import java.util.List;

/**
 * 缓存/Single-Flight 共享的答案载荷。
 * 命名口径为刻意决策：本类型是 Redis/Qdrant 内的**存储格式**（Jackson 默认 camelCase），
 * 从不直接上线；对外 SSE 帧的 snake_case 契约由 SseEvents 手工构 Map。
 * 不做统一——统一需迁移已存缓存或引入 @JsonProperty 双面映射，收益为零且引入兼容风险。
 */
public record AnswerPayload(
        String answer,
        List<Ref> refs,
        String mode,
        boolean fastPath,
        int maxAuthLevel,
        String tenant
) {
    public record Ref(String chunkId, String breadcrumb, String service) {}
}
