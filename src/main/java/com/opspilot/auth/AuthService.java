package com.opspilot.auth;

import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;

/**
 * 登录与凭证签发（P2）：凭据校验走 H2 主库（UserStore），暴力防护走 Redis
 * （限流数据易失可容忍——丢了最坏是重新计数，不放行错误凭据）。
 * 统一错误语义（未知用户/错密码/disabled 同为无效凭据），防账号枚举。
 */
@Service
public class AuthService {

    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException() { super("invalid username or password"); }
    }
    public static class LoginLockedException extends RuntimeException {
        public LoginLockedException() { super("too many failed attempts"); }
    }

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserStore users;
    private final JwtService jwt;
    private final RedissonClient redisson;
    private final OpsPilotProperties props;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    public AuthService(UserStore users, JwtService jwt, RedissonClient redisson, OpsPilotProperties props) {
        this.users = users;
        this.jwt = jwt;
        this.redisson = redisson;
        this.props = props;
    }

    /** 校验通过返回 24h JWT（携带签发时刻的 tver）；失败抛 Bad/Locked。 */
    public String login(String sub, String password) {
        var fails = redisson.getAtomicLong("auth:fail:" + sub);
        if (fails.get() >= MAX_FAILURES) throw new LoginLockedException();

        UserStore.Credential c = users.credential(sub);
        if (c == null || c.disabled() || password == null
                || !bcrypt.matches(password, c.passBcrypt())) {
            long n = fails.incrementAndGet();
            if (n == 1) fails.expire(WINDOW);
            if (n >= MAX_FAILURES) throw new LoginLockedException();
            throw new BadCredentialsException(); // 不区分"无此人/错密码/已禁用"，防枚举
        }
        fails.delete();
        return jwt.issue(c.sub(), c.role(), c.tenant(), c.authLevel(), c.tokenVer(),
                props.jwt().ttlSeconds());
    }

    public String hash(String rawPassword) {
        return bcrypt.encode(rawPassword);
    }
}
