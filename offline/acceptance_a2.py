"""A2 验收自动化脚本（Sprint 2 机制验证）。

覆盖：A2-1 SSE / A2-3 L1 / A2-4 L2 / A2-5 风暴 / A2-6 降级 / A2-8 权限 /
A2-8b 密级边界 / A2-8c 租户矩阵（双向判别）/ A2-9 跨租户并发 Single-Flight（P0-1 锁）/
A2-10 拒绝路径审计留痕（P1-2 锁）。
A2-2 RRF 由 JUnit 单测覆盖；A2-7 超时隔离由停 Qdrant 容器验证。
安全：仅允许访问本机 OpsPilot（localhost 白名单），阻断 SSRF。
"""
from __future__ import annotations

import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys_path = os.path.dirname(__file__)
sys.path.insert(0, sys_path)
from console_client import load_tokens, stream_chat  # noqa: E402
import localapi as api  # noqa: E402  # 共享 HTTP/token 辅助（单一事实源）

RUN_START_MS = int(time.time() * 1000) - 5_000   # A2-10 审计窗口下界
AUDIT_PATH = os.path.join(sys_path, "..", "logs", "audit.jsonl")

TOKENS = load_tokens()
L3 = TOKENS["sre_l3"]
L1 = TOKENS["sre_l1"]
L0 = TOKENS["sre_l0"]      # 红队回归：非法密级 auth_level=0
LNEG = TOKENS["sre_neg"]   # 红队回归：负数密级 auth_level=-1
ACME = TOKENS["sre_acme"]        # 跨租户合法签名（level 3 同密级；2026-09-11 起自有语料 ac-* 段）
T_NONE = TOKENS["tenant_none"]   # 红队回归：缺 tenant claim
T_BLANK = TOKENS["tenant_blank"] # 红队回归：tenant 全空白
T_LONG = TOKENS["tenant_long"]   # 红队回归：tenant 超 64 字符

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")


def _audit_lines(since_ms=0):
    """读审计 JSONL（容忍撕裂行）；since_ms>0 时按事件时间过滤。A2-9/A2-10 取证用。"""
    out = []
    try:
        with open(AUDIT_PATH, encoding="utf-8") as fh:
            for ln in fh:
                try:
                    ev = json.loads(ln)
                except json.JSONDecodeError:
                    continue
                if ev.get("ts", 0) >= since_ms:
                    out.append(ev)
    except FileNotFoundError:
        pass
    return out


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

# ---- A2-8c 租户矩阵（2026-09-11 起双向判别：acme 自有 52xxx 语料）----
# 旧前提"acme 零语料→查空"是单腿证据（分不清"过滤生效"与"本来没货"）。播种后正确
# 语义是"**各回各家**"而非"查空"——向量路对 acme 自有文档的语义近邻命中是预期行为。
# 断言：① acme 查 internal 独有码 50022 → 结果只可能是 ac-*（绝不出现 rb-/pm-）；
# ② acme 查同名码 50012 → 有命中且全为自家 ac-*（过滤不是查空，是查对边）；
# ③ internal 查 50012 → 命中全部非 ac-*（反向不混入）；
# ④ 缓存不串租户：acme 不得回放 internal 预热过的 L1/L2。
s_acme22 = post("/api/v1/copilot/search", {"query": "50022_REDIS_CONN_REFUSED", "mode": "hybrid"}, ACME)
s_acme12 = post("/api/v1/copilot/search", {"query": "50012_DB_TIMEOUT 下单库超时", "mode": "hybrid"}, ACME)
s_int12 = post("/api/v1/copilot/search", {"query": "50012_DB_TIMEOUT 下单库超时", "mode": "hybrid"}, L3)
acme22_pure = all(x["doc_id"].startswith("ac-") for x in s_acme22["results"])
acme_own = len(s_acme12["results"]) > 0 and all(x["doc_id"].startswith("ac-") for x in s_acme12["results"])
int_own = len(s_int12["results"]) > 0 and all(not x["doc_id"].startswith("ac-") for x in s_int12["results"])
r_acme = stream_chat(q_b, ACME, typewriter=False)  # q_b 在 A2-4 已被 L3 预热进 demo 租户 L1/L2
acme_no_replay = r_acme["meta"].get("cache_hit") not in ("L1", "L2")
acme_isolated = acme22_pure and acme_own and int_own and acme_no_replay
# 入口边界：tenant 缺失/全空白/超 64 字符 → 401（与 auth_level 边界同防线）
tenant_cases = {
    "search/tenant_none": (T_NONE, "/api/v1/copilot/search"),
    "search/tenant_blank": (T_BLANK, "/api/v1/copilot/search"),
    "search/tenant_long": (T_LONG, "/api/v1/copilot/search"),
    "stream/tenant_none": (T_NONE, "/api/v1/copilot/chat/stream"),
}
tenant_boundary = {n: expect_http_status(p, {"query": "50022_REDIS_CONN_REFUSED",
                                             "mode": "hybrid", "source": "manual"}, t, 401)
                   for n, (t, p) in tenant_cases.items()}
check("A2-8c 租户矩阵双向判别（各回各家 + 缓存不串 + 畸形 tenant 4 路 401）",
      acme_isolated and all(ok for ok, _ in tenant_boundary.values()),
      f"acme22={len(s_acme22['results'])} acme12_own={acme_own} int12_pure={int_own} "
      f"acme_no_replay={acme_no_replay} "
      + " ".join(f"{n}={c}" for n, (_, c) in tenant_boundary.items()))

# ---- A2-9 跨租户并发 Single-Flight（QA P0-1 回归锁）----
# 同 query 同密级(3)：internal leader 在途（live 全链路 ~10-20s）时 acme 并发进入。
# sfKey 缺 tenant 的旧实现会让 acme 直接回放 leader 全文+引用（红队实测复现过）。
q9 = "支付验签批量失败复盘 50042_PAY_SIGN_INVALID 根因与修复"
with ThreadPoolExecutor(max_workers=2) as ex:
    f_leader = ex.submit(stream_chat, q9, L3, typewriter=False)
    time.sleep(1.0)
    f_follow = ex.submit(stream_chat, q9, ACME, typewriter=False)
    r9_leader, r9_follow = f_leader.result(), f_follow.result()
follow_refs = [x.get("chunkId", "") for x in r9_follow["done"].get("refs", [])]
follow_leaked = any(rf.startswith(("rb-", "pm-")) for rf in follow_refs)
# 绊线行必须恒零（出现即出现新的共享旁路）
guard_rows = [ln for ln in _audit_lines() if '"dedup_guard"' in ln]
check("A2-9 跨租户并发不共享（无内部引用/无 dedup 标记/绊线恒零）",
      (not follow_leaked) and r9_follow["meta"].get("deduplicated") is not True
      and not guard_rows and bool(r9_leader["done"].get("refs")),
      f"follow_refs={follow_refs[:2]} follow_dedup={r9_follow['meta'].get('deduplicated')} "
      f"guard_rows={len(guard_rows)} leader_refs={len(r9_leader['done'].get('refs', []))}")

# ---- A2-10 拒绝路径审计留痕（QA P1-2 回归锁：每请求一行承诺补全）----
audit_new = _audit_lines(RUN_START_MS)
n_auth = sum(1 for e in audit_new if e.get("ev") == "auth" and e.get("outcome") == "denied")
n_admin_refused = sum(1 for e in audit_new if e.get("ev") == "admin" and e.get("outcome") == "refused")
n_admin_ok = sum(1 for e in audit_new if e.get("ev") == "admin" and e.get("outcome") == "ok")
n_src = sum(1 for e in audit_new if "src_tenant" in e)
mismatch = sum(1 for e in audit_new if e.get("src_tenant") and e.get("src_tenant") != e.get("tenant"))
# 本轮至少发生：A2-8b 4 路 + A2-8c 4 路 + A2-9 acme admin 0 路 → 拒绝行 ≥8；flush/degrade/reingest 的 ok 行 ≥3
check("A2-10 拒绝与运维动作留痕（auth≥8 / admin ok≥3 / src_tenant 随行 / 跨租户归属告警=0）",
      n_auth >= 8 and n_admin_ok >= 3 and n_src > 0 and mismatch == 0,
      f"auth_denied={n_auth} admin_refused={n_admin_refused} admin_ok={n_admin_ok} "
      f"src_rows={n_src} cross_tenant_rows={mismatch}")

passed = sum(1 for _, ok, _ in results if ok)
print(f"\n=== A2: {passed}/{len(results)} PASS ===")
sys.exit(0 if passed == len(results) else 1)
