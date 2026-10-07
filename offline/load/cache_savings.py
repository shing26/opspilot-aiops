#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""缓存节省账实测（把"我做了缓存"变成"缓存省了多少毫秒、省了多少次 LLM 调用"）。

为什么需要它：`l1_cache_hits` / `l2_cache_hits` 两个计数早就有了，但它们只回答"命中了多少次"，
不回答"命中值多少"。本脚本补上后半句——一次受控混合负载（热 query 回放 + 冷 query 实测），
把**命中/未命中的延迟差**与**命中率**相乘，得到"单请求平均节省"。

口径与归因（关键，勿混）：
- **归因靠"本脚本自己的请求"**，不靠全局计数取差。理由：`total_requests` 把 Single-Flight 的
  follower 也算进去，`hits/total` 会把去重流量当成 miss——分母是浑的。故命中率以本负载的
  `cache_hit` 分布为准；全局计数另作**交叉核对**列出（标注为"全系统口径，含他人流量"）。
- 延迟一律用**服务端 TTFT**（SSE done 帧 `ttft_ms`）做差；客户端端到端另列参考值，不参与节省账
  （它含本机解释器与 HTTP 往返开销，混进去会把本机开销记成系统收益）。
- 冷 query 带随机尾注（`secrets`）以避免撞上 L2 语义缓存；命中分组直接读 meta 的 `cache_hit`，
  若某条冷 query 仍被判命中，它按实际分组计，**不假装**。

成本：每次 `/chat/stream` 消耗 1 次当日配额；冷 query 还会真实调用 LLM（真金白银）。
故默认 hot=20 / cold=5，合计 25 次。**未命中组的延迟就是 LLM 生成耗时**，这是节省账的另一半。

用法（离线目录，需活体栈 + `.env`）:
  python load/cache_savings.py                     # hot=20 cold=5
  python load/cache_savings.py --hot 40 --cold 3
"""
from __future__ import annotations

import argparse
import json
import secrets
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import localapi  # noqa: E402

REPORTS = Path(__file__).resolve().parent / "reports"
HOT_QUERY = "50012_DB_TIMEOUT 下单超时怎么排查"

# 冷 query 池：**必须落在语料内且能过门控**，否则"未命中组"测到的是拒答而不是生成。
# 2026-09-24 首版用的是无错误码的泛化问句，结果 5 条冷 query 全被置信度门控拒掉
# （实测 `low_confidence_refusals=5`、`llm_calls=0`），于是"单请求平均节省"是拿**拒答**
# 当基线算出来的——拒答本身很便宜，这个数字会显著低估真实节省。改用含精确错误码的问句：
# 精确符号 → 快路径命中 → 豁免门控 → 真调 LLM，这才是"未命中"该有的样子。
COLD_QUERIES = [
    "50013_DB_DEADLOCK 库存扣减死锁的排查步骤",
    "50021_REDIS_TIMEOUT 购物车 Redis 超时怎么处理",
    "50031_MQ_CONSUME_LAG 支付回调积压如何定位",
    "50051_ES_INDEX_MISSING 索引缺失导致检索为空",
]


def pct(values: list[float], p: float) -> float:
    """最近秩百分位（nearest-rank），与 l1_latency.py 同口径。"""
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = max(1, min(len(ordered), int(round((p / 100.0) * len(ordered) + 0.5))))
    return ordered[k - 1]


def stats(values: list[float]) -> dict:
    if not values:
        return {"n": 0, "mean": None, "p50": None, "p95": None}
    return {"n": len(values), "mean": round(statistics.fmean(values), 2),
            "p50": round(pct(values, 50), 2), "p95": round(pct(values, 95), 2)}


def _counters(token: str) -> dict:
    m = (localapi.get_json("/api/v1/admin/state", token).get("metrics") or {})
    # 含 low_confidence_refusals 与 llm_calls：这两项是**判读"未命中组"性质的证据**——
    # 若 refusals 增量 == 冷 query 条数 而 llm_calls 为 0，说明那组全是拒答，
    # 此时"节省"是拿拒答当基线算的、会低估真实节省（2026-09-24 首版即此坑）。
    return {k: int(m.get(k) or 0) for k in
            ("total_requests", "llm_calls", "l1_cache_hits", "l2_cache_hits",
             "dedup_aggregated", "low_confidence_refusals")}


def _one(token: str, query: str) -> tuple[str, float]:
    """发一次 chat，返回 (cache_hit, 服务端 TTFT ms)。TTFT 缺失时用 NaN 占位（不猜）。"""
    t0 = time.perf_counter()
    r = localapi.stream_chat({"query": query, "source": "manual", "service": "", "env": "prod"},
                             token, collect_deltas=False)
    if r["status"] != 200:
        raise RuntimeError(f"chat 失败：HTTP {r['status']} {r.get('code')}")
    hit = str((r.get("meta") or {}).get("cache_hit"))
    ttft = (r.get("done") or {}).get("ttft_ms")
    if not isinstance(ttft, (int, float)):
        ttft = float("nan")
    return hit, float(ttft)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="缓存节省账实测")
    ap.add_argument("--hot", type=int, default=20, help="热 query 回放次数（默认 20）")
    ap.add_argument("--cold", type=int, default=5, help="冷 query 次数（默认 5；每条真调 LLM）")
    ap.add_argument("--user", default=localapi.DEFAULT_EVAL_USER,
                    help=f"主体（默认 {localapi.DEFAULT_EVAL_USER}）——必须是真实用户名，不是 load_tokens 的键名")
    a = ap.parse_args(argv)

    token = localapi.login(a.user)
    before = _counters(token)

    # 预热一次，让热 query 确实进 L1（否则第一轮全是 miss，会污染命中组）
    _one(token, HOT_QUERY)

    by_group: dict[str, list[float]] = {}
    for _ in range(a.hot):
        hit, ttft = _one(token, HOT_QUERY)
        by_group.setdefault(hit, []).append(ttft)
    for i in range(a.cold):
        # 随机尾注避免撞 L2；仍被判命中就按实际分组计，不假装
        base = COLD_QUERIES[i % len(COLD_QUERIES)]
        hit, ttft = _one(token, f"{base}（工单 {secrets.token_hex(4)}）")
        by_group.setdefault(hit, []).append(ttft)

    after = _counters(token)
    delta = {k: after[k] - before[k] for k in before}

    hit_n = sum(len(v) for k, v in by_group.items() if k != "none")
    total_n = sum(len(v) for v in by_group.values())
    hit_rate = (hit_n / total_n) if total_n else 0.0

    hit_ttfts = [x for k, v in by_group.items() if k != "none" for x in v]
    miss_ttfts = by_group.get("none", [])
    hit_s, miss_s = stats(hit_ttfts), stats(miss_ttfts)

    # 节省账只在两侧都有样本时才算——单侧样本算出来的"节省"是编的
    saved_ms = None
    if hit_s["p50"] is not None and miss_s["p50"] is not None:
        saved_ms = round((miss_s["p50"] - hit_s["p50"]) * hit_rate, 2)

    out = {
        "measured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "subject": a.user,
        "workload": {"hot": a.hot, "cold": a.cold, "total": total_n},
        "cache_hit_distribution": {k: len(v) for k, v in sorted(by_group.items())},
        "hit_rate": round(hit_rate, 4),
        "hit_ttft_ms": hit_s,
        "miss_ttft_ms": miss_s,
        "saved_ms_per_request": saved_ms,
        "global_counters_delta": delta,
        "note": ("命中率与延迟差均取自**本脚本自己的负载**（可归因）；global_counters_delta 是全系统口径"
                 "（含他人流量与 Single-Flight follower），仅作交叉核对，不可用来算命中率——"
                 "total_requests 含 follower，hits/total 会把去重流量当成 miss。"
                 "节省账用服务端 TTFT 做差；客户端端到端含本机栈开销，不参与。"),
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    # newline="\n"：Windows 文本模式会把 \n 翻译成 \r\n（同 10-06 产物族修复）
    (REPORTS / "cache_savings.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")

    def fmt(s: dict) -> str:
        return "—" if s["p50"] is None else f"{s['p50']} / {s['p95']}（n={s['n']}）"

    md = [
        "# 缓存节省账（命中率 × 延迟差）",
        "",
        f"> 实测时间：{out['measured_at']} ｜ 主体 `{a.user}` ｜ 负载 hot={a.hot} / cold={a.cold}",
        f"> cache_hit 分布：{out['cache_hit_distribution']} ｜ 命中率 **{hit_rate:.1%}**",
        "",
        "| 分组 | 服务端 TTFT p50 / p95（ms） |",
        "| --- | --- |",
        f"| 命中（L1+L2） | {fmt(hit_s)} |",
        f"| 未命中（none） | {fmt(miss_s)} |",
        "",
        f"**单请求平均节省**：{'—（两侧需各有样本才可算）' if saved_ms is None else f'{saved_ms} ms'}"
        f"　＝（未命中 p50 − 命中 p50）× 命中率",
        "",
        f"交叉核对（全系统计数增量，**非**本负载归因）：{delta}",
        "",
        out["note"],
        "",
        "复现：`cd offline && python load/cache_savings.py`（需活体栈 + `.env`；"
        "每次请求消耗 1 次当日配额，冷 query 另真调 LLM）。",
    ]
    (REPORTS / "cache_savings.md").write_text("\n".join(md) + "\n", encoding="utf-8", newline="\n")
    print(f"OK 命中率={hit_rate:.1%} hit_p50={hit_s['p50']}ms miss_p50={miss_s['p50']}ms "
          f"saved={saved_ms}ms -> {REPORTS / 'cache_savings.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
