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
            svc.log(U, "chat", "sse", "q" + i, "fp", "none", "hybrid", false, 1, i);
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
            svc.log(U, "chat", "sse", "q", "fp", "none", "hybrid", false, 1, 1);
        } finally {
            org.slf4j.MDC.remove("request_id");
        }
        svc.log(U, "chat", "sse", "q2", "fp", "none", "hybrid", false, 1, 2);
        var r = svc.recentSince(0, 10);
        assertEquals("abc12345", r.events().get(0).get("request_id"), "MDC 在场→行携带 id");
        assertFalse(r.events().get(1).containsKey("request_id"), "MDC 缺席→无该字段");
    }
}
