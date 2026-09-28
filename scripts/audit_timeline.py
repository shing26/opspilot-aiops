#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""降级档位时间线（OP-A3）：从 `logs/audit.jsonl` 复原"档位切换发生过"这条序列。

**为什么必须有它**：面板只有**实时**档位灯与 200 条有界事件环（`AuditService.RING_CAP`）——
而"切换发生过"这件事在压测那一刻恰恰会因环轮转而丢（高 RPS 下 200 条必被冲掉，见 OP-A3 定形记录）。
权威历史是 14 天滚动的 `logs/audit.jsonl`，本脚本把它读成 `L0→L1→L0` 这样一条可核对的序列。

**判据三源互证**（缺一条就不算证据充分）：
  1. `ev=degrade_transition` 行给出 `from`/`to`/`cause`（cause 是有限词表，见 `CONTEXT.md` 降级域）；
  2. chat 业务行的 `degrade_level` 给出每条请求当时的档位（密度高，可交叉验证 1 的时序）；
  3. 审计行上的 `request_id` / `trace_id` 让这些行能与其他日志对齐（`trace_id` 还能串上层 agent 的多步调用）。

已知边界（如实写在这，不装作覆盖）：
  · 2026-09-28 之前的日志**没有** `degrade_level`/`degrade_transition`，本脚本会明说该窗口不可判读，
    而不是静默输出空结果；
  · 状态机是拉模型：切换在计数器变化与 `current()` 被调用时被观察到，"既无请求又无人看 /state"
    的静默期内发生的切换会与下一次观察合并（`DegradationStateMachine` 类注释有完整口径）。

用法:
  python scripts/audit_timeline.py                      # 读默认 logs/audit.jsonl
  python scripts/audit_timeline.py --file logs/audit.jsonl --json
  python scripts/audit_timeline.py --only-cause inflight    # 只看负载压出来的（不含 manual）
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_FILE = Path("logs/audit.jsonl")
LEVELS = ("L0", "L1", "L2")
CAUSE_NOTE = {
    "inflight": "在途超阈 → L1（负载压出来的）",
    "llm_failure": "连续失败达阈 → L2（上游坏）",
    "load_subsided": "负载回落 → L0",
    "cooldown_expired": "冷却到期半开 → L0",
    "manual": "人工锁定（演示用；不是负载导致）",
    "manual_clear": "解除人工锁定",
}


def iso(ms: float) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).astimezone().isoformat(timespec="seconds")


def load(path: Path) -> tuple[list[dict], int]:
    if not path.is_file():
        print(f"FAIL 找不到审计文件：{path}（审计行由网关运行期落盘，14 天滚动；"
              f"本仓运行历史里该文件不入库）", file=sys.stderr)
        raise SystemExit(2)
    rows, bad = [], 0
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except Exception:
            bad += 1
    return rows, bad


def analyse(rows: list[dict]) -> dict:
    trans = [r for r in rows if r.get("ev") == "degrade_transition"]
    chat_like = [r for r in rows if r.get("degrade_level")]
    unreadable = [r for r in rows if r.get("ev") in ("chat", "search") and not r.get("degrade_level")]
    ev_counts = Counter(str(r.get("ev")) for r in rows)
    countries = Counter()

    # 切换序列（按 ts 排；同 ts 用 request_id 兜底稳定排序）
    trans.sort(key=lambda r: (r.get("ts") or 0, str(r.get("request_id") or "")))
    timeline = [{
        "ts": iso(r["ts"]) if r.get("ts") else "?",
        "from": r.get("from"), "to": r.get("to"), "cause": r.get("cause"),
        "cause_note": CAUSE_NOTE.get(str(r.get("cause")), "（词表外的 cause——判据已漂，需查）"),
        "request_id": r.get("request_id"), "trace_id": r.get("trace_id"),
    } for r in trans]

    # 档位请求数分布：chat 行的 `degrade_level` 记录的是**进管线时**的档位，而行在**完成时**才写
    # （L2 走 SOP 直出、毫秒级完成；L0/L1 要等 LLM 数秒）——故行序**不等于**档位时序，**不能**按
    # 相邻行推"驻留段"。这里只统计各档请求数，时序一律以 `degrade_transition` 行（切换当场写）为准。
    seq = sorted((r for r in chat_like if r.get("ts")), key=lambda r: r["ts"])
    for r in seq:
        countries[r["degrade_level"]] += 1

    # 交叉验证（只做**可推的**那一条）：切换序列必须**链式连续**——前一条的 to 应等于后一条的 from。
    # 不连续说明中间有事件丢失（或 from/to 装配错），这是能在事件自身上判的真不变量；
    # 原先按"切换后 5s 内 chat 行多数票"判一致性是**无效推断**（受上面那条完成时序影响），已废。
    warnings = []
    for prev, cur in zip(trans, trans[1:]):
        if prev.get("to") != cur.get("from"):
            warnings.append(f"{iso(cur.get('ts') or 0)} 切换序列不连续：上一条 to={prev.get('to')} "
                            f"但这一条 from={cur.get('from')}——中间可能有事件未被观察到")
    for t in trans:
        if str(t.get("cause")) not in CAUSE_NOTE:
            warnings.append(f"{iso(t.get('ts') or 0)} cause={t.get('cause')!r} 不在词表内"
                            f"（词表是判据的一部分，漂了要查）")
    return {
        "rows_total": len(rows), "rows_unparsable": None,
        "ev_counts": dict(ev_counts),
        "transitions": timeline,
        "level_rows": dict(countries),
        "chat_rows_without_degrade_level": len(unreadable),
        "warnings": warnings,
        "boundary_note": ("chat 行的 `degrade_level` 是**进管线时**的档位、行在**完成时**写；"
                          "L2 走 SOP 毫秒级、L0/L1 等 LLM 数秒，故行序不等于档位时序——"
                          "时序只以 degrade_transition 行（切换当场落行）为准，本报告不按行序推驻留段。"),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="从审计日志复原降级档位时间线")
    ap.add_argument("--file", default=str(DEFAULT_FILE))
    ap.add_argument("--json", action="store_true", help="输出结构化结果（供脚本消费）")
    ap.add_argument("--only-cause", default=None, help="只看某个 cause（如 inflight）")
    a = ap.parse_args(argv)

    rows, bad = load(Path(a.file))
    res = analyse(rows)
    res["rows_unparsable"] = bad
    res["file"] = a.file
    if a.only_cause:
        res["transitions"] = [t for t in res["transitions"] if t["cause"] == a.only_cause]

    if a.json:
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return 0

    print(f"审计文件：{a.file}")
    print(f"可解析 {res['rows_total']} 行／不可解析 {bad} 行 ｜ 事件分布 {res['ev_counts']}")
    if not res["transitions"] and res["chat_rows_without_degrade_level"] > 0:
        print(f"\n⚠️ 本窗口**不可判读**：{res['chat_rows_without_degrade_level']} 条 chat/search 行没有 "
              f"`degrade_level`，且 0 条 `degrade_transition`——这些行来自 2026-09-28 之前"
              f"（该字段是本轮才加的）。换用新窗口的日志再读。")
        return 0
    print(f"\n档位切换（{len(res['transitions'])} 条）：")
    for t in res["transitions"]:
        who = f"req={t['request_id']}" if t.get("request_id") else ""
        if t.get("trace_id"):
            who += f" trace={t['trace_id']}"
        print(f"  {t['ts']}  {t['from']} → {t['to']}  cause={t['cause']}  {t['cause_note']}  {who}")
    if res["level_rows"]:
        print(f"\nchat 行按进管线档位分布：{res['level_rows']}")
        print(f"（注：{res['boundary_note']}）")
    if res["warnings"]:
        print("\n序列不变量警告：")
        for w in res["warnings"]:
            print(f"  ⚠️ {w}")
    else:
        print("\n序列不变量：切换链式连续、cause 均在词表内。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
