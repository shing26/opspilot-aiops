# -*- coding: utf-8 -*-
"""真实 Docker 告警源——把"自举告警闭环"从演练变成真的。

**为什么要有这个**：ADR-0011 的告警生产者此前是演示脚本（demo_self_alert.sh——
自己停自己的中间件、自己给自己发告警），闭环的"链路是真的、输入是造的"。
本脚本把输入也变成真的：监视本机 Docker 容器的真实状态翻转
（running→exited / healthy→unhealthy），翻转即以 source=alert 发告警——
**告警文本就是容器真实状态的一手描述**，不是编的故障。

它发出的告警走的是和人工提问完全相同的编排链路（指纹 → Single-Flight → 检索 →
生成/拒答），所以"容器连环挂"这类风暴会被指纹收敛——这正是本系统存在的理由，
现在第一次有真实流量能打到它。

**设计纪律**：
- 只观测、不重启。重启是人的决策（OPS §7：别在诊断前先动手）。
- 复用 `offline/localapi.py` 的 `assert_local`（SSRF 白名单）与 login（凭据只从 .env），
  **不自带一份 HTTP 客户端**——同一防线抄两份必然漂移。
- 服务归属按容器名前缀映射（opspilot-*→opspilot / shoppilot-*→shoppilot / 其余→docker-host），
  使指纹的 service 维度真实可分。
- 状态持久化在 logs/watchdog_state.json：**看门狗自己重启不会把上次已知状态当翻转重报**
  （告警去重的责任在服务端，但"别把冷启动当事故"是客户端的体面）。

用法：
    python scripts/watchdog_alerts.py --interval 10            # 常驻
    python scripts/watchdog_alerts.py --once                   # 扫一轮就退（给计划任务）
    python scripts/watchdog_alerts.py --containers a,b         # 只盯指定容器
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "offline"))
import localapi  # noqa: E402  # 单一 HTTP/token 事实源（含 assert_local SSRF 防线）

def _load_dotenv() -> None:
    """把 .env 装进进程环境（不覆盖已有值、绝不打印值）。

    为什么脚本自己做：这脚本要挂计划任务/常驻——那些环境里没有人先 `source .env`，
    而 login 的 DEMO_PASSWORD 只从环境来（localapi 的硬约束）。"""
    env_file = REPO / ".env"
    if not env_file.is_file():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        k, v = k.strip(), v.strip().strip('"').strip("'")
        if k and k not in os.environ:
            os.environ[k] = v

DEFAULT_INTERVAL = 10
STATE_FILE = REPO / "logs" / "watchdog_state.json"

# 容器名前缀 → 告警的 service 维度（指纹用 service|env|normalized_error 拼摘要，
# 前缀映射让不同项目的容器天然收敛到不同指纹，互不吞并）
SERVICE_PREFIXES = [
    ("opspilot", "opspilot"),
    ("shoppilot", "shoppilot"),
    ("nexus", "nexus"),
    ("moa-gateway", "moa-gateway"),
]

EXIT_HINTS = {
    137: "exit 137 = SIGKILL，最常见原因是宿主内存不足被内核 OOM 杀（见 rb-102："
         "先看宿主可用内存，<2GB 先腾再 docker compose up -d）",
    143: "exit 143 = SIGTERM，多为 docker stop / compose down 的正常收尾",
    0: "exit 0：正常退出，但容器不该停——查是不是单进程容器跑完了",
}


def service_of(container: str) -> str:
    for prefix, service in SERVICE_PREFIXES:
        if container.startswith(prefix):
            return service
    return "docker-host"


def docker_state() -> dict[str, dict]:
    """docker ps -a → {name: {state, health, exit, ago_s}}。docker 不可达时抛 RuntimeError。"""
    try:
        out = subprocess.run(
            ["docker", "ps", "-a", "--format",
             "{{.Names}}\t{{.State}}\t{{.Status}}\t{{.Image}}"],
            capture_output=True, text=True, timeout=20, check=True,
        ).stdout
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        raise RuntimeError(f"docker 不可用: {e}") from e
    import re
    ago_re = re.compile(r"(\d+)\s+(second|minute|hour|day)s?\s+ago")
    exit_re = re.compile(r"\((\d+)\)")   # 只取括号内的码——"Exited (137) 13 seconds ago"
    # 若用"括号后所有数字"的土办法会把 13 秒的 13 粘进来得到 13713（实测踩过）
    unit_s = {"second": 1, "minute": 60, "hour": 3600, "day": 86400}
    states: dict[str, dict] = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        name, state, status = parts[0], parts[1], parts[2]
        exit_code = None
        ago_s = None
        if "Exited" in status:
            m = exit_re.search(status)
            exit_code = int(m.group(1)) if m else None
            m = ago_re.search(status)
            if m:
                ago_s = int(m.group(1)) * unit_s[m.group(2)]
        health = None
        if "(healthy)" in status:
            health = "healthy"
        elif "(unhealthy)" in status or ("health: " in status and "failing" in status):
            health = "unhealthy"
        states[name] = {"state": state, "health": health, "exit": exit_code, "ago_s": ago_s}
    return states


def diff_alerts(prev: dict, curr: dict, recent_s: int = 300) -> list[str]:
    """两次快照的差分 → 告警文本列表。

    两类都算事故：
      ① 观察到翻转：见过它活着（running/restarting），现在 exited/unhealthy；
      ② **首见即刚死**：看门狗从没见过它，但它是新近退出的（默认 5 分钟内，非零码或
         unhealthy）。没有这条，"容器在两次扫描之间生生死死"和"看门狗恰好在故障后
         冷启动"都会静默漏报——后者恰是最需要告警的时刻。
    死了很久的（首见且超过 recent_s）不算：那是历史遗留，不是翻转。"""
    alerts = []
    for name, now in sorted(curr.items()):
        before = prev.get(name)
        if before is not None:
            died = before["state"] in ("running", "restarting", "created") and now["state"] == "exited"
            sick = (before["health"] == "healthy" and now["health"] == "unhealthy")
            if not (died or sick):
                continue
            reason = "已退出" if died else "健康检查转 unhealthy"
        elif now["state"] == "exited" and (now["exit"] or 0) != 0 \
                and now["ago_s"] is not None and now["ago_s"] <= recent_s:
            reason = "退出（看门狗启动前后刚发生）"
        else:
            continue
        bits = [f"容器 {name} {reason}"]
        if now["exit"] is not None:
            bits.append(f"退出码 {now['exit']}")
            hint = EXIT_HINTS.get(now["exit"])
            if hint:
                bits.append(hint)
        if now["state"] == "running" and now["health"] == "unhealthy":
            bits.append("容器仍在运行但健康检查持续失败——多半是依赖断了或内部假死")
        alerts.append("；".join(bits))
    return alerts


def send_alert(text: str, env: str, timeout: int) -> dict:
    """以 source=alert 走完整编排链路（指纹/收敛/检索/生成）。非 2xx 不抛——告警链路
    绝不能因一次限流崩掉（与 console_client 同一口径）。"""
    token = localapi.login("sre-watcher")
    try:
        r = localapi.stream_chat(
            {"query": text, "source": "alert", "service": service_of(text), "env": env},
            token, timeout=timeout, collect_deltas=False)
        return {"status": r.get("status"), "meta": r.get("meta", {}), "done": r.get("done", {})}
    except Exception as e:  # noqa: BLE001
        return {"status": -1, "error": f"{type(e).__name__}: {e}"}


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 - 首跑/损坏都当冷启动
        return {}


def save_state(states: dict) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(states, ensure_ascii=False, indent=1), encoding="utf-8")


def scan_once(only: set[str], env: str, timeout: int) -> list[dict]:
    prev = load_state()
    curr = docker_state()
    if only:
        curr = {k: v for k, v in curr.items() if k in only}
    alerts = []
    if prev:  # 冷启动只落基线不发告警
        for text in diff_alerts(prev, curr):
            alerts.append({"text": text, "result": send_alert(text, env, timeout)})
    merged = {**prev, **curr}
    save_state(merged)
    return alerts


def main(argv: list[str] | None = None) -> int:
    _load_dotenv()
    ap = argparse.ArgumentParser(description="Docker 容器健康 → OpsPilot 告警源（只观测不重启）")
    ap.add_argument("--base", default=None, help="网关根 URL（默认读 OPSPILOT_BASE 或 http://localhost:8081）")
    ap.add_argument("--env", default="local", help="告警的 env 维度（默认 local）")
    ap.add_argument("--interval", type=float, default=DEFAULT_INTERVAL)
    ap.add_argument("--timeout", type=int, default=60)
    ap.add_argument("--containers", default="", help="逗号分隔的白名单，空=全部容器")
    ap.add_argument("--once", action="store_true")
    args = ap.parse_args(argv)

    # 与 localapi.assert_local 同口径的环回约束：这脚本只该打本机网关
    base = args.base or "http://localhost:8081"
    u = urlparse(base if "://" in base else "http://" + base)
    if u.scheme != "http" or u.hostname not in ("localhost", "127.0.0.1") or u.username:
        print(f"FAIL: --base 只允许环回明文 http（当前 {base}）", flush=True)
        return 2
    localapi.BASE = base.rstrip("/")

    only = {c.strip() for c in args.containers.split(",") if c.strip()}
    if args.once:
        alerts = scan_once(only, args.env, args.timeout)
        for a in alerts:
            meta = a["result"].get("meta", {})
            print(f"告警已发：{a['text']}")
            print(f"  → 指纹 {meta.get('fingerprint', '—')} · 检索档 {meta.get('degradation_level', '—')}"
                  f" · 缓存 {meta.get('cache_hit', '—')}")
        if not alerts:
            print("本轮无翻转（首跑只落基线）" if not load_state() else "本轮无翻转")
        return 0

    print(f"监视 Docker 每 {args.interval}s（仅白名单 {sorted(only) if only else '全部'}）；Ctrl-C 停")
    while True:
        try:
            for a in scan_once(only, args.env, args.timeout):
                meta = a["result"].get("meta", {})
                print(f"[{time.strftime('%H:%M:%S')}] 告警已发：{a['text']}")
                print(f"  → 指纹 {meta.get('fingerprint', '—')} · 检索档 {meta.get('degradation_level', '—')}")
        except RuntimeError as e:
            print(f"[{time.strftime('%H:%M:%S')}] {e}（下轮重试）", flush=True)
        time.sleep(args.interval)


if __name__ == "__main__":
    raise SystemExit(main())