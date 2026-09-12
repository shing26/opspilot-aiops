package com.opspilot.llm;

import com.opspilot.retrieval.ScoredChunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成质量包 Q2=C / Q3（/tdd 红先行）：Prompt 层规则注入的锁。
 * 规则 5（总结口径+80 字引语配额）无条件在场；语态条款只允许在弱问题下出现。
 */
class PromptAssemblerTest {

    private static ScoredChunk chunk() {
        return new ScoredChunk("rb-001::s1", "rb-001", "runbook", "正文", "面包屑", "order-service",
                List.of("50012_DB_TIMEOUT"), 2, new ScoredChunk.Scores(1, 1, 1, 1));
    }

    private static String systemOf(List<Map<String, String>> msgs) {
        return msgs.stream().filter(m -> "system".equals(m.get("role"))).findFirst()
                .orElseThrow().get("content");
    }

    @Test
    void systemCarriesVerbatimSummaryRule() {
        String sys = systemOf(new PromptAssembler().build("任意问题", List.of(chunk())));
        assertTrue(sys.contains("总结/转述"), "缺少仅摘要口径: " + sys);
        assertTrue(sys.contains("80"), "单条引语配额未入 prompt（与 VerbatimGuard.MAX_OVERLAP_CHARS 同数）");
        assertTrue(sys.contains("逐字"), "未明确拒绝逐字导出: " + sys);
    }

    // ---- 生成质量包 Q3：防断言语态条件注入（/tdd 红先行）----

    @Test
    void weakQueryGetsHypotheticalClause() {
        String sys = systemOf(new PromptAssembler().build(
                "订单最近老是超时，接口变慢，用户也在骂，帮我看看可能是什么原因", List.of(chunk())));
        assertTrue(sys.contains("疑似"), "弱问题缺假设语态条款: " + sys);
        assertTrue(sys.contains("锚定为结论"), "未禁止断言式编号锚定: " + sys);
    }

    @Test
    void errorCodeQueryKeepsBaselineWithoutHypothetical() {
        String sys = systemOf(new PromptAssembler().build(
                "支付回调超时 50012_DB_TIMEOUT 怎么排查", List.of(chunk())));
        assertFalse(sys.contains("锚定为结论"), "强标识符问题不应注入语态条款（现行为逐字不变）");
    }

    @Test
    void fqcnQueryKeepsBaselineWithoutHypothetical() {
        String sys = systemOf(new PromptAssembler().build(
                "com.ordercenter.order.OrderCreateService.createOrder 报通信异常", List.of(chunk())));
        assertFalse(sys.contains("锚定为结论"), "FQCN 亦为强标识符，不应注入语态条款");
    }
}
