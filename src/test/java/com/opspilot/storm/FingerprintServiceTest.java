package com.opspilot.storm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** QA 缺陷 #3 回归：真实告警变体（唯一 msgId/traceId/时间戳/耗时）应归一到同一指纹。 */
class FingerprintServiceTest {

    private final FingerprintService svc = new FingerprintService();

    @Test
    void variantsOfSameAlertShareFingerprint() {
        String a = "2026-09-08T03:14:22.113 ERROR msgId=abc123def456 traceId=7f3a9b2c "
                + "SQLTransientException error code 50012_DB_TIMEOUT elapsed=1842ms order=88342911";
        String b = "2026-09-08T03:19:55.777 ERROR msgId=xyz789ghi012 traceId=1a2b3c4d "
                + "SQLTransientException error code 50012_DB_TIMEOUT elapsed=93ms order=11223344";
        assertEquals(svc.fingerprint("order-service", "prod", a),
                     svc.fingerprint("order-service", "prod", b),
                     "同错误码不同实例噪声应收敛到同一指纹");
    }

    @Test
    void differentErrorCodesDiffer() {
        String a = "error code 50012_DB_TIMEOUT at OrderCreateService";
        String b = "error code 50013_DB_DEADLOCK at StockDeductService";
        assertNotEquals(svc.fingerprint("order-service", "prod", a),
                        svc.fingerprint("order-service", "prod", b));
    }

    @Test
    void errorCodePreservedInNormalization() {
        String norm = svc.normalizeError("2026-09-08T03:14:22 error code 50012_DB_TIMEOUT msgId=abc123");
        assertTrue(norm.contains("50012_DB_TIMEOUT"), "错误码必须保留");
        assertFalse(norm.contains("abc123"), "msgId 应被掩码");
    }
}
