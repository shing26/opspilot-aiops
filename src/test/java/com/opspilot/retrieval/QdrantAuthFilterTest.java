package com.opspilot.retrieval;

import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 回归：auth_level<=0 必须失败关闭（filter 恒存在且命中空集），
 * 杜绝签发 level-0 JWT 绕过 Qdrant 权限过滤的提权路径。
 */
class QdrantAuthFilterTest {

    private static Points.FieldCondition fieldCond(Points.Filter filter) {
        assertEquals(1, filter.getMustCount(), "必须恒有一条 auth 过滤条件");
        Points.Condition cond = filter.getMust(0);
        assertTrue(cond.hasField(), "过滤条件须为字段条件");
        return cond.getField();
    }

    @Test
    void positiveLevelFiltersByLteBoundary() {
        Points.FieldCondition fc = fieldCond(QdrantSearchService.authFilter(3));
        assertEquals("metadata.auth_level", fc.getKey());
        assertTrue(fc.getRange().hasLte());
        assertEquals(3.0, fc.getRange().getLte(), 1e-9);
    }

    @Test
    void zeroLevelIsFailClosedNotUnfiltered() {
        Points.FieldCondition fc = fieldCond(QdrantSearchService.authFilter(0));
        assertEquals("metadata.auth_level", fc.getKey());
        // lte(0)：库内文档 auth_level>=1，命中空集 —— 安全失败而非放行全库
        assertTrue(fc.getRange().hasLte());
        assertEquals(0.0, fc.getRange().getLte(), 1e-9);
    }

    @Test
    void negativeLevelClampsToZeroBoundary() {
        Points.FieldCondition fc = fieldCond(QdrantSearchService.authFilter(-5));
        assertTrue(fc.getRange().hasLte());
        assertEquals(0.0, fc.getRange().getLte(), 1e-9);
    }
}
