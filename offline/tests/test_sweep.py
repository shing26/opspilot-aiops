# -*- coding: utf-8 -*-
"""并发曲线脚本的纯函数回归锁（`offline/load/sweep.py`）。

只锁两件**不需要活体栈**的事——它们在 CI 里跑得动，而曲线本身要活体栈：

  1. **locust `_stats.csv` 的解析**：字段名是 locust 的外部契约（`Requests/s`、`50%`、`Aggregated`），
     上游改列名就会静默解析成 None，报告里出现一列"—"而没人知道为什么。用**真实入库产物**
     （`load/reports/locust_a_stats.csv`）做一次真值断言，比合成夹具更能钉住这个契约。
  2. **失败率的分母语义**：总请求数为 0 时必须返回 None（"没测"），不得返回 0.0——
     0% 会被读成"零失败"，而实际是"根本没跑"。
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent
LOAD = OFFLINE / "load"

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把某条不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
sys.path.insert(0, str(LOAD))
import sweep  # noqa: E402


def _write_csv(tmp_path: Path, rows: list[dict]) -> Path:
    cols = ["Type", "Name", "Request Count", "Failure Count", "Requests/s",
            "50%", "95%", "99%", "Max Response Time"]
    p = tmp_path / "stats.csv"
    lines = [",".join(cols)]
    for r in rows:
        lines.append(",".join(str(r[c]) for c in cols))
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p


def test_parses_aggregated_row(tmp_path):
    p = _write_csv(tmp_path, [
        {"Type": "", "Name": "/api/v1/x", "Request Count": 10, "Failure Count": 1,
         "Requests/s": 1.5, "50%": 10, "95%": 20, "99%": 30, "Max Response Time": 40},
        {"Type": "", "Name": "Aggregated", "Request Count": 100, "Failure Count": 2,
         "Requests/s": 12.5, "50%": 16, "95%": 2000, "99%": 12000, "Max Response Time": 18184},
    ])
    st = sweep.parse_stats_csv(p)
    assert st["requests"] == 100, "必须取 Aggregated 行，不是第一行"
    assert st["failures"] == 2
    assert st["rps"] == pytest.approx(12.5)
    assert st["p50_ms"] == 16 and st["p99_ms"] == 12000


def test_missing_aggregated_row_is_loud(tmp_path):
    """没有 Aggregated 行必须抛错——静默返回空字典会让报告出现整片"—"而无从归因。"""
    p = _write_csv(tmp_path, [
        {"Type": "", "Name": "/api/v1/x", "Request Count": 1, "Failure Count": 0,
         "Requests/s": 1, "50%": 1, "95%": 1, "99%": 1, "Max Response Time": 1},
    ])
    with pytest.raises(ValueError):
        sweep.parse_stats_csv(p)


def test_failure_rate_zero_requests_is_none_not_zero():
    """0 请求 → None（没测），不得 0.0（零失败）——两者运维结论完全相反。"""
    assert sweep.failure_rate({"requests": 0, "failures": 0}) is None
    assert sweep.failure_rate({"requests": 100, "failures": 0}) == 0.0
    assert sweep.failure_rate({"requests": 100, "failures": 3}) == pytest.approx(0.03)


def test_real_committed_artifact_parses_to_readme_numbers():
    """用**入库的真实产物**钉住 locust 的列名契约（合成夹具挡不住上游改列名）。"""
    st = sweep.parse_stats_csv(LOAD / "reports" / "locust_a_stats.csv")
    assert st["requests"] > 0 and st["failures"] == 0
    assert st["p50_ms"] == 16.0, "场景 A 的 P50 是 README 引用值，列名漂了这里就红"


def test_degradation_rank_is_ordered():
    """档位排序用于取"运行期间最高档"——顺序错了会把 L1 判成没降级。"""
    assert sweep.LEVEL_RANK["L0"] < sweep.LEVEL_RANK["L1"] < sweep.LEVEL_RANK["L2"]

# ---------------------------------------------------------------- 失败明细

def test_parse_failures_csv_separates_connection_from_app_errors(tmp_path):
    """失败**性质**必须可与计数分开看：`HTTP 0`=连接层（容量极限），`HTTP 5xx`=应用层（缺陷）。"""
    p = tmp_path / "lvl_200_failures.csv"
    p.write_text("Method,Name,Error,Occurrences,First Seen,Last Seen\n"
                 "POST,/api/v1/copilot/chat/stream,\"CatchResponseError('HTTP 0')\",3715,t0,t1\n"
                 "POST,/api/v1/copilot/chat/stream,\"CatchResponseError('HTTP 500')\",7,t0,t1\n",
                 encoding="utf-8")
    got = sweep.parse_failures_csv(p)
    assert len(got) == 2
    assert got[0]["occurrences"] == 3715 and "HTTP 0" in got[0]["error"]
    assert "HTTP 500" in got[1]["error"]


def test_parse_failures_csv_empty_and_missing(tmp_path):
    """无失败时 locust 仍会写表头——必须返回空列表而不是一条假记录。"""
    p = tmp_path / "f.csv"
    p.write_text("Method,Name,Error,Occurrences,First Seen,Last Seen\n", encoding="utf-8")
    assert sweep.parse_failures_csv(p) == []
    assert sweep.parse_failures_csv(tmp_path / "nope.csv") == []


# ---------------------------------------------------------------- 实到并发

def test_parse_max_users_reads_history(tmp_path):
    """实到并发取自 stats_history 的 User Count 列——它是判断"标签是否等于事实"的唯一依据。"""
    p = tmp_path / "h.csv"
    p.write_text("Timestamp,User Count,Total RPS\n1,10,1\n2,200,5\n3,290,9\n", encoding="utf-8")
    assert sweep.parse_max_users(p) == 290


def test_parse_max_users_missing_or_empty(tmp_path):
    assert sweep.parse_max_users(tmp_path / "nope.csv") is None
    p = tmp_path / "e.csv"
    p.write_text("", encoding="utf-8")
    assert sweep.parse_max_users(p) is None


def test_ramp_rate_reaches_target_within_ramp_seconds():
    """ramp 速率必须按档位自适应：固定 `-r 10` + 30s 只能爬到 300 用户，
    于是「u=500」那档实到只有 290——报告标签与事实不符（实测踩过）。"""
    ramp = 5
    for level in (25, 50, 100, 200, 300, 500):
        rate = max(1, round(level / ramp))
        assert rate * ramp >= level, f"u={level} 在 {ramp}s 内爬不到目标（rate={rate}）"


# ---------------------------------------------------------------- locust 退出码语义

def _fake_run(stats_path, returncode):
    def _run(cmd, **kw):
        if stats_path is not None:
            stats_path.parent.mkdir(parents=True, exist_ok=True)
            stats_path.write_text("Type,Name\n,Aggregated\n", encoding="utf-8")
        return type("P", (), {"returncode": returncode})()
    return _run


def test_locust_command_disables_failure_exit_code(tmp_path, monkeypatch):
    """**核心修复的锁**：必须带 `--exit-code-on-error 0`。

    locust 默认在任何失败时以 1 退出，而本脚本测的就是失败率——失败是**被测量的量**。
    首版用 check=True 把 u=200 的连接层失败当致命错，整个 sweep 崩在那档、连拐点数据一起丢。
    变异验证：去掉该 flag 或改回 check=True → 本用例必红。
    """
    seen = {}

    def _run(cmd, **kw):
        seen["cmd"] = cmd
        seen["check"] = kw.get("check")
        (tmp_path / "lvl_9_stats.csv").write_text("Type,Name\n,Aggregated\n", encoding="utf-8")
        return type("P", (), {"returncode": 1})()

    monkeypatch.setattr(sweep, "RAW", tmp_path)
    monkeypatch.setattr(sweep.subprocess, "run", _run)
    sweep._run_locust(9, "1s", 1)
    assert "--exit-code-on-error" in seen["cmd"]
    assert seen["cmd"][seen["cmd"].index("--exit-code-on-error") + 1] == "0"
    assert seen["check"] is False, "不得用 check=True——失败是数据不是错误"


def test_locust_real_crash_still_raises(tmp_path, monkeypatch):
    """真崩（参数错/依赖缺 ⇒ 无 stats.csv）仍必须报错——不能把"没产出"也吞掉。"""
    monkeypatch.setattr(sweep, "RAW", tmp_path)
    monkeypatch.setattr(sweep.subprocess, "run", _fake_run(None, 2))
    with pytest.raises(RuntimeError, match="未产出 stats.csv"):
        sweep._run_locust(9, "1s", 1)


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
