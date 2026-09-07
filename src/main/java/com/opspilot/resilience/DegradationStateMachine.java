package com.opspilot.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;

/**
 * 三级自适应降级状态机：
 * L0 全链路；L1 摘向量+Rerank（纯 ES）；L2 熔断 LLM 直出静态 SOP。
 * 触发：inflight 超阈 → L1；LLM 连续失败/429 → L2（冷却后回 L0）。
 * 支持 admin 手动锁定（演示确定性切换）。
 */
@Service
public class DegradationStateMachine {

    public enum Level { L0, L1, L2 }

    private final OpsPilotProperties props;
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicInteger llmConsecutiveFailures = new AtomicInteger();
    private final AtomicLong l2UntilEpochMs = new AtomicLong();
    private volatile Level manualLock = null;

    public DegradationStateMachine(OpsPilotProperties props) {
        this.props = props;
    }

    public Level current() {
        if (manualLock != null) return manualLock;
        if (System.currentTimeMillis() < l2UntilEpochMs.get()) return Level.L2;
        if (inflight.get() >= props.degrade().inflightThreshold()) return Level.L1;
        if (llmConsecutiveFailures.get() >= props.degrade().llmFailureThreshold()) return Level.L2;
        return Level.L0;
    }

    public void enter() { inflight.incrementAndGet(); }
    public void exit() { inflight.decrementAndGet(); }

    public void llmSuccess() { llmConsecutiveFailures.set(0); }

    public void llmFailure() {
        int f = llmConsecutiveFailures.incrementAndGet();
        if (f >= props.degrade().llmFailureThreshold()) {
            l2UntilEpochMs.set(System.currentTimeMillis()
                    + props.degrade().llmOpenSeconds() * 1000L);
        }
    }

    public void manualSet(Level level) { this.manualLock = level; }
    public void manualClear() { this.manualLock = null; }
    public boolean isManual() { return manualLock != null; }

    public int inflightValue() { return inflight.get(); }
}
