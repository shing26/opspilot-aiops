package com.opspilot.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.metrics.AuditService;

/**
 * 进程内三级自适应降级状态机（{@link DegradationState} 默认实现）：
 * L0 全链路；L1 摘向量+Rerank（纯 ES）；L2 熔断 LLM 直出静态 SOP。
 * 触发：inflight 超阈 → L1；LLM 连续失败/429 → L2（冷却到期半开：放行探测，
 * 成功自愈、再败重开——勿改回"过期仍判 L2"，那会把熔断锁死）。
 * 支持 admin 手动锁定（演示确定性切换，manualLock 优先于一切自动判定）。
 *
 * <p><b>档位转移留痕（2026-09-28，OP-A5）</b>：每次档位变化落一条
 * {@code ev=degrade_transition} 审计事件（from/to/cause）。为什么不能只靠审计行的
 * {@code degrade_level} 字段反推：那个字段答的是"**这次请求时**是几档"，切换时刻只能靠请求
 * 密度间接推断，无请求的区间里切换会被整段漏掉——而那恰好是"降级有没有在真实负载下发生"
 * 唯一想证明的事。
 *
 * <p><b>观察点与量具边界</b>：本状态机是**拉模型**（无独立定时器），转移在两种时刻被观察到：
 * ① 计数器变化处（enter/exit/llmFailure/llmSuccess/manual*）——负载回落那一刻即落痕，不依赖
 * 后续请求；② {@link #current()} 被调用时（每请求与面板 1s 轮询 {@code /state}）。故"既无请求、
 * 又无人看 /state"的静默期内发生的切换会与下一次观察合并——这是拉模型的固有边界，不是丢事件。
 * 并发下由 {@code transitionLock} 双检去重，同一次切换只落一条。
 *
 * <p>分布式演进（Redis 原子计数 + Lua 判档）见 {@link DegradationState} 契约注释与 ADR-0015。
 */
@Service
public class DegradationStateMachine implements DegradationState {

    private static final Logger log = LoggerFactory.getLogger(DegradationStateMachine.class);

    private final OpsPilotProperties props;
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicInteger llmConsecutiveFailures = new AtomicInteger();
    private final AtomicLong l2UntilEpochMs = new AtomicLong();
    private volatile Level manualLock = null;

    /** 最后一次被观察到的档位（转移事件的 from）。 */
    private volatile Level lastObserved = Level.L0;
    private final Object transitionLock = new Object();
    private volatile TransitionSink sink = (from, to, cause) -> { };

    public DegradationStateMachine(OpsPilotProperties props) {
        this.props = props;
    }

    /**
     * Spring 注入构造：把转移接到合规审计（{@code logs/audit.jsonl}）。
     * 保留单参构造供单测使用（纯状态机），也让本类不硬依赖 metrics 包。
     */
    @Autowired
    public DegradationStateMachine(OpsPilotProperties props, AuditService audit) {
        this.props = props;
        this.sink = (from, to, cause) -> audit.logDegradeTransition(from.name(), to.name(), cause);
    }

    @Override
    public Level current() {
        return observe(null);
    }

    @Override
    public void enter() {
        inflight.incrementAndGet();
        observe(null);   // 跨过阈值的那一次递增必须当场落痕
    }

    @Override
    public void exit() {
        inflight.decrementAndGet();
        observe(null);   // 负载回落→L0 在此落痕：压测停了已无新请求，等 current() 会把这次回落漏掉
    }

    @Override
    public void llmSuccess() {
        llmConsecutiveFailures.set(0);
        observe(null);
    }

    @Override
    public void llmFailure() {
        int f = llmConsecutiveFailures.incrementAndGet();
        if (f >= props.degrade().llmFailureThreshold()) {
            l2UntilEpochMs.set(System.currentTimeMillis()
                    + props.degrade().llmOpenSeconds() * 1000L);
        }
        observe(null);
    }

    @Override
    public void manualSet(Level level) {
        this.manualLock = level;
        observe("manual");
    }

    @Override
    public void manualClear() {
        this.manualLock = null;
        observe("manual_clear");
    }

    @Override
    public boolean isManual() { return manualLock != null; }

    @Override
    public int inflightValue() { return inflight.get(); }

    /** 只读（Ops Console）：LLM 连续失败计数（熔断阈值进度 K/N）。 */
    @Override
    public int llmConsecutiveFailures() { return llmConsecutiveFailures.get(); }

    /** 只读：熔断（含手动锁 L2 不计）剩余冷却秒数，无冷却返回 0。 */
    @Override
    public long l2CooldownRemainingSeconds() {
        long remain = l2UntilEpochMs.get() - System.currentTimeMillis();
        return remain > 0 ? (remain + 999) / 1000 : 0;
    }

    /** 观察当前档位，变化时落一条转移事件；返回值恒等于当前档位（读语义不变）。 */
    private Level observe(String forcedCause) {
        Level now = auto();
        if (now == lastObserved) return now;
        synchronized (transitionLock) {
            if (now != lastObserved) {
                Level from = lastObserved;
                lastObserved = now;
                String cause = forcedCause != null ? forcedCause : autoCause(from, now);
                try {
                    sink.on(from, now, cause);
                } catch (Exception e) {
                    // 留痕失败不阻断业务链路（与 AuditService 同纪律）：漏一条可接受，压垮请求不可接受
                    log.warn("degrade transition sink failed: {}", e.toString());
                }
            }
        }
        return now;
    }

    /** 有限词表的转移原因（消费端按此穷举；勿改成自由文本）。 */
    private static String autoCause(Level from, Level to) {
        if (to == Level.L1) return "inflight";
        if (to == Level.L2) return "llm_failure";
        if (to == Level.L0) return from == Level.L2 ? "cooldown_expired" : "load_subsided";
        return "observed";
    }

    /**
     * 档位判定（原 current() 主体，语义未变）：手动锁优先 → 冷却期内 L2 → 冷却到期半开
     * （清零连续失败计数放行探测）→ inflight 超阈 L1 → L0。
     *
     * 冷却到期=半开（生成质量包 live 验收暴露的死锁修复，见 DegradationRecoveryTest）：
     * 条件必须带"曾熔断过"（until>0）——否则初始 until=0 会把未达阈的零星失败随手清零，
     * 计数永不可达阈值，熔断永远开不了（比死锁更糟）。竞态多线程重复 set(0) 无害。
     */
    private Level auto() {
        if (manualLock != null) return manualLock;
        long until = l2UntilEpochMs.get();
        long now = System.currentTimeMillis();
        if (now < until) return Level.L2;
        if (until > 0 && llmConsecutiveFailures.get() >= props.degrade().llmFailureThreshold()) {
            llmConsecutiveFailures.set(0);
        }
        if (inflight.get() >= props.degrade().inflightThreshold()) return Level.L1;
        return Level.L0;
    }
}
