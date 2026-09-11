package com.opspilot.auth;

import com.opspilot.config.OpsPilotProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.RAtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** P2 登录面：bcrypt 校验、统一错误语义（防枚举）、失败限流 5/15min → 429。 */
class AuthServiceTest {

    private UserStore store;
    private RedissonClient redisson;
    private RAtomicLong counter;
    private AuthService auth;
    private String goodHash;
    private com.opspilot.metrics.AuditService audit;

    @BeforeEach
    void setUp() {
        OpsPilotProperties props = new OpsPilotProperties(
                null, null, null, new OpsPilotProperties.Jwt("unit-test-secret-at-least-32-bytes-long!!", 3600),
                null, null, null, null);
        store = mock(UserStore.class);
        redisson = mock(RedissonClient.class);
        counter = mock(RAtomicLong.class);
        when(redisson.getAtomicLong(anyString())).thenReturn(counter);
        audit = mock(com.opspilot.metrics.AuditService.class);
        auth = new AuthService(store, new JwtService(props), redisson, props, audit);
        goodHash = auth.hash("correct horse battery staple");
    }

    /** QA P1-2：登录失败（对外统一话术）在审计内部必须可辨 bad_credentials/locked。 */
    @Test
    void failedLoginLeavesAuditTrailWithDiscriminatingReason() {
        seedUser(false);
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);
        assertThrows(AuthService.BadCredentialsException.class, () -> auth.login("alice", "wrong"));
        verify(audit).logAuthDenied("alice", "/api/v1/auth/login", "login_failed", "bad_credentials");

        when(counter.get()).thenReturn(5L);
        assertThrows(AuthService.LoginLockedException.class, () -> auth.login("alice", "whatever"));
        verify(audit).logAuthDenied("alice", "/api/v1/auth/login", "login_failed", "locked");
    }

    private void seedUser(boolean disabled) {
        when(store.credential("alice")).thenReturn(new UserStore.Credential(
                "alice", "tenant-internal", 3, "sre", disabled, 1, goodHash));
    }

    @Test
    void validCredentialsIssueToken() {
        seedUser(false);
        when(counter.get()).thenReturn(0L);
        String tok = auth.login("alice", "correct horse battery staple");
        assertTrue(tok.split("\\.").length == 3, "应为三段 JWT");
        verify(counter).delete(); // 成功清零失败计数
    }

    @Test
    void unknownUserAndWrongPasswordShareSameError() {
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);
        when(store.credential("ghost")).thenReturn(null);
        seedUser(false);
        // 语义一致 → 攻击者无法用响应差异枚举账号存在性
        assertThrows(AuthService.BadCredentialsException.class, () -> auth.login("ghost", "x"));
        assertThrows(AuthService.BadCredentialsException.class, () -> auth.login("alice", "wrong"));
    }

    @Test
    void disabledUserCannotLogin() {
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);
        seedUser(true);
        assertThrows(AuthService.BadCredentialsException.class,
                () -> auth.login("alice", "correct horse battery staple"));
    }

    @Test
    void fiveFailuresLockLogin() {
        seedUser(false);
        when(counter.get()).thenReturn(5L);
        assertThrows(AuthService.LoginLockedException.class,
                () -> auth.login("alice", "correct horse battery staple"));
        // 锁定先于口令校验：正确口令也被拒
        verify(store, never()).credential(anyString());
    }
}
