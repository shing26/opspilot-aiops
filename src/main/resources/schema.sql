-- P2 账号体系主用户表（H2 文件库，ADR-0005）。
-- 密码只存 bcrypt 散列；token_ver 为吊销版本号：token claim 的 tver ≠ 表值 → 该用户全部存量 token 即时失效。
CREATE TABLE IF NOT EXISTS users (
    sub          VARCHAR(64)  PRIMARY KEY,
    pass_bcrypt  VARCHAR(100) NOT NULL,
    tenant       VARCHAR(64)  NOT NULL,
    auth_level   INT          NOT NULL CHECK (auth_level BETWEEN 1 AND 9),
    role         VARCHAR(32)  NOT NULL,
    disabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    token_ver    INT          NOT NULL DEFAULT 1,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
