package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.resilience.DegradationStateMachine;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 运维端点：需有效 JWT；状态变更（降级/清缓存）额外要求 auth_level>=3。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Set<String> VALID_LEVELS = Set.of("L0", "L1", "L2", "auto");

    private final OpsMetrics metrics;
    private final DegradationStateMachine degrade;
    private final L1CacheService l1;
    private final L2SemanticCacheService l2;
    private final OpsPilotProperties props;

    public AdminController(OpsMetrics metrics, DegradationStateMachine degrade,
                           L1CacheService l1, L2SemanticCacheService l2, OpsPilotProperties props) {
        this.metrics = metrics;
        this.degrade = degrade;
        this.l1 = l1;
        this.l2 = l2;
        this.props = props;
    }

    private void requireAdmin(HttpServletRequest http) {
        UserContext u = JwtAuthFilter.from(http);
        if (u == null || u.authLevel() < 3) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要 auth_level>=3 管理员凭证");
        }
    }

    /** 清空 L1+L2 缓存（验收隔离 / 演示重置）。 */
    @PostMapping("/cache/flush")
    public Map<String, Object> flushCache(HttpServletRequest http) {
        requireAdmin(http);
        long n = l1.flush();
        l2.flush();
        return Map.of("l1_flushed", n);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>(metrics.snapshot());
        var ds = props.dashscope();
        // 后端真相由服务端自报（评测/压测报告据此标注，避免 mock/live 元数据错标——
        // live 首跑曾因报告硬编码 mock 而暴露此需求）
        m.put("backend", Map.of(
                "embedding", ds.live() ? "dashscope:" + ds.embeddingModel() : "mock-lexical-hash",
                "rerank", ds.live() ? "dashscope:" + ds.rerankModel() : "mock-idf-coverage",
                "llm", ds.live() ? "dashscope:" + ds.llmModel() : "mock-template"));
        m.put("degradation_level", degrade.current().name());
        m.put("degradation_manual", degrade.isManual());
        m.put("inflight", degrade.inflightValue());
        return m;
    }

    /** body: {"level":"L0|L1|L2"} 锁定；{"level":"auto"} 解除手动锁定。 */
    @PostMapping("/degrade")
    public Map<String, Object> degrade(@RequestBody Map<String, String> body, HttpServletRequest http) {
        requireAdmin(http);
        String level = body.getOrDefault("level", "auto");
        if (!VALID_LEVELS.contains(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非法 level，允许值: " + VALID_LEVELS);
        }
        if ("auto".equalsIgnoreCase(level)) {
            degrade.manualClear();
        } else {
            degrade.manualSet(DegradationStateMachine.Level.valueOf(level.toUpperCase()));
        }
        return Map.of("degradation_level", degrade.current().name(), "manual", degrade.isManual());
    }
}
