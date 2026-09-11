package com.opspilot.resilience;

import java.time.Duration;
import java.time.LocalDate;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 每用户日配额（P4）：Redis INCR + 首次设 TTL，超限 429。
 * 目的不是防攻击而是成本护栏——为异常脚本/死循环请求兜底，给 LLM 预算设硬上限。
 * 默认 5000/天：50 人团队 ×100 次/人；同时必须容得下验收风暴场景（A2-5 的 500 并发同 sub，
 * 且它们被 Single-Flight 收敛后根本不烧 LLM——护栏防的是失控循环，不是去重后的并发）。
 * 仅挂 /chat/stream；/search 是评测/审计路径不限流。登录与管理端点豁免。
 */
@Component
public class QuotaService {

    private final RedissonClient redisson;
    private final long dailyLimit;

    public QuotaService(RedissonClient redisson,
                        @Value("${opspilot.quota.daily-limit:5000}") long dailyLimit) {
        this.redisson = redisson;
        this.dailyLimit = dailyLimit;
    }

    public void checkAndConsume(String sub) {
        RAtomicLong day = redisson.getAtomicLong("quota:" + sub + ":" + LocalDate.now());
        long n = day.incrementAndGet();
        if (n == 1) day.expire(Duration.ofHours(26)); // 跨时区余量，日切后自然换新 key
        if (n > dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "日请求配额已用尽（" + dailyLimit + "/天），明日重置或联系管理员");
        }
    }

    /** 只读水位（Ops Console）：当日已用；键不存在返回 0，绝不 INCR（读不得消耗配额）。 */
    public long usedToday(String sub) {
        return redisson.getAtomicLong("quota:" + sub + ":" + LocalDate.now()).get();
    }

    public long dailyLimit() { return dailyLimit; }
}
