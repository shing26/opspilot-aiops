#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L1 回放延迟实测（把"热点命中"这条表头指标变成可复现产物）。

为什么单独做这一件事：README 表头那条「热点命中 TP99」此前是全仓**唯一没有随附产物的指标**
（`grep -rn "36.6"` 只命中 README 自己），与 DoD「数字必须与随附产物同代」直接冲突。
本脚本把它变成一条谁都能重跑的命令：

  预热一条 query 进 L1 → 顺序回放 n 次 → 同时记录**服务端 TTFT**（SSE done 帧的 ttft_ms）
  与**客户端端到端**（含本机 HTTP/Python 栈开销）→ 落 `offline/load/reports/l1_hit_latency.{json,md}`。

两个口径为什么都要留：服务端 TTFT 才是"系统处理"的真实耗时（可与目标线比较）；
客户端端到端包含本机解释器与 HTTP 往返开销，单独报会把它误记成系统慢（历史上就有过
把 Windows Docker 端口代理的固定开销误判为应用缺陷的教训）。

零 LLM 成本：回放命中 L1 不触外部 API——脚本**断言每次 cache_hit == "L1"**，一旦有非 L1
（缓存过期/被淘汰）立即中止，绝不把混合样本当纯回放数字。
配额：每次 /chat/stream 消耗 1 次当日配额，故默认 n=200。

用法（离线目录）:
  python load/l1_latency.py                 # n=200，默认 query
  python load/l1_latency.py --n 500 --query "50012_DB_TIMEOUT"
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import localapi  # noqa: E402

REPORTS = Path(__file__).resolve().parent / "reports"
DEFAULT_QUERY = "50012_DB_TIMEOUT 下单超时怎么排查"


def pct(values: list[float], p: float) -> float:
    """最近秩百分位（nearest-rank）：小样本下比插值法更保守，也不依赖 numpy。"""
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = max(1, min(len(ordered), int(round((p / 100.0) * len(ordered) + 0.5))))
    return ordered[k - 1]


def stats(values: list[float]) -> dict:
    return {"n": len(values), "mean": round(statistics.fmean(values), 2),
            "p50": round(pct(values, 50), 2), "p90": round(pct(values, 90), 2),
            "p95": round(pct(values, 95), 2), "p99": round(pct(values, 99), 2),
            "max": round(max(values), 2), "min": round(min(values), 2)}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="L1 回放延迟实测")
    ap.add_argument("--n", type=int, default=200, help="回放次数（默认 200；每次消耗 1 次当日配额）")
    ap.add_argument("--query", default=DEFAULT_QUERY, help="用于预热与回放的 query")
    ap.add_argument("--user", default="sre-full", help="主体（默认 sre-full，会记进产物）")
    a = ap.parse_args(argv)

    token = localapi.login(a.user)
    state = localapi.get_json("/api/v1/admin/state", token)
    backend = (state.get("metrics") or {}).get("backend") or {}
    body = {"query": a.query, "source": "manual", "service": "", "env": "prod"}

    warm = localapi.stream_chat(body, token, collect_deltas=False)
    if warm["status"] != 200:
        print(f"预热失败：HTTP {warm['status']} {warm.get('code')}", file=sys.stderr)
        return 1

    client_ms: list[float] = []
    ttft_ms: list[float] = []
    cache_hits: dict[str, int] = {}
    ref_counts: list[int] = []
    for i in range(a.n):
        t0 = time.perf_counter()
        r = localapi.stream_chat(body, token, collect_deltas=False)
        client_ms.append((time.perf_counter() - t0) * 1000.0)
        hit = (r.get("meta") or {}).get("cache_hit")
        cache_hits[str(hit)] = cache_hits.get(str(hit), 0) + 1
        if hit != "L1":
            print(f"第 {i + 1} 次未命中 L1（cache_hit={hit}）——样本被污染，中止；"
                  f"先确认 L1 TTL 未过期且 Redis 未淘汰，再重跑", file=sys.stderr)
            return 2
        done = r.get("done") or {}
        if isinstance(done.get("ttft_ms"), (int, float)):
            ttft_ms.append(float(done["ttft_ms"]))
        ref_counts.append(len(done.get("refs") or []))

    out = {
        "measured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "subject": a.user,
        "query": a.query,
        "n": a.n,
        "backend": backend,
        "cache_hit_distribution": cache_hits,
        "server_ttft_ms": stats(ttft_ms),
        "client_end_to_end_ms": stats(client_ms),
        "refs_min": min(ref_counts) if ref_counts else 0,
        "note": ("服务端 TTFT = SSE done 帧 ttft_ms（系统处理耗时，可与目标线比较）；"
                 "客户端端到端含本机解释器与 HTTP 往返开销，勿拿来当系统性能。"
                 "样本全部为 L1 命中（非 L1 即中止），不触外部 API，零 LLM 成本。"),
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "l1_hit_latency.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")

    md = [
        "# L1 回放延迟实测（热点命中口径的产物）",
        "",
        f"> 实测时间：{out['measured_at']} ｜ 主体 `{a.user}` ｜ n={a.n} ｜ "
        f"cache_hit 分布：{cache_hits}",
        f"> 后端：embedding={backend.get('embedding')} / rerank={backend.get('rerank')} / "
        f"llm={backend.get('llm')}（回放路径不触外部 API，故后端与延迟无关）",
        f"> query：`{a.query}` ｜ refs 最少 {out['refs_min']} 条（回放保留溯源）",
        "",
        "| 口径 | p50 | p90 | p95 | p99 | max | mean |",
        "| --- | --- | --- | --- | --- | --- | --- |",
        "| **服务端 TTFT**（系统处理，目标 <50ms） | {p50} | {p90} | {p95} | **{p99}** | {max} | {mean} |".format(
            **out["server_ttft_ms"]),
        "| 客户端端到端（含本机栈开销，参考值） | {p50} | {p90} | {p95} | {p99} | {max} | {mean} |".format(
            **out["client_end_to_end_ms"]),
        "",
        out["note"],
        "",
        "复现：`cd offline && python load/l1_latency.py`（需活体栈 + `.env`；"
        "每次请求消耗 1 次当日配额，n 次即 n 次）。",
    ]
    (REPORTS / "l1_hit_latency.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"OK n={a.n} server_ttft p50={out['server_ttft_ms']['p50']}ms "
          f"p99={out['server_ttft_ms']['p99']}ms | client p99={out['client_end_to_end_ms']['p99']}ms "
          f"-> {REPORTS / 'l1_hit_latency.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
