# -*- coding: utf-8 -*-
"""真实输入探测的回归锁（`offline/eval/observed_probe.py`）。

锁两件事——它们都不需要活体栈，且失效时都不会自己报错：

  1. **聚合输出里不得出现 query 文本**。`logs/` 含查询内容与租户标识，项目纪律是"不外发"；
     本脚本的产物要入库，所以"报告只含聚合计数"必须是**结构性保证**而不是靠人记得别加字段。
     判据：`summarize` 的返回值里除已知的数值字段外不得有字符串——一旦有人把 query 塞进报告，
     这条立刻红。
  2. **无样本时比率必须是 None**（"没测"），不得是 0.0（"零召回/零拒答"）——两者结论相反。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent
EVAL = OFFLINE / "eval"

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把某条不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
sys.path.insert(0, str(OFFLINE))
sys.path.insert(0, str(EVAL))
import observed_probe as op  # noqa: E402


def _row(n, fast=False, top=None, code=False):
    return {"n_results": n, "fast_path": fast, "top_rerank": top, "has_code": code}


# ---------------------------------------------------------------- 聚合口径

def test_summarize_counts_zero_recall_fast_path_and_gate():
    rows = [
        _row(3, top=0.9, code=True, fast=True),   # 有码且快路径命中
        _row(3, top=0.05, code=True),             # 有码但未命中快路径，且低相关 → 门控拒
        _row(0),                                  # 零召回
        _row(2, top=0.8),                         # 正常作答
    ]
    agg = op.summarize(rows, threshold=0.2)
    assert agg["samples"] == 4
    assert agg["zero_recall"] == 1 and agg["zero_recall_rate"] == pytest.approx(0.25)
    assert agg["with_code"] == 2 and agg["fast_path_hits"] == 1
    assert agg["fast_path_hit_rate"] == pytest.approx(0.5)
    assert agg["gate_refusal_rate"] == pytest.approx(0.5)   # 零召回 1 + 低相关 1


def test_fast_path_rate_is_none_when_no_query_has_a_code():
    """没有含码 query 时，"快路径命中率"必须是 None——0% 会被读成"一条都没命中"。"""
    agg = op.summarize([_row(3, top=0.9), _row(2, top=0.8)])
    assert agg["with_code"] == 0
    assert agg["fast_path_hit_rate"] is None


def test_zero_samples_yields_none_not_zero():
    agg = op.summarize([])
    assert agg["samples"] == 0
    for k in ("zero_recall_rate", "fast_path_hit_rate", "gate_refusal_rate"):
        assert agg[k] is None, f"{k} 无样本时必须为 None（没测），不得为 0（零发生）"


def test_summarize_output_carries_no_string_values():
    """**结构性锁**：聚合结果里不得出现任何字符串——报告要入库，而 query 文本不得外发。

    一旦有人把 query（或租户标识）塞进聚合返回，这条立刻红。数值/None 之外的任何类型都算违规。
    """
    rows = [_row(3, top=0.9, code=True, fast=True), _row(0), _row(2, top=0.1)]
    for k, v in op.summarize(rows).items():
        assert isinstance(v, (int, float)) or v is None, \
            f"聚合字段 {k} 含非数值内容（{type(v).__name__}）——报告只允许聚合计数，query 文本不得入库"


# ---------------------------------------------------------------- 运行史取样

def _write_audit(p: Path, events: list[dict]) -> Path:
    p.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in events) + "\n", encoding="utf-8")
    return p


def test_collect_queries_dedupes_and_prefers_latest(tmp_path):
    log = _write_audit(tmp_path / "audit.jsonl", [
        {"ev": "chat", "q": "旧问题", "source": "manual"},
        {"ev": "auth", "outcome": "denied"},                    # 非 chat：跳过
        {"ev": "chat", "q": "新问题", "source": "alert"},
        {"ev": "chat", "q": "旧问题", "source": "manual"},       # 重复：跳过
        {"ev": "chat", "q": "   ", "source": "manual"},          # 空白：跳过
    ])
    got = op.collect_queries(log, None, max_n=10)
    assert [g["query"] for g in got] == ["旧问题", "新问题"], "从最新往回取且去重"
    assert {g["source"] for g in got} == {"manual", "alert"}


def test_collect_queries_respects_max(tmp_path):
    log = _write_audit(tmp_path / "audit.jsonl",
                       [{"ev": "chat", "q": f"q{i}", "source": "manual"} for i in range(10)])
    assert len(op.collect_queries(log, None, max_n=3)) == 3


def test_collect_queries_missing_log_returns_empty(tmp_path):
    assert op.collect_queries(tmp_path / "nope.jsonl", None, max_n=10) == []


def test_collect_queries_tolerates_malformed_lines(tmp_path):
    """审计行可能被轮转截断——坏行跳过，不能让整轮探测失败。"""
    p = tmp_path / "audit.jsonl"
    p.write_text('{"ev":"chat","q":"好行","source":"manual"}\n{坏行\n', encoding="utf-8")
    got = op.collect_queries(p, None, max_n=10)
    assert [g["query"] for g in got] == ["好行"]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
