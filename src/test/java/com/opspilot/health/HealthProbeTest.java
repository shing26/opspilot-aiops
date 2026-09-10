package com.opspilot.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import com.opspilot.config.OpsPilotProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P4 健康面：三依赖全 UP 聚合 UP；单依赖故障必须变 DEGRADED 且异常被吞（健康检查不拖死服务）。 */
class HealthProbeTest {

    private ElasticsearchClient es;
    private QdrantClient qdrant;
    private RedissonClient redisson;
    private HealthProbe probe;

    @BeforeEach
    void setUp() {
        es = mock(ElasticsearchClient.class);
        qdrant = mock(QdrantClient.class);
        redisson = mock(RedissonClient.class);
        var bucket = mock(org.redisson.api.RBucket.class);
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(false); // 键不存在没关系——没抛异常=连通
        OpsPilotProperties props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://mock", "", "m", 1024, "m", "m", 30, "mock"),
                new OpsPilotProperties.Es("http://localhost:9200", "opspilot-chunks-read", "elastic", ""),
                new OpsPilotProperties.Qdrant("localhost", 6334, "opspilot-vectors-live", "semantic-cache", ""),
                new OpsPilotProperties.Jwt("secret", 3600), null, null, null, null);
        probe = new HealthProbe(es, qdrant, redisson, props);
    }

    private void stubHealthy() throws Exception {
        when(qdrant.getCollectionInfoAsync(anyString()))
                .thenReturn(Futures.immediateFuture(
                        Collections.CollectionInfo.newBuilder().setPointsCount(251).build()));
        when(es.ping()).thenReturn(new BooleanResponse(true));
        CountResponse cnt = mock(CountResponse.class);
        when(cnt.count()).thenReturn(251L);
        when(es.count(any(co.elastic.clients.elasticsearch.core.CountRequest.class))).thenReturn(cnt);
    }

    @Test
    void allDependenciesUpAggregatesUp() throws Exception {
        stubHealthy();
        Map<String, Object> s = probe.summary();
        assertEquals("UP", s.get("status"));
        assertEquals("251 docs", ((HealthProbe.Comp) s.get("es")).detail());
        assertEquals(Boolean.FALSE, s.get("live_mode")); // mock 形态（apiKey 空）
    }

    @Test
    void singleDependencyFailureDegradesWithoutThrowing() throws Exception {
        stubHealthy();
        when(qdrant.getCollectionInfoAsync(anyString()))
                .thenThrow(new RuntimeException("collection not found (alias missing)"));
        Map<String, Object> s = probe.summary();
        assertEquals("DEGRADED", s.get("status"));
        assertEquals("DOWN", ((HealthProbe.Comp) s.get("qdrant")).status());
        assertEquals("UP", ((HealthProbe.Comp) s.get("es")).status(), "其余组件不受牵连");
    }
}
