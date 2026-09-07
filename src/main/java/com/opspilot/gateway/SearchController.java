package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.retrieval.HybridSearchService;
import com.opspilot.retrieval.ScoredChunk;
import com.opspilot.retrieval.SearchOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 非流式纯检索端点：供评测脚本（HitRate/MRR）与降级演示使用，不触发 LLM。
 * mode: hybrid | es_only | vector_only。authLevel<=0 时引擎层不过滤（仅评测内部用）。
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class SearchController {

    public record SearchReq(String query, String mode, Integer authLevelOverride) {}

    private final HybridSearchService searchService;

    public SearchController(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchReq req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        int level = req.authLevelOverride() != null ? req.authLevelOverride() : user.authLevel();
        String mode = req.mode() == null ? "hybrid" : req.mode();
        SearchOutcome outcome = searchService.search(req.query(), level, mode);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mode", outcome.mode());
        resp.put("fast_path", outcome.fastPath());
        resp.put("degraded", outcome.degraded());
        resp.put("took_ms", outcome.tookMs());
        resp.put("results", outcome.chunks().stream().map(c -> Map.of(
                "chunk_id", c.chunkId(),
                "doc_id", c.docId(),
                "breadcrumb", c.breadcrumb(),
                "service", c.service(),
                "auth_level", c.authLevel(),
                "es_score", c.esScore(),
                "vector_score", c.vectorScore(),
                "rrf_score", c.rrfScore(),
                "rerank_score", c.rerankScore())).toList());
        return resp;
    }
}
