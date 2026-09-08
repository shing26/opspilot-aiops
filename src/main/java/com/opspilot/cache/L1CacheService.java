package com.opspilot.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;

/**
 * L1 精确缓存：key = tenant + authLevel + SHA-256(normalize(query))，TTL 2h。
 * 掺 authLevel 防止不同密级用户串答案（权限隔离延伸）。
 */
@Service
public class L1CacheService {

    private static final Pattern WS = Pattern.compile("\\s+");

    private final RedissonClient redisson;
    private final OpsPilotProperties props;

    public L1CacheService(RedissonClient redisson, OpsPilotProperties props) {
        this.redisson = redisson;
        this.props = props;
    }

    public static String normalize(String query) {
        return WS.matcher(query.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    public String key(String tenantId, int authLevel, String query) {
        String hash = sha256(normalize(query));
        return "cache:l1:" + tenantId + ":" + authLevel + ":" + hash;
    }

    public String get(String tenantId, int authLevel, String query) {
        RBucket<String> bucket = redisson.getBucket(key(tenantId, authLevel, query));
        return bucket.get();
    }

    public void put(String tenantId, int authLevel, String query, String answerJson) {
        RBucket<String> bucket = redisson.getBucket(key(tenantId, authLevel, query));
        bucket.set(answerJson, Duration.ofHours(props.cache().l1TtlHours()));
    }

    /** 清空全部 L1 缓存（验收隔离 / 演示重置）。 */
    public long flush() {
        return redisson.getKeys().deleteByPattern("cache:l1:*");
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
