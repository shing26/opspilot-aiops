# -*- coding: utf-8 -*-
"""门控判据的回归锁（`offline/eval/gate_matrix.py`）。

为什么这些用例必须存在：混淆矩阵的全部价值取决于两件事，两件都不会自己报错：

  1. **判据与 Java 同构**——`gate_refuses` 是 `ChatOrchestrator.runPipeline` 里
     `lowConfidence` 表达式的复刻。若 Java 改了判据而这里没跟，矩阵会安静地继续输出
     "看起来合理"的数字，而它测的已经不是线上那道门了。
  2. **拒答集的premise 为真**——`refuse_set.jsonl` 的"应拒答"半集成立的前提是：里面的
     幽灵错误码**确实不在语料里**。若哪天语料补进了某个 6xxxx 码，那些样本就不再是"应拒答"，
     漏拒率会被算错——而且没人会注意到。

第 2 条本文件用"现算语料错误码集合再求交"来锁（不是靠记性）。
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
import gate_matrix as gm  # noqa: E402
import grounding  # noqa: E402   # 错误码词法的单一事实源（勿在本文件另写正则）


# ---------------------------------------------------------------- 判据本身

def test_zero_recall_always_refuses():
    assert gm.gate_refuses(0, False, None, 0.2) is True
    assert gm.gate_refuses(0, True, None, 0.2) is True, "零召回优先于快路径豁免"


def test_fast_path_exempts_the_gate():
    """快路径豁免：含真实错误码且 ES Top-1 命中的 query 天然不参与相关性门控。"""
    assert gm.gate_refuses(3, True, 0.01, 0.2) is False


def test_low_relevance_refuses_and_high_relevance_answers():
    assert gm.gate_refuses(3, False, 0.1, 0.2) is True
    assert gm.gate_refuses(3, False, 0.9, 0.2) is False


def test_threshold_boundary_is_strict_less_than():
    """边界必须严格小于：`top == threshold` 不拒答。这与 Java 的 `<` 逐字符同构——
    写成 `<=` 会让阈值附近的样本整批翻面（变异验证：改 `<=` 本用例必红）。"""
    assert gm.gate_refuses(3, False, 0.2, 0.2) is False
    assert gm.gate_refuses(3, False, 0.199999, 0.2) is True


def test_missing_rerank_score_does_not_refuse():
    """无 rerank 分（None）不判拒答——不拿缺失值当低相关，避免静默误拒。"""
    assert gm.gate_refuses(3, False, None, 0.2) is False


# ---------------------------------------------------------------- 混淆矩阵

def _s(expect, n, fast=False, top=None):
    return {"expect": expect, "n_results": n, "fast_path": fast, "top_rerank": top}


def test_confusion_counts_both_error_directions():
    samples = [
        _s("answer", 3, top=0.9),      # 答对
        _s("answer", 3, top=0.05),     # 误拒（低相关）
        _s("answer", 0),               # 误拒（零召回）
        _s("answer", 2, fast=True, top=0.01),   # 快路径豁免 → 答对
        _s("refuse", 0),               # 拒对
        _s("refuse", 3, top=0.05),     # 拒对
        _s("refuse", 3, top=0.8),      # 漏拒（高相关）
    ]
    m = gm.confusion(samples, 0.2)
    assert m["correct_answers"] == 2
    assert m["false_refusals"] == 2
    assert m["correct_refusals"] == 2
    assert m["missed_refusals"] == 1
    assert m["false_refusal_rate"] == pytest.approx(0.5)     # 2/4
    assert m["missed_refusal_rate"] == pytest.approx(1 / 3)  # 1/3
    assert m["zero_recall_among_false_refusals"] == 1, "零召回误拒必须单列——它是检索问题不是阈值问题"


def test_higher_threshold_trades_availability_for_trust():
    """阈值升高：误拒率上升（更爱拒答）、漏拒率下降——这是同一枚硬币的两面。"""
    samples = [_s("answer", 3, top=0.25), _s("refuse", 3, top=0.25)]
    low, high = gm.confusion(samples, 0.1), gm.confusion(samples, 0.4)
    assert low["false_refusal_rate"] == 0.0 and low["missed_refusal_rate"] == 1.0
    assert high["false_refusal_rate"] == 1.0 and high["missed_refusal_rate"] == 0.0


def test_empty_sides_yield_none_not_zero():
    """单侧无样本时比率必须是 None（不编 0%）——0% 会被读成"完美"，而实际是"没测"。"""
    m = gm.confusion([_s("answer", 3, top=0.9)], 0.2)
    assert m["missed_refusal_rate"] is None
    assert m["false_refusal_rate"] == 0.0


# ---------------------------------------------------------------- 拒答集的 premise

def test_refuse_set_stays_out_of_corpus():
    """纪律锁：探针词进 offline/corpus/ 会污染 evaluate 指标（台账 §0 同源）。"""
    assert not str((EVAL / "refuse_set.jsonl").resolve()).startswith(
        str((OFFLINE / "corpus").resolve())), "拒答集必须位于 corpus 之外"


def test_refuse_set_ghost_codes_really_absent_from_corpus():
    """拒答集成立的前提：幽灵错误码确实不在语料里。现算语料错误码集合求交，不靠记性。

    为什么必须锁：若语料补进了某个幽灵码，该样本就不再"应拒答"——漏拒率会被算错，
    而矩阵仍会安静地输出数字。
    """
    corpus_codes: set[str] = set()
    for line in (OFFLINE / "corpus" / "chunks.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            corpus_codes.update((json.loads(line).get("metadata") or {}).get("error_codes") or [])

    ghosts = set()
    for line in (EVAL / "refuse_set.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            ghosts.update(grounding.error_codes_in(json.loads(line)["query"]))

    assert ghosts, "拒答集里应当有幽灵错误码样本（否则该半集只覆盖域外提问）"
    overlap = ghosts & corpus_codes
    assert not overlap, f"这些码已在语料里，对应样本不再是「应拒答」: {sorted(overlap)}"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
