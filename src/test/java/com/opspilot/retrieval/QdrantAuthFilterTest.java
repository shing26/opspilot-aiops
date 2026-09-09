package com.opspilot.retrieval;

import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P0+P1 回归：authFilter 恒失败关闭的双条件——
 * ① term(metadata.tenant) 等值（跨租户零命中）；② range(metadata.auth_level).lte(user 级)。
 * level<=0 钳到 lte(0) 命中空集（防 level-0 token 绕过），方向必须是 doc ≤ user。
 */
class QdrantAuthFilterTest {

    private static final String TENANT = "tenant-demo";

    private static Points.Filter filter() {
        return QdrantSearchService.authFilter(TENANT, 3);
    }

    private static Points.Condition must(Points.Filter f, int i) {
        return f.getMust(i);
    }

    @Test
    void alwaysCarriesBothConditions() {
        Points.Filter f = filter();
        assertEquals(2, f.getMustCount(), "必须恒有 [term tenant, range level] 两条 must");
    }

    @Test
    void tenantIsExactTermMatchNotRange() {
        Points.Condition c = must(filter(), 0);
        assertTrue(c.hasField() && c.getField().hasMatch(), "第一条件必须是字段 match");
        assertEquals("metadata.tenant", c.getField().getKey());
        assertEquals(TENANT, c.getField().getMatch().getKeyword());
    }

    @Test
    void levelDirectionIsDocLteUser() {
        Points.FieldCondition fc = must(filter(), 1).getField();
        assertEquals("metadata.auth_level", fc.getKey());
        assertTrue(fc.getRange().hasLte());
        assertEquals(3.0, fc.getRange().getLte(), 1e-9); // doc.level <= user.level
    }

    @Test
    void zeroLevelIsFailClosedNotUnfiltered() {
        Points.Filter f = QdrantSearchService.authFilter(TENANT, 0);
        assertEquals(2, f.getMustCount(), "level-0 也必须双条件齐（tenant 过滤不因降级密级丢失）");
        Points.Range r = f.getMust(1).getField().getRange();
        assertTrue(r.hasLte());
        assertEquals(0.0, r.getLte(), 1e-9); // lte(0)：库内文档 >=1 → 空集
    }

    @Test
    void negativeLevelClampsToZeroBoundary() {
        Points.Range r = QdrantSearchService.authFilter(TENANT, -5).getMust(1).getField().getRange();
        assertTrue(r.hasLte());
        assertEquals(0.0, r.getLte(), 1e-9);
    }
}
