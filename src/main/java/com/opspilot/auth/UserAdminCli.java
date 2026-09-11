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
        // H2 2.x 移除了 RESTORE SQL：走官方 Restore 工具（纯文件操作，目标库必须不存在=天然防误覆盖）
        if (cmd.equals("restore")) {
            restore(require(argv, "--from"), opt(argv, "--to-db"));
            return;
        }
        try (Connection c = open(url, dbUser, dbPass)) {
            ensureTable(c);
            switch (cmd) {
                case "add" -> add(c, require(argv, "--user"), require(argv, "--tenant"),
                        Integer.parseInt(require(argv, "--level")), require(argv, "--role"), readPw(argv));
                case "passwd" -> passwd(c, require(argv, "--user"), readPw(argv));
                case "role" -> role(c, require(argv, "--user"), require(argv, "--role"));
                case "disable" -> flag(c, "disable", require(argv, "--user"), true);
                case "enable" -> flag(c, "enable", require(argv, "--user"), false);
                case "rotate" -> rotate(c, require(argv, "--user"));
                case "backup" -> backup(c, require(argv, "--to"));
                case "list" -> list(c);
                default -> { usage(); System.exit(2); }
            }
        }
    }

    /** 双路连接：先嵌入式独占（网关未跑时的正道，零网络依赖）；文件被占则回退 AUTO_SERVER。 */
    private static Connection open(String url, String user, String pass) {
        try {
            return DriverManager.getConnection(url.replace(";AUTO_SERVER=TRUE", ""), user, pass);
        } catch (Exception embeddedLocked) {
            try {
                return DriverManager.getConnection(url, user, pass);
            } catch (Exception autoServerFailed) {
                throw new IllegalStateException(
                        "无法连接 H2：嵌入式被网关占用且 AUTO_SERVER 不可达。"
                        + "请停网关后重试，或在 Windows 防火墙放行 java 的 TCP 服务器（详见 OPS.md §7）",
                        autoServerFailed);
            }
        }
    }

    /** 在线热备份（H2 BACKUP 是事务一致的，取代危险的 cp 热拷）。 */
    private static void backup(Connection c, String to) throws Exception {
        try (var st = c.createStatement()) {
            st.execute("BACKUP TO '" + safeZipPath(to) + "'");
        }
        System.out.println("backup written: " + to);
    }

    /** 从 zip 恢复（Restore 工具要求目标库不存在，防误覆盖）；--to-db 支持恢复到演练库。 */
    private static void restore(String from, String toDb) throws Exception {
        String path = H2BackupPath.safe(from);
        String file = env("H2_DB_URL", "jdbc:h2:file:./data/users;AUTO_SERVER=TRUE")
                .replaceFirst("^jdbc:h2:file:", "").split(";")[0];
        java.io.File db = new java.io.File(file).getAbsoluteFile();
        String dir = db.getParent();
        String target = toDb != null ? toDb.replaceAll("[^\\w.\\-]", "") : db.getName();
        if (new java.io.File(dir, target + ".mv.db").exists()) {
            throw new IllegalStateException("目标库已存在: " + dir + "/" + target + ".mv.db —— "
                    + "恢复请先停网关并移走旧库（mv users.mv.db users.broken），演练库则换 --to-db 名或删掉");
        }
        org.h2.tools.Restore.execute(path, dir, target);
        System.out.println("restored " + path + " -> " + dir + "/" + target + ".mv.db");
    }

    /** SQL 字面量注入防线：委托与网关共用的白名单校验（H2BackupPath）。 */
    private static String safeZipPath(String raw) {
        return H2BackupPath.safe(raw);
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

    /** 变更角色（信任域维度，QA P0-2）：如 sre-full → platform。role 不进 token、每请求查 DB，
     *  故即时生效且无需 bump token_ver（区别于 passwd/disable 的吊销语义）。 */
    private static void role(Connection c, String sub, String role) throws Exception {
        if (!role.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("role 仅允许字母数字/_/-，长度≤32");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET role = ?, updated_at = CURRENT_TIMESTAMP WHERE sub = ?")) {
            ps.setString(1, role);
            ps.setString(2, sub);
            if (ps.executeUpdate() == 0) throw new IllegalStateException("no such user: " + sub);
        }
        System.out.println("role=" + role + " for " + sub + "（即时生效，不吊销 token）");
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
                "  role    --user U --role R               (信任域变更，如 platform；即时生效不吊销)",
                "  disable --user U | enable --user U",
                "  rotate  --user U                        (仅吊销 token，不动口令)",
                "  backup  --to backup/users-YYYYMMDD.zip  (在线热备份，H2 BACKUP)",
                "  restore --from <zip> [--to-db NAME]     (目标库须不存在；恢复前停网关并移走旧库)",
                "  list")));
    }
}
