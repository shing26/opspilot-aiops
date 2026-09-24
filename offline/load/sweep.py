#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""并发-延迟曲线（把"我压过 50 并发"变成"我知道拐点在哪、多少并发开始劣化"）。

为什么需要它：此前只有两个孤立档位的实测——场景 A（50 并发）与场景 B（500 并发风暴）。
中间 100/200/300 是空的，于是"系统极限在哪"这个问题答不出来：只知道 50 稳、500 触发了降级，
不知道从哪一档开始劣化、L1 第一次被触发是在什么并发。本脚本扫六档，出"并发 vs（P50/P99、
失败率、RPS）"三条曲线，并标出**首次 L1 触发档**。

口径（勿误读）：
- **后端必须 live**：曲线形状依赖真实的 embedding/rerank 延迟（`leg-timeout-ms: 4500` 与
  `inflight-threshold: 40` 都是按真实延迟定的）。mock 后端是词法代理、无网络延迟，曲线会明显偏平，
  拐点位置失真——那种曲线不该拿来回答"极限在哪"。
- 降级档位靠**轮询 `/admin/state`** 的 `runtime.degradation.level`，取每档运行期间的**最高**档
  （瞬时采样会漏掉脉冲）；`manual=true` 时该档视为受人工锁定影响，报告中标注。
- 报告**只入汇总**（json+md）：每档的原始 locust CSV/HTML 留在本地。理由：六档 × 五文件 = 30 个
  文件会淹掉 `reports/`，而曲线的价值在汇总表；汇总表内记完整复现命令，谁都能重跑。
- 冷启动会污染曲线：每档前先预热（脚本自动做一轮单请求），否则首档会把 JIT/连接建立的开销
  记成"低并发更慢"。

用法（离线目录，需活体栈 + `.env`）:
  python load/sweep.py                          # 六档默认 25/50/100/200/300/500，各 30s
  python load/sweep.py --levels 50,200,500 --duration 20s
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import localapi  # noqa: E402

LOAD = Path(__file__).resolve().parent
REPORTS = LOAD / "reports"
RAW = LOAD / "_sweep_raw"          # 原始 locust 产物（不入库）
DEFAULT_LEVELS = (25, 50, 100, 200, 300, 500)
LEVEL_RANK = {"L0": 0, "L1": 1, "L2": 2}


def parse_stats_csv(path: Path) -> dict:
    """从 locust `_stats.csv` 的 Aggregated 行取汇总指标（纯函数，可离线单测）。"""
    with path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if row.get("Name") == "Aggregated":
                def num(k):
                    try:
                        return float(row[k])
                    except (KeyError, TypeError, ValueError):
                        return None
                return {
                    "requests": int(float(row["Request Count"])),
                    "failures": int(float(row["Failure Count"])),
                    "rps": num("Requests/s"),
                    "p50_ms": num("50%"),
                    "p95_ms": num("95%"),
                    "p99_ms": num("99%"),
                    "max_ms": num("Max Response Time"),
                }
    raise ValueError(f"{path} 里没有 Aggregated 行")


def failure_rate(stats: dict) -> float | None:
    """失败率 = 失败数 / 总请求数。总数为 0 时返回 None（不编 0%）。"""
    n = stats.get("requests") or 0
    if n <= 0:
        return None
    return (stats.get("failures") or 0) / n


def _degradation_level(token: str) -> tuple[str, bool]:
    st = localapi.get_json("/api/v1/admin/state", token)
    d = ((st.get("runtime") or {}).get("degradation") or {})
    return str(d.get("level") or "L0"), bool(d.get("manual"))


class LevelSampler:
    """运行期间轮询降级档位，记最高档（瞬时采样会漏脉冲）。"""

    def __init__(self, token: str, interval: float = 1.0):
        self.token, self.interval = token, interval
        self.worst = "L0"
        self.manual_seen = False
        self._stop = threading.Event()
        self._t: threading.Thread | None = None

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                lvl, manual = _degradation_level(self.token)
                if LEVEL_RANK.get(lvl, 0) > LEVEL_RANK.get(self.worst, 0):
                    self.worst = lvl
                self.manual_seen = self.manual_seen or manual
            except Exception:
                pass
            self._stop.wait(self.interval)

    def __enter__(self):
        self._t = threading.Thread(target=self._loop, daemon=True)
        self._t.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        if self._t:
            self._t.join(timeout=5)


def _run_locust(level: int, duration: str, rate: int) -> Path:
    RAW.mkdir(parents=True, exist_ok=True)
    prefix = RAW / f"lvl_{level}"
    cmd = [sys.executable, "-m", "locust", "-f", str(LOAD / "locustfile.py"), "--headless",
           "-u", str(level), "-r", str(rate), "-t", duration, "--csv", str(prefix),
           "--only-summary"]
    env = dict(os.environ)
    subprocess.run(cmd, check=True, cwd=str(LOAD), env=env,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return prefix.with_name(prefix.name + "_stats.csv")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="并发-延迟曲线扫描")
    ap.add_argument("--levels", default=",".join(str(x) for x in DEFAULT_LEVELS),
                    help="逗号分隔的并发档位（默认 25,50,100,200,300,500）")
    ap.add_argument("--duration", default="30s", help="每档时长（默认 30s）")
    ap.add_argument("--rate", type=int, default=10, help="每秒启动用户数（默认 10）")
    ap.add_argument("--user", default=localapi.DEFAULT_EVAL_USER,
                    help=f"主体（默认 {localapi.DEFAULT_EVAL_USER}）——必须是真实用户名，不是 load_tokens 的键名")
    a = ap.parse_args(argv)
    levels = [int(x) for x in a.levels.split(",")]

    token = localapi.login(a.user)
    state = localapi.get_json("/api/v1/admin/state", token)
    backend = (state.get("metrics") or {}).get("backend") or {}
    inflight_threshold = ((state.get("runtime") or {}).get("degradation") or {}).get("threshold")
    quota_limit = ((state.get("runtime") or {}).get("quota") or {}).get("limit")

    # 预热：冷启动会把 JIT/连接建立记成"低并发更慢"，污染曲线
    localapi.stream_chat({"query": "50012_DB_TIMEOUT 预热", "source": "manual", "service": "",
                          "env": "prod"}, token, collect_deltas=False)

    points = []
    first_l1 = None
    for lv in levels:
        with LevelSampler(token) as s:
            stats_csv = _run_locust(lv, a.duration, a.rate)
            worst, manual = s.worst, s.manual_seen
        st = parse_stats_csv(stats_csv)
        pt = {"level": lv, "degradation_worst": worst, "manual_lock_seen": manual,
              "failure_rate": failure_rate(st), **st}
        points.append(pt)
        if first_l1 is None and LEVEL_RANK.get(worst, 0) >= 1:
            first_l1 = lv
        print(f"  u={lv}: rps={st['rps']} p50={st['p50_ms']} p99={st['p99_ms']} "
              f"fail={pt['failure_rate']} worst={worst}")

    out = {
        "measured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "subject": a.user,
        "backend": backend,
        "inflight_threshold": inflight_threshold,
        "quota_limit": quota_limit,
        "duration_per_level": a.duration,
        "points": points,
        "first_degraded_level": first_l1,
        "note": ("后端必须 live：曲线形状依赖真实 embedding/rerank 延迟，mock 是词法代理、曲线偏平。"
                 "降级档位取每档运行期间的**最高**档（轮询 1s，瞬时采样会漏脉冲）；manual_lock_seen=true "
                 "表示该档期间存在人工锁定，其档位不纯由负载导致。原始 locust 产物在 "
                 "load/_sweep_raw/（不入库），本报告是唯一入库的汇总。"
                 "**配额口径须一并读**：六档合计请求数远超默认配额（5000/天/主体），故本轮的网关是以"
                 "抬高的 OPSPILOT_QUOTA_DAILY_LIMIT 启动的——本报告记录的 quota_limit 即当时真值；"
                 "曲线测的是延迟/吞吐，不是配额护栏，配额耗尽后 locust 会把 429 记成失败使曲线失真，"
                 "故抬高并披露。各档 failure_rate 即该档是否被 429 污染的判据。"),
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "concurrency_sweep.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")

    def f(x, nd=1):
        return "—" if x is None else f"{x:.{nd}f}"

    def fpct(x):
        return "—" if x is None else f"{x:.2%}"

    md = [
        "# 并发-延迟曲线（拐点与首次降级档）",
        "",
        f"> 实测时间：{out['measured_at']} ｜ 主体 `{a.user}` ｜ 每档 {a.duration} ｜ "
        f"inflight 降级阈值 {inflight_threshold} ｜ 当日配额上限 {quota_limit}",
        f"> 后端：embedding={backend.get('embedding')} / rerank={backend.get('rerank')} / "
        f"llm={backend.get('llm')}",
        f"> **首次降级档**：{'未触发（六档全 L0）' if first_l1 is None else f'u={first_l1}'}",
        "",
        "| 并发 | RPS | P50 (ms) | P95 (ms) | P99 (ms) | 失败率 | 期间最高档 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for p in points:
        lock = "（人工锁定）" if p["manual_lock_seen"] else ""
        md.append(f"| {p['level']} | {f(p['rps'])} | {f(p['p50_ms'])} | {f(p['p95_ms'])} | "
                  f"{f(p['p99_ms'])} | {fpct(p['failure_rate'])} | {p['degradation_worst']}{lock} |")
    md += ["", out["note"], "",
           f"复现：`cd offline && python load/sweep.py --levels {a.levels} --duration {a.duration}`"
           "（需活体栈 + `.env` + live key；每档真实调用 LLM，有 token 成本）。"]
    (REPORTS / "concurrency_sweep.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"OK 六档完成，首次降级档={first_l1} -> {REPORTS / 'concurrency_sweep.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
