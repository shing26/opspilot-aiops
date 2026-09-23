package com.opspilot.metrics;

import com.opspilot.auth.UserContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ops Console 数据源的进程内事件环（ADR-0009）：有界、游标不重不漏、
 * 轮转/重启的丢失必须 truncated 显式告知——禁止静默空洞（外审①）。
 */
class AuditServiceTest {

    private static final UserContext U = new UserContext("sre-x", "sre", 1, "tenant-demo");

    private static void writeN(AuditService svc, int n) {
        for (int i = 0; i < n; i++) {
            svc.log(U, "chat", "sse", "manual", "q" + i, "fp", "none", "hybrid", false, 1, i, null, null, null);
        }
    }

    @Test
    void ringIsBoundedAt200WithMonotonicSeq() {
        AuditService svc = new AuditService();
        writeN(svc, 300);
        var r = svc.recentSince(0, 500);
        assertTrue(r.events().size() <= 200, "有界 200：实际 " + r.events().size());
        assertEquals(300, r.maxSeq());
        long prev = 0;
        for (var ev : r.events()) {
            long s = ((Number) ev.get("seq")).longValue();
            assertTrue(s > prev, "seq 必须严格递增");
            prev = s;
        }
    }

    @Test
    void cursorIsInclusiveExclusiveWithNoGapsNoDupes() {
        AuditService svc = new AuditService();
        writeN(svc, 50);
        var page1 = svc.recentSince(0, 30);
        assertEquals(30, page1.events().size());
        long c1 = ((Number) page1.events().get(29).get("seq")).longValue();
        var page2 = svc.recentSince(c1, 30);
        assertEquals(20, page2.events().size(), "不重不漏：第二页恰为剩余 20 条");
        assertTrue(page2.events().stream().allMatch(e ->
                ((Number) e.get("seq")).longValue() > c1));
        assertTrue(svc.recentSince(50, 50).events().isEmpty(), "追平后增量为空");
    }

    /** W8：落后游标穿越轮转边界 → truncated=true（前端据此插提示行并跳 maxSeq）。 */
    @Test
    void evictedGapIsNeverSilent() {
        AuditService svc = new AuditService();
        writeN(svc, 300);                       // 1..100 已驱逐，驻留 101..300
        var r = svc.recentSince(1, 200);
        assertTrue(r.truncated(), "游标落在已驱逐区必须报警");
        assertEquals(101, ((Number) r.events().get(0).get("seq")).longValue());

        assertFalse(svc.recentSince(100, 200).truncated(), "游标恰在最老驻留前一位=无丢失");
        assertFalse(svc.recentSince(299, 200).truncated());
    }

    @Test
    void serverRestartRollsCursorForward() {
        AuditService svc = new AuditService();
        writeN(svc, 5);
        var r = svc.recentSince(9999, 50);      // 客户端拿着重启前的旧游标
        assertTrue(r.truncated(), "游标超前=服务重启，同样显式告知");
        assertEquals(5, r.events().size(), "重启后给全部现存事件，游标不得卡死");
        assertEquals(5, r.maxSeq());
    }

    @Test
    void limitIsClampedAndEmptyStateIsSafe() {
        AuditService svc = new AuditService();
        var empty = svc.recentSince(0, 50);
        assertTrue(empty.events().isEmpty() && !empty.truncated() && empty.maxSeq() == 0);
        writeN(svc, 10);
        assertEquals(1, svc.recentSince(0, 0).events().size(), "limit 下限夹到 1");
    }

    /** H2：审计行携带请求级关联 id（MDC 缺席时不落字段——系统内部触发无 id 可言）。 */
    @Test
    void auditRowCarriesRequestIdFromMdc() {
        AuditService svc = new AuditService();
        org.slf4j.MDC.put("request_id", "abc12345");
        try {
            svc.log(U, "chat", "sse", "manual", "q", "fp", "none", "hybrid", false, 1, 1, null, null, null);
        } finally {
            org.slf4j.MDC.remove("request_id");
        }
        svc.log(U, "chat", "sse", "manual", "q2", "fp", "none", "hybrid", false, 1, 2, null, null, null);
        var r = svc.recentSince(0, 10);
        assertEquals("abc12345", r.events().get(0).get("request_id"), "MDC 在场→行携带 id");
        assertFalse(r.events().get(1).containsKey("request_id"), "MDC 缺席→无该字段");
    }

    /**
     * 告警来源可辨识（2026-09-16 自举告警源配套）：source 与 via 正交，必须是一等审计事实——
     * 否则"这条请求是系统自诊断发的还是人发的"在日志里答不出来，闭环叙事就没有可核验面。
     */
    @Test
    void auditRowCarriesSourceAndBlankNormalizesToManual() {
        AuditService svc = new AuditService();
        svc.log(U, "chat", "sse", "alert", "q", "fp", "none", "hybrid", false, 1, 1, null, null, null);
        svc.log(U, "chat", "sse", "   ", "q2", "fp", "none", "hybrid", false, 1, 2, null, null, null);
        var r = svc.recentSince(0, 10);
        assertEquals("alert", r.events().get(0).get("source"), "告警请求必须可在审计中辨识");
        assertEquals("manual", r.events().get(1).get("source"),
                "空白来源归一为 manual——审计行不留空，避免'没来源'与'人工'两种含义混淆");
    }

    /**
     * G2：`stage_ms` 仅在携带时落字段。**null ≠ 全 0**——null 是"非 chat 路径根本没测"
     * （/search、鉴权、管理面），全 0 是"测了且各段确实为 0"。混同会让运维把"没测"读成"很快"。
     */
    @Test
    void auditRowCarriesStageTimingsOnlyWhenPresent() {
        AuditService svc = new AuditService();
        svc.log(U, "chat", "sse", "manual", "q", "fp", "none", "hybrid", false, 1, 1, null, null,
                new StageTimings(100, 11, 22, 33, 0, 40, 50, 60));
        svc.log(U, "search", "search-api", "manual", "q2", "fp", "none", "hybrid", false, 1, 2,
                null, null, null);
        var r = svc.recentSince(0, 10);

        @SuppressWarnings("unchecked")
        var stages = (java.util.Map<String, Object>) r.events().get(0).get("stage_ms");
        assertEquals(8, stages.size(), "chat 行必须携带完整 stage_ms: " + stages.keySet());
        assertEquals(0, stages.get("rerank"), "0 值段仍落字段（未调用≠没测）");
        assertFalse(r.events().get(1).containsKey("stage_ms"), "非 chat 路径（传 null）不得落该字段");
    }
}
