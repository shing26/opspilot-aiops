#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自举告警生产者（ADR-0011）：让 OpsPilot 成为自己的告警源。

为什么存在：语料、query、评测集全部由本项目自己生成——系统至今没接触过任何它无法预测的
输入。本脚本轮询网关**已有的真相面**，命中异常就把观测到的事实当作一条真实告警 POST 回
自己的 /chat/stream（source=alert）。事件是真的（真触发、真 root cause、可对提交与台账核验），
只是来源恰好是系统自身的运行史。

它只读既有观测面、只调既有端点：不加后端接口、不新增指标、不改架构。

已知盲区（不假装覆盖）：网关自身挂掉时 /api/v1/admin/state 不可达，watcher 只能记一行
unreachable——"进程内自证自己已死"不可能，这是自举路径的固有边界，交付台账里如实登记。

用法:
  python alert_producer.py --once                      # 单轮探测后退出（验收友好）
  python alert_producer.py                             # 常驻，默认 15s 一轮
  python alert_producer.py --dry-run --once            # 只判定与留痕，不发任何告警请求
  python alert_producer.py --interval 5 --cooldown 60 --max-per-hour 10
  python alert_producer.py --user sre-watcher          # 告警主体（默认 sre-watcher）

退出码: 0 正常（含"无告警"与 dry-run）/ 2 主体权限不足（403，重试无意义）
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import localapi  # noqa: E402  （单一事实源：登录/取数/SSRF 白名单/SSE 原语）

REPO = Path(__file__).resolve().parent.parent
LEDGER = REPO / "logs" / "alert-producer.jsonl"
ENV = "local"
# 配额保留线：告警不得把人挤出去——防的是极端风暴下"告警自己烧穿配额，
# 导致随后真人排障被 429"。sre-watcher 已是独立主体，这条是第二层防御。
QUOTA_RESERVE = 50

# 规则 → 告警文案。文案刻意带错误码：真实告警本就携带错误码，且精确符号走快路径
# （跳过 rerank 压 TTFT）；两条复用既有语料（51001/51005），两条指向 2xx 自举语料，
# 一条纯语义兜底——这样"闭环"不依赖新写的文档也能被验证。
QUERIES = {
    "dep_down": ("告警：依赖组件 {comp} 健康检查异常（status={status}，detail={detail}）。"
                 "混合检索/缓存的对应腿已不可用，请给出排查步骤与止损操作 51208_DEPENDENCY_DOWN"),
    "degrade": ("告警：网关降级档位={level}（熔断失败计数 {failures}/{threshold}，冷却剩余 {cooldown}s，"
                "在途 {inflight}），LLM 链路可能已熔断改直出 SOP，"
                "请给出止损与恢复确认步骤 51201_CIRCUIT_BREAKER_NO_SELFHEAL"),
    "upstream_limited": ("告警：上游模型服务异常（llm_rate_limited 增量 {rate_limited}，"
                         "llm_network_errors 增量 {net_errors}），流式链路可能整体失败，"
                         "请给出分型诊断与止损操作 51001_MODEL_ARREARAGE"),
    "retrieval_degraded": ("告警：检索路劣化（retrieval_timeouts 增量 {timeouts}，"
                           "es_only_requests 增量 {es_only}），向量腿疑似持续超时、双路静默退化为单路，"
                           "请给出排查与止损步骤 51005_LEG_TIMEOUT_SILENT_DEGRADE"),
    "refusal_spike": ("告警：拒答/降级直出激增（low_confidence_refusals 增量 {refusals}，"
                      "sop_fallbacks 增量 {sop}），召回质量或上游与配置可能不一致，请给出排查方向"),
}

METRIC_KEYS = ("llm_rate_limited", "llm_network_errors", "retrieval_timeouts",
               "es_only_requests", "low_confidence_refusals", "sop_fallbacks")


def _num(v) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def metrics_of(state: dict) -> dict:
    return {k: _num((state.get("metrics") or {}).get(k)) for k in METRIC_KEYS}


def deltas(cur: dict, prev: dict | None) -> tuple[dict, bool]:
    """计数器增量。返回 (增量, 是否复位)。

    计数器是进程内 AtomicLong，重启即归零——负增量必须当复位处理（记 0 并留痕），
    否则会凭空造出"负告警"，或把重启掩盖成一次静默。
    """
    if prev is None:
        return {k: 0 for k in METRIC_KEYS}, False
    reset = any(cur[k] < prev.get(k, 0) for k in METRIC_KEYS)
    return ({k: (0 if reset else max(0, cur[k] - prev.get(k, 0))) for k in METRIC_KEYS}, reset)


def detect(state: dict, d: dict) -> list[dict]:
    """真相面 → 候选告警。只做判定；冷却/预算/配额等发送闸门在 Producer 里。"""
    out: list[dict] = []

    for comp in ("redis", "qdrant", "es"):
        c = (state.get("health") or {}).get(comp) or {}
        if str(c.get("status", "UP")).upper() != "UP":
            out.append({"rule": "dep_down", "service": comp,
                        "query": QUERIES["dep_down"].format(comp=comp, status=c.get("status"),
                                                            detail=c.get("detail"))})

    rt = state.get("runtime") or {}
    deg = rt.get("degradation") or {}
    level = str(deg.get("level") or "L0")
    if level in ("L1", "L2") or _num(deg.get("cooldown_s")) > 0:
        out.append({"rule": "degrade", "service": "opspilot-gateway",
                    "query": QUERIES["degrade"].format(level=level, failures=deg.get("failures"),
                                                       threshold=deg.get("threshold"),
                                                       cooldown=deg.get("cooldown_s"),
                                                       inflight=rt.get("inflight"))})

    if d["llm_rate_limited"] or d["llm_network_errors"]:
        out.append({"rule": "upstream_limited", "service": "dashscope-api",
                    "query": QUERIES["upstream_limited"].format(rate_limited=d["llm_rate_limited"],
                                                                net_errors=d["llm_network_errors"])})

    if d["retrieval_timeouts"] and d["es_only_requests"]:
        out.append({"rule": "retrieval_degraded", "service": "opspilot-gateway",
                    "query": QUERIES["retrieval_degraded"].format(timeouts=d["retrieval_timeouts"],
                                                                  es_only=d["es_only_requests"])})

    if d["low_confidence_refusals"] or d["sop_fallbacks"]:
        out.append({"rule": "refusal_spike", "service": "opspilot-gateway",
                    "query": QUERIES["refusal_spike"].format(refusals=d["low_confidence_refusals"],
                                                             sop=d["sop_fallbacks"])})
    return out


class Producer:
    """一轮 = 读一次真相面 → 判定 → 过闸门 → 发告警 → 落台账。单轮失败绝不让进程死。"""

    def __init__(self, args):
        self.user = args.user
        self.dry_run = args.dry_run
        self.cooldown = args.cooldown
        self.max_per_hour = args.max_per_hour
        # 可调是为了可测：把保留线设 0 才能构造"配额真被打满 → 429"的活体用例，
        # 否则保留线总是先一步挡住，429 分支永远测不到。
        self.quota_reserve = getattr(args, "quota_reserve", QUOTA_RESERVE)
        self.token = ""
        self.prev: dict | None = None
        self.last_emit: dict[str, float] = {}   # rule → 上次**真的发出去**的时刻（冷却只约束发送）
        self.emits: list[float] = []
        self.stats = {"cycles": 0, "candidates": 0, "emitted": 0, "skipped": 0, "errors": 0}

    # ---- 与网关交互 -------------------------------------------------------
    def login(self) -> None:
        self.token = localapi.login(self.user)

    def fetch_state(self) -> dict:
        """读管理面；401 重登一次（24h token 过期是常态），403 直接判主体配置错误。"""
        try:
            return localapi.get_json("/api/v1/admin/state", self.token)
        except urllib.error.HTTPError as e:
            if e.code == 401:
                self.login()
                return localapi.get_json("/api/v1/admin/state", self.token)
            if e.code == 403:
                raise PermissionError(
                    f"主体 {self.user} 无权读 /admin/state（需 role=platform 且 auth_level>=3）") from e
            raise

    def emit(self, alert: dict) -> dict:
        """发一条告警；台账记录里带上服务端权威事实（fingerprint/cache_hit/refs）。"""
        body = {"query": alert["query"], "source": "alert", "service": alert["service"], "env": ENV}
        if self.dry_run:
            return {"rule": alert["rule"], "service": alert["service"], "emit": False,
                    "skip": "dry_run", "query": alert["query"]}
        r = localapi.stream_chat(body, self.token)
        meta, done = r.get("meta") or {}, r.get("done") or {}
        # refs 帧字段是 chunkId（AnswerPayload.Ref 的 Jackson 默认命名，非 chunk_id）；
        # chunk_id 形如 "<doc_id>::<breadcrumb>"，doc_id 侧由此派生——台账同时留原始与派生，
        # 便于读者拿原始帧对账而不必信派生结果。
        refs = [x.get("chunkId") or "" for x in (done.get("refs") or [])]
        return {"rule": alert["rule"], "service": alert["service"], "emit": True,
                "http": r["status"], "error_code": r.get("code"),
                "fp": meta.get("fingerprint"), "cache_hit": meta.get("cache_hit"),
                "deduplicated": meta.get("deduplicated"),
                "degradation_level": meta.get("degradation_level"),
                "refs": refs, "ref_docs": sorted({c.split("::")[0] for c in refs if c}),
                "ttft_s": r.get("ttft_s"), "total_s": r.get("total_s"),
                "server_error": r.get("error"), "query": alert["query"]}

    def ledger(self, rec: dict) -> None:
        rec = {"ts": int(time.time() * 1000), "user": self.user, **rec}
        try:
            LEDGER.parent.mkdir(parents=True, exist_ok=True)
            with open(LEDGER, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
        except OSError as e:
            print(f"[warn] 台账写入失败（不阻断告警）: {e}", file=sys.stderr)

    # ---- 发送闸门 ---------------------------------------------------------
    def budget_exhausted(self) -> bool:
        now = time.time()
        self.emits = [t for t in self.emits if now - t < 3600]
        return len(self.emits) >= self.max_per_hour

    def quota_low(self, state: dict) -> bool:
        q = (state.get("runtime") or {}).get("quota") or {}
        limit, used = _num(q.get("limit")), _num(q.get("used"))
        return limit > 0 and (limit - used) < self.quota_reserve

    def gate(self, alert: dict, state: dict, now: float) -> dict:
        """闸门顺序即优先级：预算 > 配额 > 冷却。每个 skip 都留痕，便于事后问"为什么没发"。"""
        if self.budget_exhausted():
            return {"rule": alert["rule"], "service": alert["service"], "emit": False,
                    "skip": "hour_budget", "query": alert["query"]}
        if self.quota_low(state):
            return {"rule": alert["rule"], "service": alert["service"], "emit": False,
                    "skip": "quota_reserve", "query": alert["query"]}
        if now - self.last_emit.get(alert["rule"], 0.0) < self.cooldown:
            return {"rule": alert["rule"], "service": alert["service"], "emit": False,
                    "skip": "cooldown", "query": alert["query"]}
        return self.emit(alert)

    # ---- 主循环 -----------------------------------------------------------
    def cycle(self) -> list[dict]:
        state = self.fetch_state()
        self.stats["cycles"] += 1
        cur = metrics_of(state)
        d, reset = deltas(cur, self.prev)
        self.prev = cur
        if reset:
            self.ledger({"rule": "-", "emit": False, "skip": "counter_reset",
                         "note": "计数器归零（服务重启）——本轮增量按 0 处理，不据复位造告警"})

        now = time.time()
        written = []
        for alert in detect(state, d):
            self.stats["candidates"] += 1
            rec = self.gate(alert, state, now)
            if rec.get("emit"):
                self.last_emit[alert["rule"]] = now
                self.emits.append(now)
                self.stats["emitted"] += 1
                print(f"[alert] {alert['rule']} service={alert['service']} http={rec.get('http')} "
                      f"fp={(rec.get('fp') or '')[:12]} cache={rec.get('cache_hit')} refs={rec.get('refs')}")
            else:
                self.stats["skipped"] += 1
            self.ledger(rec)
            written.append(rec)
        return written

    def run(self, interval: int) -> int:
        if self.dry_run:
            print(f"[dry-run] 主体={self.user}：只判定与留痕，不发告警请求")
        while True:
            try:
                # 登录放进循环：常驻进程很可能比网关先起（cron / 开机自启），
                # 初始登录失败必须是"这一轮失败"而不是进程猝死——否则一次重启窗口
                # 就能让告警源永久失效，而那正是最需要它的时刻。
                if not self.token:
                    self.login()
                self.cycle()
            except PermissionError as e:
                print(f"[fatal] {e}", file=sys.stderr)
                return 2
            except Exception as e:                       # 单轮失败不得让 watcher 死掉
                self.stats["errors"] += 1
                self.token = ""                          # 凭证/网络态未知 → 下轮重新登录
                self.ledger({"rule": "-", "emit": False, "skip": "unreachable",
                             "note": f"{type(e).__name__}: {e}"})
                print(f"[warn] 本轮失败（继续）: {type(e).__name__}: {e}", file=sys.stderr)
            if interval <= 0:
                break
            time.sleep(interval)
        return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="OpsPilot 自举告警生产者（ADR-0011）")
    ap.add_argument("--once", action="store_true", help="只跑一轮后退出（等价 --interval 0）")
    ap.add_argument("--interval", type=int, default=15, help="轮询间隔秒（默认 15；健康面本身 5s TTL）")
    ap.add_argument("--cooldown", type=int, default=300, help="同一规则两次发送的最小间隔秒（默认 300）")
    ap.add_argument("--max-per-hour", type=int, default=20, help="每小时告警上限（默认 20，防风暴自伤）")
    ap.add_argument("--quota-reserve", type=int, default=QUOTA_RESERVE,
                    help=f"配额保留线（默认 {QUOTA_RESERVE}）：剩余低于此值则不发告警，留给人用")
    ap.add_argument("--user", default="sre-watcher", help="告警主体（默认 sre-watcher）")
    ap.add_argument("--dry-run", action="store_true", help="只判定与留痕，不发告警请求")
    a = ap.parse_args(argv)
    p = Producer(a)
    rc = p.run(0 if a.once else a.interval)
    s = p.stats
    print(f"[summary] cycles={s['cycles']} candidates={s['candidates']} emitted={s['emitted']} "
          f"skipped={s['skipped']} errors={s['errors']}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
