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

    public void llmCall() { llmCalls.incrementAndGet(); }
    public void llmRateLimited() { llmRateLimited.incrementAndGet(); }
    public void dedupAggregated() { dedupAggregated.incrementAndGet(); }
    public void l1Hit() { l1CacheHits.incrementAndGet(); }
    public void l2Hit() { l2CacheHits.incrementAndGet(); }
    public void esOnly() { esOnlyRequests.incrementAndGet(); }
    public void sopFallback() { sopFallbacks.incrementAndGet(); }
    public void retrievalTimeout() { retrievalTimeouts.incrementAndGet(); }
    public void lowConfidence() { lowConfidenceRefusals.incrementAndGet(); }
    public void request() { totalRequests.incrementAndGet(); }

    public long llmCallsValue() { return llmCalls.get(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_requests", totalRequests.get());
        m.put("llm_calls", llmCalls.get());
        m.put("llm_rate_limited", llmRateLimited.get());
        m.put("dedup_aggregated", dedupAggregated.get());
        m.put("l1_cache_hits", l1CacheHits.get());
        m.put("l2_cache_hits", l2CacheHits.get());
        m.put("es_only_requests", esOnlyRequests.get());
        m.put("sop_fallbacks", sopFallbacks.get());
        m.put("retrieval_timeouts", retrievalTimeouts.get());
        m.put("low_confidence_refusals", lowConfidenceRefusals.get());
        return m;
    }
}
