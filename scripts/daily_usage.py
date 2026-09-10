#!/usr/bin/env python3
"""每日用量汇总（OPS 可观测性，配 OPS.md 的 cron 示例）。

从 logs/audit.jsonl 聚合指定日期的合规审计事件，输出一行 JSON 追加到
logs/usage.log（schema 锁定，jq/Loki 可直接消费）：
  {"date","total_requests","unique_users","refuse_rate","l3_hits","top_user"}
拒答率超 --threshold-refuse 时 stderr 告警并以退出码 1 结束（cron MAILTO 语义）。

用法（本机 Git Bash 与 Linux cron 同构）:
  python scripts/daily_usage.py --date yesterday
  python scripts/daily_usage.py --date 2026-09-10 --threshold-refuse 0.10
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AUDIT = ROOT / "logs" / "audit.jsonl"


def resolve_date(arg: str) -> str:
    if arg == "yesterday":
        return (dt.date.today() - dt.timedelta(days=1)).isoformat()
    dt.date.fromisoformat(arg)  # 非法格式直接抛错
    return arg


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--date", default="yesterday", help="yesterday 或 YYYY-MM-DD（本地时区）")
    ap.add_argument("--threshold-refuse", type=float, default=0.10,
                    help="拒答率告警阈值（超过退出码 1）")
    ap.add_argument("--log", default=str(ROOT / "logs" / "usage.log"), help="输出生文件路径")
    args = ap.parse_args()

    day = resolve_date(args.date)
    if not AUDIT.exists():
        print(f"ERROR: {AUDIT} 不存在（服务未跑过？），本日无数据", file=sys.stderr)
        return 2

    total = refused = l3 = 0
    per_sub: dict[str, int] = {}
    with open(AUDIT, encoding="utf-8") as fh:
        for line in fh:
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue  # 滚动写盘偶发撕裂行，跳过不阻塞统计
            ts = dt.datetime.fromtimestamp(ev["ts"] / 1000).date().isoformat()
            if ts != day:
                continue
            total += 1
            per_sub[ev.get("sub", "?")] = per_sub.get(ev.get("sub", "?"), 0) + 1
            refused += 1 if ev.get("refused") else 0
            l3 += 1 if ev.get("max_level", 0) >= 3 else 0

    out = {"date": day, "total_requests": total, "unique_users": len(per_sub),
           "refuse_rate": round(refused / total, 4) if total else 0.0,
           "l3_hits": l3,
           "top_user": (max(per_sub.items(), key=lambda kv: kv[1])[0]
                        + ":" + str(max(per_sub.values())) if per_sub else "")}
    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with open(log_path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(out, ensure_ascii=False) + "\n")
    print(json.dumps(out, ensure_ascii=False))

    if out["refuse_rate"] > args.threshold_refuse:
        print(f"ALERT: {day} 拒答率 {out['refuse_rate']:.1%} > 阈值 "
              f"{args.threshold_refuse:.0%} —— 按 OPS.md《拒答处置 SOP》排查语料",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
