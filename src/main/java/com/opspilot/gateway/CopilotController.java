package com.opspilot.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.gateway.dto.ChatRequest;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.llm.LlmClient;
import com.opspilot.llm.PromptAssembler;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.resilience.DegradationStateMachine;
import com.opspilot.resilience.DegradationStateMachine.Level;
import com.opspilot.resilience.SopFallbackService;
import com.opspilot.retrieval.HybridSearchService;
import com.opspilot.retrieval.ScoredChunk;
import com.opspilot.retrieval.SearchOutcome;
import com.opspilot.storm.FingerprintService;
import com.opspilot.storm.SingleFlightRegistry;
import com.opspilot.storm.SlidingWindowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 排障 Copilot 流式入口（单链路，ADR-0004）。
 * 编排：L1 → L2 → 滑动窗口去重 → Single-Flight → 双路召回 → Rerank → LLM 流式。
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class CopilotController {

    private static final Logger log = LoggerFactory.getLogger(CopilotController.class);

    private final L1CacheService l1;
    private final L2SemanticCacheService l2;
    private final FingerprintService fingerprintService;
    private final SlidingWindowService slidingWindow;
    private final SingleFlightRegistry singleFlight;
    private final HybridSearchService searchService;
    private final EmbeddingClient embedding;
    private final LlmClient llm;
    private final PromptAssembler promptAssembler;
    private final DegradationStateMachine degrade;
    private final SopFallbackService sopFallback;
    private final OpsMetrics metrics;
    private final ExecutorService vt;
    private final OpsPilotProperties props;
    private final SseEvents events;
    private final ObjectMapper mapper = new ObjectMapper();

    public CopilotController(L1CacheService l1, L2SemanticCacheService l2,
                             FingerprintService fingerprintService, SlidingWindowService slidingWindow,
                             SingleFlightRegistry singleFlight, HybridSearchService searchService,
                             EmbeddingClient embedding, LlmClient llm, PromptAssembler promptAssembler,
                             DegradationStateMachine degrade, SopFallbackService sopFallback,
                             OpsMetrics metrics, ExecutorService virtualThreadExecutor,
                             OpsPilotProperties props, SseEvents events) {
        this.l1 = l1; this.l2 = l2; this.fingerprintService = fingerprintService;
        this.slidingWindow = slidingWindow; this.singleFlight = singleFlight;
        this.searchService = searchService; this.embedding = embedding; this.llm = llm;
        this.promptAssembler = promptAssembler; this.degrade = degrade; this.sopFallback = sopFallback;
        this.metrics = metrics; this.vt = virtualThreadExecutor; this.props = props;
        this.events = events;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@jakarta.validation.Valid @RequestBody ChatRequest req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        SseEmitter emitter = new SseEmitter(120_000L);
        metrics.request();
        vt.execute(() -> {
            degrade.enter();
            try {
                handle(req, user, emitter);
            } catch (Exception e) {
                log.warn("stream error: {}", e.getClass().getSimpleName());
                events.trySend(emitter, "error", Map.of("code", "PIPELINE_ERROR", "message", "排障链路异常，请重试或联系值班 SRE"));
                emitter.complete();
            } finally {
                degrade.exit();
            }
        });
        return emitter;
    }

    private void handle(ChatRequest req, UserContext user, SseEmitter emitter) throws Exception {
        String query = req.query();
        String source = req.sourceOrDefault();
        String fp = fingerprintService.fingerprint(req.service(), req.env(), query);
        long t0 = System.nanoTime();

        // Single-Flight 为主闸门：同 (fingerprint, authLevel) 并发仅 1 个 leader 穿透，
        // 缓存检查在 leader 内部完成，杜绝「L1 检查后、flight 移除前」的竞态重复穿透。
        String sfKey = fp + ":" + user.authLevel();
        SingleFlightRegistry.Registration reg = singleFlight.getOrCreate(sfKey);
        if (!reg.leader()) {
            String shared = reg.future().get(90, TimeUnit.SECONDS);
            metrics.dedupAggregated();
            replay(emitter, shared, "none", true, fp, t0);
            return;
        }

        // leader：滑动窗口计数（30s 聚合叙事）→ L1 → L2 → 全链路
        try {
            SlidingWindowService.WindowResult win = slidingWindow.tryAcquire(fp, source);
            if (!win.first()) metrics.dedupAggregated();

            String cached = l1.get(user.tenantId(), user.authLevel(), query);
            if (cached != null) {
                metrics.l1Hit();
                reg.future().complete(cached);
                replay(emitter, cached, "L1", false, fp, t0);
                return;
            }
            L2SemanticCacheService.CacheHit l2hit = l2.lookup(query, user.tenantId(), user.authLevel());
            if (l2hit != null) {
                metrics.l2Hit();
                String json = l2hit.payloadJson();   // 含 refs，回放溯源完整
                l1.put(user.tenantId(), user.authLevel(), query, json);
                reg.future().complete(json);
                replay(emitter, json, "L2", false, fp, t0);
                return;
            }
            String json = runPipeline(req, user, emitter, fp, t0);
            reg.future().complete(json);
        } catch (Exception e) {
            reg.future().completeExceptionally(e);
            throw e;
        } finally {
            singleFlight.finish(sfKey, reg.future());
        }
    }

    /** leader 全链路：降级判定 → 检索 → LLM 流式（或 SOP 直出）→ 写缓存。返回答案 JSON。 */
    private String runPipeline(ChatRequest req, UserContext user, SseEmitter emitter,
                               String fp, long t0) throws Exception {
        Level level = degrade.current();
        String query = req.query();

        // Level 2：熔断 LLM，直出静态 SOP（分片流式，保留打字机体验）
        if (level == Level.L2) {
            metrics.sopFallback();
            String sop = sopFallback.lookup(query, req.service(), user.tenantId());
            String answer = sop != null ? sop : "系统高负载，已触发熔断降级，暂无可用止损清单，请联系值班 SRE。";
            AnswerPayload p = new AnswerPayload(answer, List.of(), "sop_fallback", false, user.authLevel(), user.tenantId());
            String json = mapper.writeValueAsString(p);
            events.emitMeta(emitter, fp, "none", level, false, false, 0);
            long sopFirstDeltaNano = System.nanoTime();
            events.streamInChunks(emitter, answer);
            events.emitDone(emitter, t0, sopFirstDeltaNano, List.of());
            l1.put(user.tenantId(), user.authLevel(), query, json);
            return json;
        }

        // 检索（L1 降级走 es_only）
        String mode = level == Level.L1 ? "es_only" : "hybrid";
        SearchOutcome outcome = searchService.search(query, user.tenantId(), user.authLevel(), mode);
        List<ScoredChunk> chunks = outcome.chunks();

        // 置信度空态门控：零召回，或非快路径且 Top-1 相关度低于阈值 → 显式拒答，不调 LLM。
        // 快路径（精确符号命中）天然高置信，豁免门控。
        double minRel = props.retrieval().minRelevance();
        boolean lowConfidence = chunks.isEmpty()
                || (!outcome.fastPath() && outcome.topRelevance() < minRel);
        if (lowConfidence) {
            metrics.lowConfidence();
            String reason = chunks.isEmpty() ? "未匹配到任何参考"
                    : String.format("检索置信度不足（%.2f<%.2f）", outcome.topRelevance(), minRel);
            String refusal = "当前知识库无足够相关的参考（" + reason + "），无法可靠作答。"
                    + "请补充错误码（如 50012_DB_TIMEOUT）或服务名后重试，或联系值班 SRE。";
            AnswerPayload p = new AnswerPayload(refusal, List.of(), outcome.mode(), false, user.authLevel(), user.tenantId());
            String json = mapper.writeValueAsString(p);
            events.emitMeta(emitter, fp, "none", level, false, false, outcome.tookMs());
            long refusalNano = System.nanoTime();
            events.emitDelta(emitter, refusal);
            events.emitDone(emitter, t0, refusalNano, List.of());
            l1.put(user.tenantId(), user.authLevel(), query, json);
            return json;
        }

        int maxAuth = chunks.stream().mapToInt(ScoredChunk::authLevel).max().orElse(user.authLevel());

        events.emitMeta(emitter, fp, "none", level, outcome.fastPath(), false, outcome.tookMs());

        // LLM 流式
        metrics.llmCall();
        StringBuilder full = new StringBuilder();
        AtomicLong firstTokenNano = new AtomicLong(0);
        List<AnswerPayload.Ref> refs = new ArrayList<>();
        for (ScoredChunk c : chunks) {
            refs.add(new AnswerPayload.Ref(c.chunkId(), c.breadcrumb(), c.service()));
        }
        try {
            String answer = llm.streamChat(promptAssembler.build(query, chunks), token -> {
                firstTokenNano.compareAndSet(0, System.nanoTime());
                full.append(token);
                events.emitDelta(emitter, token);
            });
            degrade.llmSuccess();
            AnswerPayload p = new AnswerPayload(answer, refs, outcome.mode(), outcome.fastPath(), maxAuth, user.tenantId());
            String json = mapper.writeValueAsString(p);
            l1.put(user.tenantId(), user.authLevel(), query, json);
            // L2 写入：存完整 payload（含 refs），复用检索时已算好的 query 向量
            float[] qv = embedding.embedOne(query);
            l2.store(query, qv, json, maxAuth, user.tenantId());
            long ftNano = firstTokenNano.get();
            events.emitDone(emitter, t0, ftNano == 0 ? System.nanoTime() : ftNano, refs);
            return json;
        } catch (LlmClient.LlmRateLimitedException e) {
            metrics.llmRateLimited();
            degrade.llmFailure();
            throw e;
        } catch (Exception e) {
            degrade.llmFailure();
            throw e;
        }
    }

    /** 缓存回放 / Single-Flight 共享答案 → 分片打字机下发（SSE 帧格式见 SseEvents）。 */
    private void replay(SseEmitter emitter, String json, String cacheHit, boolean deduplicated,
                        String fp, long t0) throws Exception {
        AnswerPayload p = mapper.readValue(json, AnswerPayload.class);
        events.emitMeta(emitter, fp, cacheHit, degrade.current(), p.fastPath(), deduplicated, 0);
        long replayFirstDeltaNano = System.nanoTime();
        events.streamInChunks(emitter, p.answer());
        events.emitDone(emitter, t0, replayFirstDeltaNano, p.refs());
    }
}
