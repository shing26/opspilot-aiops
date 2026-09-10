package com.opspilot.auth;

import java.nio.file.Path;

/**
 * H2 BACKUP/RESTORE 目标路径的白名单校验（UserStore 与 UserAdminCli 共用）。
 * H2 的 BACKUP TO / RESTORE FROM 是命令语句，文件名字面量不支持参数绑定——
 * 因此以严格白名单替代：必须是 <cwd>/backup/ 下的 *.zip，且不含引号/分号/反斜杠/穿越段。
 */
public final class H2BackupPath {

    private H2BackupPath() {}

    public static String safe(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("备份路径为空");
        }
        if (raw.matches(".*['\"\\\\;].*")) {
            throw new IllegalArgumentException("路径含非法字符: " + raw);
        }
        Path p = Path.of(raw).toAbsolutePath().normalize();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().resolve("backup");
        String name = p.getFileName().toString();
        if (!p.startsWith(root) || !name.endsWith(".zip") || !name.matches("[\\w.\\-]+\\.zip")) {
            throw new IllegalArgumentException("路径必须为 backup/ 目录下的合法 .zip（防穿越/注入）: " + raw);
        }
        return p.toString().replace('\\', '/');
    }
}
