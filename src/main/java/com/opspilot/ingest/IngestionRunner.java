package com.opspilot.ingest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.opspilot.chunk.Chunk;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 离线 JSONL → 在线双写（ADR-0001 接缝）。
 * 触发：--opspilot.ingest=true。幂等：先删后建索引/集合。
 * 顺带预热 Level 2 静态 SOP 清单进 Redis。
 */
@Component
public class IngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final ElasticsearchClient es;
    private final QdrantClient qdrant;
    private final RedissonClient redisson;
    private final EmbeddingClient embedding;
    private final OpsPilotProperties props;
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    private final String chunksPath;

    public IngestionRunner(ElasticsearchClient es, QdrantClient qdrant, RedissonClient redisson,
                           EmbeddingClient embedding, OpsPilotProperties props,
                           @Value("${opspilot.chunks-path:offline/corpus/chunks.jsonl}") String chunksPath) {
        this.es = es;
        this.qdrant = qdrant;
        this.redisson = redisson;
        this.embedding = embedding;
        this.props = props;
        this.chunksPath = chunksPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("opspilot.ingest")) return;

        long t0 = System.currentTimeMillis();
        List<Chunk> chunks = readChunks();
        log.info("读取 chunks.jsonl: {} 条", chunks.size());

        createEsIndex();
        createQdrantCollections();
        dualWrite(chunks);
        warmSop(chunks);

        log.info("入库完成: {} chunks, 耗时 {}ms", chunks.size(), System.currentTimeMillis() - t0);
    }

    private List<Chunk> readChunks() throws Exception {
        List<Chunk> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(Path.of(chunksPath))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Chunk c = mapper.readValue(line, Chunk.class);
                // P1 租户显式化 fail-closed：缺 tenant 的 chunk 拒绝入库——一旦入库就是
                // "过滤 term 永不命中该文档"的静默丢失，比启动期报错难发现得多
                if (c.metadata() == null || c.metadata().tenant() == null
                        || c.metadata().tenant().isBlank()) {
                    throw new IllegalStateException(
                            "chunk 缺少 metadata.tenant，拒绝入库: " + c.chunkId());
                }
                out.add(c);
            }
        }
        return out;
    }

    private void createEsIndex() throws Exception {
        String index = props.es().index();
        if (Boolean.TRUE.equals(es.indices().exists(e -> e.index(index)).value())) {
            es.indices().delete(d -> d.index(index));
        }
        es.indices().create(c -> c
                .index(index)
                .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(mp -> mp
                        .properties("chunk_id", p -> p.keyword(k -> k))
                        .properties("doc_id", p -> p.keyword(k -> k))
                        .properties("type", p -> p.keyword(k -> k))
                        .properties("text", p -> p.text(t -> t))
                        .properties("breadcrumb", p -> p.keyword(k -> k))
                        .properties("metadata", p -> p.object(o -> o
                                .properties("service", pp -> pp.keyword(k -> k))
                                .properties("endpoint", pp -> pp.keyword(k -> k))
                                .properties("method", pp -> pp.keyword(k -> k))
                                .properties("error_codes", pp -> pp.keyword(k -> k))
                                .properties("auth_level", pp -> pp.integer(i -> i))
                                .properties("env", pp -> pp.keyword(k -> k))
                                .properties("tenant", pp -> pp.keyword(k -> k))))));
        log.info("ES 索引已建: {}", index);
    }

    private void createQdrantCollections() throws Exception {
        recreateCollection(props.qdrant().collection());
        recreateCollection(props.qdrant().cacheCollection());
        // Payload 索引：doc_id/service/tenant keyword、auth_level integer、
        // 缓存集合 tenant keyword + max_auth_level integer（L2 双硬过滤维度）
        qdrant.createPayloadIndexAsync(props.qdrant().collection(), "doc_id",
                Collections.PayloadSchemaType.Keyword, null, null, null,
                java.time.Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        qdrant.createPayloadIndexAsync(props.qdrant().collection(), "metadata.service",
                Collections.PayloadSchemaType.Keyword, null, null, null,
                java.time.Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        qdrant.createPayloadIndexAsync(props.qdrant().collection(), "metadata.auth_level",
                Collections.PayloadSchemaType.Integer, null, null, null,
                java.time.Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        qdrant.createPayloadIndexAsync(props.qdrant().collection(), "metadata.tenant",
                Collections.PayloadSchemaType.Keyword, null, null, null,
                java.time.Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        qdrant.createPayloadIndexAsync(props.qdrant().cacheCollection(), "tenant",
                Collections.PayloadSchemaType.Keyword, null, null, null,
                java.time.Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        qdrant.createPayloadIndexAsync(props.qdrant().cacheCollection(), "max_auth_level",
                Collections.PayloadSchemaType.Integer, null, null, null,
                java.time.Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        log.info("Qdrant 集合与 Payload 索引已建");
    }

    private void recreateCollection(String name) throws Exception {
        if (Boolean.TRUE.equals(qdrant.collectionExistsAsync(name).get(10, TimeUnit.SECONDS))) {
            qdrant.deleteCollectionAsync(name).get(10, TimeUnit.SECONDS);
        }
        qdrant.createCollectionAsync(name, Collections.VectorParams.newBuilder()
                .setSize(props.dashscope().embeddingDim())
                .setDistance(Collections.Distance.Cosine)
                .build(), java.time.Duration.ofSeconds(20)).get(20, TimeUnit.SECONDS);
    }

    private void dualWrite(List<Chunk> chunks) throws Exception {
        List<String> texts = chunks.stream().map(Chunk::text).toList();
        List<float[]> vectors = embedding.embed(texts);

        // ES bulk
        String index = props.es().index();
        es.bulk(b -> {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                Map<String, Object> src = toEsSource(c);
                b.operations(op -> op.index(idx -> idx.index(index).id(c.chunkId()).document(src)));
            }
            return b;
        });

        // Qdrant upsert
        List<Points.PointStruct> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            io.qdrant.client.grpc.JsonWithInt.Value metadataValue =
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                            .setStructValue(io.qdrant.client.grpc.JsonWithInt.Struct.newBuilder()
                                    .putFields("service", value(str(c.metadata().service())))
                                    .putFields("endpoint", value(str(c.metadata().endpoint())))
                                    .putFields("method", value(str(c.metadata().method())))
                                    .putFields("error_codes", value(c.metadata().errorCodes().stream()
                                            .map(io.qdrant.client.ValueFactory::value).toList()))
                                    .putFields("auth_level", value(c.metadata().authLevel()))
                                    .putFields("env", value(str(c.metadata().env())))
                                    .putFields("tenant", value(str(c.metadata().tenant())))
                                    .build())
                            .build();
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new LinkedHashMap<>();
            payload.put("chunk_id", value(c.chunkId()));
            payload.put("doc_id", value(c.docId()));
            payload.put("type", value(c.type()));
            payload.put("text", value(c.text()));
            payload.put("breadcrumb", value(c.breadcrumb()));
            payload.put("metadata", metadataValue);
            points.add(Points.PointStruct.newBuilder()
                    .setId(id(UUID.nameUUIDFromBytes(c.chunkId().getBytes())))
                    .setVectors(vectors(vectors.get(i)))
                    .putAllPayload(payload)
                    .build());
        }
        qdrant.upsertAsync(props.qdrant().collection(), points).get(60, TimeUnit.SECONDS);
        log.info("双写完成: ES {} + Qdrant {}", chunks.size(), chunks.size());
    }

    private Map<String, Object> toEsSource(Chunk c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunk_id", c.chunkId());
        m.put("doc_id", c.docId());
        m.put("type", c.type());
        m.put("text", c.text());
        m.put("breadcrumb", c.breadcrumb());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("service", c.metadata().service());
        meta.put("endpoint", c.metadata().endpoint());
        meta.put("method", c.metadata().method());
        meta.put("error_codes", c.metadata().errorCodes());
        meta.put("auth_level", c.metadata().authLevel());
        meta.put("env", c.metadata().env());
        meta.put("tenant", c.metadata().tenant());
        m.put("metadata", meta);
        return m;
    }

    private static String str(String s) { return s == null ? "" : s; }

    /** Level 2 兜底：止损步骤按 doc 与 error_code 双维度预热进 Redis（P1：键按租户分片，杜绝跨租户 SOP 泄漏）。 */
    private void warmSop(List<Chunk> chunks) {
        // 先清后写（按租户分片）：避免重建后残留已删除文档的旧 SOP
        java.util.Set<String> tenants = chunks.stream()
                .map(c -> c.metadata().tenant()).collect(java.util.stream.Collectors.toSet());
        for (String t : tenants) {
            redisson.getKeys().getKeysStreamByPattern("sop:" + t + ":*").forEach(k -> redisson.getKeys().delete(k));
        }
        for (Chunk c : chunks) {
            if (c.breadcrumb() != null && c.breadcrumb().contains("止损操作")) {
                String tenant = c.metadata().tenant();
                var steps = redisson.getMap("sop:" + tenant + ":steps");
                steps.put(c.docId(), c.text());
                for (String code : c.metadata().errorCodes()) {
                    redisson.getSet("sop:" + tenant + ":code:" + code).add(c.docId());
                }
                redisson.getSet("sop:" + tenant + ":service:" + c.metadata().service()).add(c.docId());
            }
        }
        log.info("SOP 预热完成（按租户分片）");
    }
}
