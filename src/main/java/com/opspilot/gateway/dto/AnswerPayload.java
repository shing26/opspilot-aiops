package com.opspilot.gateway.dto;

import java.util.List;

/**
 * 缓存/Single-Flight 共享的答案载荷。
 * 命名口径为刻意决策：本类型是 Redis/Qdrant 内的**存储格式**（Jackson 默认 camelCase），
 * 从不直接上线；对外 SSE 帧的 snake_case 契约由 SseEvents 手工构 Map。
 * 不做统一——统一需迁移已存缓存或引入 @JsonProperty 双面映射，收益为零且引入兼容风险。
 *
 * <p><b>refused 是存储合同的一部分（2026-09-28，live QA F-1）</b>：标记"这份载荷是拒答/澄清
 * 而非排障答案"。为什么必须显式：`replay()`（缓存与 Single-Flight 回放路径）的审计行此前
 * **硬编码 `refused=false`**——一条被拒答并写入 L1 的 query，2h TTL 内重发时走回放，
 * 客户端收到的仍是拒答话术，审计却记成"未拒答、触达密级=N"（live 实测三次复现，
 * `daily_usage` 的 refuse_rate 因此被系统性低估）。原始 boolean：旧缓存 JSON 缺该字段时
 * 反序列化为 false（L1 TTL 2h 内自然消失；L2 从不存拒答，无残窗）。
 */
public record AnswerPayload(
        String answer,
        List<Ref> refs,
        String mode,
        boolean fastPath,
        int maxAuthLevel,
        String tenant,
        boolean refused
) {
    public record Ref(String chunkId, String breadcrumb, String service) {}
}
