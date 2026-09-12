package com.opspilot.metrics;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** H2：请求级关联 id——链内可见（8 位）、链后清理（Tomcat 线程复用防串号）。 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

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
}
