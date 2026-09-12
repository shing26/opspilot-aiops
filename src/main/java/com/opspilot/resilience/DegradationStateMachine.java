package com.opspilot.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;

/**
 * 三级自适应降级状态机：
 * L0 全链路；L1 摘向量+Rerank（纯 ES）；L2 熔断 LLM 直出静态 SOP。
 * 触发：inflight 超阈 → L1；LLM 连续失败/429 → L2（冷却到期半开：放行探测，
 * 成功自愈、再败重开——勿改回"过期仍判 L2"，那会把熔断锁死）。
 * 支持 admin 手动锁定（演示确定性切换，manualLock 优先于一切自动判定）。
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
        long until = l2UntilEpochMs.get();
        long now = System.currentTimeMillis();
        if (now < until) return Level.L2;
        // 冷却到期=半开（生成质量包 live 验收暴露的死锁修复，见 DegradationRecoveryTest）：
        // 清零连续失败计数放行探测请求，探测成败由该次调用的 llmSuccess/llmFailure 重新定档。
        // 条件必须带"曾熔断过"（until>0）——否则初始 until=0 会把未达阈的零星失败随手清零，
        // 计数永不可达阈值，熔断永远开不了（比死锁更糟）。竞态多线程重复 set(0) 无害。
        if (until > 0 && llmConsecutiveFailures.get() >= props.degrade().llmFailureThreshold()) {
            llmConsecutiveFailures.set(0);
        }
        if (inflight.get() >= props.degrade().inflightThreshold()) return Level.L1;
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

    /** 只读（Ops Console）：LLM 连续失败计数（熔断阈值进度 K/N）。 */
    public int llmConsecutiveFailures() { return llmConsecutiveFailures.get(); }

    /** 只读：熔断（含手动锁 L2 不计）剩余冷却秒数，无冷却返回 0。 */
    public long l2CooldownRemainingSeconds() {
        long remain = l2UntilEpochMs.get() - System.currentTimeMillis();
        return remain > 0 ? (remain + 999) / 1000 : 0;
    }
}
