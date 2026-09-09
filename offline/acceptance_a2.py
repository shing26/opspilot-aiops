"""A2 验收自动化脚本（Sprint 2 机制验证）。

覆盖：A2-1 SSE / A2-3 L1 / A2-4 L2 / A2-5 风暴 / A2-6 降级 / A2-8 权限。
A2-2 RRF 由 JUnit 单测覆盖；A2-7 超时隔离由停 Qdrant 容器验证。
安全：仅允许访问本机 OpsPilot（localhost 白名单），阻断 SSRF。
"""
from __future__ import annotations

import os
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(__file__))
from console_client import load_tokens, stream_chat  # noqa: E402
import localapi as api  # noqa: E402  # 共享 HTTP/token 辅助（单一事实源）

TOKENS = load_tokens()
L3 = TOKENS["sre_l3"]
L1 = TOKENS["sre_l1"]
L0 = TOKENS["sre_l0"]      # 红队回归：非法密级 auth_level=0
LNEG = TOKENS["sre_neg"]   # 红队回归：负数密级 auth_level=-1

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")


def metrics():
    return api.get_json("/api/v1/admin/metrics", L3)


post = api.post_json                       # noqa: E402  本机校验内嵌于 localapi
expect_http_status = api.expect_http_status  # noqa: E402  A2-8b 提权边界用


# 验收隔离：清空 L1/L2 缓存，避免跨运行残留（复用已校验的 post 辅助）
post("/api/v1/admin/cache/flush", {}, L3)

# ---- A2-1 SSE 事件序列 ----
r = stream_chat("支付回调报 50031_MQ_CONSUME_LAG 怎么处理", L3, typewriter=False)
check("A2-1 SSE meta/delta/done 序列",
      bool(r["meta"]) and r["ttft_s"] is not None and bool(r["done"].get("refs")),
      f"ttft={r['ttft_s']:.3f}s refs={len(r['done'].get('refs', []))}")

# ---- A2-3 L1 精确缓存 ----
q = "库存扣减死锁 50013_DB_DEADLOCK 怎么排查"
r1 = stream_chat(q, L3, typewriter=False)
r2 = stream_chat(q, L3, typewriter=False)
check("A2-3 L1 精确缓存命中",
      r2["meta"].get("cache_hit") == "L1",
      f"first={r1['meta'].get('cache_hit')} second={r2['meta'].get('cache_hit')}")

# ---- A2-4 L2 语义缓存（近义改写，L1 不命中但向量余弦 >0.95）----
# mock/live 双后端均适用：词法向量靠高重叠命中，神经向量（text-embedding-v3）真余弦命中。
q_a = "下单接口报 50012_DB_TIMEOUT 怎么排查"
q_b = "下单接口报 50012_DB_TIMEOUT 怎么排查啊"
stream_chat(q_a, L3, typewriter=False)
r_b = stream_chat(q_b, L3, typewriter=False)
check("A2-4 L2 语义缓存命中（改写查询）",
      r_b["meta"].get("cache_hit") == "L2",
      f"paraphrase cache_hit={r_b['meta'].get('cache_hit')}")

# ---- A2-5 风暴收敛：500 并发同指纹 → llm_calls +1 ----
# 锁 P1-4：workers 必须等于任务数，保证 500 个请求真正同窗口并发（而非排队的 100 路）
m0 = metrics()["llm_calls"]
storm_q = "org.springframework.jdbc.SQLTransientException error code 50092_THREAD_POOL_EXHAUSTED at com.ordercenter.order.PostOrderExecutor"
with ThreadPoolExecutor(max_workers=500) as ex:
    futs = [ex.submit(stream_chat, storm_q, L3, "alert", "order-service", False) for _ in range(500)]
    [f.result() for f in futs]
m1 = metrics()
llm_delta = m1["llm_calls"] - m0
dedup_delta = m1["dedup_aggregated"]
check("A2-5 风暴 500 并发 LLM 仅触发 1 次",
      llm_delta == 1 and dedup_delta >= 400,
      f"llm_delta={llm_delta} dedup_aggregated_delta={dedup_delta}")

# ---- A2-6 三级降级 ----
post("/api/v1/admin/degrade", {"level": "L1"}, L3)
r_l1 = stream_chat("订单超时排查", L3, typewriter=False)
degraded_l1 = r_l1["meta"].get("degradation_level") == "L1"
post("/api/v1/admin/degrade", {"level": "L2"}, L3)
m_before = metrics()["llm_calls"]
r_l2 = stream_chat("订单超时排查 50012_DB_TIMEOUT", L3, typewriter=False)
m_after = metrics()["llm_calls"]
degraded_l2 = r_l2["meta"].get("degradation_level") == "L2" and m_after == m_before
post("/api/v1/admin/degrade", {"level": "auto"}, L3)
check("A2-6 降级 L1(纯ES)/L2(SOP直出零LLM)",
      degraded_l1 and degraded_l2,
      f"L1_level={r_l1['meta'].get('degradation_level')} L2_no_llm={m_after == m_before}")

# ---- A2-8 权限引擎层隔离 ----
s_l1 = post("/api/v1/copilot/search", {"query": "50022_REDIS_CONN_REFUSED", "mode": "hybrid"}, L1)
leak = any(x["auth_level"] > 1 for x in s_l1["results"])
s_l3 = post("/api/v1/copilot/search", {"query": "50022_REDIS_CONN_REFUSED", "mode": "hybrid"}, L3)
visible = any(x["auth_level"] == 3 for x in s_l3["results"])
check("A2-8 权限硬隔离（L1 零越权 / L3 可见）",
      (not leak) and visible,
      f"L1_leak={leak} L3_sees_level3={visible}")

# ---- A2-8b P0 回归：auth_level<=0 提权边界，入口必须 401（含 /search 与 /chat/stream 两路） ----
boundary_cases = [
    ("search/L0", "/api/v1/copilot/search", {"query": "50022_REDIS_CONN_REFUSED", "mode": "hybrid"}, L0),
    ("search/L-1", "/api/v1/copilot/search", {"query": "50022_REDIS_CONN_REFUSED", "mode": "hybrid"}, LNEG),
    ("stream/L0", "/api/v1/copilot/chat/stream", {"query": "50022_REDIS_CONN_REFUSED", "source": "manual"}, L0),
    ("stream/L-1", "/api/v1/copilot/chat/stream", {"query": "50022_REDIS_CONN_REFUSED", "source": "manual"}, LNEG),
]
boundary = {name: expect_http_status(path, body, tok, 401)
            for name, path, body, tok in boundary_cases}
boundary_ok = all(ok for ok, _ in boundary.values())
check("A2-8b auth_level<=0 提权边界（4 路全部 401 拒绝）",
      boundary_ok,
      " ".join(f"{name}={code}" for name, (_, code) in boundary.items()))

passed = sum(1 for _, ok, _ in results if ok)
print(f"\n=== A2: {passed}/{len(results)} PASS ===")
sys.exit(0 if passed == len(results) else 1)
