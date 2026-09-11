package com.opspilot.gateway;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QA 台账 2026-09-11 P0-1 的回归锁：Single-Flight 组键必须掺入 tenant。
 * 全仓共享路径（ES/Qdrant/L1/L2/SOP）都带租户维度，唯独 sfKey 漏了——
 * 跨租户同密级并发（正是"告警风暴"卖点场景）下 follower 会拿到 leader 的全文+引用。
 * 本测试 + replay 绊线构成"修复后不可复发"的双保险。
 */
class ChatOrchestratorTest {

    private static final UserContext INTERNAL =
            new UserContext("sre-full", "platform", 3, "tenant-internal");
    private static final UserContext ACME =
            new UserContext("sre-acme", "sre", 3, "tenant-acme");

    private final CountDownLatch llmGate = new CountDownLatch(1);

    private L1CacheService l1;
    private HybridSearchService searchService;
    private LlmClient llm;
    private AuditService audit;
    private ChatOrchestrator orchestrator;
    private ExecutorService vt;

    @BeforeEach
    void setUp() {
        OpsPilotProperties props = new OpsPilotProperties(
                null, null, null, null, null, null,
                new OpsPilotProperties.Retrieval(10, 10, 60, 5, 3, 4500, 0.2), null);
        l1 = mock(L1CacheService.class);
        L2SemanticCacheService l2 = mock(L2SemanticCacheService.class);
        FingerprintService fps = mock(FingerprintService.class);
        when(fps.fingerprint(any(), any(), anyString())).thenReturn("fp1");
        SlidingWindowService sw = mock(SlidingWindowService.class);
        when(sw.tryAcquire(anyString(), anyString()))
                .thenReturn(new SlidingWindowService.WindowResult(true, 1));
        searchService = mock(HybridSearchService.class);
        when(searchService.search(anyString(), eq("tenant-internal"), anyInt(), anyString()))
                .thenReturn(outcome("rb-001::s1"));
        when(searchService.search(anyString(), eq("tenant-acme"), anyInt(), anyString()))
                .thenReturn(outcome("acme-52001::s1"));
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f});
        llm = mock(LlmClient.class);
        // 桩语义：提示词含 leader 检索到的 chunkId → internal 答案（慢，受门控闩锁）；
        // 含 acme chunkId → acme 自己的答案（快）。泄露与否在 sink 帧上一眼可断。
        PromptAssembler pa = mock(PromptAssembler.class);
        when(pa.build(anyString(), any())).thenAnswer(inv -> {
            List<ScoredChunk> chunks = inv.getArgument(1);
            return List.of(Map.of("role", "user", "content", chunks.get(0).chunkId()));
        });
        when(llm.streamChat(any(), any())).thenAnswer(inv -> {
            List<Map<String, String>> msgs = inv.getArgument(0);
            String prompt = msgs.get(0).get("content");
            @SuppressWarnings("unchecked")
            Consumer<String> onToken = inv.getArgument(1);
            if (prompt.startsWith("rb-")) {
                if (!llmGate.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
                onToken.accept("INTERNAL-SECRET-ANSWER");
                return "INTERNAL-SECRET-ANSWER";
            }
            onToken.accept("ACME-OWN-ANSWER");
            return "ACME-OWN-ANSWER";
        });
        DegradationStateMachine degrade = mock(DegradationStateMachine.class);
        when(degrade.current()).thenReturn(Level.L0);
        audit = mock(AuditService.class);
        vt = Executors.newVirtualThreadPerTaskExecutor();
        orchestrator = new ChatOrchestrator(l1, l2, fps, sw, new SingleFlightRegistry(),
                searchService, embedding, llm, pa, degrade, mock(SopFallbackService.class),
                mock(OpsMetrics.class), vt, props, audit, mock(QuotaService.class));
    }

    @AfterEach
    void tearDown() {
        llmGate.countDown();
        vt.shutdownNow();
    }

    private static SearchOutcome outcome(String chunkId) {
        ScoredChunk c = new ScoredChunk(chunkId, chunkId.split("::")[0], "runbook", "text",
                chunkId, "svc", List.of("50042_PAY_SIGN_INVALID"), 3,
                new ScoredChunk.Scores(1, 1, 1, 1));
        return new SearchOutcome(List.of(c), "hybrid", true, false, 1.0, 5);
    }

    private static final class RecordingSink implements ChatSink {
        final StringBuilder answer = new StringBuilder();
        final CountDownLatch finished = new CountDownLatch(1);
        volatile List<AnswerPayload.Ref> refs = List.of();
        volatile String cacheHit;
        volatile Boolean deduplicated;
        volatile Throwable error;

        @Override public void meta(String fp, String cacheHit, Level level,
                                  boolean fastPath, boolean dedup, long ms) {
            this.cacheHit = cacheHit; this.deduplicated = dedup;
        }
        @Override public void delta(String token) { answer.append(token); }
        @Override public void streamInChunks(String a) { answer.append(a); }
        @Override public void done(long t0, long ft, List<AnswerPayload.Ref> refs) {
            this.refs = refs; finished.countDown();
        }
        @Override public void error(Throwable t) { this.error = t; finished.countDown(); }
    }

    private static ChatRequest req(String query) {
        return new ChatRequest(query, "manual", null, null);
    }

    private static String q() {
        return "50042_PAY_SIGN_INVALID 支付验签批量失败复盘 根因与修复";
    }

    /** P0-1 主案：leader(tenant-internal,L3) 在途时，follower(tenant-acme,L3) 绝不可拿到其答案/引用。 */
    @Test
    void crossTenantConcurrentRequestsNeverShareAnswer() throws Exception {
        RecordingSink leader = new RecordingSink();
        RecordingSink follower = new RecordingSink();
        orchestrator.submit(req(q()), INTERNAL, leader, "sse");
        Thread.sleep(300);                      // 确保 leader 已注册 flight 且阻塞在 llm
        orchestrator.submit(req(q()), ACME, follower, "sse");
        Thread.sleep(300);
        llmGate.countDown();                    // 放行 internal 慢答案
        assertTrue(follower.finished.await(30, TimeUnit.SECONDS), "follower 未收尾");
        assertTrue(leader.finished.await(30, TimeUnit.SECONDS), "leader 未收尾");

        assertEquals("ACME-OWN-ANSWER", follower.answer.toString(),
                "跨租户 follower 拿到了别租户答案（P0-1 复发）");
        assertFalse(follower.answer.toString().contains("INTERNAL"), "leader 全文泄露");
        assertTrue(follower.refs.stream().allMatch(r -> r.chunkId().startsWith("acme-")),
                "leader 引用泄露: " + follower.refs);
        assertEquals(Boolean.FALSE, follower.deduplicated, "跨租户不得标记 dedup");
    }

    /** 回归护栏：修 key 不能弄丢收敛性——同租户同密级并发仍收敛为 1 次 LLM 调用。 */
    @Test
    void sameTenantConcurrentRequestsStillConverge() throws Exception {
        RecordingSink leader = new RecordingSink();
        RecordingSink follower = new RecordingSink();
        orchestrator.submit(req(q()), INTERNAL, leader, "sse");
        Thread.sleep(300);
        orchestrator.submit(req(q()), INTERNAL, follower, "sse");
        Thread.sleep(300);
        llmGate.countDown();
        assertTrue(leader.finished.await(30, TimeUnit.SECONDS));
        assertTrue(follower.finished.await(30, TimeUnit.SECONDS));

        verify(llm, times(1)).streamChat(any(), any());
        assertEquals("INTERNAL-SECRET-ANSWER", follower.answer.toString());
        assertEquals(Boolean.TRUE, follower.deduplicated);
    }

    /** 绊线（fail-closed 纵深防御）：即便未来又出现别的路由把跨租户载荷送进 replay，也必须拒回放。 */
    @Test
    void replayRejectsForeignTenantPayload() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new AnswerPayload("INTERNAL-SECRET", List.of(
                        new AnswerPayload.Ref("rb-001::s1", "面包屑", "svc")),
                        "hybrid", false, 3, "tenant-internal"));
        RecordingSink sink = new RecordingSink();
        orchestrator.replay(sink, json, "none", true, "fp1", System.nanoTime(), ACME, q(), "sse");

        assertNotNull(sink.error, "外来租户载荷必须走 error 收尾而非回放");
        assertEquals("", sink.answer.toString(), "绊线触发前不得吐出任何内容");
        verify(audit).log(eq(ACME), eq("chat"), anyString(), anyString(), anyString(),
                eq("dedup_guard"), anyString(), eq(true), anyInt(), anyLong());
    }

    /** 组键口径：tenant 是第一字段——与 L1 key 的 cache:l1:<tenant>:<level>: 同构（权限维度完备）。 */
    @Test
    void singleFlightKeyIncludesTenant() {
        assertEquals("tenant-internal:fp1:3", ChatOrchestrator.singleFlightKey(INTERNAL, "fp1"));
        assertNotEquals(ChatOrchestrator.singleFlightKey(INTERNAL, "fp1"),
                ChatOrchestrator.singleFlightKey(ACME, "fp1"),
                "同指纹同密级跨租户必须落到不同组（P0-1 根因）");
    }
}
