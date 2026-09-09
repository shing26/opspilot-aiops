package com.opspilot.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;

/**
 * JWT 签发/验签的统一实现（P2）：登录服务与鉴权过滤器共用一个密钥出口，
 * 杜绝两处各自 build key 的漂移。HS256 对称密钥仅适用于单实例内部工具
 * （持密者可签发）——生产多实例/跨服务需换 RS256 非对称验签（记 ADR-0005 欠账）。
 */
@Component
public class JwtService {

    private final SecretKey key;

    public JwtService(OpsPilotProperties props) {
        String secret = props.jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 环境变量未设置（凭据只从环境读取，见 .env.example）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发短时凭证；tver=签发时的用户 token_ver，过滤器逐请求比对实现全局吊销。 */
    public String issue(String sub, String role, String tenant, int authLevel, int tokenVer, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder().subject(sub)
                .claim("role", role)
                .claim("tenant_id", tenant)
                .claim("auth_level", authLevel)
                .claim("tver", tokenVer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
