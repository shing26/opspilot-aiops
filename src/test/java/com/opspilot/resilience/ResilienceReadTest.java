package com.opspilot.resilience;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 只读增量（Ops Console）：usedToday 只 GET 不 INCR（读面板不得消耗配额，
 * 尤其 5000 边界用户"看一眼就多 1 次"是不可接受的观测者效应）；
 * 熔断器读数 getter 无副作用。
 */
class ResilienceReadTest {

    @Test
    void usedTodayReadsWithoutIncrementing() {
        RedissonClient redisson = mock(RedissonClient.class);
        RAtomicLong day = mock(RAtomicLong.class);
        when(redisson.getAtomicLong(startsWith("quota:"))).thenReturn(day);
        when(day.get()).thenReturn(42L);
        QuotaService quota = new QuotaService(redisson, 5000L);

        assertEquals(42L, quota.usedToday("sre-x"));
        assertEquals(5000L, quota.dailyLimit());
        verify(day, never()).incrementAndGet();
        verify(day, never()).expire(any(Duration.class));
    }

    @Test
    void degradeGettersReflectStateWithoutMutation() {
        var props = new com.opspilot.config.OpsPilotProperties(
                null, null, null, null, null, null, null,
                new com.opspilot.config.OpsPilotProperties.Degrade(10, 3, 60));
        var sm = new DegradationStateMachine(props);
        assertEquals(0, sm.llmConsecutiveFailures());
        assertEquals(0L, sm.l2CooldownRemainingSeconds());

        sm.llmFailure(); sm.llmFailure(); sm.llmFailure();       // 达阈=3 → 熔断 60s
        assertEquals(3, sm.llmConsecutiveFailures());
        long cd1 = sm.l2CooldownRemainingSeconds();
        assertTrue(cd1 >= 59 && cd1 <= 60, "冷却倒计时 ~60s，实际 " + cd1);
        assertEquals(cd1, sm.l2CooldownRemainingSeconds(), "重复读值单调（getter 无副作用）");

        sm.llmSuccess();
        assertEquals(0, sm.llmConsecutiveFailures(), "成功清零计数（冷却窗仍至时限）");
        assertEquals(DegradationStateMachine.Level.L2, sm.current());
    }
}
