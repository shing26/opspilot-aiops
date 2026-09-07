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
 * mode: hybrid | es_only | vector_only。
 * 权限：检索密级恒等于 token 的 auth_level，客户端不可覆盖（防越权提权）。
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class SearchController {

    public record SearchReq(String query, String mode) {}

    private final HybridSearchService searchService;

    public SearchController(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchReq req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        if (req.query() == null || req.query().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        int level = user.authLevel();
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
