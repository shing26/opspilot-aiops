package com.opspilot.retrieval;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;

/**
 * Qdrant 向量检索：Query 实时向量化 + auth_level Payload 硬过滤（权限隔离第二道闸）。
 */
@Component
public class QdrantSearchService {

    private final QdrantClient qdrant;
    private final EmbeddingClient embedding;
    private final OpsPilotProperties props;

    public QdrantSearchService(QdrantClient qdrant, EmbeddingClient embedding, OpsPilotProperties props) {
        this.qdrant = qdrant;
        this.embedding = embedding;
        this.props = props;
    }

    public List<ScoredChunk> search(String query, int authLevel, int topK) throws Exception {
        float[] vector = embedding.embedOne(query);
        Points.SearchPoints.Builder req = Points.SearchPoints.newBuilder()
                .setCollectionName(props.qdrant().collection())
                .addAllVector(toFloatList(vector))
                .setLimit(topK)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .setFilter(authFilter(authLevel));

        List<Points.ScoredPoint> hits = qdrant.searchAsync(req.build()).get(5, TimeUnit.SECONDS);
        List<ScoredChunk> out = new ArrayList<>();
        for (Points.ScoredPoint p : hits) {
            out.add(fromPoint(p));
        }
        return out;
    }

    /**
     * auth_level <= user 的 Payload 过滤。恒构建、失败关闭：
     * authLevel<=0 时边界钳到 lte(0)（库内文档 auth_level>=1，命中空集），
     * 与 ES 侧 lte(0) 行为一致，杜绝 level-0 token 绕过向量路过滤的提权。
     */
    static Points.Filter authFilter(int authLevel) {
        return Points.Filter.newBuilder()
                .addMust(Points.Condition.newBuilder()
                        .setField(Points.FieldCondition.newBuilder()
                                .setKey("metadata.auth_level")
                                .setRange(Points.Range.newBuilder().setLte(Math.max(authLevel, 0)).build())
                                .build())
                        .build())
                .build();
    }

    public static List<Float> toFloatList(float[] v) {
        List<Float> l = new ArrayList<>(v.length);
        for (float f : v) l.add(f);
        return l;
    }

    @SuppressWarnings("unchecked")
    static ScoredChunk fromPoint(Points.ScoredPoint p) {
        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> pl = p.getPayloadMap();
        io.qdrant.client.grpc.JsonWithInt.Value meta = pl.get("metadata");
        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> mf =
                meta.hasStructValue() ? meta.getStructValue().getFieldsMap() : Map.of();
        List<String> codes = new ArrayList<>();
        io.qdrant.client.grpc.JsonWithInt.Value ec = mf.get("error_codes");
        if (ec != null && ec.hasListValue()) {
            for (io.qdrant.client.grpc.JsonWithInt.Value v : ec.getListValue().getValuesList()) {
                codes.add(v.getStringValue());
            }
        }
        return new ScoredChunk(
                str(pl.get("chunk_id")), str(pl.get("doc_id")), str(pl.get("type")),
                str(pl.get("text")), str(pl.get("breadcrumb")),
                str(mf.get("service")), codes,
                mf.get("auth_level") == null ? 1 : (int) mf.get("auth_level").getIntegerValue(),
                0, p.getScore(), 0, 0);
    }

    private static String str(io.qdrant.client.grpc.JsonWithInt.Value v) {
        return v == null ? "" : v.getStringValue();
    }
}
