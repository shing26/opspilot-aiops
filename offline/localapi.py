"""本机 OpsPilot API 共享辅助 —— 验收/评测脚本的单一事实源。

收敛此前散落在 acceptance_a2 / acceptance_a3 / evaluate / console_client 的
重复实现（_assert_local / load_tokens / search_docs / post）。
安全口径不变：仅允许访问本机 http 服务（localhost 白名单），阻断 SSRF。
token 路径相对本文件解析，不再依赖运行目录（修复 a3/evaluate 的 cwd 脆弱）。
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

BASE = "http://localhost:8081"
ALLOWED_HOSTS = {"localhost", "127.0.0.1", "::1"}
_TOKENS = Path(__file__).resolve().parent.parent / "scripts" / "redteam_tokens.txt"

# 合法账号名 → 登录用户名（P2 起口令登录，token 不再落盘）。口令只从 DEMO_PASSWORD 环境变量读。
ACCOUNTS = {"sre_l1": "sre-limited", "sre_l3": "sre-full", "sre_acme": "sre-acme"}


def assert_local(url: str) -> str:
    p = urlparse(url)
    if p.scheme != "http" or p.hostname not in ALLOWED_HOSTS:
        raise ValueError(f"验收脚本仅允许本机 http 服务，拒绝: {url}")
    return url


def get_json(path: str, token: str, timeout: int = 10) -> dict:
    req = urllib.request.Request(assert_local(BASE + path), method="GET",
                                 headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def login(username: str, password: str | None = None) -> str:
    """P2 账号体系：/api/v1/auth/login 换 24h token。口令缺省读 DEMO_PASSWORD 环境变量。

    复用 post_json（localhost 白名单校验内嵌），不新增网络调用原语；
    登录路由不在 JwtAuthFilter 守卫清单，空 Authorization 头被服务端忽略。"""
    import os
    pw = password or os.environ.get("DEMO_PASSWORD")
    if not pw:
        raise RuntimeError("DEMO_PASSWORD 环境变量未设置（demo 账号口令只从环境读取）")
    return post_json("/api/v1/auth/login", {"username": username, "password": pw},
                     token="")["token"]


def load_tokens() -> dict:
    """兼容旧调用名的统一入口：合法账号=实时 login，红队畸形 token=读 redteam_tokens.txt。

    返回键与既有脚本一致：sre_l1/sre_l3/sre_acme + sre_l0/sre_neg/tenant_none/tenant_blank/tenant_long。
    """
    out = {name: login(user) for name, user in ACCOUNTS.items()}
    if _TOKENS.exists():
        with open(_TOKENS, encoding="utf-8") as fh:
            for line in fh:
                if "=" in line:
                    k, v = line.strip().split("=", 1)
                    out[k] = v
    return out


def post_json(path: str, body: dict, token: str, timeout: int = 60) -> dict:
    req = urllib.request.Request(
        assert_local(BASE + path), data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def search_docs(query: str, mode: str, token: str) -> list[dict]:
    """非流式 /search，直接返回 results 列表。timeout=30 保持 a3/evaluate 原口径。"""
    return post_json("/api/v1/copilot/search", {"query": query, "mode": mode}, token, timeout=30)["results"]


def expect_http_status(path: str, body: dict, token: str, expected: int) -> tuple[bool, int]:
    """断言端点返回指定状态码；返回 (是否符合, 实际码)。"""
    req = urllib.request.Request(
        assert_local(BASE + path), data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status == expected, r.status
    except urllib.error.HTTPError as e:
        return e.code == expected, e.code
