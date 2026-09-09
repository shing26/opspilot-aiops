package com.opspilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** opspilot.* 配置聚合，全部不可变 record。 */
@ConfigurationProperties(prefix = "opspilot")
public record OpsPilotProperties(
        DashScope dashscope,
        Es es,
        Qdrant qdrant,
        Jwt jwt,
        Cache cache,
        Storm storm,
        Retrieval retrieval,
        Degrade degrade
) {
    public record DashScope(String baseUrl, String apiKey, String embeddingModel,
                            int embeddingDim, String rerankModel, String llmModel,
                            int llmTimeoutSeconds, String mode) {
        /** auto: 有 Key 走 live，否则 mock（机制验证用，词法向量非神经语义）。 */
        public boolean live() {
            if ("live".equalsIgnoreCase(mode)) return true;
            if ("mock".equalsIgnoreCase(mode)) return false;
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Es(String uri, String index) {}

    public record Qdrant(String host, int grpcPort, String collection, String cacheCollection) {}

    public record Jwt(String secret, long ttlSeconds) {}

    public record Cache(int l1TtlHours, double l2Threshold) {}

    public record Storm(int windowSeconds, int alertWindowSeconds) {}

    public record Retrieval(int esTopK, int qdrantTopK, int rrfK, int rerankTopK,
                            int finalTopK, int legTimeoutMs, double minRelevance) {}

    public record Degrade(int inflightThreshold, int llmFailureThreshold, int llmOpenSeconds) {}
}
