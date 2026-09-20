package com.opspilot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * opspilot.* 配置聚合，全部不可变 record。
 *
 * <p>P2（2026-09-19 外部评审核实采纳）：非法值必须在<b>启动期拒绝</b>，不得静默生效。
 * 此前全 record 零校验，实测锐边：{@code inflight-threshold=-1} 会让降级状态机的
 * {@code inflight >= threshold} 恒真 = <b>永久 L1（向量腿永远被摘）且无任何告警</b>；
 * {@code llm-timeout-seconds=0} 等于每次调用立即超时→熔断常开。约束只挡"会让系统静默走错"
 * 的值；边界值（如 min-relevance=0 表示关闭拒答门控）是合法语义，不挡。
 * 验收：{@code OpsPilotPropertiesValidationTest}（ApplicationContextRunner 断言启动失败
 * 且报错含字段名；正例用全量合法值）＋ 活体启动复验（打包 jar 带
 * {@code --opspilot.degrade.inflight-threshold=-1} → exit=1 且报错点名该字段；
 * 真实 yml 合法值启动零校验告警、推进到 Tomcat/H2 才因本机未起 Redis 中止）。
 */
@Validated
@ConfigurationProperties(prefix = "opspilot")
public record OpsPilotProperties(
        @Valid DashScope dashscope,
        @Valid Es es,
        @Valid Qdrant qdrant,
        @Valid Jwt jwt,
        @Valid Cache cache,
        @Valid Storm storm,
        @Valid Retrieval retrieval,
        @Valid Degrade degrade
) {
    public record DashScope(String baseUrl, String apiKey, String embeddingModel,
                            @Min(1) int embeddingDim, String rerankModel, String llmModel,
                            @Min(1) int llmTimeoutSeconds,
                            @Pattern(regexp = "auto|live|mock", flags = Pattern.Flag.CASE_INSENSITIVE,
                                    message = "dashscope.mode 只接受 auto|live|mock（不区分大小写）；" +
                                            "写错会静默退回按 key 判定，故启动期拒绝")
                            String mode) {
        /** auto: 有 Key 走 live，否则 mock（机制验证用，词法向量非神经语义）。 */
        public boolean live() {
            if ("live".equalsIgnoreCase(mode)) return true;
            if ("mock".equalsIgnoreCase(mode)) return false;
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Es(String uri, String index, String username, String password) {}

    public record Qdrant(String host, @Min(1) @Max(65535) int grpcPort, String collection,
                         String cacheCollection, String apiKey) {}

    public record Jwt(String secret, @Min(1) long ttlSeconds) {}

    public record Cache(@Min(1) int l1TtlHours, @Min(0) @Max(1) double l2Threshold) {}

    public record Storm(@Min(1) int windowSeconds, @Min(1) int alertWindowSeconds) {}

    public record Retrieval(@Min(1) int esTopK, @Min(1) int qdrantTopK, @Min(1) int rrfK,
                            @Min(1) int rerankTopK, @Min(1) int finalTopK,
                            @Min(1) int legTimeoutMs, @Min(0) @Max(1) double minRelevance) {}

    public record Degrade(@Min(1) int inflightThreshold, @Min(1) int llmFailureThreshold,
                          @Min(1) int llmOpenSeconds) {}
}
