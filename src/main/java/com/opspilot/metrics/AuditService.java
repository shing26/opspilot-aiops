package com.opspilot.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.auth.UserContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 合规审计事件（P4，ADR-0005）：写专用 logger（logback-spring.xml 挂
 * logs/audit.jsonl，gitignore+14 天滚动）。回答"谁在何时查过什么、答案最高触到
 * 哪个密级"——权限引擎层的可核查闭环。query 明文截断（问题文本属团队内部，不存
 * 答案全文以控制体积与敏感度）。
 */
@Component
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("opspilot.audit");
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param via 调用面来源（"sse"/"openai"/"search-api"），合规视角区分谁经哪个协议面进来；
     *            旧日志无此字段，消费端（daily_usage）按 dict .get 解析天然向后兼容。
     */
    public void log(UserContext user, String kind, String via, String query, String fingerprint,
                    String cacheHit, String mode, boolean refused, int maxResultAuthLevel, long tookMs) {
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("ts", System.currentTimeMillis());
            ev.put("ev", kind);
            ev.put("via", via);
            ev.put("sub", user == null ? "" : user.sub());
            ev.put("tenant", user == null ? "" : user.tenantId());
            ev.put("level", user == null ? 0 : user.authLevel());
            ev.put("q", query == null ? "" : query.substring(0, Math.min(200, query.length())));
            ev.put("fp", fingerprint);
            ev.put("cache_hit", cacheHit);
            ev.put("mode", mode);
            ev.put("refused", refused);
            ev.put("max_level", maxResultAuthLevel);
            ev.put("took_ms", tookMs);
            AUDIT.info(mapper.writeValueAsString(ev));
        } catch (Exception e) {
            // 审计失败不阻断业务链路，但必须留痕可排障
            LoggerFactory.getLogger(AuditService.class).warn("audit log failed: {}", e.toString());
        }
    }
}
