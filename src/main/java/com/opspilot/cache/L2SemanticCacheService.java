package com.opspilot.cache;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.retrieval.QdrantSearchService;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * L2 语义缓存：复用检索时已算好的 Query 向量查专属 Collection，
 * 余弦 > 0.95 判定命中直接回放答案。payload 携带 max_auth_level，
 * 检索时硬过滤——语义缓存本身不能成为权限泄漏通道。
 */
@Service
public class L2SemanticCacheService {

    public record CacheHit(String answer, double score) {}

    private final QdrantClient qdrant;
    private final EmbeddingClient embedding;
    private final OpsPilotProperties props;

    public L2SemanticCacheService(QdrantClient qdrant, EmbeddingClient embedding, OpsPilotProperties props) {
        this.qdrant = qdrant;
        this.embedding = embedding;
        this.props = props;
    }

    public CacheHit lookup(String query, int authLevel) {
        try {
            float[] vector = embedding.embedOne(query);
            Points.SearchPoints req = Points.SearchPoints.newBuilder()
                    .setCollectionName(props.qdrant().cacheCollection())
                    .addAllVector(QdrantSearchService.toFloatList(vector))
                    .setLimit(1)
                    .setScoreThreshold((float) props.cache().l2Threshold())
                    .setFilter(Points.Filter.newBuilder()
                            .addMust(Points.Condition.newBuilder()
                                    .setField(Points.FieldCondition.newBuilder()
                                            .setKey("max_auth_level")
                                            .setRange(Points.Range.newBuilder().setLte(authLevel).build())
                                            .build())
                                    .build())
                            .build())
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();
            List<Points.ScoredPoint> hits = qdrant.searchAsync(req).get(3, TimeUnit.SECONDS);
            if (hits.isEmpty()) return null;
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> pl = hits.get(0).getPayloadMap();
            return new CacheHit(pl.get("answer").getStringValue(), hits.get(0).getScore());
        } catch (Exception e) {
            return null; // 缓存故障不致命
        }
    }

    /** 清空语义缓存全部向量点（验收隔离 / 演示重置）。 */
    public void flush() {
        try {
            qdrant.deleteAsync(props.qdrant().cacheCollection(),
                    Points.Filter.getDefaultInstance()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 清理失败不致命
        }
    }

    /** LLM 完整回答结束后才写入（避免半成品入缓存）。 */
    public void store(String query, float[] queryVector, String answer, int maxAuthLevel) {
        try {
            Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(id(UUID.randomUUID()))
                    .setVectors(vectors(queryVector))
                    .putPayload("query_text", value(query))
                    .putPayload("answer", value(answer))
                    .putPayload("max_auth_level", value(maxAuthLevel))
                    .putPayload("created_at", value(System.currentTimeMillis()))
                    .build();
            qdrant.upsertAsync(props.qdrant().cacheCollection(), List.of(point)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 写缓存失败不影响主链路
        }
    }
}
