package com.opspilot.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成质量包 Q3：强标识符判据（错误码/FQCN）的谓词锁。
 * 词法单一事实源在 EsSearchService（ERROR_CODE/FQCN 与快路径共用形状），
 * 防断言语态门只消费本谓词——不得在别处复制正则（ADR-0008 同构纪律）。
 */
class EsSearchServiceStrongIdentifierTest {

    @Test
    void errorCodeIsStrong() {
        assertTrue(EsSearchService.hasStrongIdentifier("支付回调超时 50012_DB_TIMEOUT 怎么排查"));
    }

    @Test
    void fqcnIsStrong() {
        assertTrue(EsSearchService.hasStrongIdentifier(
                "com.ordercenter.order.OrderCreateService.createOrder 抛通信异常"));
    }

    @Test
    void proseWithoutIdentifiersIsWeak() {
        assertFalse(EsSearchService.hasStrongIdentifier(
                "订单最近老是超时，接口也变慢了，用户投诉变多，帮我看看可能是什么原因"));
        assertFalse(EsSearchService.hasStrongIdentifier("数据库 慢 query 多 连接 池 不够"));
        assertFalse(EsSearchService.hasStrongIdentifier(""));
        assertFalse(EsSearchService.hasStrongIdentifier(null));
    }

    /** 服务名不是强标识符（grill Q3 裁定：词表=第二真相源，服务名单独出现仍走假设语态）。 */
    @Test
    void serviceNameAloneIsWeak() {
        assertFalse(EsSearchService.hasStrongIdentifier("order-service 最近超时增多怎么排查"));
    }
}
