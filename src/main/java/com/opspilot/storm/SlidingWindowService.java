package com.opspilot.storm;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;

/**
 * Redisson 滑动窗口去重：ZSET score=时间戳，窗口内同指纹仅首条穿透。
 * alert 来源用更长窗口（默认 60s），manual 用 30s。
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
     * 尝试穿透。返回 first=true 表示本请求是窗口内首条，应执行检索；
     * 否则为聚合命中，应等待 Single-Flight 结果。
     */
    public WindowResult tryAcquire(String fingerprint, String source) {
        int windowSec = "alert".equals(source)
                ? props.storm().alertWindowSeconds() : props.storm().windowSeconds();
        long now = System.currentTimeMillis();
        RScoredSortedSet<String> zset = redisson.getScoredSortedSet("storm:win:" + fingerprint);
        // 清理窗口外成员
        zset.removeRangeByScore(0, true, now - windowSec * 1000L, true);
        long size = zset.size();
        zset.add(now, now + "-" + Thread.currentThread().threadId());
        zset.expire(Duration.ofSeconds(windowSec + 10L));
        return new WindowResult(size == 0, size);
    }

    public long windowCount(String fingerprint) {
        RScoredSortedSet<String> zset = redisson.getScoredSortedSet("storm:win:" + fingerprint);
        return zset.size();
    }
}
