package com.opspilot.gateway.dto;

import jakarta.validation.constraints.NotBlank;

/** 排障请求（单链路，source 区分告警/人工，ADR-0004）。 */
public record ChatRequest(
        @NotBlank String query,
        String source,      // alert | manual
        String service,
        String env
) {
    public String sourceOrDefault() {
        return source == null || source.isBlank() ? "manual" : source;
    }
}
