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


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
