"""Locust 压测：场景 A 热点命中（80%）+ 场景 B 告警风暴（500 并发同指纹）。

运行（offline/ 目录）:
  .venv/Scripts/locust.exe -f load/locustfile.py --headless -u 50 -r 10 -t 30s \
      --html load/reports/locust_a.html --csv load/reports/locust_a
  SCENARIO=storm .venv/Scripts/locust.exe -f load/locustfile.py --headless -u 500 -r 50 -t 20s \
      --html load/reports/locust_b.html --csv load/reports/locust_b
"""
from __future__ import annotations

import json
import os
import time
import urllib.parse
import urllib.request
from urllib.parse import urlparse

from locust import HttpUser, task, between

_ALLOWED = {"localhost", "127.0.0.1"}
BASE = os.environ.get("OPSPILOT_BASE", "http://localhost:8081")
_p = urlparse(BASE)
assert _p.scheme == "http" and _p.hostname in _ALLOWED, f"仅允许本机: {BASE}"

TOKEN = os.environ.get("OPSPILOT_TOKEN", "")
if not TOKEN:
    # 从 demo_tokens.txt 读取（固定相对路径）
    _tp = os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "demo_tokens.txt")
    with open(_tp, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("sre_l3="):
                TOKEN = line.strip().split("=", 1)[1]

SCENARIO = os.environ.get("SCENARIO", "hot")

# 热点查询池（场景 A：80% 命中这些，制造 L1/L2 缓存命中）
HOT_QUERIES = [
    "下单接口报 50012_DB_TIMEOUT 怎么排查",
    "库存扣减死锁 50013_DB_DEADLOCK",
    "购物车 Redis 超时 50021_REDIS_TIMEOUT",
    "支付回调积压 50031_MQ_CONSUME_LAG",
    "线程池耗尽 50092_THREAD_POOL_EXHAUSTED",
]
COLD_QUERIES = [
    "订单状态机冲突如何定位",
    "DNS 解析失败排查",
    "证书快过期怎么办",
    "节点 NotReady 处理",
    "GC 停顿过长优化",
]
STORM_QUERY = ("org.springframework.jdbc.SQLTransientException error code 50012_DB_TIMEOUT "
               "at com.ordercenter.order.OrderCreateService.createOrder")


class OpsUser(HttpUser):
    host = BASE
    wait_time = between(0.05, 0.2)

    def on_start(self):
        self.headers = {"Authorization": "Bearer " + TOKEN,
                        "Content-Type": "application/json",
                        "Accept": "text/event-stream"}

    @task
    def chat(self):
        if SCENARIO == "storm":
            body = {"query": STORM_QUERY, "source": "alert",
                    "service": "order-service", "env": "prod"}
        else:
            import secrets
            q = secrets.choice(HOT_QUERIES) if secrets.randbelow(100) < 80 else secrets.choice(COLD_QUERIES)
            body = {"query": q, "source": "manual"}
        t0 = time.time()
        with self.client.post("/api/v1/copilot/chat/stream", json=body,
                              headers=self.headers, stream=True,
                              catch_response=True) as resp:
            if resp.status_code == 200:
                # 读到首个 delta 即记 TTFT
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")
