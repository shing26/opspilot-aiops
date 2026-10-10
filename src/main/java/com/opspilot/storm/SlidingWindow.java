package com.opspilot.storm;

/**
 * 滑动窗口契约（ADR-0003）：`来源×指纹` 的窗口内计数，供 dedup 计数叙事。
 * <b>窗口不是穿透排除闸门</b>——穿透的唯一闸门是 {@link SingleFlight}。
 *
 * <p><b>实现双轨</b>（ADR-0015）：
 * <ul>
 *   <li><b>Redis ZSET</b>（当前默认，{@code SlidingWindowService}）：Redisson
 *       {@code RScoredSortedSet}，天然分布式，多实例共享同一窗口。</li>
 *   <li><b>进程内存</b>（压测/离线备选）：ConcurrentSkipListMap + 惰性清理，
 *       仅单 JVM 正确。接口已按分布式优先设计，降级实现只需换 storage。</li>
 * </ul>
 */
public interface SlidingWindow {

    /** 窗口登记结果：{@code first} = 该来源窗口内首条；{@code aggregated} = 窗口内累计数。 */
    record WindowResult(boolean first, long aggregated) {}

    /**
     * 登记一次窗口内计数。调用方（ChatOrchestrator leader 路径）仅据此记 dedup 指标，
     * 不据此排除请求——等待/复用由 {@link SingleFlight} 负责。
     */
    WindowResult tryAcquire(String fingerprint, String source);
}
