package com.opspilot.llm;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成质量包 Q2=C 单元测试（/tdd 红先行）：verbatim 句级出口护栏。
 * 零 token：不依赖 LLM，模拟"模型逐字倒出参考原文"的输出流。
 */
class VerbatimGuardTest {

    /** 参考 chunk 原文：三行自带换行（换行属原文自身），总长 >100 字，护栏判定的靶文本。
     *  行1=29 字（含 \n）≤80；行1+行2=87 字 >80 —— 跨句拼接用例的前提由测试自证。 */
    private static final String REF =
            "订单中心支付回调大面积超时确认根因为消息队列消费组积压，\n"
                    + "叠加数据库连接池打满临时止损采用重放死信队列并扩容消费组至十六实例冻结运营侧批量导出任务以释放连接资源和线程池容量\n"
                    + "复盘编号 pm-008 已归档完整时间线与改进项清单需验证读写分离方案落地情况。";
    /** 同一原文去掉换行的形态（构造无句读长块用）。 */
    private static final String REF_ONELINE = REF.replace("\n", "").replace("，", "").replace("。", "");

    @Test
    void longestOverlapMeasuresContiguousRun() {
        assertEquals(80, VerbatimGuard.longestOverlap(REF.substring(0, 80), List.of(REF)));
        assertTrue(VerbatimGuard.longestOverlap(REF.substring(0, 81), List.of(REF))
                > VerbatimGuard.MAX_OVERLAP_CHARS);
    }

    @Test
    void emptyInputsYieldZero() {
        assertEquals(0, VerbatimGuard.longestOverlap(null, List.of(REF)));
        assertEquals(0, VerbatimGuard.longestOverlap("随便一句话", List.of()));
        assertEquals(0, VerbatimGuard.longestOverlap("随便一句话", null));
    }

    /** 整段原文被逐句吐出：配额内首句放行，拼接超阈处起掩码——全流不得存在 >80 字连续原文。 */
    @Test
    void verbatimDumpExceedingQuotaIsMasked() {
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF), out::append);
        f.accept(REF);
        f.finish();
        assertTrue(out.indexOf(VerbatimGuard.PLACEHOLDER) >= 0, "超阈逐字导出未被掩码: " + out);
        assertFalse(out.toString().contains(REF.substring(0, 90)), "90 字连续原文漏出");
        assertTrue(f.maskedCount() >= 1);
    }

    /** 配额边界：恰好 80 字引语（含句读）原样通过，不触发掩码。 */
    @Test
    void exactlyEightyCharsPassesThrough() {
        String quote = REF.substring(0, 80) + "」";   // 「」非原文字符：确保 LCS 恰为 80
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF), out::append);
        f.accept(quote);
        assertEquals(quote, f.finish());
        assertEquals(0, f.maskedCount());
    }

    /** 表格防漏算：每行单独 <80 字但跨行连续拼接超阈 → 超阈值行必须拦（carry 参与检测）。 */
    @Test
    void crossSentenceAccumulationIsMasked() {
        int cut1 = REF.indexOf('\n') + 1;
        int cut2 = REF.indexOf('\n', cut1) + 1;
        assertTrue(cut1 <= VerbatimGuard.MAX_OVERLAP_CHARS && cut2 > VerbatimGuard.MAX_OVERLAP_CHARS,
                "测试前提破坏：需 行1≤80 < 行1+行2（REF=" + REF.length() + " 行1=" + cut1 + "）");
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF), out::append);
        f.accept(REF.substring(0, cut1));                       // 行1（自带 \n 结尾）
        assertTrue(out.toString().startsWith(REF.substring(0, cut1)), "配额内首行应放行");
        f.accept(REF.substring(cut1, cut2));                    // 行2：拼接超阈
        assertTrue(out.toString().endsWith(VerbatimGuard.PLACEHOLDER), "跨句拼接超阈未拦");
        assertEquals(1, f.maskedCount());
    }

    /** 拦截后 carry 被占位符打断：后续正常总结句不受前文牵连、照常下发。 */
    @Test
    void carryResetsAfterMaskAllowsContinuation() {
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF), out::append);
        f.accept(REF);
        String normal = "根因疑似连接池与消息积压叠加。";
        f.accept(normal);
        f.finish();
        assertTrue(out.toString().endsWith(normal), "掩码后正常句被误伤: " + out);
        assertFalse(out.toString().contains(REF.substring(0, 90)));
    }

    /** 无句读长块（模型吐连续表格/代码块）不得憋死客户端：安全冲刷上限兜底。 */
    @Test
    void longUnpunctuatedRunIsSafetyFlushed() {
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF), out::append);
        String blob = "数".repeat(500);
        f.accept(blob);
        assertTrue(out.length() >= 200, "无句读块必须能下发，不许憋到流末");
        f.finish();
        assertEquals(blob, out.toString());   // 非原文内容，安全放行
        assertEquals(0, f.maskedCount());
    }

    /** 安全冲刷的块若本身就是逐字原文（无任何句读可依托），同样按超阈拦截。 */
    @Test
    void safetyFlushedBlockStillGuarded() {
        String blob = REF_ONELINE + REF_ONELINE + REF_ONELINE;   // >80 字连续原文，且不含任何句读
        assertFalse(containsSentenceEnd(blob));
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF_ONELINE), out::append);
        f.accept(blob);
        assertEquals(VerbatimGuard.PLACEHOLDER, out.toString());
        assertEquals(1, f.maskedCount());
    }

    @Test
    void finishFlushesTrailingSentenceWithoutEndMark() {
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF), out::append);
        f.accept("最后一句没有标点");
        assertEquals("最后一句没有标点", f.finish());
    }

    /** 英文句末只认 "." + 空白（保护版本号/小数）：`v1.2.3 报错` 不得被切碎。 */
    @Test
    void englishPeriodInsideTokenNotSentenceEnd() {
        StringBuilder out = new StringBuilder();
        VerbatimStreamFilter f = new VerbatimStreamFilter(List.of(REF), out::append);
        f.accept("HikariCP v1.2.3 threw 50012_DB_TIMEOUT\n");
        assertEquals("HikariCP v1.2.3 threw 50012_DB_TIMEOUT\n", f.finish());
        assertEquals(0, f.maskedCount());
    }

    private static boolean containsSentenceEnd(String s) {
        return s.chars().anyMatch(c -> "。！？；!?;\n".indexOf(c) >= 0);
    }
}
