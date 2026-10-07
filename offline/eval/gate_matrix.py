#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门控混淆矩阵 + 阈值扫描（把"我加了门控"变成"我知道门控的代价是多少"）。

为什么需要它：置信度门控此前只有一个数——`low_confidence_refusals` 计数。它回答"拒了多少次"，
不回答"拒对了吗"。门控有两类错误，方向相反且都伤系统：
  - **误拒**（应作答却拒答）：伤可用性——用户明明问了知识库里有的东西，却被告知"无相关参考"；
  - **漏拒**（应拒答却作答）：伤可信度——正是"不知道就不答"这条主张要挡的那类。
两类都不出现在现有计数里，故门控的**代价**此前是未知的。

关键简化（本脚本成立的前提）：门控信号 `topRelevance` 在正常路径上就等于 `results[0].rerank_score`，
而 `/search` 的响应**已返回** `rerank_score` 与 `fast_path`。于是：
  1. 一次 `/search` 遍历即可离线算出**任意**阈值下的混淆矩阵——**无需重启网关**改
     `MIN_RELEVANCE` 重跑四次（清单原估的做法）；
  2. 走 `/search` 而非 `/chat` 还顺带**排除了缓存混淆**：`/search` 不查 L1/L2，故测到的就是
     门控判据本身，不会把"L2 命中回放绕过门控"误记成漏拒。

样本构成（grill 裁定：复用 golden + 手建拒答集）：
  - **应作答半集** = `golden_dataset.jsonl`（59 条，已有期望 chunk_id 真值）；其中被门控拒掉的
    正是"误拒"样本。
  - **应拒答半集** = `refuse_set.jsonl`（24 条：语料中不存在的错误码 + 域外提问）。
  - 两者都在 `offline/corpus/` **之外**——探针词进语料会污染 evaluate 指标（台账 §0 同源纪律）。

口径提醒：本脚本只评估**门控判据**（`chunks.isEmpty() || (!fastPath && topRelevance < minRel)`）。
`fast_path` 命中会豁免门控，故含真实错误码的精确 query 天然不参与相关性门控——矩阵里会体现为
"应作答且作答"，那不是门控的功劳，是快路径的。

用法（离线目录，需活体栈 + `.env`；/search 不调 LLM，故**零 token 成本且配额豁免**——
配额只挂 /chat/stream 与 /v1/chat/completions，检索面是评测路径）:
  python eval/gate_matrix.py
  python eval/gate_matrix.py --thresholds 0.1,0.2,0.3,0.4,0.5
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import localapi  # noqa: E402

EVAL = Path(__file__).resolve().parent
REPORTS = EVAL / "reports"
DEFAULT_THRESHOLDS = (0.1, 0.2, 0.3, 0.4)


def gate_refuses(n_results: int, fast_path: bool, top_rerank: float | None, threshold: float) -> bool:
    """门控判据——与 `ChatOrchestrator.runPipeline` 的 lowConfidence 同构。

    复刻自 Java：`lowConfidence = chunks.isEmpty() || (!outcome.fastPath() && outcome.topRelevance() < minRel)`
    其中正常路径的 `topRelevance` = Top-1 rerank 分；快路径/es_only/降级时为 1.0（即不门控）。
    本函数只建模**正常路径**（mode=hybrid 且未降级），故 fast_path 命中即豁免。
    """
    if n_results == 0:
        return True
    if fast_path:
        return False
    return top_rerank is not None and top_rerank < threshold


def confusion(samples: list[dict], threshold: float) -> dict:
    """按给定阈值统计混淆矩阵。samples 元素：{expect: 'answer'|'refuse', n_results, fast_path, top_rerank}。"""
    correct_refusals = missed_refusals = false_refusals = correct_answers = 0
    zero_recall_false_refusals = 0
    for s in samples:
        refused = gate_refuses(s["n_results"], s["fast_path"], s.get("top_rerank"), threshold)
        if s["expect"] == "refuse":
            if refused:
                correct_refusals += 1
            else:
                missed_refusals += 1
        else:
            if refused:
                false_refusals += 1
                if s["n_results"] == 0:
                    zero_recall_false_refusals += 1   # 零召回导致的误拒：属检索问题，非阈值标定问题
            else:
                correct_answers += 1
    n_refuse = correct_refusals + missed_refusals
    n_answer = false_refusals + correct_answers
    return {
        "threshold": threshold,
        "correct_refusals": correct_refusals,
        "missed_refusals": missed_refusals,
        "false_refusals": false_refusals,
        "correct_answers": correct_answers,
        "missed_refusal_rate": (missed_refusals / n_refuse) if n_refuse else None,
        "false_refusal_rate": (false_refusals / n_answer) if n_answer else None,
        "zero_recall_among_false_refusals": zero_recall_false_refusals,
        "n_refuse_samples": n_refuse,
        "n_answer_samples": n_answer,
    }


def _probe(query: str, token: str) -> dict:
    """一次 /search：取门控所需的两项信号（fast_path 与 Top-1 rerank 分）。"""
    resp = localapi.post_json("/api/v1/copilot/search", {"query": query, "mode": "hybrid"}, token, timeout=30)
    results = resp.get("results") or []
    top = results[0].get("rerank_score") if results else None
    return {"n_results": len(results), "fast_path": bool(resp.get("fast_path")),
            "top_rerank": top if isinstance(top, (int, float)) else None}


def _load(path: Path) -> list[dict]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            out.append(json.loads(line))
    return out


def _fmt_rate(x: float | None) -> str:
    return "—" if x is None else f"{x:.1%}"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="门控混淆矩阵 + 阈值扫描")
    ap.add_argument("--thresholds", default=",".join(str(t) for t in DEFAULT_THRESHOLDS),
                    help="逗号分隔的阈值列表（默认 0.1,0.2,0.3,0.4）")
    ap.add_argument("--user", default=localapi.DEFAULT_EVAL_USER,
                    help=f"主体（默认 {localapi.DEFAULT_EVAL_USER}）——必须是真实用户名，不是 load_tokens 的键名")
    a = ap.parse_args(argv)
    thresholds = [float(x) for x in a.thresholds.split(",")]

    token = localapi.login(a.user)
    golden = _load(EVAL / "golden_dataset.jsonl")
    refuse = _load(EVAL / "refuse_set.jsonl")

    samples: list[dict] = []
    for g in golden:
        samples.append({"id": g["id"], "expect": "answer", **_probe(g["query"], token)})
    for r in refuse:
        samples.append({"id": r["id"], "expect": "refuse", **_probe(r["query"], token)})

    matrix = [confusion(samples, t) for t in thresholds]
    out = {
        "measured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "subject": a.user,
        "mode": "hybrid",
        "samples": {"answer_expected": len(golden), "refuse_expected": len(refuse)},
        "matrix": matrix,
        "note": ("门控判据 = chunks.isEmpty() || (!fastPath && topRelevance < minRel)，与 "
                 "ChatOrchestrator 同构；走 /search 故不含 L1/L2 缓存（排除'缓存回放绕过门控'的混淆）。"
                 "误拒伤可用性、漏拒伤可信度，两者方向相反，须一起看。"
                 "zero_recall_among_false_refusals 单列：零召回导致的误拒属检索问题，不是阈值标定问题。"),
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    # newline="\n"：Windows 文本模式会把 \n 翻译成 \r\n（同 10-06 产物族修复）
    (REPORTS / "gate_matrix.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")

    md = [
        "# 门控混淆矩阵 + 阈值扫描",
        "",
        f"> 实测时间：{out['measured_at']} ｜ 主体 `{a.user}` ｜ mode=hybrid",
        f"> 样本：应作答 {len(golden)}（golden）｜ 应拒答 {len(refuse)}（refuse_set）",
        "",
        "| 阈值 | 误拒率（应答却拒） | 漏拒率（应拒却答） | 拒对 | 漏拒 | 误拒 | 答对 | 其中零召回误拒 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for m in matrix:
        md.append(f"| {m['threshold']} | {_fmt_rate(m['false_refusal_rate'])} | "
                  f"{_fmt_rate(m['missed_refusal_rate'])} | {m['correct_refusals']} | "
                  f"{m['missed_refusals']} | {m['false_refusals']} | {m['correct_answers']} | "
                  f"{m['zero_recall_among_false_refusals']} |")
    md += ["", out["note"], "",
           "复现：`cd offline && python eval/gate_matrix.py`（需活体栈 + `.env`；/search 不调 LLM，"
           "故零 token 成本且**配额豁免**——配额只挂 /chat/stream 与 /v1/chat/completions）。"]
    (REPORTS / "gate_matrix.md").write_text("\n".join(md) + "\n", encoding="utf-8", newline="\n")
    cur = next((m for m in matrix if abs(m["threshold"] - 0.2) < 1e-9), None)
    if cur:
        print(f"OK 当前阈值 0.2：误拒率={_fmt_rate(cur['false_refusal_rate'])} "
              f"漏拒率={_fmt_rate(cur['missed_refusal_rate'])} -> {REPORTS / 'gate_matrix.md'}")
    else:
        print(f"OK {len(matrix)} 档阈值 -> {REPORTS / 'gate_matrix.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
