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
     * 进程内有界事件环（Ops Console 数据流面，ADR-0009）：writeEvent 是全部审计事件的
     * 唯一必经出口，在此挂 200 条 ring buffer ——**不改落盘行为**。有界即有轮转，
     * 轮转必有丢失：recentSince 以 truncated 显式告知（客户端游标早于最老驻留事件，
     * 或服务重启游标越过 maxSeq 的"回绕"），禁止静默空洞。seq 全局单调，与 ts 无关
     * （同毫秒乱序是文件行的既实现实，面板消费必须用 seq）。
     */
    private static final int RING_CAP = 200;
    private final java.util.ArrayDeque<Map<String, Object>> ring = new java.util.ArrayDeque<>();
    private long seq;

    /** 自 lastSeq 以来的增量事件；truncated=有丢失（轮转或重启）。 */
    public synchronized RecentResult recentSince(long lastSeq, int limit) {
        long maxSeq = seq;
        boolean restart = lastSeq > maxSeq; // 客户端游标超前：服务重启（seq 归零）
        if (restart) {
            lastSeq = 0;                    // 当前驻留事件全给，游标不得卡死
        }
        boolean truncated = restart || (!ring.isEmpty() && lastSeq < seq - ring.size());
        int capped = Math.max(1, Math.min(limit, RING_CAP));
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> ev : ring) {
            long s = ((Number) ev.get("seq")).longValue();
            if (s > lastSeq) {
                out.add(ev);
                if (out.size() >= capped) break;
            }
        }
        return new RecentResult(java.util.List.copyOf(out), maxSeq, truncated);
    }

    public record RecentResult(java.util.List<Map<String, Object>> events, long maxSeq, boolean truncated) {}

    /**
     * @param via 调用面来源（"sse"/"openai"/"search-api"），合规视角区分谁经哪个协议面进来；
     *            旧日志无此字段，消费端（daily_usage）按 dict .get 解析天然向后兼容。
     */
    public void log(UserContext user, String kind, String via, String query, String fingerprint,
                    String cacheHit, String mode, boolean refused, int maxResultAuthLevel, long tookMs) {
        log(user, kind, via, query, fingerprint, cacheHit, mode, refused, maxResultAuthLevel, tookMs, null);
    }

    /**
     * 完整形态：srcTenant = 回放/共享载荷的来源租户（QA P1-2 补"命中来源"宣称缺口）。
     * 仅缓存/Single-Flight 回放路径携带（正常恒等于请求者租户；不等即 P0-1 类复发的可 grep 告警面），
     * 普通生成请求传 null 不落字段，避免全量噪音。
     */
    public void log(UserContext user, String kind, String via, String query, String fingerprint,
                    String cacheHit, String mode, boolean refused, int maxResultAuthLevel, long tookMs,
                    String srcTenant) {
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
        if (srcTenant != null) ev.put("src_tenant", srcTenant);
        writeEvent(ev);
    }

    /**
     * 鉴权拒绝留痕（QA P1-2：此前"每请求一行"只覆盖成功业务请求——401/403/登录失败全是黑洞，
     * 攻击探测零可查）。sub 仅在 token 可解析时携带（未知/畸形凭证不编造身份）。
     *
     * @param outcome denied（守卫面 401）/ login_failed（登录面，含暴力锁定）
     */
    public void logAuthDenied(String sub, String path, String outcome, String reason) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "auth");
        ev.put("outcome", outcome);
        if (sub != null && !sub.isBlank()) ev.put("sub", sub);
        ev.put("path", path);
        ev.put("reason", reason);
        writeEvent(ev);
    }

    /** 管理面动作留痕（QA P1-2）：破坏性端点的成功与被拒都必须可回溯。 */
    public void logAdmin(UserContext user, String action, String outcome, String detail) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "admin");
        ev.put("sub", user == null ? "" : user.sub());
        ev.put("tenant", user == null ? "" : user.tenantId());
        ev.put("level", user == null ? 0 : user.authLevel());
        ev.put("role", user == null ? "" : user.role());
        ev.put("action", action);
        ev.put("outcome", outcome);
        if (detail != null) ev.put("detail", detail.substring(0, Math.min(200, detail.length())));
        writeEvent(ev);
    }

    /** 请求体校验失败留痕（QA 小周 P2-2：400 既无信息又无痕，用户改请求如坠迷雾）。 */
    public void logInvalid(UserContext user, String path, String message) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", System.currentTimeMillis());
        ev.put("ev", "invalid");
        ev.put("sub", user == null ? "" : user.sub());
        ev.put("tenant", user == null ? "" : user.tenantId());
        ev.put("level", user == null ? 0 : user.authLevel());
        ev.put("path", path);
        if (message != null) ev.put("msg", message.substring(0, Math.min(200, message.length())));
        writeEvent(ev);
    }

    private void writeEvent(Map<String, Object> ev) {
        try {
            synchronized (this) {
                ev.put("seq", ++seq);
                ring.addLast(ev);
                while (ring.size() > RING_CAP) ring.removeFirst();
            }
            AUDIT.info(mapper.writeValueAsString(ev));
        } catch (Exception e) {
            // 审计失败不阻断业务链路，但必须留痕可排障
            LoggerFactory.getLogger(AuditService.class).warn("audit log failed: {}", e.toString());
        }
    }
}
