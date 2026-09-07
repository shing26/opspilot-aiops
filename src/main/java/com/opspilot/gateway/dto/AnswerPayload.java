package com.opspilot.gateway.dto;

import java.util.List;

/** 缓存/Single-Flight 共享的答案载荷。 */
public record AnswerPayload(
        String answer,
        List<Ref> refs,
        String mode,
        boolean fastPath,
        int maxAuthLevel
) {
    public record Ref(String chunkId, String breadcrumb, String service) {}
}
