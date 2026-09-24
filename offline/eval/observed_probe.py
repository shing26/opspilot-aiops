#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真实输入探测（G4）：在**系统自己跑出来的 query** 上测那几件无需真值就能测的事。

为什么需要它：语料、query、评测集全部由本项目自己生成——`build_golden.py` 的注释自陈语义查询
"与语料主题词重叠，mock 词法向量可召回"。这是循环论证风险：64%/88% 是在**合成输入**上测的，
而系统至今没接触过它无法预测的输入。本脚本把"真实输入"这一面变成可测量的：从运行史
（`logs/audit*.jsonl` **全部分片**里的真实 query + 自举告警生产者发的告警 query）取样本，
测三件**不需要
ground truth** 的事：

  1. **零召回率**——检索一条都没召回的比例（无需真值，只看 `results` 是否为空）；
  2. **快路径命中率**——含精确错误码的 query 里，ES Top-1 命中该码的比例（快路径是"压 TTFT"的
     设计，这里看它在真实流量上到底命不命中）；
  3. **门控拒答率**——按当前判据会被拒答的比例（复用 `gate_matrix.gate_refuses`，同一份判据）。

**为什么不是 Hit@k**：真实 query 没有期望 chunk_id，而 `build_golden` 的真值是从语料**派生**的
（"含该错误码的文档集合"）——真实 query 派生不出真值。硬造一套真值等于把循环论证再叠一层。
故本脚本只报"无需真值即可测"的项，**不冒充**召回质量。

纪律（与 `pack_evidence` 的 local-evidence 档同源）：
- **query 集不入库**：`logs/` 含查询内容与租户标识，按既有纪律不外发；本脚本本地现读现算。
- **报告只入聚合**：`reports/observed_probe.{json,md}` 只有计数与比率，**绝不含 query 文本**。
- **与合成集分开统计**：本报告的数字**不得**并入 README 的 64%/88%——那是合成集的数，两者口径不同。

用法（离线目录，需活体栈 + `.env` + 本机 `logs/` 运行史）:
  python eval/observed_probe.py
  python eval/observed_probe.py --max 200 --include-alerts
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import localapi  # noqa: E402
import gate_matrix as gm  # noqa: E402
import grounding  # noqa: E402   # 错误码词法单一事实源

EVAL = Path(__file__).resolve().parent
REPORTS = EVAL / "reports"
REPO = EVAL.parent.parent
LOGS = REPO / "logs"
DEFAULT_MIN_RELEVANCE = 0.2   # 与 application.yml 的 MIN_RELEVANCE 默认值同源


def audit_files() -> list[Path]:
    """按**新→旧**返回运行史的所有审计分片（`logs/audit*.jsonl`）。

    为什么是 glob 而不是单个 `audit.jsonl`：logback 按天滚动（maxHistory=14），**当前那份只含今天**。
    只读当前文件会让样本在每次滚动后凭空缩小——而且缩到"今天"，读到的就全是本轮自己跑的测试
    query，正是本脚本要避免的自指。运行史的价值在历史分片里。

    为什么 reverse：名字含日期，字典序即时间序；而当前文件 `audit.jsonl` 字典序**大于**任何
    `audit.<日期>.jsonl`，故降序排列正好把当前文件排在最前。取样本要**从最近往回**取——
    近期 query 才反映当前语料状态（早期分片的语料早已被 blue/green 重建换掉）。
    """
    return sorted((p for p in LOGS.glob("audit*.jsonl") if p.is_file()),
                  key=lambda p: p.name, reverse=True)


def collect_queries(audit_paths: list[Path], max_n: int,
                    prefer_source: str | None = None) -> list[dict]:
    """从运行史取去重后的真实 query。返回 [{query, source, shard}]——**只在本机内存里流转，不落盘**。

    audit_paths 按给定顺序（新的在前）扫描；每条查询记下来源分片，以便报告如实交代**取样实际
    落在哪些分片**（只列候选分片会让人误以为全都参与了取样——曾如此）。

    `prefer_source`：把该来源的样本排到最前（各组内部保持"新→旧"）。用于 `--include-alerts`
    ——自举告警的 query 本来就在审计流里（`source=alert`），故"纳入告警样本"的正确做法是在审计行里
    优先取它们，而**不是**去读 `alert-producer.jsonl`（那份记的是告警元数据，**没有 query 字段**，
    读了等于什么也没做——本脚本首版就是那样，属"宣称了却不起作用的机制"）。
    """
    seen: set[str] = set()
    rows: list[dict] = []
    for audit_path in audit_paths:
        if not audit_path.exists():
            continue
        lines = audit_path.read_text(encoding="utf-8", errors="replace").splitlines()
        for line in reversed(lines):                  # 片内从最新往回取
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            if ev.get("ev") != "chat":
                continue
            q = (ev.get("q") or "").strip()
            if not q or q in seen:
                continue
            seen.add(q)
            rows.append({"query": q, "source": ev.get("source") or "manual",
                         "shard": audit_path.name})
    if prefer_source:
        pick = [r for r in rows if r["source"] == prefer_source]
        rows = pick + [r for r in rows if r["source"] != prefer_source]
    return rows[:max_n]


def summarize(rows: list[dict], threshold: float = DEFAULT_MIN_RELEVANCE) -> dict:
    """把逐条观测汇总成聚合指标。**不返回任何 query 文本**——报告的可外发性由此保证。

    rows 元素：{n_results, fast_path, top_rerank, has_code}
    """
    n = len(rows)
    if n == 0:
        return {"samples": 0, "zero_recall": None, "zero_recall_rate": None,
                "with_code": 0, "fast_path_hits": None, "fast_path_hit_rate": None,
                "gate_refusal_rate": None, "threshold": threshold}
    zero_recall = sum(1 for r in rows if r["n_results"] == 0)
    with_code = [r for r in rows if r["has_code"]]
    fast_hits = sum(1 for r in with_code if r["fast_path"])
    refused = sum(1 for r in rows
                  if gm.gate_refuses(r["n_results"], r["fast_path"], r.get("top_rerank"), threshold))
    return {
        "samples": n,
        "zero_recall": zero_recall,
        "zero_recall_rate": zero_recall / n,
        "with_code": len(with_code),
        "fast_path_hits": fast_hits,
        "fast_path_hit_rate": (fast_hits / len(with_code)) if with_code else None,
        "gate_refusal_rate": refused / n,
        "threshold": threshold,
    }


def _probe(query: str, token: str) -> dict:
    resp = localapi.post_json("/api/v1/copilot/search", {"query": query, "mode": "hybrid"}, token, timeout=30)
    results = resp.get("results") or []
    top = results[0].get("rerank_score") if results else None
    return {"n_results": len(results), "fast_path": bool(resp.get("fast_path")),
            "top_rerank": top if isinstance(top, (int, float)) else None,
            "has_code": bool(grounding.error_codes_in(query))}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="真实输入探测（无真值口径）")
    ap.add_argument("--max", type=int, default=100, help="最多取多少条真实 query（默认 100）")
    ap.add_argument("--include-alerts", action="store_true",
                    help="优先取自举告警（source=alert）的样本，再补人工 query"
                         "（告警 query 本就在审计流里；alert-producer.jsonl 无 query 字段，不读它）")
    ap.add_argument("--threshold", type=float, default=DEFAULT_MIN_RELEVANCE, help="门控阈值（默认 0.2）")
    ap.add_argument("--user", default=localapi.DEFAULT_EVAL_USER,
                    help=f"主体（默认 {localapi.DEFAULT_EVAL_USER}）——必须是真实用户名，不是 load_tokens 的键名")
    ap.add_argument("--audit-log", default=None,
                    help="逗号分隔的审计分片路径（默认自动取 logs/audit*.jsonl 全部，按名排序）")
    a = ap.parse_args(argv)

    paths = ([Path(p) for p in a.audit_log.split(",")] if a.audit_log else audit_files())
    queries = collect_queries(paths, a.max, prefer_source="alert" if a.include_alerts else None)
    if not queries:
        print(f"运行史里没有可用的真实 query（已扫 {[p.name for p in paths] or '（无分片）'}）——"
              f"先让系统跑一段（含自举告警源），再来测。", file=sys.stderr)
        return 2

    token = localapi.login(a.user)
    rows = [_probe(q["query"], token) for q in queries]
    agg = summarize(rows, a.threshold)

    out = {
        "measured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "subject": a.user,
        "candidate_shards": [p.name for p in paths],
        "contributing_shards": sorted({q["shard"] for q in queries}),
        "includes_alert_producer": bool(a.include_alerts),
        "source_breakdown": {s: sum(1 for q in queries if q["source"] == s)
                             for s in sorted({q["source"] for q in queries})},
        "aggregate": agg,
        "note": ("**无真值口径**：真实 query 派生不出期望 chunk_id（合成集的真值是从语料派生的），"
                 "故本报告只报无需真值即可测的三项（零召回率 / 快路径命中率 / 门控拒答率），"
                 "**不冒充**召回质量。**不得并入** README 的 64%/88%——那是合成集的数，口径不同。"
                 "query 集不入库（logs/ 含查询内容与租户标识）；本报告只含聚合计数。"),
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "observed_probe.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")

    def r(x):
        return "—" if x is None else f"{x:.1%}"

    md = [
        "# 真实输入探测（无真值口径，与合成集分开）",
        "",
        f"> 实测时间：{out['measured_at']} ｜ 主体 `{a.user}`"
        f"{' + 自举告警' if a.include_alerts else ''}",
        f"> 取样**实际落在**：{out['contributing_shards']}"
        f"（候选分片 {len(out['candidate_shards'])} 片；按新→旧扫描，取够即止）",
        f"> 来源构成：{out['source_breakdown']} ｜ 门控阈值 {agg['threshold']}",
        "",
        "| 指标 | 值 | 说明 |",
        "| --- | --- | --- |",
        f"| 样本数 | {agg['samples']} | 去重后的真实 query |",
        f"| 零召回率 | {r(agg['zero_recall_rate'])}（{agg['zero_recall']}/{agg['samples']}） | 检索一条都没召回 |",
        f"| 快路径命中率 | {r(agg['fast_path_hit_rate'])}"
        f"（{agg['fast_path_hits']}/{agg['with_code']}） | 含精确错误码的 query 里 ES Top-1 命中该码 |",
        f"| 门控拒答率 | {r(agg['gate_refusal_rate'])} | 按当前判据会被拒答的比例 |",
        "",
        out["note"],
        "",
        "复现：`cd offline && python eval/observed_probe.py --include-alerts`"
        "（需活体栈 + `.env` + 本机 `logs/` 运行史；/search 不调 LLM，零 token 成本且配额豁免）。",
    ]
    (REPORTS / "observed_probe.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"OK 样本 {agg['samples']} 零召回率={r(agg['zero_recall_rate'])} "
          f"快路径命中率={r(agg['fast_path_hit_rate'])} -> {REPORTS / 'observed_probe.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
