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

/**
 * 运维端点：**全部要求平台管理员** —— DB `role=="platform"` ∧ `auth_level>=3`。
 * 2026-09-11 QA P0-2/P1-1：旧口径"level≥3 即管理员"把密级当信任，外来租户 L3 可重建共享索引/
 * 清全局缓存（已被实测误触发）；metrics 曾完全无门禁暴露全局计数与内部模型名。
 * 索引/集合/缓存/H2 都是平台级共享资源，任何单一租户的用户（无论多高密级）不得持有其写权。
 * role 与 tenant/level 同源于 DB（JwtAuthFilter 每请求注入），保持"DB 是唯一真相"（ADR-0008）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Set<String> VALID_LEVELS = Set.of("L0", "L1", "L2", "auto");

    private final OpsMetrics metrics;
    private final DegradationStateMachine degrade;
    private final L1CacheService l1;
    private final L2SemanticCacheService l2;
    private final OpsPilotProperties props;
    private final com.opspilot.ingest.IngestionRunner ingestion;
    private final com.opspilot.auth.UserStore users;
    private final com.opspilot.health.HealthProbe healthProbe;
    private final com.opspilot.metrics.AuditService audit;

    public AdminController(OpsMetrics metrics, DegradationStateMachine degrade,
                           L1CacheService l1, L2SemanticCacheService l2, OpsPilotProperties props,
                           com.opspilot.ingest.IngestionRunner ingestion, com.opspilot.auth.UserStore users,
                           com.opspilot.health.HealthProbe healthProbe, com.opspilot.metrics.AuditService audit) {
        this.metrics = metrics;
        this.degrade = degrade;
        this.l1 = l1;
        this.l2 = l2;
        this.props = props;
        this.ingestion = ingestion;
        this.users = users;
        this.healthProbe = healthProbe;
        this.audit = audit;
    }

    /** 平台门禁 + 拒绝留痕（QA P1-2：破坏性端点被拒/成功都必须可回溯）。 */
    private void requirePlatformAdmin(HttpServletRequest http, String action) {
        UserContext u = JwtAuthFilter.from(http);
        if (u == null || !("platform".equals(u.role()) && u.authLevel() >= 3)) {
            audit.logAdmin(u, action, "refused", null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要平台管理员凭证（role=platform 且 auth_level>=3）");
        }
    }

    /** 清空 L1+L2 缓存（验收隔离 / 演示重置）。 */
    @PostMapping("/cache/flush")
    public Map<String, Object> flushCache(HttpServletRequest http) {
        requirePlatformAdmin(http, "cache/flush");
        long n = l1.flush();
        l2.flush();
        audit.logAdmin(JwtAuthFilter.from(http), "cache/flush", "ok", "l1_flushed=" + n);
        return Map.of("l1_flushed", n);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpServletRequest http) {
        requirePlatformAdmin(http, "metrics");
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
        m.put("reingest_busy", ingestion.busy());
        m.put("reingest_last", ingestion.lastResult());
        return m;
    }

    /** 部署级健康明细（平台管理员）：依赖探测+live 口径+索引规模；组件聚合另见 /actuator/health。 */
    @GetMapping("/health")
    public Map<String, Object> health(HttpServletRequest http) {
        requirePlatformAdmin(http, "health");
        return healthProbe.summary();
    }

    /** body: {"level":"L0|L1|L2"} 锁定；{"level":"auto"} 解除手动锁定。 */
    @PostMapping("/degrade")
    public Map<String, Object> degrade(@RequestBody Map<String, String> body, HttpServletRequest http) {
        requirePlatformAdmin(http, "degrade");
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
        audit.logAdmin(JwtAuthFilter.from(http), "degrade", "ok", "level=" + level);
        return Map.of("degradation_level", degrade.current().name(), "manual", degrade.isManual());
    }

    /** P4：异步触发 blue/green 重灌（平台管理员）；已有任务在跑返回 409。 */
    @PostMapping("/reingest")
    public Map<String, Object> reingest(HttpServletRequest http) {
        requirePlatformAdmin(http, "reingest");
        if (!ingestion.reingestAsync()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有 reingest 在执行中");
        }
        audit.logAdmin(JwtAuthFilter.from(http), "reingest", "ok", null);
        return Map.of("accepted", true, "hint", "完成后 /metrics 的 reingest_last 会更新");
    }

    /** 运维备份（平台管理员）：网关是 H2 的所有者，BACKUP TO 在自己的连接上执行——
     *  不依赖 AUTO_SERVER TCP（Windows 防火墙常拦），gateway 活着就能备，cron 友好。
     *  路径非法是**客户端错误**（QA P2-2：曾以 500 呈现，污染 5xx SLO 且用户看不懂），
     *  H2BackupPath 白名单拒绝翻译为 400 + 可操作文案。 */
    @PostMapping("/backup")
    public Map<String, Object> backup(@RequestBody Map<String, String> body, HttpServletRequest http) {
        requirePlatformAdmin(http, "backup");
        String to;
        try {
            to = users.backup(body.getOrDefault("to", null));
        } catch (IllegalArgumentException e) {
            audit.logAdmin(JwtAuthFilter.from(http), "backup", "rejected", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "备份路径非法：须为 backup/ 目录下的 .zip，且不含引号/分号/反斜杠");
        }
        audit.logAdmin(JwtAuthFilter.from(http), "backup", "ok", to);
        return Map.of("backup", to);
    }
}
