package com.opspilot.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.auth.UserContext;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.gateway.dto.ChatRequest;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.llm.LlmClient;
import com.opspilot.llm.PromptAssembler;
import com.opspilot.metrics.AuditService;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.resilience.DegradationStateMachine;
import com.opspilot.resilience.DegradationStateMachine.Level;
import com.opspilot.resilience.QuotaService;
import com.opspilot.resilience.SopFallbackService;
import com.opspilot.retrieval.HybridSearchService;
import com.opspilot.retrieval.ScoredChunk;
import com.opspilot.retrieval.SearchOutcome;
import com.opspilot.storm.FingerprintService;
import com.opspilot.storm.SingleFlightRegistry;
import com.opspilot.storm.SlidingWindowService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 排障编排核心（P1-P4 之后自 CopilotController 抽出，sink 化）：
 * quota/计数前置 → 指纹 → Single-Flight → 滑动窗口 → L1 → L2 → 检索（tenant+level 双硬过滤）
 * → 置信度门控 → LLM 流式/降级直出 → 缓存回写 → 审计。
 * /api/v1/copilot/chat/stream（SSE）与 /v1/chat/completions（OpenAI）共享本链路——
 * 缓存/风暴收敛/熔断/吊销/配额/审计在两个协议面语义完全一致。
 */
@Component
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

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
    private final AuditService audit;
    private final QuotaService quota;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatOrchestrator(L1CacheService l1, L2SemanticCacheService l2,
                            FingerprintService fingerprintService, SlidingWindowService slidingWindow,
                            SingleFlightRegistry singleFlight, HybridSearchService searchService,
                            EmbeddingClient embedding, LlmClient llm, PromptAssembler promptAssembler,
                            DegradationStateMachine degrade, SopFallbackService sopFallback,
                            OpsMetrics metrics, ExecutorService virtualThreadExecutor,
                            OpsPilotProperties props, AuditService audit, QuotaService quota) {
        this.l1 = l1; this.l2 = l2; this.fingerprintService = fingerprintService;
        this.slidingWindow = slidingWindow; this.singleFlight = singleFlight;
        this.searchService = searchService; this.embedding = embedding; this.llm = llm;
        this.promptAssembler = promptAssembler; this.degrade = degrade; this.sopFallback = sopFallback;
        this.metrics = metrics; this.vt = virtualThreadExecutor; this.props = props;
        this.audit = audit; this.quota = quota;
    }

    /** 同步前置（必须在 servlet 线程调用）：配额 429 与请求计数要能在 HTTP 响应面上抛出。 */
    public void precheck(UserContext user) {
        quota.checkAndConsume(user.sub());
        metrics.request();
    }

    /** 异步编排：错误经 sink.error 收尾（协议各自决定呈现），degrade.enter/exit 维护在飞计数。 */
    public void submit(ChatRequest req, UserContext user, ChatSink sink, String via) {
        vt.execute(() -> {
            degrade.enter();
            try {
                handle(req, user, sink, via);
            } catch (Exception e) {
                log.warn("stream error: {}", e.getClass().getSimpleName());
                sink.error(e);
            } finally {
                degrade.exit();
            }
        });
    }

    private void handle(ChatRequest req, UserContext user, ChatSink sink, String via) throws Exception {
        String query = req.query();
        String source = req.sourceOrDefault();
        String fp = fingerprintService.fingerprint(req.service(), req.env(), query);
        long t0 = System.nanoTime();

        // Single-Flight 为主闸门：同 (tenant, fingerprint, authLevel) 并发仅 1 个 leader 穿透，
        // 缓存检查在 leader 内部完成，杜绝「L1 检查后、flight 移除前」的竞态重复穿透。
        // 2026-09-11 QA P0-1：key 曾缺 tenant——跨租户同密级并发风暴下 follower 回放 leader
        // 全文+引用（引擎层过滤管不到"共享在途结果"这条旁路）。权限维度必须完备：
        // 租户×密级（ADR-0008），与 L1 key 的 cache:l1:<tenant>:<level>: 同构。
        String sfKey = singleFlightKey(user, fp);
        SingleFlightRegistry.Registration reg = singleFlight.getOrCreate(sfKey);
        if (!reg.leader()) {
            String shared = reg.future().get(90, TimeUnit.SECONDS);
            metrics.dedupAggregated();
            replay(sink, shared, "none", true, fp, t0, user, query, via);
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
                replay(sink, cached, "L1", false, fp, t0, user, query, via);
                return;
            }
            L2SemanticCacheService.CacheHit l2hit = l2.lookup(query, user.tenantId(), user.authLevel());
            if (l2hit != null) {
                metrics.l2Hit();
                String json = l2hit.payloadJson();   // 含 refs，回放溯源完整
                l1.put(user.tenantId(), user.authLevel(), query, json);
                reg.future().complete(json);
                replay(sink, json, "L2", false, fp, t0, user, query, via);
                return;
            }
            String json = runPipeline(req, user, sink, fp, t0, via);
            reg.future().complete(json);
        } catch (Exception e) {
            reg.future().completeExceptionally(e);
            throw e;
        } finally {
            singleFlight.finish(sfKey, reg.future());
        }
    }

    /** leader 全链路：降级判定 → 检索 → LLM 流式（或 SOP 直出）→ 写缓存。返回答案 JSON。 */
    private String runPipeline(ChatRequest req, UserContext user, ChatSink sink,
                               String fp, long t0, String via) throws Exception {
        Level level = degrade.current();
        String query = req.query();

        // Level 2：熔断 LLM，直出静态 SOP（分片流式，保留打字机体验）。
        // P4：SOP 直出**不写 L1**——降级期答案是应急兜底，若入缓存，恢复 auto 后同 query
        // 仍会回放 SOP（A2-6 实测坑，DEMO.md 幕⑥同源）；成本护栏因此只保真内容进缓存。
        if (level == Level.L2) {
            metrics.sopFallback();
            String sop = sopFallback.lookup(query, req.service(), user.tenantId());
            String answer = sop != null ? sop : "系统高负载，已触发熔断降级，暂无可用止损清单，请联系值班 SRE。";
            AnswerPayload p = new AnswerPayload(answer, List.of(), "sop_fallback", false, user.authLevel(), user.tenantId());
            String json = mapper.writeValueAsString(p);
            sink.meta(fp, "none", level, false, false, 0);
            long sopFirstDeltaNano = System.nanoTime();
            sink.streamInChunks(answer);
            sink.done(t0, sopFirstDeltaNano, List.of());
            audit.log(user, "chat", via, query, fp, "none", "sop_fallback", false, user.authLevel(),
                    (System.nanoTime() - t0) / 1_000_000);
            return json;
        }

        // 检索（L1 降级走 es_only；tenant+authLevel 双硬过滤在引擎层）
        String mode = level == Level.L1 ? "es_only" : "hybrid";
        SearchOutcome outcome = searchService.search(query, user.tenantId(), user.authLevel(), mode);
        List<ScoredChunk> chunks = outcome.chunks();

        // 置信度空态门控：零召回，或非快路径且 Top-1 相关度低于阈值 → 显式拒答，不调 LLM。
        double minRel = props.retrieval().minRelevance();
        boolean lowConfidence = chunks.isEmpty()
                || (!outcome.fastPath() && outcome.topRelevance() < minRel);
        if (lowConfidence) {
            metrics.lowConfidence();
            // QA P2-5：分数与阈值是内部量纲，外显=门控 oracle（攻击者可精调话术卡到阈值之上）；
            // 只进日志与 metrics，对外话术不回显。
            log.debug("low-confidence refusal: top={} threshold={} empty={}",
                    outcome.topRelevance(), minRel, chunks.isEmpty());
            String reason = chunks.isEmpty() ? "未匹配到任何参考" : "检索置信度不足";
            String refusal = "当前知识库无足够相关的参考（" + reason + "），无法可靠作答。"
                    + "请补充错误码（如 50012_DB_TIMEOUT）或服务名后重试，或联系值班 SRE。";
            AnswerPayload p = new AnswerPayload(refusal, List.of(), outcome.mode(), false, user.authLevel(), user.tenantId());
            String json = mapper.writeValueAsString(p);
            sink.meta(fp, "none", level, false, false, outcome.tookMs());
            long refusalNano = System.nanoTime();
            sink.delta(refusal);
            sink.done(t0, refusalNano, List.of());
            l1.put(user.tenantId(), user.authLevel(), query, json);
            audit.log(user, "chat", via, query, fp, "none", outcome.mode(), true, 0,
                    (System.nanoTime() - t0) / 1_000_000);
            return json;
        }

        int maxAuth = chunks.stream().mapToInt(ScoredChunk::authLevel).max().orElse(user.authLevel());

        sink.meta(fp, "none", level, outcome.fastPath(), false, outcome.tookMs());

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
                sink.delta(token);
            });
            degrade.llmSuccess();
            AnswerPayload p = new AnswerPayload(answer, refs, outcome.mode(), outcome.fastPath(), maxAuth, user.tenantId());
            String json = mapper.writeValueAsString(p);
            l1.put(user.tenantId(), user.authLevel(), query, json);
            // L2 写入：存完整 payload（含 refs），复用检索时已算好的 query 向量
            float[] qv = embedding.embedOne(query);
            l2.store(query, qv, json, maxAuth, user.tenantId());
            long ftNano = firstTokenNano.get();
            sink.done(t0, ftNano == 0 ? System.nanoTime() : ftNano, refs);
            audit.log(user, "chat", via, query, fp, "none", outcome.mode(), false, maxAuth,
                    (System.nanoTime() - t0) / 1_000_000);
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

    /** Single-Flight 组键 = 租户+指纹+密级（P0-1 修复：租户是第一字段，跨租户永不共组）。 */
    static String singleFlightKey(UserContext user, String fp) {
        return user.tenantId() + ":" + fp + ":" + user.authLevel();
    }

    /**
     * 缓存回放 / Single-Flight 共享答案 → 分片打字机下发（帧格式由 sink 决定）。
     * 入口绊线 fail-closed：载荷租户与请求者不符 → 拒绝回放、error 收尾、审计告警。
     * sfKey 掺租户后本不应触发；触发即意味着又出现了新的无租户共享旁路——宁误伤不泄露。
     */
    void replay(ChatSink sink, String json, String cacheHit, boolean deduplicated,
                String fp, long t0, UserContext user, String query, String via) throws Exception {
        AnswerPayload p = mapper.readValue(json, AnswerPayload.class);
        if (!user.tenantId().equals(p.tenant())) {
            log.error("shared replay tenant mismatch: payload={} requester={}", p.tenant(), user.tenantId());
            audit.log(user, "chat", via, query, fp, "dedup_guard", p.mode(), true, 0,
                    (System.nanoTime() - t0) / 1_000_000);
            sink.error(new IllegalStateException("shared replay tenant mismatch"));
            return;
        }
        sink.meta(fp, cacheHit, degrade.current(), p.fastPath(), deduplicated, 0);
        long replayFirstDeltaNano = System.nanoTime();
        sink.streamInChunks(p.answer());
        sink.done(t0, replayFirstDeltaNano, p.refs());
        // src_tenant 随行（QA P1-2"命中来源"）：与 tenant 相等=正常同租户回放；
        // grep 不等即可发现任何新的跨租户共享旁路。
        audit.log(user, "chat", via, query, fp, cacheHit + (deduplicated ? "+dedup" : ""),
                p.mode(), false, p.maxAuthLevel(), (System.nanoTime() - t0) / 1_000_000, p.tenant());
    }
}
