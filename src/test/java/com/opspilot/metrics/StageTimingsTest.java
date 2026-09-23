package com.opspilot.metrics;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G2 审计行 `stage_ms` 的形状锁：八个分段键必须齐，且**全 0 的段也落字段**。
 *
 * 为什么这条要单独锁：「测了且为 0」与「没测」是两回事——`rerankMs == 0` 表示快路径/es_only/
 * 降级**未调用** Rerank，而「没测」的语义由"审计行根本不落 `stage_ms` 字段"（传 null）承担。
 * 若把 0 值段省略掉，读的人会把"没调用"误读成"耗时为 0"，两者的运维结论完全不同。
 */
class StageTimingsTest {

    @Test
    void toMapCarriesAllEightStagesIncludingZeros() {
        Map<String, Object> m = new StageTimings(100, 11, 22, 33, 0, 40, 50, 60).toMap();

        assertEquals(8, m.size(), "八个分段键必须齐全（含 0 值段）: " + m.keySet());
        assertEquals(100, m.get("retrieval"));
        assertEquals(11, m.get("es"));
        assertEquals(22, m.get("vector"));
        assertEquals(33, m.get("rrf"));
        assertEquals(0, m.get("rerank"), "未调用也要落字段——省略会让「未调用」被读成「没测」");
        assertEquals(40, m.get("llm_ttft"));
        assertEquals(50, m.get("llm"));
        assertEquals(60, m.get("l2_store"));
    }

    @Test
    void allZeroTimingsStillEmitEveryKey() {
        assertEquals(8, new StageTimings(0, 0, 0, 0, 0, 0, 0, 0).toMap().size());
    }
}
