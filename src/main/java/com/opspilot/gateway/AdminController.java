package com.opspilot.gateway;

import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.resilience.DegradationStateMachine;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运维端点（本机演示，不鉴权）：指标快照 + 降级手动开关 + 缓存清理。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final OpsMetrics metrics;
    private final DegradationStateMachine degrade;
    private final L1CacheService l1;
    private final L2SemanticCacheService l2;

    public AdminController(OpsMetrics metrics, DegradationStateMachine degrade,
                           L1CacheService l1, L2SemanticCacheService l2) {
        this.metrics = metrics;
        this.degrade = degrade;
        this.l1 = l1;
        this.l2 = l2;
    }

    /** 清空 L1+L2 缓存（验收隔离 / 演示重置）。 */
    @PostMapping("/cache/flush")
    public Map<String, Object> flushCache() {
        long n = l1.flush();
        l2.flush();
        return Map.of("l1_flushed", n);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>(metrics.snapshot());
        m.put("degradation_level", degrade.current().name());
        m.put("degradation_manual", degrade.isManual());
        m.put("inflight", degrade.inflightValue());
        return m;
    }

    /** body: {"level":"L0|L1|L2"} 锁定；{"level":"auto"} 解除手动锁定。 */
    @PostMapping("/degrade")
    public Map<String, Object> degrade(@RequestBody Map<String, String> body) {
        String level = body.getOrDefault("level", "auto");
        if ("auto".equalsIgnoreCase(level)) {
            degrade.manualClear();
        } else {
            degrade.manualSet(DegradationStateMachine.Level.valueOf(level.toUpperCase()));
        }
        return Map.of("degradation_level", degrade.current().name(), "manual", degrade.isManual());
    }
}
