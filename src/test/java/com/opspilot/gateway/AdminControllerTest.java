package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.health.HealthProbe;
import com.opspilot.ingest.IngestionRunner;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.resilience.DegradationStateMachine;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * QA 台账 2026-09-11 P0-2/P1-1 的回归锁：admin 面授权 = DB role=="platform" ∧ auth_level>=3。
 * 旧口径"level≥3 即管理员"让外来租户 L3（sre-acme）握有重建共享索引/清全局缓存的权力，
 * 且 metrics 完全无门禁——权限维度必须含 role（信任域），level 只是密级不是信任。
 * 6 端点 × 3 身份 = 18 格矩阵，只放行 platform 列。
 */
class AdminControllerTest {

    private static final UserContext PLATFORM =
            new UserContext("sre-full", "platform", 3, "tenant-internal");
    private static final UserContext CUSTOMER_L3 =
            new UserContext("sre-acme", "sre", 3, "tenant-acme");   // 同密级、外来租户
    private static final UserContext PLAIN_L1 =
            new UserContext("sre-limited", "sre", 1, "tenant-internal");

    private AdminController controller;

    @BeforeEach
    void setUp() {
        OpsMetrics metrics = mock(OpsMetrics.class);
        when(metrics.snapshot()).thenReturn(Map.of("total_requests", 1));
        DegradationStateMachine degrade = mock(DegradationStateMachine.class);
        when(degrade.current()).thenReturn(DegradationStateMachine.Level.L0);
        L1CacheService l1 = mock(L1CacheService.class);
        L2SemanticCacheService l2 = mock(L2SemanticCacheService.class);
        OpsPilotProperties props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope(
                        "http://x", null, null, 0, null, null, 0, "mock"),
                null, null, null, null, null, null, null);
        IngestionRunner ingestion = mock(IngestionRunner.class);
        when(ingestion.reingestAsync()).thenReturn(true);
        UserStore users = mock(UserStore.class);
        when(users.backup(any())).thenReturn("backup/ok.zip");
        HealthProbe probe = mock(HealthProbe.class);
        when(probe.summary()).thenReturn(Map.of("status", "UP"));
        controller = new AdminController(metrics, degrade, l1, l2, props, ingestion, users, probe);
    }

    private static MockHttpServletRequest withUser(UserContext u) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (u != null) req.setAttribute(UserContext.REQUEST_ATTR, u);
        return req;
    }

    /** 端点调用表：路径 → 触发函数（request 注入身份）。 */
    private Map<String, Function<MockHttpServletRequest, ?>> endpoints() {
        return Map.of(
                "POST /cache/flush", r -> controller.flushCache(r),
                "GET  /metrics",     r -> controller.metrics(r),
                "GET  /health",      r -> controller.health(r),
                "POST /degrade",     r -> controller.degrade(Map.of("level", "auto"), r),
                "POST /reingest",    r -> controller.reingest(r),
                "POST /backup",      r -> controller.backup(Map.of("to", "backup/x.zip"), r));
    }

    @Test
    void platformAdminPassesAllEndpoints() {
        endpoints().forEach((name, call) ->
                assertDoesNotThrow(() -> call.apply(withUser(PLATFORM)), name + " 平台管理员应放行"));
    }

    @Test
    void customerTenantL3ForbiddenOnAllEndpoints() {
        // P0-2 事故格：外来租户 L3 曾真实执行过 reingest/cache-flush
        endpoints().forEach((name, call) -> {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> call.apply(withUser(CUSTOMER_L3)),
                    name + " 外来租户 L3 必须 403（level 高≠可信，缺 role 维度即 P0-2）");
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode(), name);
        });
    }

    @Test
    void plainL1ForbiddenOnAllEndpoints() {
        endpoints().forEach((name, call) -> assertThrows(ResponseStatusException.class,
                () -> call.apply(withUser(PLAIN_L1)), name + " L1 必须 403"));
    }

    @Test
    void anonymousForbiddenOnAllEndpoints() {
        endpoints().forEach((name, call) -> assertThrows(ResponseStatusException.class,
                () -> call.apply(withUser(null)), name + " 无身份必须 403"));
    }

    /** 破坏性动作在 403 时绝不可触达执行面（reingest 被误触发的教训）。 */
    @Test
    void forbiddenRequestsNeverReachDestructiveCollaborators() {
        MockHttpServletRequest acme = withUser(CUSTOMER_L3);
        assertThrows(ResponseStatusException.class, () -> controller.reingest(acme));
        assertThrows(ResponseStatusException.class, () -> controller.flushCache(acme));
        assertThrows(ResponseStatusException.class, () -> controller.backup(Map.of("to", "backup/x.zip"), acme));
    }
}
