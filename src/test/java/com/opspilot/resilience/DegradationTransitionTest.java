package com.opspilot.resilience;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.metrics.AuditService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 档位转移留痕（OP-A5，2026-09-28）：`ev=degrade_transition` 的 from/to/cause 必须与实际切换一致。
 *
 * 为什么锁：这是"降级到底有没有在真实负载下发生"唯一可复核的事件序列——离线时间线
 * （读 14 天滚动的 logs/audit.jsonl）与压测结论都建立在它之上。与 `DegradationRecoveryTest`
 * 分工互补：那里锁**判定语义**（半开/手动锁优先），这里锁**留痕**（切了几次、从哪到哪、为何）。
 *
 * 本测试走**真实接线路径**（状态机 → AuditService → 事件形状），不额外暴露测试专用 API——
 * 测的就是生产那一条链。
 */
class DegradationTransitionTest {

    private final AuditService audit = new AuditService();

    private DegradationStateMachine sm(int inflightThreshold, int failThreshold, int openSec) {
        return new DegradationStateMachine(new OpsPilotProperties(
                null, null, null, null, null, null, null,
                new OpsPilotProperties.Degrade(inflightThreshold, failThreshold, openSec)), audit);
    }

    /** 只取转移事件，便于按序列断言（混在业务事件里读不出来）。 */
    private List<Map<String, Object>> transitions() {
        return audit.recentSince(0, 100).events().stream()
                .filter(e -> "degrade_transition".equals(e.get("ev")))
                .toList();
    }

    private static String triple(Map<String, Object> e) {
        return e.get("from") + "→" + e.get("to") + "(" + e.get("cause") + ")";
    }

    /** 在途超阈：递增那一刻就落痕（不靠后续 current() 兜），且回落时同样落痕。 */
    @Test
    void inflightCrossingAndSubsidingAreBothRecordedExactlyOnce() {
        DegradationStateMachine s = sm(2, 3, 60);

        s.enter();
        assertTrue(transitions().isEmpty(), "未达阈不得凭空落转移事件");
        s.enter();                                  // 在途 2 = 阈值 → L1
        assertEquals(List.of("L0→L1(inflight)"), transitions().stream().map(DegradationTransitionTest::triple).toList());

        s.exit();                                   // 在途 1 < 2 → 回 L0
        assertEquals(List.of("L0→L1(inflight)", "L1→L0(load_subsided)"),
                transitions().stream().map(DegradationTransitionTest::triple).toList());

        s.exit();                                   // 已 L0：不得重复落痕
        assertEquals(2, transitions().size(), "同一次切换只落一条（并发/重复观察去重）");
    }

    /** 连续失败达阈 → L2；冷却到期 → L0（半开），两条都要有，否则时间线断成两截。 */
    @Test
    void llmFailuresOpenAndCooldownExpiryCloses() throws Exception {
        DegradationStateMachine s = sm(40, 3, 1);
        s.llmFailure();
        s.llmFailure();
        assertTrue(transitions().isEmpty(), "未达阈（3）不得落 L2");
        s.llmFailure();
        assertEquals(List.of("L0→L2(llm_failure)"), transitions().stream().map(DegradationTransitionTest::triple).toList());

        Thread.sleep(1_150);
        assertEquals(DegradationState.Level.L0, s.current(), "冷却到期半开回 L0");
        assertEquals(List.of("L0→L2(llm_failure)", "L2→L0(cooldown_expired)"),
                transitions().stream().map(DegradationTransitionTest::triple).toList());
    }

    /** 手动锁定/解除必须与自动转移**可区分**（cause 不同）——演示里"这几档是我手动锁的"要答得出来。 */
    @Test
    void manualLockAndClearUseDistinctCauses() {
        DegradationStateMachine s = sm(40, 3, 60);
        s.manualSet(DegradationState.Level.L2);
        assertEquals(List.of("L0→L2(manual)"), transitions().stream().map(DegradationTransitionTest::triple).toList());

        s.manualClear();
        assertEquals(List.of("L0→L2(manual)", "L2→L0(manual_clear)"),
                transitions().stream().map(DegradationTransitionTest::triple).toList());
    }

    /** 重复观察（面板 1s 轮询 /state 会持续调 current()）不得灌水：档位不变就不落事件。 */
    @Test
    void repeatedObservationAtSameLevelDoesNotEmit() {
        DegradationStateMachine s = sm(2, 3, 60);
        s.enter();
        s.enter();                                  // → L1，1 条
        for (int i = 0; i < 10; i++) {
            s.current();
        }
        assertEquals(1, transitions().size(), "档位不变时 current() 反复调用不得重复落痕");
    }

    /** cause 是**有限词表**：本测试是词表的回归锁（新增原因须同步改这里，防止自由文本漂进证据面）。 */
    @Test
    void causeVocabularyStaysClosed() {
        DegradationStateMachine s = sm(1, 2, 1);
        s.enter();                                  // inflight
        s.exit();                                   // load_subsided
        s.llmFailure(); s.llmFailure();             // llm_failure
        s.manualSet(DegradationState.Level.L1);   // manual
        s.manualClear();                            // manual_clear
        var allowed = java.util.Set.of("inflight", "load_subsided", "llm_failure", "cooldown_expired",
                "manual", "manual_clear", "observed");
        var actual = transitions().stream().map(e -> String.valueOf(e.get("cause"))).toList();
        assertTrue(allowed.containsAll(actual), "出现词表外的 cause: " + actual);
        assertEquals(java.util.Set.copyOf(actual).size(), actual.size(), "同一次运行不应出现重复 cause: " + actual);
    }
}
