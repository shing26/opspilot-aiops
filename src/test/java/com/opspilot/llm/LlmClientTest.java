package com.opspilot.llm;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.metrics.OpsMetrics;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * H3 重试语义锁：仅"首 token 吐出前"的瞬时错（429/网络层/HTTP 5xx）允许单次退避重试；
 * 已吐 token 后重试会造成答案重复，禁止；429 与网络错分类计数，终态口径不变。
 * attemptOnce 以 spy 桩掉——不真发 HTTP。
 */
class LlmClientTest {

    private OpsMetrics metrics;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        OpsPilotProperties props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://x", "k", "e", 1024, "r", "q", 30, "live"),
                null, null, null, null, null, null, null);
        metrics = mock(OpsMetrics.class);
        client = new LlmClient(props, metrics);
        client.backoff429Ms = 1;
        client.backoffNetworkMs = 1;
    }

    private static RuntimeException networkErr() {
        return new RuntimeException("LLM 调用失败: reset", new java.io.IOException("reset"));
    }

    @Test
    void transientFailureBeforeFirstTokenRetriesOnce() {
        var spy = spy(client);
        doThrow(networkErr()).doReturn("ok").when(spy).attemptOnce(any(), any());
        assertEquals("ok", spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics).llmRetry();
        verify(metrics, never()).llmNetworkError();
    }

    @Test
    void doubleTransientFailureCountsRetryAndNetworkError() {
        var spy = spy(client);
        doThrow(networkErr()).when(spy).attemptOnce(any(), any());
        assertThrows(RuntimeException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics).llmRetry();
        verify(metrics).llmNetworkError();
    }

    @Test
    void rateLimit429RetriesOnceWithTypedPropagation() {
        var spy = spy(client);
        doThrow(new LlmClient.LlmRateLimitedException())
                .doReturn("ok").when(spy).attemptOnce(any(), any());
        assertEquals("ok", spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics).llmRetry();
        // 429 不占网络错分类（两口径独立）
        verify(metrics, never()).llmNetworkError();
        verify(metrics, never()).llmRateLimited(); // 终态 429 计数归编排层
    }

    /** 首 token 已吐后再失败：重试=答案重复，必须直抛（网络错仍计数）。 */
    @Test
    void failureAfterFirstTokenNeverRetries() {
        var spy = spy(client);
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(1);
            onToken.accept("half");
            throw networkErr();
        }).when(spy).attemptOnce(any(), any());
        assertThrows(RuntimeException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics, never()).llmRetry();
        verify(metrics).llmNetworkError();
    }

    /** 非瞬时（4xx 配置类）不重试——重试只会烧额度不改变结局。 */
    @Test
    void nonTransientFailureNeverRetries() {
        var spy = spy(client);
        doThrow(new RuntimeException("LLM HTTP 401")).when(spy).attemptOnce(any(), any());
        assertThrows(RuntimeException.class,
                () -> spy.streamChat(List.of(Map.of("content", "q")), t -> {}));
        verify(metrics, never()).llmRetry();
        verify(metrics, never()).llmNetworkError();
    }
}
