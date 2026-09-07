"""OpsPilot 控制台演示客户端（DoD：控制台输入报错日志 → SSE 打字机流式输出）。

用法:
  python console_client.py "下单报 50012_DB_TIMEOUT 怎么办"
  python console_client.py --token sre_l3 --source manual "查询"
  python console_client.py --storm --storm-n 500   # 模拟告警风暴
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.request
import urllib.error
from urllib.parse import urlparse

_ALLOWED_HOSTS = {"localhost", "127.0.0.1", "::1"}


def _safe_base(url: str) -> str:
    """演示客户端仅允许访问本机 OpsPilot 服务，阻断 SSRF 到内网/元数据地址。"""
    p = urlparse(url)
    if p.scheme not in ("http", "https"):
        raise ValueError(f"仅允许 http/https，拒绝: {p.scheme}")
    if p.hostname not in _ALLOWED_HOSTS:
        raise ValueError(f"仅允许本机主机 {_ALLOWED_HOSTS}，拒绝: {p.hostname}")
    return url.rstrip("/")


BASE = _safe_base(os.environ.get("OPSPILOT_BASE", "http://localhost:8081"))


def load_tokens() -> dict:
    p = os.path.join(os.path.dirname(__file__), "..", "scripts", "demo_tokens.txt")
    out = {}
    with open(p, encoding="utf-8") as f:
        for line in f:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                out[k] = v
    return out


def stream_chat(query: str, token: str, source: str = "manual",
                service: str = "", env: str = "prod", typewriter: bool = True) -> dict:
    body = json.dumps({"query": query, "source": source, "service": service, "env": env}).encode("utf-8")
    req = urllib.request.Request(
        BASE + "/api/v1/copilot/chat/stream", data=body, method="POST",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "Accept": "text/event-stream"})
    t0 = time.time()
    ttft = None
    meta, done = {}, {}
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            event = None
            for raw in resp:
                line = raw.decode("utf-8").rstrip("\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = json.loads(line[5:].strip())
                    if event == "meta":
                        meta = data
                    elif event == "delta":
                        if ttft is None:
                            ttft = time.time() - t0
                        tok = data.get("token", "")
                        sys.stdout.write(tok)
                        sys.stdout.flush()
                        if typewriter:
                            time.sleep(0.005)
                    elif event == "done":
                        done = data
    except urllib.error.HTTPError as e:
        print(f"[HTTP {e.code}] {e.read().decode('utf-8', 'ignore')[:200]}")
    print()
    return {"meta": meta, "ttft_s": ttft, "done": done, "total_s": time.time() - t0}


def storm(token: str, n: int = 500):
    """模拟告警风暴：n 个并发同指纹请求，验证 LLM 仅触发 1 次。"""
    query = ("org.springframework.jdbc.SQLTransientException: "
             "error code 50012_DB_TIMEOUT at com.ordercenter.order.OrderCreateService.createOrder")
    results = [None] * n

    def worker(i):
        results[i] = stream_chat(query, token, source="alert", service="order-service",
                                 typewriter=False)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print(f"\n[storm] {n} 并发完成，耗时 {time.time()-t0:.1f}s")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("query", nargs="?", default="下单接口报 50012_DB_TIMEOUT 怎么排查")
    ap.add_argument("--token", default="sre_l3")
    ap.add_argument("--source", default="manual")
    ap.add_argument("--storm", action="store_true")
    ap.add_argument("--storm-n", type=int, default=500)
    args = ap.parse_args()

    tokens = load_tokens()
    tok = tokens.get(args.token, args.token)
    if args.storm:
        storm(tok, args.storm_n)
        return
    r = stream_chat(args.query, tok, args.source)
    print(f"[meta] {json.dumps(r['meta'], ensure_ascii=False)}")
    if r["ttft_s"] is not None:
        print(f"[TTFT] {r['ttft_s']:.3f}s | 总耗时 {r['total_s']:.3f}s")
    if r["done"].get("refs"):
        print("[refs]")
        for ref in r["done"]["refs"]:
            print(f"  - {ref['breadcrumb']} ({ref['service']})")


if __name__ == "__main__":
    main()
