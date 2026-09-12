package com.opspilot.storm;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;

/**
 * Redisson 聚合计数窗口（生成质量包 Q4 语义矫正）：ZSET score=时间戳，维护
 * `来源×指纹` 的窗口内请求计数，供 dedup 计数与"30s 聚合"叙事。
 * alert 来源用更长窗口（默认 60s），manual 用 30s；key 掺 source，
 * 两源修剪互不污染计数。
 *
 * **窗口不是穿透排除闸门**：穿透的唯一闸门归属 Single-Flight（ADR-0003/0008）。
 * 旧口径"窗口内同指纹仅首条穿透"与实现不符，2026-09-13 grill 裁定废止
 * （CONTEXT.md「滑动窗口」词条为当前真相）。
 */
@Service
public class SlidingWindowService {

    public record WindowResult(boolean first, long aggregated) {}

    private final RedissonClient redisson;
    private final OpsPilotProperties props;

    public SlidingWindowService(RedissonClient redisson, OpsPilotProperties props) {
        this.redisson = redisson;
        this.props = props;
    }

    /**
     * 登记一次窗口内计数。first=true 表示该来源窗口内首条（计数从此起算）；
     * 调用方（ChatOrchestrator leader 路径）仅据此记 dedup_aggregated 指标——
     * 检索照常执行，等待/复用由 Single-Flight 负责。
     */
    public WindowResult tryAcquire(String fingerprint, String source) {
        int windowSec = "alert".equals(source)
                ? props.storm().alertWindowSeconds() : props.storm().windowSeconds();
        long now = System.currentTimeMillis();
        RScoredSortedSet<String> zset =
                redisson.getScoredSortedSet("storm:win:" + source + ":" + fingerprint);
        // 清理窗口外成员（本源窗口，不再误删别源成员）
        zset.removeRangeByScore(0, true, now - windowSec * 1000L, true);
        long size = zset.size();
        zset.add(now, now + "-" + Thread.currentThread().threadId());
        zset.expire(Duration.ofSeconds(windowSec + 10L));
        return new WindowResult(size == 0, size);
    }
}
