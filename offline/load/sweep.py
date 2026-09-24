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


def parse_max_users(path: Path) -> int | None:
    """取该档**实际达到**的最大并发用户数（locust `_stats_history.csv` 的 User Count 列）。

    为什么必须记它：`-r`（每秒启动用户数）× 时长 才是并发上限。首版固定 `-r 10` + 30s ⇒ **最多只能爬到
    300 用户**，于是"u=500"那一档实际只跑到 290——报告上却写着 500，属**标签与事实不符**。
    记下真值后，读者能自行判断高并发档是否为"够得着"的档。
    """
    if not path.exists():
        return None
    with path.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        return None
    col = next((c for c in rows[0] if "User Count" in c), None)
    if col is None:
        return None
    vals = []
    for r in rows:
        try:
            vals.append(int(float(r[col])))
        except (TypeError, ValueError):
            continue
    return max(vals) if vals else None


def parse_failures_csv(path: Path) -> list[dict]:
    """取该档的失败明细（纯函数，可离线单测）。返回 [{error, occurrences}]，空文件返回 []。

    为什么要单列失败**性质**：`HTTP 0` 是**连接层**失败（未拿到任何响应：连接被拒/重置/超时），
    与 `HTTP 5xx`（应用层错误）是完全不同的结论——前者指向容量/连接栈极限，后者指向应用缺陷。
    只看 failure_rate 会把两者混成一个"失败率"。
    """
    out: list[dict] = []
    if not path.exists():
        return out
    with path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            err = (row.get("Error") or "").strip()
            if not err:
                continue
            try:
                n = int(float(row.get("Occurrences") or 0))
            except ValueError:
                n = 0
            out.append({"error": err, "occurrences": n})
    return out


def failure_rate(stats: dict) -> float | None:
    """失败率 = 失败数 / 总请求数。总数为 0 时返回 None（不编 0%）。"""
    n = stats.get("requests") or 0
    if n <= 0:
        return None
    return (stats.get("failures") or 0) / n


def _degradation_snapshot(token: str) -> tuple[str, bool, int]:
    """一次 /admin/state 采样 → (档位, 是否人工锁定, 当前在途请求数)。"""
    st = localapi.get_json("/api/v1/admin/state", token)
    rt = st.get("runtime") or {}
    d = rt.get("degradation") or {}
    try:
        inflight = int(rt.get("inflight") or 0)
    except (TypeError, ValueError):
        inflight = 0
    return str(d.get("level") or "L0"), bool(d.get("manual")), inflight


class LevelSampler:
    """运行期间轮询降级档位与在途数，取各自最高值。

    为什么连**在途数**一起采：L1 的触发条件是"在途请求数 ≥ inflight-threshold"，
    只看"档位未变"答不出"为什么没触发"。采到最大在途后即可直接对照阈值——是"没到阈值"
    还是"到了阈值却没触发"，两者结论完全不同。
    """

    def __init__(self, token: str, interval: float = 1.0):
        self.token, self.interval = token, interval
        self.worst = "L0"
        self.max_inflight = 0
        self.manual_seen = False
        self._stop = threading.Event()
        self._t: threading.Thread | None = None

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                lvl, manual, inflight = _degradation_snapshot(self.token)
                if LEVEL_RANK.get(lvl, 0) > LEVEL_RANK.get(self.worst, 0):
                    self.worst = lvl
                self.max_inflight = max(self.max_inflight, inflight)
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
    # `--exit-code-on-error 0`：locust 默认在有**任何失败**时以 1 退出，而本脚本测的就是失败率，
    # 失败是**被测量的量、不是错误**。首版用 check=True 把"u=200 出现连接层失败"当致命错处理，
    # 结果整个 sweep 崩在那档、连拐点数据一起丢掉（2026-09-24 实测踩到）。
    cmd = [sys.executable, "-m", "locust", "-f", str(LOAD / "locustfile.py"), "--headless",
           "-u", str(level), "-r", str(rate), "-t", duration, "--csv", str(prefix),
           "--only-summary", "--exit-code-on-error", "0"]
    env = dict(os.environ)
    proc = subprocess.run(cmd, check=False, cwd=str(LOAD), env=env,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    stats_csv = prefix.with_name(prefix.name + "_stats.csv")
    if not stats_csv.exists():
        # 真崩（参数错/依赖缺）才会走到这里——此时才该报错，且带上退出码供定位
        raise RuntimeError(f"locust 在 u={level} 未产出 stats.csv（退出码 {proc.returncode}）——"
                           f"按真实错误排查，勿当成「该档有失败」")
    return stats_csv


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="并发-延迟曲线扫描")
    ap.add_argument("--levels", default=",".join(str(x) for x in DEFAULT_LEVELS),
                    help="逗号分隔的并发档位（默认 25,50,100,200,300,500）")
    ap.add_argument("--duration", default="30s", help="每档时长（默认 30s）")
    ap.add_argument("--ramp-seconds", type=int, default=5,
                    help="爬到目标并发用几秒（默认 5）；ramp 速率 = 档位/该值。"
                         "**不要用固定速率**：固定 -r 10 + 30s 只能爬到 300 用户，高档位会跑不到（实测踩过）")
    ap.add_argument("--user", default=localapi.DEFAULT_EVAL_USER,
                    help=f"主体（默认 {localapi.DEFAULT_EVAL_USER}）——必须是真实用户名，不是 load_tokens 的键名")
    a = ap.parse_args(argv)
    levels = [int(x) for x in a.levels.split(",")]

    token = localapi.login(a.user)
    state = localapi.get_json("/api/v1/admin/state", token)
    backend = (state.get("metrics") or {}).get("backend") or {}
    # 注意口径：/state 的 runtime.degradation.threshold 是 **LLM 熔断失败阈值**（并非 inflight 阈值）。
    # inflight 阈值（默认 40）**未由 /state 暴露**，故本脚本不臆造它——改采"在途峰值"直接对照。
    llm_failure_threshold = ((state.get("runtime") or {}).get("degradation") or {}).get("threshold")
    quota_limit = ((state.get("runtime") or {}).get("quota") or {}).get("limit")

    # 预热：冷启动会把 JIT/连接建立记成"低并发更慢"，污染曲线
    localapi.stream_chat({"query": "50012_DB_TIMEOUT 预热", "source": "manual", "service": "",
                          "env": "prod"}, token, collect_deltas=False)

    points = []
    first_l1 = None
    for lv in levels:
        # ramp 速率按档位自适应：保证在 --ramp-seconds 内爬到目标，高档位才"够得着"
        rate = max(1, round(lv / max(1, a.ramp_seconds)))
        with LevelSampler(token) as s:
            stats_csv = _run_locust(lv, a.duration, rate)
            worst, manual, max_inflight = s.worst, s.manual_seen, s.max_inflight
        st = parse_stats_csv(stats_csv)
        fails = parse_failures_csv(stats_csv.with_name(stats_csv.name.replace("_stats.csv", "_failures.csv")))
        max_users = parse_max_users(stats_csv.with_name(
            stats_csv.name.replace("_stats.csv", "_stats_history.csv")))
        pt = {"level": lv, "ramp_rate": rate, "max_users": max_users,
              "degradation_worst": worst, "manual_lock_seen": manual, "max_inflight": max_inflight,
              "failure_rate": failure_rate(st), "failure_detail": fails, **st}
        points.append(pt)
        if first_l1 is None and LEVEL_RANK.get(worst, 0) >= 1:
            first_l1 = lv
        print(f"  u={lv}(r={rate}, 实到 {max_users}, 在途峰值 {max_inflight}): rps={st['rps']} "
              f"p50={st['p50_ms']} p99={st['p99_ms']} fail={pt['failure_rate']} "
              f"worst={worst} detail={fails}")

    out = {
        "measured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "subject": a.user,
        "backend": backend,
        "llm_failure_threshold": llm_failure_threshold,
        "quota_limit": quota_limit,
        "ramp_seconds": a.ramp_seconds,
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
                 "故抬高并披露。各档 failure_rate 即该档是否被 429 污染的判据。"
                 "**实到并发必须看**：`-r` × 时长为并发上限，固定 ramp 速率会让高档位爬不到目标"
                 "（曾出现「u=500 实到只有 290」，标签与事实不符）；本脚本按 `档位/ramp-seconds` "
                 "自适应 ramp 并逐档记录实到值。**失败要分性质**：`HTTP 0` 是连接层（容量/本地栈极限），"
                 "`HTTP 5xx` 才是应用缺陷；且连接层失败可能**非单调**（本地临时端口耗尽等粘性资源"
                 "会让相邻档出现「低档失败、高档反而干净」），故单看某一档的失败率不足以下结论。"),
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
        f"> 实测时间：{out['measured_at']} ｜ 主体 `{a.user}` ｜ 每档 {a.duration}（ramp {a.ramp_seconds}s） ｜ "
        f"LLM 熔断失败阈值 {llm_failure_threshold}（inflight 阈值未由 /state 暴露，故以在途峰值对照） ｜ "
        f"当日配额上限 {quota_limit}",
        f"> 后端：embedding={backend.get('embedding')} / rerank={backend.get('rerank')} / "
        f"llm={backend.get('llm')}",
        f"> **首次降级档**：{'未触发（各档全程 L0）' if first_l1 is None else f'u={first_l1}'}",
        "",
        "| 目标并发 | 实到并发 | 在途峰值 | RPS | P50 (ms) | P95 (ms) | P99 (ms) | 失败率 | 期间最高档 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for p in points:
        lock = "（人工锁定）" if p["manual_lock_seen"] else ""
        short = "" if p.get("max_users") is None or p["max_users"] >= p["level"] else " ⚠️未达"
        md.append(f"| {p['level']} | {p.get('max_users')}{short} | {p.get('max_inflight')} | "
                  f"{f(p['rps'])} | {f(p['p50_ms'])} | {f(p['p95_ms'])} | {f(p['p99_ms'])} | "
                  f"{fpct(p['failure_rate'])} | {p['degradation_worst']}{lock} |")
    failing = [p for p in points if p.get("failure_detail")]
    if failing:
        md += ["", "**失败明细（区分连接层与应用层——前者指向容量极限，后者指向应用缺陷）**：", ""]
        for p in failing:
            md.append(f"- u={p['level']}：" + "；".join(
                f"`{d['error']}` ×{d['occurrences']}" for d in p["failure_detail"]))
    md += ["", out["note"], "",
           f"复现：`cd offline && python load/sweep.py --levels {a.levels} --duration {a.duration}`"
           "（需活体栈 + `.env` + live key；每档真实调用 LLM，有 token 成本）。"]
    (REPORTS / "concurrency_sweep.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"OK 六档完成，首次降级档={first_l1} -> {REPORTS / 'concurrency_sweep.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
