package com.opspilot.auth;

import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 用户管理 CLI（P2）：直连 H2 文件库（AUTO_SERVER 允许与运行中的网关并存）。
 * 密码只从 --password-env 指定的环境变量或交互 stdin 读取，绝不进 argv（避免 ps 泄露）。
 * 不进 Spring 容器、无 HTTP 面：{@code java -cp app.jar com.opspilot.auth.UserAdminCli <cmd> ...}。
 * 包装见 scripts/user_admin.sh。
 */
public final class UserAdminCli {

    private UserAdminCli() {}

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) { usage(); System.exit(2); }
        String url = env("H2_DB_URL", "jdbc:h2:file:./data/users;AUTO_SERVER=TRUE");
        String dbUser = env("H2_DB_USER", "opspilot");
        String dbPass = System.getenv().getOrDefault("H2_DB_PASSWORD", "");
        String cmd = argv[0];
        try (Connection c = DriverManager.getConnection(url, dbUser, dbPass)) {
            ensureTable(c);
            switch (cmd) {
                case "add" -> add(c, require(argv, "--user"), require(argv, "--tenant"),
                        Integer.parseInt(require(argv, "--level")), require(argv, "--role"), readPw(argv));
                case "passwd" -> passwd(c, require(argv, "--user"), readPw(argv));
                case "disable" -> flag(c, "disable", require(argv, "--user"), true);
                case "enable" -> flag(c, "enable", require(argv, "--user"), false);
                case "rotate" -> rotate(c, require(argv, "--user"));
                case "list" -> list(c);
                default -> { usage(); System.exit(2); }
            }
        }
    }

    private static void ensureTable(Connection c) throws Exception {
        try (var st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "sub VARCHAR(64) PRIMARY KEY, pass_bcrypt VARCHAR(100) NOT NULL,"
                    + " tenant VARCHAR(64) NOT NULL, auth_level INT NOT NULL,"
                    + " CHECK (auth_level BETWEEN 1 AND 9), role VARCHAR(32) NOT NULL,"
                    + " disabled BOOLEAN NOT NULL DEFAULT FALSE, token_ver INT NOT NULL DEFAULT 1,"
                    + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private static void add(Connection c, String sub, String tenant, int level, String role, String pw)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (sub, pass_bcrypt, tenant, auth_level, role) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sub);
            ps.setString(2, new BCryptPasswordEncoder(10).encode(pw));
            ps.setString(3, tenant);
            ps.setInt(4, level);
            ps.setString(5, role);
            ps.executeUpdate();
        }
        System.out.println("added user=" + sub + " tenant=" + tenant + " level=" + level);
    }

    private static void passwd(Connection c, String sub, String pw) throws Exception {
        // 改密同时 bump token_ver：旧 token 全失效
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET pass_bcrypt = ?, token_ver = token_ver + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setString(1, new BCryptPasswordEncoder(10).encode(pw));
            ps.setString(2, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println("password rotated + tokens invalidated for " + sub);
    }

    private static void flag(Connection c, String cmd, String sub, boolean disabled) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET disabled = ?, token_ver = token_ver + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setBoolean(1, disabled);
            ps.setString(2, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println(cmd + " ok: " + sub);
    }

    private static void rotate(Connection c, String sub) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET token_ver = token_ver + 1, updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setString(1, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println("token_ver bumped (all tokens revoked) for " + sub);
    }

    private static void list(Connection c) throws Exception {
        try (var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sub, tenant, auth_level, role, disabled, token_ver FROM users ORDER BY sub")) {
            while (rs.next()) {
                System.out.printf("%-16s tenant=%-16s level=%d role=%-8s disabled=%-5s ver=%d%n",
                        rs.getString("sub"), rs.getString("tenant"), rs.getInt("auth_level"),
                        rs.getString("role"), rs.getBoolean("disabled"), rs.getInt("token_ver"));
            }
        }
    }

    /** 密码来源：--password-env VAR 读环境变量；否则交互式 stdin。绝不从 argv 取明文。 */
    private static String readPw(String[] argv) {
        String envName = opt(argv, "--password-env");
        if (envName != null) {
            String v = System.getenv(envName);
            if (v == null || v.isBlank()) throw new IllegalStateException("env " + envName + " 未设置");
            return v;
        }
        Console con = System.console();
        if (con == null) throw new IllegalStateException("非交互环境请用 --password-env 指定口令环境变量");
        char[] p = con.readPassword("password: ");
        return new String(p);
    }

    private static String require(String[] argv, String name) {
        String v = opt(argv, name);
        if (v == null) throw new IllegalArgumentException("缺少参数 " + name);
        return v;
    }

    private static String opt(String[] argv, String name) {
        for (int i = 0; i < argv.length - 1; i++) {
            if (name.equals(argv[i])) return argv[i + 1];
        }
        return null;
    }

    private static String env(String k, String def) {
        return System.getenv().getOrDefault(k, def);
    }

    private static void usage() {
        System.err.println(String.join("\n", Arrays.asList(
                "用法: UserAdminCli <cmd> [opts]",
                "  add     --user U --tenant T --level N --role R [--password-env VAR]",
                "  passwd  --user U [--password-env VAR]   (同时吊销旧 token)",
                "  disable --user U | enable --user U",
                "  rotate  --user U                        (仅吊销 token，不动口令)",
                "  list")));
    }
}
