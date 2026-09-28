package com.opspilot.metrics;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2：请求级关联 id——链内可见（8 位）、链后清理（Tomcat 线程复用防串号）。
 * OP-A7（2026-09-28）：调用方关联键 `X-Trace-Id` 的校验、落位与拒绝形状。
 */
class RequestIdFilterTest {

    private final AuditService audit = new AuditService();
    private final RequestIdFilter filter = new RequestIdFilter(audit);

    @Test
    void mdcVisibleDuringChainAndClearedAfter() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(RequestIdFilter.KEY)));
        assertNotNull(seen.get(), "链内必须可读（后续 filter 与控制器日志携带同一 id）");
        assertEquals(8, seen.get().length());
        assertNull(MDC.get(RequestIdFilter.KEY), "链后必须清理");
    }

    @Test
    void eachRequestGetsDistinctId() throws Exception {
        AtomicReference<String> a = new AtomicReference<>();
        AtomicReference<String> b = new AtomicReference<>();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> a.set(MDC.get(RequestIdFilter.KEY)));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> b.set(MDC.get(RequestIdFilter.KEY)));
        assertNotEquals(a.get(), b.get());
    }

    /** 合法调用方 trace：链内可见，且**与服务端 request_id 并存**（互不覆盖，ADR-0007 修订注）。 */
    @Test
    void validTraceHeaderLandsInMdcAndIsCleanedAfterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.TRACE_HEADER, "agent-run-7f3a");
        AtomicReference<String> trace = new AtomicReference<>();
        AtomicReference<String> rid = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {
            trace.set(MDC.get(RequestIdFilter.TRACE_KEY));
            rid.set(MDC.get(RequestIdFilter.KEY));
        });
        assertEquals("agent-run-7f3a", trace.get(), "调用方 trace 必须在链内可见（审计行要落它）");
        assertNotNull(rid.get(), "服务端 request_id 同时存在——两个 id 并存，调用方输入顶不掉它");
        assertNull(MDC.get(RequestIdFilter.TRACE_KEY), "链后必须清理（Tomcat 线程复用防串号）");
    }

    /** 非法 trace：400 + 短路 + 留痕（不静默降级成"没有 trace"——那会让调用方以为自己接上了）。 */
    @Test
    void malformedTraceHeaderIsRejectedWith400ShortCircuitsAndIsAudited() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat/stream");
        req.addHeader(RequestIdFilter.TRACE_HEADER, "bad trace!!");     // 含空格与非法字符
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<Boolean> chained = new AtomicReference<>(false);
        filter.doFilter(req, resp, (r, s) -> chained.set(true));

        assertEquals(400, resp.getStatus());
        assertFalse(chained.get(), "非法头必须在过滤器短路，不得进业务链路");
        String body = resp.getContentAsString();
        assertTrue(body.contains("INVALID_REQUEST") && body.contains("message"),
                "非 /v1 面沿用 {code,message} 形状: " + body);
        var r = audit.recentSince(0, 10);
        assertEquals("invalid", r.events().get(0).get("ev"), "400 必须留痕（否则用户改请求如坠迷雾）");
    }

    /** /v1 面（OpenAI 协议）的形状必须保持 {"error":{...}} —— filter 短路早于 advice，形状得自己给。 */
    @Test
    void v1FaceUsesOpenAiErrorEnvelopeOnBadTrace() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        req.addHeader(RequestIdFilter.TRACE_HEADER, "短");               // 长度 1 < 8
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) -> { });
        assertEquals(400, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"error\""),
                "/v1 面必须保持 OpenAI error 信封: " + resp.getContentAsString());
    }

    /** 空白值按**未提供**处理：不少 HTTP 客户端默认带空头，把那当非法是给自己找 400。 */
    @Test
    void blankTraceHeaderIsTreatedAsAbsentNotIllegal() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.TRACE_HEADER, "   ");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<String> trace = new AtomicReference<>("sentinel");
        filter.doFilter(req, resp, (r, s) -> trace.set(MDC.get(RequestIdFilter.TRACE_KEY)));
        assertEquals(200, resp.getStatus(), "空白头不得触发 400");
        assertNull(trace.get(), "空白=未提供：不落 MDC");
    }

    /**
     * 边界矩阵（安全验收口径：每个可控入参都要打边界，不只打一个"正常例子"）：
     * 长度 8/64 合法、7/65 非法；空格、路径字符、非 ASCII 一律非法。
     */
    @Test
    void traceLengthAndCharsetBoundariesAreEnforced() throws Exception {
        assertEquals(400, statusFor("x".repeat(7)), "7 位越界（下限 8）");
        assertEquals(200, statusFor("x".repeat(8)), "8 位恰好合法");
        assertEquals(200, statusFor("x".repeat(64)), "64 位恰好合法");
        assertEquals(400, statusFor("x".repeat(65)), "65 位越界（上限 64）");
        assertEquals(400, statusFor("has space"), "空格非法");
        assertEquals(400, statusFor("has/slash"), "路径分隔符非法");
        assertEquals(400, statusFor("中文-ascii混"), "非 ASCII 非法（防控制字符/注入进日志）");
        assertEquals(200, statusFor("AZaz09_-AZaz09"), "词表内字符全合法");
    }

    private int statusFor(String traceValue) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.TRACE_HEADER, traceValue);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) -> { });
        return resp.getStatus();
    }
}
