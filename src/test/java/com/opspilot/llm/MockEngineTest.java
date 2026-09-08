package com.opspilot.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 置信度门控依赖 rerank 分的判别力：乱码须≈0，相关查询须显著更高。 */
class MockEngineTest {

    private static final String DOC = "订单创建超时排查手册 50012_DB_TIMEOUT "
            + "适用症状 下单接口返回 50012_DB_TIMEOUT 连接池耗尽 慢SQL 数据库超时";

    @Test
    void garbageScoresNearZero() {
        double s = MockEngine.rerankScore("asdfghjkl", DOC);
        assertTrue(s < 0.05, "乱码覆盖率应≈0，实际=" + s);
    }

    @Test
    void relevantQueryScoresHigh() {
        double s = MockEngine.rerankScore("下单接口 50012_DB_TIMEOUT 超时 怎么排查", DOC);
        assertTrue(s > 0.3, "相关查询覆盖率应高，实际=" + s);
    }

    @Test
    void relevantBeatsGarbageByMargin() {
        double good = MockEngine.rerankScore("数据库超时 连接池耗尽 50012_DB_TIMEOUT", DOC);
        double garbage = MockEngine.rerankScore("qwerty zxcvbnm", DOC);
        assertTrue(good > garbage + 0.2, "判别裕度不足 good=" + good + " garbage=" + garbage);
    }
}
