package com.opspilot.config;

import java.time.Duration;
import org.apache.http.client.config.RequestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3（2026-09-21 补）：出站 socket 超时<b>真的流进了请求工厂</b>，不只是源码里有这几个字。
 *
 * <p>为什么要有这个测试：B3 的原始验收写的是「{@code grep -i timeout} 在四个文件中应有命中」。
 * 那条只证明写了字，证明不了生效——把 {@code .withReadTimeout(...)} 删掉、只留一句注释里的
 * "timeout"，grep 照样绿。这里改成断言配置值到请求工厂设置的传递链，并<b>双向锁</b>：
 * 合法值原样到达、0/负值在启动期被拒（后者在 {@code OpsPilotPropertiesValidationTest}）。
 *
 * <p>不触网、零凭据：只构造设置对象，不建连接。
 */
class OutboundTimeoutTest {

    private static OpsPilotProperties.DashScope dashScope(int connectMs, int readMs) {
        return new OpsPilotProperties.DashScope(
                "https://example.invalid", "k", "text-embedding-v3", 1024,
                "gte-rerank-v2", "qwen-plus", 60, connectMs, readMs, "mock");
    }

    private static OpsPilotProperties.Es es(int connectMs, int readMs) {
        return new OpsPilotProperties.Es("http://localhost:9200", "idx", "elastic", "", connectMs, readMs);
    }

    @Test
    void dashScopeRestClientSettingsCarryConfiguredTimeouts() {
        ClientHttpRequestFactorySettings s = RestClientConfig.settings(dashScope(3000, 15000));
        assertThat(s.connectTimeout()).isEqualTo(Duration.ofMillis(3000));
        assertThat(s.readTimeout()).isEqualTo(Duration.ofMillis(15000));
    }

    @Test
    void dashScopeTimeoutsAreNotLeftAtLibraryDefault() {
        // Boot 的 DEFAULTS 两个超时都是 null（= 交给底层库默认，常见实现是无限等）。
        // 这条钉住"我们确实覆盖了默认"，而不是碰巧等于默认。
        ClientHttpRequestFactorySettings s = RestClientConfig.settings(dashScope(3000, 15000));
        assertThat(ClientHttpRequestFactorySettings.DEFAULTS.connectTimeout()).isNull();
        assertThat(ClientHttpRequestFactorySettings.DEFAULTS.readTimeout()).isNull();
        assertThat(s.connectTimeout()).isNotNull();
        assertThat(s.readTimeout()).isNotNull();
    }

    @Test
    void changingTheConfiguredValueChangesTheSettings() {
        // 反证：值不是硬编码的。若把配置读成常量，这条会红。
        assertThat(RestClientConfig.settings(dashScope(1, 2)).readTimeout())
                .isEqualTo(Duration.ofMillis(2));
        assertThat(RestClientConfig.settings(dashScope(3, 4)).readTimeout())
                .isEqualTo(Duration.ofMillis(4));
    }

    @Test
    void dashScopeSettingsCanActuallyBuildAFactory() {
        // 设置对象能被 Boot 的探测器吃下（classpath 上有 Apache HttpClient 时走它）。
        // 这条挡的是"设置对象构造出来了、但参数形态让工厂建不出来"这类只在起栈时才炸的错。
        assertThat(ClientHttpRequestFactories.get(RestClientConfig.settings(dashScope(3000, 15000))))
                .isNotNull();
    }

    @Test
    void esRequestConfigCarriesConfiguredTimeouts() {
        RequestConfig c = EsConfig.requestConfig(es(3000, 10000));
        assertThat(c.getConnectTimeout()).isEqualTo(3000);
        assertThat(c.getSocketTimeout()).isEqualTo(10000);
    }

    @Test
    void esSocketTimeoutIsLooserThanTheRetrievalLegBudget() {
        // 口径钉住：socket 兜底必须比业务级预算（retrieval.leg-timeout-ms）松，
        // 否则两者赛跑，抛出的是原始 socket 异常而不是业务降级信号。
        int legBudgetMs = 4500;   // = application.yml 的 retrieval.leg-timeout-ms 默认值
        assertThat(EsConfig.requestConfig(es(3000, 10000)).getSocketTimeout()).isGreaterThan(legBudgetMs);
        assertThat(RestClientConfig.settings(dashScope(3000, 15000)).readTimeout())
                .isGreaterThan(Duration.ofMillis(legBudgetMs));
    }
}
