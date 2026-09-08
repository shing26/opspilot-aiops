package com.opspilot.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.RerankClient;
import com.opspilot.metrics.OpsMetrics;

/**
 * 双路并行召回编排：CompletableFuture（虚拟线程）+ 单路超时隔离 + RRF + Rerank。
 * mode: hybrid | es_only | vector_only（评测对比开关，A3-3 验收依据）。
 */
@Service
public class HybridSearchService {

    private final EsSearchService es;
    private final QdrantSearchService qdrant;
    private final RerankClient rerank;
    private final OpsMetrics metrics;
    private final ExecutorService vt;
    private final OpsPilotProperties props;

    public HybridSearchService(EsSearchService es, QdrantSearchService qdrant, RerankClient rerank,
                               OpsMetrics metrics, ExecutorService virtualThreadExecutor,
                               OpsPilotProperties props) {
        this.es = es;
        this.qdrant = qdrant;
        this.rerank = rerank;
        this.metrics = metrics;
        this.vt = virtualThreadExecutor;
        this.props = props;
    }

    public SearchOutcome search(String query, int authLevel, String mode) {
        long t0 = System.nanoTime();
        var cfg = props.retrieval();
        boolean useEs = !mode.equals("vector_only");
        boolean useVector = mode.equals("hybrid") || mode.equals("vector_only");

        CompletableFuture<List<ScoredChunk>> esF = useEs
                ? supply(() -> es.search(query, authLevel, cfg.esTopK()))
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<ScoredChunk>> vecF = useVector
                ? supply(() -> qdrant.search(query, authLevel, cfg.qdrantTopK()))
                : CompletableFuture.completedFuture(List.of());

        List<ScoredChunk> esRes = joinSafe(esF, cfg.legTimeoutMs());
        List<ScoredChunk> vecRes = joinSafe(vecF, cfg.legTimeoutMs());
        boolean degraded = (useEs && esRes == null) || (useVector && vecRes == null);
        if (esRes == null) esRes = List.of();
        if (vecRes == null) vecRes = List.of();
        if (degraded) metrics.retrievalTimeout();

        // 快路径：查询含精确错误码且 ES Top-1 命中该码 → 跳过 Rerank
        List<String> codes = EsSearchService.extractErrorCodes(query);
        boolean fastPath = !codes.isEmpty() && !esRes.isEmpty()
                && esRes.get(0).errorCodes().contains(codes.get(0));

        List<ScoredChunk> fused = RrfFuser.apply(esRes, vecRes, cfg.rrfK());
        List<ScoredChunk> top;
        double topRelevance;
        if (fastPath) {
            top = fused.subList(0, Math.min(cfg.finalTopK(), fused.size()));
            topRelevance = 1.0;   // 精确符号命中天然高置信
        } else {
            RerankResult rr = rerankStage(query, fused, cfg);
            top = rr.chunks();
            // rerank 不可用时不门控（避免误杀），置 1.0；正常则用 Top-1 相关度
            topRelevance = rr.applied() && !top.isEmpty() ? top.get(0).rerankScore() : 1.0;
        }
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        String effectiveMode = degraded ? (esRes.isEmpty() ? "vector_only" : "es_only") : mode;
        if (degraded && !esRes.isEmpty()) metrics.esOnly();
        return new SearchOutcome(top, effectiveMode, fastPath, degraded, topRelevance, tookMs);
    }

    private record RerankResult(List<ScoredChunk> chunks, boolean applied) {}

    private RerankResult rerankStage(String query, List<ScoredChunk> fused,
                                     OpsPilotProperties.Retrieval cfg) {
        List<ScoredChunk> candidates = fused.subList(0, Math.min(cfg.rerankTopK(), fused.size()));
        if (candidates.isEmpty()) return new RerankResult(candidates, false);
        try {
            List<String> docs = candidates.stream().map(ScoredChunk::text).toList();
            List<RerankClient.Ranked> ranked = rerank.rerank(query, docs, cfg.finalTopK());
            List<ScoredChunk> out = new ArrayList<>();
            for (RerankClient.Ranked r : ranked) {
                out.add(candidates.get(r.index()).withRerank(r.score()));
            }
            return new RerankResult(out, true);
        } catch (Exception e) {
            // Rerank 失败不致命：回退 RRF 顺序，标记未应用（不触发置信度门控）
            return new RerankResult(candidates.subList(0, Math.min(cfg.finalTopK(), candidates.size())), false);
        }
    }

    private CompletableFuture<List<ScoredChunk>> supply(LegCall call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, vt);
    }

    private List<ScoredChunk> joinSafe(CompletableFuture<List<ScoredChunk>> f, int timeoutMs) {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface LegCall {
        List<ScoredChunk> get() throws Exception;
    }

    /** 供 Prompt 组装：把 Top-K chunk 拼成带引用的上下文块。 */
    public static String renderContext(List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append("[参考").append(i + 1).append("] ")
              .append(c.breadcrumb()).append('\n')
              .append(c.text()).append("\n\n");
        }
        return sb.toString();
    }
}
