package com.opspilot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * 轻量内联指标：风暴收敛与缓存命中的实证口径（A2-5 / A3-5 验收依据）。
 *
 * <p><b>单一事实源</b>（ADRs 0016）：计数由 Micrometer {@link Counter} 持有，一份数供两个出口——
 * {@link #snapshot()} 写给 {@code /state} 的 metrics 字段（控制台面板的取值源），以及 {@code aiops.*}
 * 层级名暴露在 {@code /actuator/prometheus} 供抓取。此前是裸 {@code AtomicLong}，只有前一个出口。
 * 两个出口的命名各自演化：snapshot 面向人读（snake_case 键），meter 面向时序库（层级名）。
 *
 * <p>面板契约把 snapshot 的键名锁死了（check_panel_contract.sh 逐字 grep {@code put("<key>"}，
 * 增删键必须同步 index.html 的磁贴）；Prometheus 侧不经过 snapshot，不受该契约约束。
 */
@Component
public class OpsMetrics {

    private final Counter llmCalls;
    private final Counter llmRateLimited;
    private final Counter dedupAggregated;
    private final Counter l1CacheHits;
    private final Counter l2CacheHits;
    private final Counter esOnlyRequests;
    private final Counter sopFallbacks;
    private final Counter retrievalTimeouts;
    private final Counter lowConfidenceRefusals;
    private final Counter totalRequests;
    // H3（生产就绪度 2026-09-12）：重试可观测性——retry=退避后二次尝试的次数；
    // networkError=终态网络类失败（429 终态仍走 llm_rate_limited，不重复计）
    private final Counter llmRetries;
    private final Counter llmNetworkErrors;
    // 生成质量包 Q2=C：逐字导出被出口护栏掩码的句数（按句累计，非按请求）
    private final Counter verbatimMasked;
    // ADRs 0017：只读行动契约里被命令策略判拒的条数（Prometheus 侧，**不进 snapshot**——
    // 面板契约的键集已锁死，见 check_panel_contract.sh 第二层；这个是抓取侧的可观测性)
    private final Counter actionCommandsRejected;

    public OpsMetrics(MeterRegistry registry) {
        totalRequests = counter(registry, "aiops.requests.total", "处理完成的请求总数");
        llmCalls = counter(registry, "aiops.llm.calls", "实际发起的 LLM 调用次数");
        llmRateLimited = counter(registry, "aiops.llm.rate_limited", "LLM 侧限流（429）命中次数");
        llmRetries = counter(registry, "aiops.llm.retries", "退避后二次尝试的次数");
        llmNetworkErrors = counter(registry, "aiops.llm.network_errors", "终态网络类失败次数");
        dedupAggregated = counter(registry, "aiops.dedup.aggregated", "被 SingleFlight 合并掉的重复请求数");
        l1CacheHits = counter(registry, "aiops.cache.l1.hits", "L1 精确缓存命中数（指纹级）");
        l2CacheHits = counter(registry, "aiops.cache.l2.hits", "L2 语义缓存命中数（向量相似）");
        esOnlyRequests = counter(registry, "aiops.es_only.requests", "L1 档位下仅走 ES 倒排的请求数");
        sopFallbacks = counter(registry, "aiops.sop.fallbacks", "L2 档位直出静态 SOP 的兜底次数");
        retrievalTimeouts = counter(registry, "aiops.retrieval.timeouts", "检索阶段超时次数");
        lowConfidenceRefusals = counter(registry, "aiops.guard.refusals", "置信度不足拒答次数");
        verbatimMasked = counter(registry, "aiops.guard.verbatim_masked",
                "VerbatimGuard 拦截次数：逐字导出中被出口护栏掩码的句数（按句累计）");
        actionCommandsRejected = counter(registry, "aiops.guard.action_commands_rejected",
                "只读行动契约里被命令策略判拒的命令条数（ADRs 0017：判拒即丢弃，不静默）");
        // 派生比率：无分母时给 0 而不是 NaN——NaN 写进抓取体会让部分 Prometheus 客户端解析失败，
        // 而"还没来过请求"恰是新实例最常见的状态
        gauge(registry, "aiops.storm.suppression.ratio", this, m -> m.ratio(m.dedupAggregated, m.totalRequests),
                "告警风暴抑制率 = 被合并请求数 / 总请求数");
        gauge(registry, "aiops.cache.hit.rate", this,
                m -> m.ratio(m.l1CacheHits.count() + m.l2CacheHits.count(), m.totalRequests),
                "双层缓存总命中率 = (L1+L2) / 总请求数");
        gauge(registry, "aiops.cache.l1.hit.rate", this, m -> m.ratio(m.l1CacheHits, m.totalRequests),
                "L1 精确缓存命中率（指纹级命中与总请求之比）");
        gauge(registry, "aiops.cache.l2.hit.rate", this, m -> m.ratio(m.l2CacheHits, m.totalRequests),
                "L2 语义缓存命中率（语义相似命中与总请求之比）");
    }

    public void llmCall() { llmCalls.increment(); }
    public void llmRateLimited() { llmRateLimited.increment(); }
    public void llmRetry() { llmRetries.increment(); }
    public void llmNetworkError() { llmNetworkErrors.increment(); }
    public void dedupAggregated() { dedupAggregated.increment(); }
    public void l1Hit() { l1CacheHits.increment(); }
    public void l2Hit() { l2CacheHits.increment(); }
    public void esOnly() { esOnlyRequests.increment(); }
    public void sopFallback() { sopFallbacks.increment(); }
    public void retrievalTimeout() { retrievalTimeouts.increment(); }
    public void lowConfidence() { lowConfidenceRefusals.increment(); }
    public void request() { totalRequests.increment(); }
    public void verbatimMasked(int n) { verbatimMasked.increment(n); }
    public void actionCommandRejected() { actionCommandsRejected.increment(); }

    public long llmCallsValue() { return count(llmCalls); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_requests", count(totalRequests));
        m.put("llm_calls", count(llmCalls));
        m.put("llm_rate_limited", count(llmRateLimited));
        m.put("llm_retries", count(llmRetries));
        m.put("llm_network_errors", count(llmNetworkErrors));
        m.put("dedup_aggregated", count(dedupAggregated));
        m.put("l1_cache_hits", count(l1CacheHits));
        m.put("l2_cache_hits", count(l2CacheHits));
        m.put("es_only_requests", count(esOnlyRequests));
        m.put("sop_fallbacks", count(sopFallbacks));
        m.put("retrieval_timeouts", count(retrievalTimeouts));
        m.put("low_confidence_refusals", count(lowConfidenceRefusals));
        m.put("verbatim_masked", count(verbatimMasked));
        return m;
    }

    /** 比率：无分母给 0，不给 NaN（NaN 写进抓取体会让部分 Prometheus 客户端解析失败）。 */
    private double ratio(Counter numerator, Counter denominator) {
        return ratio(numerator.count(), denominator);
    }

    private double ratio(double numerator, Counter denominator) {
        double den = denominator.count();
        return den <= 0 ? 0.0 : numerator / den;
    }

    private static long count(Counter c) { return (long) c.count(); }

    private static Counter counter(MeterRegistry registry, String name, String desc) {
        return Counter.builder(name).description(desc).register(registry);
    }

    private static <T> void gauge(MeterRegistry registry, String name, T obj, ToDoubleFunction<T> value,
            String desc) {
        Gauge.builder(name, obj, value).description(desc).register(registry);
    }
}
