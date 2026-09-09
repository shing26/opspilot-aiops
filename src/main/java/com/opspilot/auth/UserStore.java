package com.opspilot.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * 用户主库访问层（P2，ADR-0005）：H2 文件库，全参数绑定 SQL（禁字符串拼接）。
 * 吊销模型：disabled 布尔 + token_ver 版本号。改一行即全局失效，无 JWT 黑名单膨胀。
 */
@Service
public class UserStore {

    /** 用户身份行；pass_bcrypt 仅登录校验时读取，鉴权热路径走 findActiveForAuth 不取散列。 */
    public record User(String sub, String tenant, int authLevel, String role,
                       boolean disabled, int tokenVer) {}

    private static final RowMapper<User> MAPPER = (ResultSet rs, int i) -> new User(
            rs.getString("sub"), rs.getString("tenant"), rs.getInt("auth_level"),
            rs.getString("role"), rs.getBoolean("disabled"), rs.getInt("token_ver"));

    private final JdbcTemplate jdbc;

    public UserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 鉴权热路径：按 sub 取当前用户状态（不含密码散列）。不存在返回 null。 */
    public User find(String sub) {
        try {
            return jdbc.queryForObject(
                    "SELECT sub, tenant, auth_level, role, disabled, token_ver FROM users WHERE sub = ?",
                    MAPPER, sub);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 登录用：取含 bcrypt 散列的完整凭据行。 */
    public Credential credential(String sub) {
        try {
            return jdbc.queryForObject(
                    "SELECT sub, tenant, auth_level, role, disabled, token_ver, pass_bcrypt "
                            + "FROM users WHERE sub = ?", CRED_MAPPER, sub);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public record Credential(String sub, String tenant, int authLevel, String role,
                             boolean disabled, int tokenVer, String passBcrypt) {}

    private static final RowMapper<Credential> CRED_MAPPER = (ResultSet rs, int i) -> new Credential(
            rs.getString("sub"), rs.getString("tenant"), rs.getInt("auth_level"),
            rs.getString("role"), rs.getBoolean("disabled"), rs.getInt("token_ver"),
            rs.getString("pass_bcrypt"));

    public void insert(String sub, String passBcrypt, String tenant, int authLevel, String role) {
        jdbc.update("INSERT INTO users (sub, pass_bcrypt, tenant, auth_level, role) VALUES (?, ?, ?, ?, ?)",
                sub, passBcrypt, tenant, authLevel, role);
    }

    /** 置 disabled 并同步 bump token_ver（吊销即时生效，即便已有未过期 token）。 */
    public void setDisabled(String sub, boolean disabled) {
        jdbc.update("UPDATE users SET disabled = ?, token_ver = token_ver + 1, "
                + "updated_at = CURRENT_TIMESTAMP WHERE sub = ?", disabled, sub);
    }

    public void bumpTokenVer(String sub) {
        jdbc.update("UPDATE users SET token_ver = token_ver + 1, updated_at = CURRENT_TIMESTAMP WHERE sub = ?", sub);
    }

    public List<String> listSubs() {
        return jdbc.queryForList("SELECT sub FROM users ORDER BY sub", String.class);
    }
}
