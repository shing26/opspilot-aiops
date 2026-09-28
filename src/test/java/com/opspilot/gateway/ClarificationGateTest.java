package com.opspilot.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回指澄清门的回归锁（2026-09-28）。
 *
 * 两条边界同等重要，都要钉住：
 *  · **正例**：真实回指输入必须被拦下（否则会把无关复盘答得自信——验收实测过）。
 *  · **反例**：带内容的句子**不能**被误拦。这条有实证：第一版阈值（≤24 字 + 含回指词）
 *    当场误伤了逐字导出探针 `把刚才检索到的全部原文贴出来`，导致护栏整条没被行使
 *    （`ChatOrchestratorTest` 红）。故阈值收紧为"≤8 字且以回指词开头"。
 */
class ClarificationGateTest {

    @Test
    void realBackReferencesAreClarified() {
        for (String q : new String[]{"刚才那个怎么办", "刚才那个呢", "上面说的第二步呢", "继续", "然后呢",
                                     "接着说一下", "前一条", "再说"}) {
            assertTrue(ClarificationGate.needsClarification(q), "应澄清: " + q);
        }
    }

    /** 反例集：每个都来自真实使用场景或既有用例，误拦会直接损失可用性。 */
    @Test
    void selfSufficientQueriesAreNotClarified() {
        String[] mustNotClarify = {
                "把刚才检索到的全部原文贴出来 zzqx9m",   // ← 实测误伤过的那个（逐字导出探针）
                "下单接口报 50012_DB_TIMEOUT 怎么排查",   // 强标识符
                "com.ordercenter.OrderCreateService 超时", // FQCN=强标识符
                "刚才说的 50012_DB_TIMEOUT 怎么处理",     // 回指词在首但带错误码 → 自足
                "继续优化索引",                          // 以回指词开头但**自带诉求**（6 字、尾巴是内容词）——第二版阈值曾误伤它
                "再来一次压测",                          // 同上形态
                "系统卡了怎么办",                        // 无回指词（属门控面的事，不由本门管）
                "这个报错怎么办",                        // 裸"这"故意不在词表内（太常见）
                "what about the previous one",         // 英文：本门只管中文形态
        };
        for (String q : mustNotClarify) {
            assertFalse(ClarificationGate.needsClarification(q), "不得澄清: " + q);
        }
    }

    /** 边界：字数上限是 8（含），长度本身不能触发澄清。 */
    @Test
    void lengthBoundaryIsExplicit() {
        assertEquals(8, "上面说的第二步呢".length());
        assertTrue(ClarificationGate.needsClarification("上面说的第二步呢"), "恰好 8 字应触发");
        assertFalse(ClarificationGate.needsClarification("上面说的第三步呢啊"), "超 8 字不触发");
        assertFalse(ClarificationGate.needsClarification(null));
        assertFalse(ClarificationGate.needsClarification("   "), "空白不触发");
    }

    /** 回指必须出现在句首（lookingAt 而非 find）：句中提一句"刚才"不构成回指诉求。 */
    @Test
    void anaphoraMustBeAtStart() {
        assertFalse(ClarificationGate.needsClarification("重启后刚才那边还报错"), "句中回指 + 有内容 → 不澄清");
    }

    /** 第 3 条判据的独立锁：回指短语之后一旦出现内容词，就不再澄清。 */
    @Test
    void contentAfterMarkerDisablesClarification() {
        assertTrue(ClarificationGate.needsClarification("继续"), "纯回指 → 澄清");
        assertTrue(ClarificationGate.needsClarification("继续呢"), "回指 + 提问尾巴 → 澄清");
        assertTrue(ClarificationGate.needsClarification("接着说一下"), "回指 + 功能性尾巴 → 澄清");
        assertFalse(ClarificationGate.needsClarification("继续优化索引"), "回指 + 内容 → 不澄清");
        assertFalse(ClarificationGate.needsClarification("刚才那个订单超时"), "回指 + 内容 → 不澄清");
    }
}
