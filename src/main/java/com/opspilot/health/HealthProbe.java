package com.opspilot.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.qdrant.client.QdrantClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;

/**
 * 依赖健康探针（部署级健康，与 /actuator/health 的聚合互补）：一次探测回答
 * "Redis 连得上吗 / Qdrant 集合在吗 / ES 活着吗 / 现在是 live 还是 mock / 索引里有多少块"。
 * 每个探测 3s 超时自吞异常返回 DOWN——健康检查本身绝不能把服务拖死。
 */
@Component
public class HealthProbe {

    public record Comp(String status, String detail) {
        static Comp up(String detail) { return new Comp("UP", detail); }
        static Comp down(Exception e) { return new Comp("DOWN", brief(e)); }
    }

    private final ElasticsearchClient es;
    private final QdrantClient qdrant;
    private final RedissonClient redisson;
    private final OpsPilotProperties props;

    public HealthProbe(ElasticsearchClient es, QdrantClient qdrant,
                       RedissonClient redisson, OpsPilotProperties props) {
        this.es = es;
        this.qdrant = qdrant;
        this.redisson = redisson;
        this.props = props;
    }

    public Comp redis() {
        try {
            redisson.<String>getBucket("health:ping").isExists(); // 单键 GET，不 SCAN
            return Comp.up("reachable");
        } catch (Exception e) {
            return Comp.down(e);
        }
    }

    public Comp qdrant() {
        try {
            var info = qdrant.getCollectionInfoAsync(props.qdrant().collection())
                    .get(3, TimeUnit.SECONDS);   // 查询侧传的是别名，这里一并验证别名解析
            return Comp.up(info.getPointsCount() + " pts");
        } catch (Exception e) {
            return Comp.down(e);
        }
    }

    public Comp es() {
        try {
            if (!es.ping().value()) return new Comp("DOWN", "no response");
            // 显式 request 重载（Consumer 版是 final 方法，不利于 mock 拦截）
            var req = co.elastic.clients.elasticsearch.core.CountRequest.of(
                    b -> b.index(props.es().index())); // 别名=读路径同款
            long n = es.count(req).count();
            return Comp.up(n + " docs");
        } catch (Exception e) {
            return Comp.down(e);
        }
    }

    /** 细节汇总（供 /admin/health 与预检脚本消费）。 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redis", redis());
        out.put("qdrant", qdrant());
        out.put("es", es());
        var ds = props.dashscope();
        out.put("live_mode", ds.live());
        out.put("backend", Map.of(
                "embedding", ds.live() ? "dashscope:" + ds.embeddingModel() : "mock-lexical-hash",
                "rerank", ds.live() ? "dashscope:" + ds.rerankModel() : "mock-idf-coverage",
                "llm", ds.live() ? "dashscope:" + ds.llmModel() : "mock-template"));
        boolean allUp = ((Comp) out.get("redis")).status().equals("UP")
                && ((Comp) out.get("qdrant")).status().equals("UP")
                && ((Comp) out.get("es")).status().equals("UP");
        out.put("status", allUp ? "UP" : "DEGRADED");
        return out;
    }

    private static String brief(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
