package com.opspilot.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(OpsPilotProperties props) {
        QdrantGrpcClient.Builder b = QdrantGrpcClient
                .newBuilder(props.qdrant().host(), props.qdrant().grpcPort(), false)
                .withTimeout(java.time.Duration.ofSeconds(5));
        // P3：凭据存在才附加（空=CI/本地无认证形态）；api-key 同护 REST 与 gRPC
        String apiKey = props.qdrant().apiKey();
        if (apiKey != null && !apiKey.isBlank()) b.withApiKey(apiKey);
        return new QdrantClient(b.build());
    }
}
