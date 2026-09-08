package com.opspilot.retrieval;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.RerankClient;
import com.opspilot.metrics.OpsMetrics;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Spec 锁（P1-1）：L1 降级 = es_only 必须同时摘除向量路与 Rerank，
 * 结果保持 RRF（此处即 ES）顺序且 rerank_score 恒 0；hybrid 路则必须精排。
 */
class HybridSearchServiceTest {

    private EsSearchService es;
    private QdrantSearchService qdrant;
    private RerankClient rerank;
    private OpsMetrics metrics;
    private ExecutorService vt;
    private OpsPilotProperties props;

    @BeforeEach
    void setUp() {
        es = mock(EsSearchService.class);
        qdrant = mock(QdrantSearchService.class);
        rerank = mock(RerankClient.class);
        metrics = mock(OpsMetrics.class);
        vt = Executors.newVirtualThreadPerTaskExecutor();
        props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://mock", "", "m", 1024, "m", "m", 30, "mock"),
                new OpsPilotProperties.Es("http://localhost:9200", "idx"),
                new OpsPilotProperties.Qdrant("localhost", 6334, "c", "cache"),
                new OpsPilotProperties.Jwt("secret"),
                new OpsPilotProperties.Cache(2, 0.95),
                new OpsPilotProperties.Storm(30, 60),
                new OpsPilotProperties.Retrieval(10, 10, 60, 20, 3, 800, 0.05),
                new OpsPilotProperties.Degrade(5, 3, 30));
    }

    @AfterEach
    void tearDown() {
        vt.shutdownNow();
    }

    private static ScoredChunk c(String id) {
        return new ScoredChunk(id, id, "runbook", "text-" + id, "bc", "svc", List.of(), 1, 1.0, 0, 0, 0);
    }

    private static List<String> ids(SearchOutcome out) {
        return out.chunks().stream().map(ScoredChunk::chunkId).toList();
    }

    private HybridSearchService service() {
        return new HybridSearchService(es, qdrant, rerank, metrics, vt, props);
    }

    @Test
    void esOnlyModeSkipsRerankAndKeepsEsOrder() throws Exception {
        when(es.search(anyString(), anyInt(), anyInt())).thenReturn(List.of(c("A"), c("B"), c("C")));
        // 无错误码符号的查询：不触发快路径，rerank 与否完全由降级分支决定
        SearchOutcome out = service().search("数据库连接池耗尽的排查步骤", 1, "es_only");

        verifyNoInteractions(rerank);
        assertEquals(List.of("A", "B", "C"), ids(out));
        assertTrue(out.chunks().stream().allMatch(x -> x.rerankScore() == 0.0),
                "es_only 不得携带任何 rerank 分数");
        assertEquals("es_only", out.mode());
        assertEquals(1.0, out.topRelevance(), 1e-9); // 降级纯 ES：best-effort，不做门控
    }

    @Test
    void hybridModeAppliesRerankOrderAndRelevance() throws Exception {
        when(es.search(anyString(), anyInt(), anyInt())).thenReturn(List.of(c("A"), c("B"), c("C")));
        when(qdrant.search(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(2, 0.9), new RerankClient.Ranked(0, 0.4),
                new RerankClient.Ranked(1, 0.1)));

        SearchOutcome out = service().search("数据库连接池耗尽的排查步骤", 1, "hybrid");

        verify(rerank, times(1)).rerank(anyString(), anyList(), eq(3)); // finalTopK
        assertEquals(List.of("C", "A", "B"), ids(out));                 // 精排序覆盖 RRF 序
        assertEquals(0.9, out.chunks().get(0).rerankScore(), 1e-9);
        assertEquals(0.9, out.topRelevance(), 1e-9);                    // 门控取 Top-1 相关度
    }
}
