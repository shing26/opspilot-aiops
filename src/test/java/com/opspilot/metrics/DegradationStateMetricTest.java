package com.opspilot.metrics;

import com.opspilot.resilience.DegradationState;
import com.opspilot.resilience.DegradationState.Level;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 降级档位 gauge（ADRs 0016）：{@code aiops.degradation.state} 必须是**实时读**状态机，不是 bind 时刻快照。
 *
 * 为什么锁：gauge 与 counter 不同——counter 的值在 increment 那一刻就定了，gauge 只在被抓取时才求值。
 * 若把 {@code current()} 的序数在 bindTo 里求成常量写死，首次抓取后档位怎么变，Prometheus 里永远
 * 是同一条线（甚至会退回 0），"降级有没有发生"就彻底看不见了。这条锁直接对着那个失效模式打。
 */
class DegradationStateMetricTest {

    @Test
    void gaugeFollowsLiveLevelRatherThanBindTimeSnapshot() {
        DegradationState state = mock(DegradationState.class);
        when(state.current()).thenReturn(Level.L0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DegradationStateMetric(state).bindTo(registry);

        assertEquals(0.0, registry.get("aiops.degradation.state").gauge().value(),
                "L0 全链路");

        when(state.current()).thenReturn(Level.L1);
        assertEquals(1.0, registry.get("aiops.degradation.state").gauge().value(),
                "L1 仅 ES：值随状态机实时变化，不是 bind 时定格的 0");

        when(state.current()).thenReturn(Level.L2);
        assertEquals(2.0, registry.get("aiops.degradation.state").gauge().value(),
                "L2 SOP 兜底");
    }
}
