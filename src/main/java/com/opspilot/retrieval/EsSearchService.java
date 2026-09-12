package com.opspilot.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;

/**
 * ES 倒排检索：精确符号（错误码/类名/endpoint）走 keyword term 加权，
 * 正文走 match；auth_level 硬过滤在引擎层注入（权限隔离第一道闸）。
 */
@Component
public class EsSearchService {

    public static final Pattern ERROR_CODE = Pattern.compile("\\b\\d{5}_[A-Z][A-Z0-9_]*\\b");
    public static final Pattern FQCN = Pattern.compile("\\b(?:[a-z][a-z0-9]*\\.){2,}[A-Z][A-Za-z0-9]*\\b");

    private final ElasticsearchClient es;
    private final OpsPilotProperties props;

    public EsSearchService(ElasticsearchClient es, OpsPilotProperties props) {
        this.es = es;
        this.props = props;
    }

    public static List<String> extractErrorCodes(String query) {
        List<String> out = new ArrayList<>();
        Matcher m = ERROR_CODE.matcher(query);
        while (m.find()) out.add(m.group());
        return out;
    }

    /**
     * 强标识符 = 错误码 ∨ 全限定名（CONTEXT.md 词条；词法与快路径共用同一形状，
     * 消费方不得复制正则——单一事实源纪律）。当前消费方：防断言语态门
     * （生成质量包 Q3：弱问题只能假设语态，见 PromptAssembler）。
     * 与快路径判据的区别：快路径只认错误码+keyword 精确命中，FQCN 不参与其判定。
     */
    public static boolean hasStrongIdentifier(String query) {
        return query != null && (ERROR_CODE.matcher(query).find() || FQCN.matcher(query).find());
    }

    public List<ScoredChunk> search(String query, String tenant, int authLevel, int topK) throws Exception {
        List<String> codes = extractErrorCodes(query);
        List<Query> should = new ArrayList<>();
        if (!codes.isEmpty()) {
            should.add(Query.of(q -> q.terms(t -> t
                    .field("metadata.error_codes")
                    .terms(v -> v.value(codes.stream()
                            .map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))
                    .boost(20f))));
        }
        if (FQCN.matcher(query).find()) {
            should.add(Query.of(q -> q.match(m -> m.field("text").query(query).boost(8f))));
        }
        should.add(Query.of(q -> q.matchPhrase(mp -> mp.field("text").query(query).boost(5f))));
        should.add(Query.of(q -> q.match(m -> m.field("text").query(query))));

        SearchResponse<Map> resp = es.search(s -> s
                .index(props.es().index())
                .size(topK)
                .query(q -> q.bool(b -> b
                        .minimumShouldMatch("1")
                        // P1 租户显式化：等值 term 与 auth_level range 同为 filter 硬条件（失败关闭：
                        // tenant 为 null/空时 term 不匹配任何文档 → 空集，与 Qdrant 侧语义一致）
                        .filter(f -> f.term(t -> t.field("metadata.tenant")
                                .value(co.elastic.clients.elasticsearch._types.FieldValue
                                        .of(tenant == null ? "" : tenant))))
                        .filter(f -> f.range(r -> r.field("metadata.auth_level")
                                .lte(co.elastic.clients.json.JsonData.of(authLevel))))
                        .should(should))),
                Map.class);

        List<ScoredChunk> out = new ArrayList<>();
        for (Hit<Map> h : resp.hits().hits()) {
            out.add(fromHit(h));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private ScoredChunk fromHit(Hit<Map> h) {
        Map<String, Object> src = h.source();
        Map<String, Object> meta = (Map<String, Object>) src.getOrDefault("metadata", Map.of());
        return new ScoredChunk(
                (String) src.get("chunk_id"),
                (String) src.get("doc_id"),
                (String) src.get("type"),
                (String) src.get("text"),
                (String) src.get("breadcrumb"),
                (String) meta.getOrDefault("service", ""),
                (List<String>) meta.getOrDefault("error_codes", List.of()),
                ((Number) meta.getOrDefault("auth_level", 1)).intValue(),
                new ScoredChunk.Scores(h.score() == null ? 0 : h.score(), 0, 0, 0));
    }
}
