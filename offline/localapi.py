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
_ALLOWED = {"localhost", "127.0.0.1", "::1"}
_TOKENS = Path(__file__).resolve().parent.parent / "scripts" / "demo_tokens.txt"


def assert_local(url: str) -> str:
    p = urlparse(url)
    if p.scheme != "http" or p.hostname not in _ALLOWED:
        raise ValueError(f"验收脚本仅允许本机 http 服务，拒绝: {url}")
    return url


def load_tokens() -> dict:
    """读演示凭据（sre_l1/sre_l3 + 红队 sre_l0/sre_neg）。"""
    out = {}
    with open(_TOKENS, encoding="utf-8") as fh:
        for line in fh:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                out[k] = v
    return out


def get_json(path: str, token: str, timeout: int = 10) -> dict:
    req = urllib.request.Request(assert_local(BASE + path), method="GET",
                                 headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def post_json(path: str, body: dict, token: str, timeout: int = 60) -> dict:
    req = urllib.request.Request(
        assert_local(BASE + path), data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def search_docs(query: str, mode: str, token: str) -> list[dict]:
    """非流式 /search，直接返回 results 列表。"""
    return post_json("/api/v1/copilot/search", {"query": query, "mode": mode}, token)["results"]


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
