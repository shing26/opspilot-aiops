package com.opspilot.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 轻量内联指标：风暴收敛与缓存命中的实证口径（A2-5 / A3-5 验收依据）。 */
@Component
public class OpsMetrics {

    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmRateLimited = new AtomicLong();
    private final AtomicLong dedupAggregated = new AtomicLong();
    private final AtomicLong l1CacheHits = new AtomicLong();
    private final AtomicLong l2CacheHits = new AtomicLong();
    private final AtomicLong esOnlyRequests = new AtomicLong();
    private final AtomicLong sopFallbacks = new AtomicLong();
    private final AtomicLong retrievalTimeouts = new AtomicLong();
    private final AtomicLong lowConfidenceRefusals = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();
    // H3（生产就绪度 2026-09-12）：重试可观测性——retry=退避后二次尝试的次数；
    // networkError=终态网络类失败（429 终态仍走 llm_rate_limited，不重复计）
    private final AtomicLong llmRetries = new AtomicLong();
    private final AtomicLong llmNetworkErrors = new AtomicLong();
    // 生成质量包 Q2=C：逐字导出被出口护栏掩码的句数（按句累计，非按请求）
    private final AtomicLong verbatimMasked = new AtomicLong();

    public void llmCall() { llmCalls.incrementAndGet(); }
    public void llmRateLimited() { llmRateLimited.incrementAndGet(); }
    public void llmRetry() { llmRetries.incrementAndGet(); }
    public void llmNetworkError() { llmNetworkErrors.incrementAndGet(); }
    public void dedupAggregated() { dedupAggregated.incrementAndGet(); }
    public void l1Hit() { l1CacheHits.incrementAndGet(); }
    public void l2Hit() { l2CacheHits.incrementAndGet(); }
    public void esOnly() { esOnlyRequests.incrementAndGet(); }
    public void sopFallback() { sopFallbacks.incrementAndGet(); }
    public void retrievalTimeout() { retrievalTimeouts.incrementAndGet(); }
    public void lowConfidence() { lowConfidenceRefusals.incrementAndGet(); }
    public void request() { totalRequests.incrementAndGet(); }
    public void verbatimMasked(int n) { verbatimMasked.addAndGet(n); }

    public long llmCallsValue() { return llmCalls.get(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_requests", totalRequests.get());
        m.put("llm_calls", llmCalls.get());
        m.put("llm_rate_limited", llmRateLimited.get());
        m.put("llm_retries", llmRetries.get());
        m.put("llm_network_errors", llmNetworkErrors.get());
        m.put("dedup_aggregated", dedupAggregated.get());
        m.put("l1_cache_hits", l1CacheHits.get());
        m.put("l2_cache_hits", l2CacheHits.get());
        m.put("es_only_requests", esOnlyRequests.get());
        m.put("sop_fallbacks", sopFallbacks.get());
        m.put("retrieval_timeouts", retrievalTimeouts.get());
        m.put("low_confidence_refusals", lowConfidenceRefusals.get());
        m.put("verbatim_masked", verbatimMasked.get());
        return m;
    }
}
