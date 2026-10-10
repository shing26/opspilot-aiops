package com.opspilot.resilience;

/**
 * 三级自适应降级状态机契约（ADR-0012）：L0 全链路 / L1 摘向量+Rerank（纯 ES）/
 * L2 熔断 LLM 直出静态 SOP。<b>拉模型</b>——无独立定时器，档位在计数器变化处
 * 与 {@link #current()} 被调用时被观察到。
 *
 * <p><b>实现双轨</b>（ADR-0015）：
 * <ul>
 *   <li><b>进程内</b>（当前默认，{@code DegradationStateMachine}）：
 *       {@code AtomicInteger/AtomicLong/volatile}，单 JVM 内正确。
 *       拉模型 + 双检去重保证同一次切换只落一条转移事件。</li>
 *   <li><b>分布式</b>（预留演进）：inflight/失败计数用 Redis 原子计数
 *       （{@code RAtomicLong} 或 Lua 脚本 compare-and-set），冷却期与手动锁用
 *       {@code RAtomicLong/RString}，档位判定改为 Redis Lua 脚本保证全局原子。
 *       转移事件仍走 {@link TransitionSink}（各实例本地落审计）；手动锁可加
 *       Redis Pub/Sub 广播使 admin 操作全局生效。</li>
 * </ul>
 * 半开语义（冷却到期清零失败计数放行探测）是恢复能力的根，任何实现不得退化为
 * "过期仍判 L2" 的死锁态（见 DegradationRecoveryTest）。
 */
public interface DegradationState {

    /** 三级降级档位。 */
    enum Level { L0, L1, L2 }

    /** 档位转移观察者（审计落痕用）。默认空实现——单测构造纯状态机时不产生任何副作用。 */
    @FunctionalInterface
    interface TransitionSink {
        void on(Level from, Level to, String cause);
    }

    /** 当前档位（拉模型观察点：每次调用都可能触发一次转移落痕）。 */
    Level current();

    /** 在途 +1（请求进入编排）。 */
    void enter();

    /** 在途 -1（请求离开编排；负载回落→L0 在此落痕）。 */
    void exit();

    /** LLM 成功：清零连续失败计数。 */
    void llmSuccess();

    /** LLM 失败/429：累加连续失败计数，达阈进入 L2 冷却。 */
    void llmFailure();

    /** admin 手动锁定档位（演示确定性切换，优先于一切自动判定）。 */
    void manualSet(Level level);

    /** 解除手动锁定，恢复自动判定。 */
    void manualClear();

    /** 是否处于手动锁定状态。 */
    boolean isManual();

    /** 当前在途数。 */
    int inflightValue();

    /** 只读（Ops Console）：LLM 连续失败计数（熔断阈值进度 K/N）。 */
    int llmConsecutiveFailures();

    /** 熔断剩余冷却秒数，无冷却返回 0。 */
    long l2CooldownRemainingSeconds();
}
