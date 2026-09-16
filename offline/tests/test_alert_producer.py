"""自举告警生产者的判定与闸门回归锁（ADR-0011）。

为什么这些用例必须存在：告警链路的失效方式不是"报错"，而是**静默错判**——
计数器归零被当成负增量、配额/预算闸门顺序颠倒、健康面读成默认值，
三者都会让 watcher 看起来在正常工作却发出错误或不发告警。故用纯函数级用例锁死。

不触网：detect/deltas/gate 全部以合成 state 驱动，网络部分由活体验收覆盖。
"""
from __future__ import annotations

import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
import alert_producer as ap  # noqa: E402
import localapi  # noqa: E402


def _state(metrics=None, health=None, degradation=None, quota=None, inflight=0) -> dict:
    return {
        "metrics": metrics or {},
        "health": health or {c: {"status": "UP"} for c in ("redis", "qdrant", "es")},
        "runtime": {"degradation": degradation or {"level": "L0", "failures": 0, "threshold": 3,
                                                   "cooldown_s": 0},
                    "quota": quota or {"limit": 5000, "used": 0, "sub": "sre-watcher"},
                    "inflight": inflight},
    }


def _producer(**kw):
    args = types.SimpleNamespace(user="sre-watcher", dry_run=False, cooldown=300, max_per_hour=20,
                                 quota_reserve=50)
    for k, v in kw.items():
        setattr(args, k, v)
    return ap.Producer(args)


# ---- 计数器增量与归零 ------------------------------------------------------

def test_first_cycle_has_no_deltas():
    """首轮没有基准，不得凭历史计数造告警（否则重启后第一轮必误报）。"""
    d, reset = ap.deltas(ap.metrics_of(_state(metrics={"llm_calls": 99, "retrieval_timeouts": 7})), None)
    assert set(d.values()) == {0} and reset is False


def test_counter_reset_yields_zero_delta_and_flag():
    """进程内计数器重启归零：负增量必须当复位（记 0 + 置位），不得造负告警或静默掩盖。"""
    prev = ap.metrics_of(_state(metrics={"retrieval_timeouts": 9, "es_only_requests": 4}))
    cur = ap.metrics_of(_state(metrics={"retrieval_timeouts": 0, "es_only_requests": 0}))
    d, reset = ap.deltas(cur, prev)
    assert reset is True and d["retrieval_timeouts"] == 0 and d["es_only_requests"] == 0
    assert ap.detect(_state(metrics={"retrieval_timeouts": 0, "es_only_requests": 0}), d) == []


def test_deltas_track_growth():
    prev = ap.metrics_of(_state(metrics={"retrieval_timeouts": 2, "es_only_requests": 1}))
    cur = ap.metrics_of(_state(metrics={"retrieval_timeouts": 5, "es_only_requests": 3}))
    d, reset = ap.deltas(cur, prev)
    assert reset is False and d["retrieval_timeouts"] == 3 and d["es_only_requests"] == 2


# ---- 每条规则的触发与不触发 ------------------------------------------------

def test_healthy_and_quiet_state_emits_nothing():
    assert ap.detect(_state(), {k: 0 for k in ap.METRIC_KEYS}) == []


def test_dependency_down_is_per_component():
    st = _state(health={"redis": {"status": "UP"}, "qdrant": {"status": "DOWN", "detail": "conn refused"},
                        "es": {"status": "UP"}})
    got = ap.detect(st, {k: 0 for k in ap.METRIC_KEYS})
    assert [a["rule"] for a in got] == ["dep_down"]
    assert got[0]["service"] == "qdrant" and "qdrant" in got[0]["query"]


def test_degrade_triggers_on_level_and_on_cooldown():
    quiet = {k: 0 for k in ap.METRIC_KEYS}
    l1 = ap.detect(_state(degradation={"level": "L1", "failures": 1, "threshold": 3, "cooldown_s": 0}), quiet)
    assert [a["rule"] for a in l1] == ["degrade"]
    # 熔断冷却期档位显示 L0 但 cooldown>0（半开前）同样必须报——只看 level 会漏
    cool = ap.detect(_state(degradation={"level": "L0", "failures": 0, "threshold": 3, "cooldown_s": 42}), quiet)
    assert [a["rule"] for a in cool] == ["degrade"] and "42" in cool[0]["query"]


def test_metric_rules_need_their_companion_signal():
    only_timeouts = {k: 0 for k in ap.METRIC_KEYS}
    only_timeouts["retrieval_timeouts"] = 3
    assert ap.detect(_state(), only_timeouts) == []          # 单腿超时不足以下"双路退化"结论
    both = dict(only_timeouts, es_only_requests=2)
    got = ap.detect(_state(), both)
    assert [a["rule"] for a in got] == ["retrieval_degraded"]


def test_upstream_and_refusal_rules():
    d = {k: 0 for k in ap.METRIC_KEYS}
    d["llm_rate_limited"] = 1
    assert [a["rule"] for a in ap.detect(_state(), d)] == ["upstream_limited"]
    d2 = {k: 0 for k in ap.METRIC_KEYS}
    d2["sop_fallbacks"] = 2
    assert [a["rule"] for a in ap.detect(_state(), d2)] == ["refusal_spike"]


# ---- 发送闸门：顺序即优先级 -------------------------------------------------

def test_gate_order_budget_beats_quota_beats_cooldown():
    p = _producer(max_per_hour=1)
    p.emits = [__import__("time").time()]                    # 已用满小时预算
    low_q = _state(quota={"limit": 100, "used": 99})
    alert = {"rule": "dep_down", "service": "qdrant", "query": "q"}
    assert p.gate(alert, low_q, 0.0)["skip"] == "hour_budget"

    p2 = _producer()
    assert p2.gate(alert, low_q, 0.0)["skip"] == "quota_reserve"

    p3 = _producer(cooldown=300)
    p3.last_emit["dep_down"] = 1000.0
    assert p3.gate(alert, _state(), 1200.0)["skip"] == "cooldown"
    assert "skip" not in p3.gate(alert, _state(), 1400.0)   # 冷却以"真的发出去"计时


def test_gate_skips_are_recorded_with_rule_and_query():
    """每个 skip 都要带 rule/query——否则事后问"为什么没发"时无从回答。"""
    p = _producer()
    rec = p.gate({"rule": "degrade", "service": "opspilot-gateway", "query": "qq"},
                 _state(quota={"limit": 100, "used": 99}), 0.0)
    assert rec["emit"] is False and rec["skip"] == "quota_reserve"
    assert rec["rule"] == "degrade" and rec["query"] == "qq"


def test_dry_run_sends_nothing():
    p = _producer(dry_run=True)
    rec = p.emit({"rule": "dep_down", "service": "qdrant", "query": "q"})
    assert rec["emit"] is False and rec["skip"] == "dry_run"


# ---- 出口白名单（SSRF 面）-------------------------------------------------

def test_network_primitives_refuse_non_local_targets():
    for bad in ("http://evil.example.com/v1", "https://127.0.0.1:8081/x", "file:///etc/passwd"):
        try:
            localapi.assert_local(bad)
        except ValueError:
            continue
        raise AssertionError("非本机 http 目标必须被拒绝: " + bad)


# ---- 鉴权失败的两条路径（401 自愈 / 403 致命）-----------------------------

def _http_error(code: int):
    import urllib.error
    return urllib.error.HTTPError("http://localhost:8081/x", code, "err", {}, None)


def test_fetch_state_relogins_on_401(monkeypatch):
    """24h token 过期是常态：401 必须换新 token 重试一次，而不是让 watcher 停摆。"""
    p = _producer()
    calls = {"login": 0, "get": 0}

    def fake_login(username=None):
        calls["login"] += 1
        p.token = "fresh-token"
        return p.token

    def fake_get(path, token, timeout=10):
        calls["get"] += 1
        if calls["get"] == 1:
            raise _http_error(401)
        return {"metrics": {}, "runtime": {}}

    monkeypatch.setattr(ap.localapi, "login", fake_login)
    monkeypatch.setattr(ap.localapi, "get_json", fake_get)
    assert p.fetch_state() == {"metrics": {}, "runtime": {}}
    assert calls == {"login": 1, "get": 2} and p.token == "fresh-token"


def test_fetch_state_403_is_fatal_and_not_retried(monkeypatch):
    """403 = 主体权限配置错（非 platform/level<3），重试无意义——必须快速失败而非空转。"""
    p = _producer()
    calls = {"get": 0}

    def fake_get(path, token, timeout=10):
        calls["get"] += 1
        raise _http_error(403)

    monkeypatch.setattr(ap.localapi, "get_json", fake_get)
    try:
        p.fetch_state()
    except PermissionError:
        assert calls["get"] == 1
        return
    raise AssertionError("403 必须抛 PermissionError（退出码 2），不得静默继续")


def test_run_survives_unreachable_gateway(monkeypatch):
    """网关不可达（自举的固有盲区）：单轮必须失败-留痕-继续，绝不把 watcher 打死。"""
    p = _producer()
    writes = []
    monkeypatch.setattr(p, "ledger", lambda rec: writes.append(rec))
    monkeypatch.setattr(p, "login", lambda: None)
    monkeypatch.setattr(p, "cycle", lambda: (_ for _ in ()).throw(ConnectionRefusedError("refused")))
    assert p.run(interval=0) == 0                      # 不可达不是致命错误，进程照常收尾
    assert p.stats["errors"] == 1
    assert writes and writes[0]["skip"] == "unreachable" and writes[0]["emit"] is False
