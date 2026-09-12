package com.opspilot.storm;

import com.opspilot.config.OpsPilotProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成质量包 Q4 锁：滑动窗口 = `来源×指纹` 的聚合计数窗口（非穿透闸门）。
 * key 必须掺 source——否则 manual(30s) 的修剪会删掉 alert(60s) 窗口内成员，两边计数都打偏。
 * 纯 mock（零 Redis 依赖，CI 可跑）；混源真实行为在 V6 live 验收用 Redis 直读佐证。
 */
class SlidingWindowServiceTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RScoredSortedSet<String> zset = mock(RScoredSortedSet.class);
    private final SlidingWindowService svc = new SlidingWindowService(redisson,
            new OpsPilotProperties(null, null, null, null, null,
                    new OpsPilotProperties.Storm(30, 60), null, null));

    @Test
    void windowKeyIsScopedBySource() {
        stubZset();
        when(zset.size()).thenReturn(0);

        svc.tryAcquire("fp1", "alert");
        svc.tryAcquire("fp1", "manual");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redisson, times(2)).getScoredSortedSet(keys.capture());
        assertEquals("storm:win:alert:fp1", keys.getAllValues().get(0));
        assertEquals("storm:win:manual:fp1", keys.getAllValues().get(1));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                "两源共用一个 ZSET 则短窗口修剪污染长窗口计数（Q4 根因）");
    }

    @Test
    void alertPrunesLessAndTtlLongerThanManual() {
        stubZset();
        when(zset.size()).thenReturn(0);
        long before = System.currentTimeMillis();

        svc.tryAcquire("fp1", "alert");
        svc.tryAcquire("fp1", "manual");

        ArgumentCaptor<Double> pruneTo = ArgumentCaptor.forClass(Double.class);
        verify(zset, times(2)).removeRangeByScore(anyDouble(), anyBoolean(),
                pruneTo.capture(), anyBoolean());
        double alertPruneTo = pruneTo.getAllValues().get(0);
        double manualPruneTo = pruneTo.getAllValues().get(1);
        assertTrue(alertPruneTo < manualPruneTo,
                "alert(60s) 删除区间终点应比 manual(30s) 更早=删得更少");
        assertTrue(before - 60_000L - 5_000 <= alertPruneTo && alertPruneTo <= before, "alert 窗口宽度异常");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(zset, times(2)).expire(ttl.capture());
        assertEquals(70, ttl.getAllValues().get(0).toSeconds(), "alert TTL=窗口+10s");
        assertEquals(40, ttl.getAllValues().get(1).toSeconds(), "manual TTL=窗口+10s");
    }

    /** first 仅是计数起点归属（喂 dedup_aggregated 指标），不承担"穿透/等待"语义。 */
    @Test
    void firstReflectsCountStartOnly() {
        stubZset();
        when(zset.size()).thenReturn(0, 5);

        assertTrue(svc.tryAcquire("fp1", "alert").first());
        SlidingWindowService.WindowResult again = svc.tryAcquire("fp1", "alert");
        assertFalse(again.first());
        assertEquals(5, again.aggregated());
        verify(zset, times(2)).add(anyDouble(), anyString());   // 两源请求都照常计数
    }

    /** 泛型方法 getScoredSortedSet 用 when().thenReturn() 会被推断成 <Object> 桩——doReturn 绕开。 */
    private void stubZset() {
        org.mockito.Mockito.doReturn(zset).when(redisson).getScoredSortedSet(anyString());
    }
}
