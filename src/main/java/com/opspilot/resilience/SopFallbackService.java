package com.opspilot.resilience;

import java.util.List;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import com.opspilot.retrieval.EsSearchService;

/** Level 2 兜底：从 Redis 预热的静态 SOP 止损清单直出，零 LLM 调用。 */
@Service
public class SopFallbackService {

    private final RedissonClient redisson;

    public SopFallbackService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 按查询中的错误码/服务名匹配静态 SOP，返回拼接文本；无匹配返回 null。
     *  P1：所有键按请求方 tenant 分片——L2 降级直出同样不能成为跨租户泄漏面。 */
    public String lookup(String query, String service, String tenant) {
        String ns = "sop:" + (tenant == null ? "" : tenant) + ":";
        var steps = redisson.getMap(ns + "steps");
        if (steps.isEmpty()) return null;
        List<String> codes = EsSearchService.extractErrorCodes(query);
        StringBuilder sb = new StringBuilder();
        if (!codes.isEmpty()) {
            for (String code : codes) {
                for (Object docId : redisson.getSet(ns + "code:" + code).readAll()) {
                    appendStep(sb, steps.get(docId));
                }
            }
        }
        if (sb.isEmpty() && service != null && !service.isBlank()) {
            for (Object docId : redisson.getSet(ns + "service:" + service).readAll()) {
                appendStep(sb, steps.get(docId));
            }
        }
        if (sb.isEmpty()) {
            // 兜底：返回本租户任意一篇含错误码上下文的 SOP（绝不越租户）
            steps.readAllValues().stream().limit(2).forEach(v -> appendStep(sb, v));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void appendStep(StringBuilder sb, Object text) {
        if (text != null) sb.append(text).append("\n\n");
    }
}
