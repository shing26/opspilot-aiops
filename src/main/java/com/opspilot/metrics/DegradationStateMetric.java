package com.opspilot.metrics;

import com.opspilot.resilience.DegradationState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * 把三级降级状态机的当前档位暴露为 Prometheus gauge（ADRs 0016）。
 *
 * <p>取值是档位序数而非布尔：{@code 0=L0 全链路 / 1=L1 仅 ES / 2=L2 SOP 兜底}。为什么用枚举序数而不是
 * 三个 0/1 gauge：时序库里"当前处于哪一档"的告警规则要写成"值从 0 变 1"这种状态迁移，多 gauge 会退化成
 * 需要自己算和的组合量。档位切换本身另有 {@code ev=degrade_transition} 审计事件留痕（OP-A5），
 * gauge 答的是"此刻在第几档"，事件答的是"何时发生了切换"，两者互补。
 *
 * <p>实现走 {@link MeterBinder} 而非在 {@link DegradationStateMachine} 里注入 registry：gauge 的值
 * 来自另一个 bean，注册权归指标侧，被测的状态机构造签名因此不必带上量具依赖。Spring Boot 自动把
 * 所有 MeterBinder bean 绑到每个 MeterRegistry，无需手工调用 bindTo。
 */
@Component
public class DegradationStateMetric implements MeterBinder {

    private final DegradationState state;

    public DegradationStateMetric(DegradationState state) {
        this.state = state;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("aiops.degradation.state", state, s -> (double) s.current().ordinal())
                .description("三级降级当前档位：0=L0 全链路 / 1=L1 仅 ES / 2=L2 SOP 兜底")
                .register(registry);
    }
}
