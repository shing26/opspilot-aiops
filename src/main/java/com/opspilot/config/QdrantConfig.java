package com.opspilot.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(OpsPilotProperties props) {
        QdrantGrpcClient grpc = QdrantGrpcClient.newBuilder(props.qdrant().host(), props.qdrant().grpcPort(), false)
                .withTimeout(java.time.Duration.ofSeconds(5))
                .build();
        return new QdrantClient(grpc);
    }
}
