package com.opspilot.resilience;

import com.opspilot.config.OpsPilotProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 熔断自愈死锁回归锁（生成质量包 live 验收暴露的存量缺陷，/tdd 红先行）。
 *
 * 旧实现：`current()` 在冷却过期后仍因 llmConsecutiveFailures≥阈值恒判 L2，而 L2 分支
 * 不调 LLM → 计数没有归零路径 → 熔断永久锁死，只能等 llmSuccess（不可达）或人工解锁。
 * 既往 QA 只测过 manualLock 形态的 L2（A2-6），自动触发+恢复路径从未实弹——本包第一轮
 * live 探针撞上 DashScope 瞬时三连 4xx 即被锁进 L2，才暴露。
 *
 * 修复语义=半开：冷却到期后 current() 清零连续失败计数并放行探测请求，
 * 探测成败由该次调用的 llmSuccess/llmFailure 重新定档。
 */
class DegradationRecoveryTest {

    private static DegradationStateMachine sm(int openSec) {
        OpsPilotProperties props = new OpsPilotProperties(
                null, null, null, null, null, null, null,
                new OpsPilotProperties.Degrade(40, 3, openSec));
        return new DegradationStateMachine(props);
    }

    @Test
    void cooldownExpiryHalfOpensBackToL0() throws Exception {
        DegradationStateMachine s = sm(1);
        s.llmFailure(); s.llmFailure(); s.llmFailure();
        assertEquals(DegradationStateMachine.Level.L2, s.current(), "达阈即熔断");
        Thread.sleep(1_150);
        assertEquals(DegradationStateMachine.Level.L0, s.current(),
                "冷却过期必须回 L0（半开放行）——否则熔断永不自愈");
        s.llmSuccess();
        assertEquals(DegradationStateMachine.Level.L0, s.current());
    }

    @Test
    void halfOpenProbeFailuresReopenBreaker() throws Exception {
        DegradationStateMachine s = sm(1);
        s.llmFailure(); s.llmFailure(); s.llmFailure();
        assertEquals(DegradationStateMachine.Level.L2, s.current());
        Thread.sleep(1_150);
        assertEquals(DegradationStateMachine.Level.L0, s.current(), "半开：放行");
        s.llmFailure(); s.llmFailure(); s.llmFailure();
        assertEquals(DegradationStateMachine.Level.L2, s.current(),
                "半开期再次连败=上游仍坏，熔断重开");
    }

    @Test
    void manualLockStillOverridesAutoHalfOpen() throws Exception {
        DegradationStateMachine s = sm(1);
        s.llmFailure(); s.llmFailure(); s.llmFailure();
        s.manualSet(DegradationStateMachine.Level.L1);
        assertEquals(DegradationStateMachine.Level.L1, s.current(), "演示手动锁优先（A2-6 语义不回归）");
        s.manualClear();
        assertEquals(DegradationStateMachine.Level.L2, s.current(), "解锁后回到自动判定（冷却未过）");
    }
}
