package com.opspilot.auth;

/** 从 JWT 解析出的调用方上下文。authLevel 是数据密级过滤的唯一依据。 */
public record UserContext(String sub, String role, int authLevel, String tenantId) {

    public static final String REQUEST_ATTR = "opspilot.user";
}
