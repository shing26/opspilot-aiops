# -*- coding: utf-8 -*-
"""核色前置脚本的回归锁（`scripts/check_upstream.py`）。

锁两件事，都不触网（假 opener 注入）：

  1. **判定语义**：`all_ok` 必须是"全通过才算通过"——**不允许部分通过**。理由不是洁癖：
     任一路断都会让核色数字无效，而系统降级会静默掩盖它（embedding 断→检索退 `es_only`；
     LLM 断→熔断直出 SOP，两者 HTTP 均 200）。若这里放水成 any，门闩就失去意义。
  2. **错误归因可读**：欠费要能被点出 `code=Arrearage`（而不是只报"HTTP 400"），
     否则运维拿到的是一个需要二次排查的含糊信号。
"""
from __future__ import annotations

import io
import sys
import urllib.error
import urllib.request
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
SCRIPTS = REPO / "scripts"

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
sys.path.insert(0, str(SCRIPTS))
import check_upstream as cu  # noqa: E402

URL = "https://example.invalid/v1/x"
BODY = {"model": "m"}


class _Resp:
    def __init__(self, status=200):
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _opener(status=200, err_body: bytes | None = None, exc: Exception | None = None):
    def _open(req, timeout=None):
        if exc is not None:
            raise exc
        if err_body is not None:
            raise urllib.error.HTTPError(req.full_url, status, "err", {}, io.BytesIO(err_body))
        return _Resp(status)
    return _open


def test_probe_ok_on_200():
    r = cu.probe("x", URL, BODY, "k", opener=_opener(200))
    assert r["ok"] is True and r["status"] == 200 and r["code"] is None


def test_probe_surfaces_arrearage_code():
    """欠费必须点名到 `code=Arrearage`——只报 HTTP 400 会让运维二次排查。"""
    body = b'{"error":{"message":"Access denied...","code":"Arrearage"}}'
    r = cu.probe("x", URL, BODY, "k", opener=_opener(400, body))
    assert r["ok"] is False and r["status"] == 400 and r["code"] == "Arrearage"


def test_probe_tolerates_non_json_error_body():
    """错误体不是 JSON（网关/代理返回 HTML 等）时不得崩，仍要给出 ok=False。"""
    r = cu.probe("x", URL, BODY, "k", opener=_opener(502, b"<html>bad gateway</html>"))
    assert r["ok"] is False and r["status"] == 502 and r["code"] is None


def test_probe_reports_network_layer_failure():
    r = cu.probe("x", URL, BODY, "k", opener=_opener(exc=TimeoutError("boom")))
    assert r["ok"] is False and r["status"] is None and r["code"] == "TimeoutError"


def test_all_ok_requires_every_endpoint():
    """**核心语义**：全通过才算通过。变异验证：改成 any(...) → 本用例必红。"""
    assert cu.all_ok([{"ok": True}, {"ok": True}]) is True
    assert cu.all_ok([{"ok": True}, {"ok": False}]) is False, "任一路断即整体不过"
    assert cu.all_ok([]) is False, "空结果不得当通过"


def test_main_exits_2_without_api_key(monkeypatch, capsys):
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    assert cu.main([]) == 2, "没 key 时无从探测，且核色口径不同（mock 后端）"


def test_main_exits_0_when_all_pass(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "dummy")
    monkeypatch.setattr(urllib.request, "urlopen", _opener(200))
    assert cu.main(["--quiet"]) == 0


def test_main_exits_1_when_any_endpoint_fails(monkeypatch):
    """只要有一路被拒就必须非 0 退出——这是"停下、别把降级态当基线"的机器判据。"""
    monkeypatch.setenv("DASHSCOPE_API_KEY", "dummy")
    calls = {"n": 0}

    def flaky(req, timeout=None):
        calls["n"] += 1
        if calls["n"] == 2:                      # 只让第二路（embedding）失败
            raise urllib.error.HTTPError(req.full_url, 400, "err", {},
                                         io.BytesIO(b'{"error":{"code":"Arrearage"}}'))
        return _Resp(200)

    monkeypatch.setattr(urllib.request, "urlopen", flaky)
    assert cu.main(["--quiet"]) == 1


def test_probes_cover_the_three_endpoints():
    """三路缺一不可：少一路就等于留一条"静默降级"的盲区。"""
    names = " ".join(n for n, _, _ in cu.PROBES)
    assert "qwen-plus" in names and "text-embedding-v3" in names and "gte-rerank-v2" in names


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
