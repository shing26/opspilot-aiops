package com.opspilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2（2026-09-19 外部评审核实采纳）：opspilot.* 配置的非法值必须在<b>启动期拒绝</b>，
 * 不得静默生效。此前的失效方式是"看起来启动成功、实际系统走错"——
 * {@code inflight-threshold=-1} 使降级状态机 {@code inflight >= threshold} 恒真 = 永久 L1
 * 无告警；{@code llm-timeout-seconds=0} 每次调用立即超时。这类错误不会抛异常，
 * 只会表现为"莫名降级/莫名熔断"，事后只能翻配置。
 *
 * <p>两个方向都锁（与本项目其他门闩同一纪律）：
 * <ul>
 *   <li>非法值 → 启动失败，且报错<b>点名字段</b>（只说"启动失败"不说是谁，等于没修）。
 *       负例一律以全量合法值打底、只注入单个非法值——保证失败原因唯一，断言才立得住；</li>
 *   <li>合法值（yml 全部默认值、大小写混写的 mode）→ 正常绑定——否则门闩会把正常流程
 *       也拦下，下一个人就会把它拆掉。</li>
 * </ul>
 *
 * <p><b>为什么正例必须给全所有数值字段</b>（首跑实测踩到）：record 构造器绑定下，
 * 缺省的 int 字段绑成 0，而 0 违反 @Min(1) → 启动被拒。这是<b>刻意语义</b>而非缺陷：
 * application.yml 对每个数值字段都有字面量或 ${VAR:default}（已逐项核对），生产里不存在
 * "缺一个数值字段还想起动"的合法场景；谁删了 yml 的一行，就应在启动期被点名，而不是
 * 带着 0 阈值静默跑。故本测试的合法基线 = application.yml 的数值面（改 yml 默认值时
 * 这里要同步）。
 *
 * <p>不触网、零凭据：ApplicationContextRunner 只做属性绑定与校验，不拉起任何中间件客户端。
 */
class OpsPilotPropertiesValidationTest {

    @Configuration
    @EnableConfigurationProperties(OpsPilotProperties.class)
    static class BindCfg {
    }

    /** 与 application.yml 数值面一致的完整合法基线（改 yml 默认值时同步这里）。 */
    private static final String[] LEGAL_BASELINE = {
            "opspilot.dashscope.embedding-dim=1024",
            "opspilot.dashscope.llm-timeout-seconds=60",
            "opspilot.dashscope.mode=auto",
            "opspilot.qdrant.grpc-port=6334",
            "opspilot.jwt.ttl-seconds=86400",
            "opspilot.cache.l1-ttl-hours=2",
            "opspilot.cache.l2-threshold=0.95",
            "opspilot.storm.window-seconds=30",
            "opspilot.storm.alert-window-seconds=60",
            "opspilot.retrieval.es-top-k=50",
            "opspilot.retrieval.qdrant-top-k=50",
            "opspilot.retrieval.rrf-k=60",
            "opspilot.retrieval.rerank-top-k=20",
            "opspilot.retrieval.final-top-k=3",
            "opspilot.retrieval.leg-timeout-ms=4500",
            "opspilot.retrieval.min-relevance=0.2",
            "opspilot.degrade.inflight-threshold=40",
            "opspilot.degrade.llm-failure-threshold=3",
            "opspilot.degrade.llm-open-seconds=60",
    };

    private static ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(BindCfg.class)
                .withPropertyValues(LEGAL_BASELINE);
    }

    private static ApplicationContextRunner runnerWith(String... overrides) {
        return new ApplicationContextRunner()
                .withUserConfiguration(BindCfg.class)
                .withPropertyValues(concat(LEGAL_BASELINE, overrides));
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void ymlDefaultsBindAndStart() {
        baseRunner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            OpsPilotProperties props = ctx.getBean(OpsPilotProperties.class);
            assertThat(props.degrade().inflightThreshold()).isEqualTo(40);
            assertThat(props.retrieval().minRelevance()).isEqualTo(0.2);
        });
    }

    @Test
    void modeIsCaseInsensitive() {
        runnerWith("opspilot.dashscope.mode=LiVe",
                "opspilot.dashscope.api-key=whatever").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(OpsPilotProperties.class).dashscope().live()).isTrue();
        });
    }

    @Test
    void modeAutoWithoutKeyFallsBackToMock() {
        baseRunner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // 基线未给 api-key → auto 语义回落 mock（双模判定的既有行为，勿被校验破坏）
            assertThat(ctx.getBean(OpsPilotProperties.class).dashscope().live()).isFalse();
        });
    }

    @Test
    void negativeInflightThresholdIsRejectedAtStartup() {
        // 评审核实的原始锐边：-1 → 状态机恒判 L1（永久降级）且无告警
        runnerWith("opspilot.degrade.inflight-threshold=-1").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("inflightThreshold");
        });
    }

    @Test
    void zeroLlmTimeoutIsRejectedAtStartup() {
        // 0 = 每次调用立即超时 → 熔断常开，表现为"莫名全走 SOP 直出"
        runnerWith("opspilot.dashscope.llm-timeout-seconds=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("llmTimeoutSeconds");
        });
    }

    @Test
    void outOfRangeRelevanceIsRejectedAtStartup() {
        // min-relevance 是 [0,1] 上的分数：越界值只会让拒答门控行为失真（0=关闭门控是合法语义）
        runnerWith("opspilot.retrieval.min-relevance=1.5").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("minRelevance");
        });
    }

    @Test
    void unknownModeTypoIsRejectedAtStartup() {
        // mode="LIVA" 此前会静默退回"按 key 判定"——演示时 key 在场就悄悄走 live，烧真钱
        runnerWith("opspilot.dashscope.mode=LIVA",
                "opspilot.dashscope.api-key=whatever").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("auto|live|mock");
        });
    }

    @Test
    void zeroPortIsRejectedAtStartup() {
        runnerWith("opspilot.qdrant.grpc-port=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("grpcPort");
        });
    }
}
