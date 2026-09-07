package com.opspilot.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 排障请求（单链路，source 区分告警/人工，ADR-0004）。 */
public record ChatRequest(
        @NotBlank(message = "query 不能为空") String query,
        @Pattern(regexp = "manual|alert", message = "source 仅允许 manual|alert") String source,
        String service,
        String env
) {
    public String sourceOrDefault() {
        return source == null || source.isBlank() ? "manual" : source;
    }
}
