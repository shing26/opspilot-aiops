"""Locust 压测：场景 A 热点命中（80%）+ 场景 B 告警风暴（500 并发同指纹）+ 场景 C 唯一指纹（降级复核）。

运行（offline/ 目录）:
  .venv/Scripts/locust.exe -f load/locustfile.py --headless -u 50 -r 10 -t 30s \
      --html load/reports/locust_a.html --csv load/reports/locust_a
  SCENARIO=storm .venv/Scripts/locust.exe -f load/locustfile.py --headless -u 500 -r 50 -t 20s \
      --html load/reports/locust_b.html --csv load/reports/locust_b
  # 场景 C 走 load/sweep.py --scenario unique（它同时采 /admin/state 的档位与在途峰值）
"""
from __future__ import annotations

import json
import os
import time
import urllib.parse
import urllib.request
from urllib.parse import urlparse

from locust import HttpUser, task, between, constant

_ALLOWED = {"localhost", "127.0.0.1"}
BASE = os.environ.get("OPSPILOT_BASE", "http://localhost:8081")
_p = urlparse(BASE)
assert _p.scheme == "http" and _p.hostname in _ALLOWED, f"仅允许本机: {BASE}"

TOKEN = os.environ.get("OPSPILOT_TOKEN", "")
if not TOKEN:
    # token 读取去重至 localapi.load_tokens（cwd 无关）；OPSPILOT_BASE 覆盖为压测特需，保留本文件局部
    import sys
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    import localapi
    TOKEN = localapi.load_tokens()["sre_l3"]

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


def unique_query() -> str:
    """场景 C：给每条请求一个**唯一指纹**。

    nonce 必须是**纯字母**，这条是实验成败的关键：指纹归一化会把数字与 UUID 掩码掉
    （`\\d+`→`<N>`、UUID→`<UUID>`、引号串→`<STR>`，见 `FingerprintService.normalizeError`），
    故带数字/UUID 的 nonce 归一化后**所有请求指纹相同** → 被 Single-Flight 折成一个 leader，
    在途永远上不去（2026-09-25 那次 u=500 在途仅 11 的成因之一）。

    唯一指纹同时绕过三层折叠：L1 缓存键含 query、L2 语义缓存被后缀稀释到阈值以下、
    Single-Flight 组键含 fp——于是每条请求都必须走完整链路，**在途 ≈ 并发数**。
    查询里保留错误码身份（检索命中与快路径都要它）。
    """
    import secrets
    import string
    letters = "".join(secrets.choice(string.ascii_lowercase) for _ in range(10))
    return f"{HOT_QUERIES[0]} {letters}"


class OpsUser(HttpUser):
    host = BASE
    # 场景 C 要的是**占空比≈1**（原场景 0.05–0.2 的等待把在途压掉一个量级）
    wait_time = constant(0) if SCENARIO == "unique" else between(0.05, 0.2)

    def on_start(self):
        self.headers = {"Authorization": "Bearer " + TOKEN,
                        "Content-Type": "application/json",
                        "Accept": "text/event-stream"}

    @task
    def chat(self):
        if SCENARIO == "storm":
            body = {"query": STORM_QUERY, "source": "alert",
                    "service": "order-service", "env": "prod"}
        elif SCENARIO == "unique":
            body = {"query": unique_query(), "source": "manual"}
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
