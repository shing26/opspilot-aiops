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
    private final com.opspilot.metrics.AuditService audit;

    public SearchController(HybridSearchService searchService, com.opspilot.metrics.AuditService audit) {
        this.searchService = searchService;
        this.audit = audit;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchReq req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        // 配额只挂 /chat/stream（LLM 成本护栏）；/search 仅 embedding+rerank，是评测/审计路径，不限流
        if (req.query() == null || req.query().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        int level = user.authLevel();
        // 纵深防御：JwtAuthFilter 已在入口拒绝 level<1（401）；此处兜底拦截任何绕过 filter 的调用路径
        if (level < 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "auth_level 非法（须 >=1）");
        }
        String mode = req.mode() == null ? "hybrid" : req.mode();
        if (!java.util.Set.of("hybrid", "es_only", "vector_only").contains(mode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "mode 仅允许 hybrid|es_only|vector_only");
        }
        SearchOutcome outcome = searchService.search(req.query(), user.tenantId(), level, mode);
        audit.log(user, "search", req.query(), null, "none", outcome.mode(),
                outcome.chunks().isEmpty(),
                outcome.chunks().stream().mapToInt(c -> c.authLevel()).max().orElse(0),
                outcome.tookMs());
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
