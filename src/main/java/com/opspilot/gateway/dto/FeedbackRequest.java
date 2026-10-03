package com.opspilot.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 答案反馈（闭环前置）：人把"这个答案不对"告诉系统的入口。
 *
 * 为什么按 `fingerprint` 而不是 `requestId` 归档：`fingerprint = SHA256(service + env + 归一化错误信息)`，
 * 标识的是**问题**而非某一次生成——"这个问题对应的知识是错的"才是可沉淀的单位（复盘→知识回灌
 * 要沉淀的是知识，不是某次生成）。且 fingerprint 已在 SSE `meta` 帧下发，客户端天然能回传；
 * request_id 只存在于 MDC 与审计行，客户端拿不到。
 *
 * 校验口径与 ChatRequest 同范式：注解在 DTO，失败由 GlobalExceptionHandler 转 400 + 留痕
 * （不落 500——错误分层是第五轮 QA 的既有成果）。
 *
 * <p><b>形状校验（2026-09-28，live QA F-5）</b>：fingerprint 必须是 **32 位十六进制**
 * （`FingerprintService` 的 SHA-256 前 128 位）——此前只限长度不限形状，`not-a-fingerprint!!`
 * 这类值被 200 接受并落审计，治理归档被稀释。**存在性仍宽容**：未知 fingerprint 照常接受
 * （"这个问题对应的知识是错的"不要求系统认识它），形状非法才是拒绝。
 */
public record FeedbackRequest(
        @NotBlank(message = "fingerprint 不能为空")
        @Pattern(regexp = "[0-9a-f]{32}", message = "fingerprint 应为 32 位十六进制指纹")
        String fingerprint,

        @NotBlank(message = "verdict 不能为空")
        @Pattern(regexp = "up|down", message = "verdict 仅允许 up|down")
        String verdict,

        @Size(max = 200, message = "note 最长 200 字符")
        String note
) {}
