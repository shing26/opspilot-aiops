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
 * 余弦 > 0.95 判定命中直接回放答案。payload 携带 tenant + max_auth_level，
 * 检索时双硬过滤——语义缓存本身不能成为权限或租户的泄漏通道（P1：缓存串租户
 * 与权限泄漏同级，回放前必须等值命中请求方 tenant）。
 */
@Service
public class L2SemanticCacheService {

    public record CacheHit(String payloadJson, double score) {}

    private final QdrantClient qdrant;
    private final EmbeddingClient embedding;
    private final OpsPilotProperties props;
    private final java.util.concurrent.ExecutorService vt;

    public L2SemanticCacheService(QdrantClient qdrant, EmbeddingClient embedding, OpsPilotProperties props,
                                  java.util.concurrent.ExecutorService virtualThreadExecutor) {
        this.qdrant = qdrant;
        this.embedding = embedding;
        this.props = props;
        this.vt = virtualThreadExecutor;
    }

    public CacheHit lookup(String query, String tenant, int authLevel) {
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
                                            .setKey("tenant")
                                            .setMatch(Points.Match.newBuilder()
                                                    .setKeyword(tenant == null ? "" : tenant).build())
                                            .build())
                                    .build())
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
            return new CacheHit(pl.get("payload_json").getStringValue(), hits.get(0).getScore());
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

    /**
     * LLM 完整回答结束后才写入（避免半成品入缓存）。payloadJson 含 refs，回放时溯源完整。
     *
     * <p><b>2026-09-28（OP-R3）：upsert 改为投递即返回</b>，不再同步等 {@code .get(5, SECONDS)}。
     * 那个等待发生在**请求尾段**（LLM 已答完、答案已下发），纯属缓存写入的延迟，却直接计入用户可见的
     * 端到端时延；而写缓存失败/慢本就不该进主链路 SLO（异常原本就被吞）。语义变化只有一条：
     * 写入变**最终一致**——同一 query 紧接着重放时可能仍未命中 L2（L1 已在编排里同步写好，不受影响）。
     * 失败语义不变：任务内吞异常，不阻断也不重试。
     *
     * <p>注意本方法**不负责**去掉"同一请求 embed 两次"的成本：向量仍由调用方传入，检索腿算出的那次
     * 没有被回传复用——那是 `OPS.md` §5 单列的另立触发线（`stage_ms.l2_store` 占比 >10% 或上游调用
     * 量成为瓶颈才立项），本项只去掉等待，不碰调用次数。
     */
    public void store(String query, float[] queryVector, String payloadJson, int maxAuthLevel, String tenant) {
        try {
            Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(id(UUID.randomUUID()))
                    .setVectors(vectors(queryVector))
                    .putPayload("query_text", value(query))
                    .putPayload("payload_json", value(payloadJson))
                    .putPayload("max_auth_level", value(maxAuthLevel))
                    .putPayload("tenant", value(tenant == null ? "" : tenant))
                    .putPayload("created_at", value(System.currentTimeMillis()))
                    .build();
            vt.execute(() -> {
                try {
                    qdrant.upsertAsync(props.qdrant().cacheCollection(), List.of(point)).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // 写缓存失败不影响主链路（与同步版同语义）
                }
            });
        } catch (Exception e) {
            // 构造 point 失败（入参异常）同样不致命
        }
    }
}
