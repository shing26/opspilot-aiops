#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""核色前置：确认 DashScope 三路（LLM / embedding / rerank）真的可用。

**为什么必须有这一步**：系统的降级设计会**优雅地掩盖**上游故障——两条路径都返回 HTTP 200、
都不报错，只有 `mode` 字段才看得出：

  - embedding 断 → 向量腿 `degraded` → 检索**静默**退化为 `es_only`
  - LLM 连续失败达阈 → 熔断 L2 → 全部请求直出静态 SOP（`mode=sop_fallback`）

于是"核色"会在一个**已经降级**的系统上跑完，产出一整套看似正常、实则无效的数字。
2026-09-24 就真踩了：`cache_savings` 首跑报出"命中率 0.0%"，看着像"缓存无效"，
实际是 26 条请求全部走了 L2 SOP 兜底（该路径按设计不写 L1）——数字是假象，报告已作废。

故本脚本是**核色步骤的前置**，不是可选的自检：三路全通过才继续，任一被拒就停下，
别把降级态当基线。

用法（先 `set -a; . ./.env; set +a` 让 `DASHSCOPE_API_KEY` 进入环境）:
  python scripts/check_upstream.py          # 三路探活，全通过 exit 0
  python scripts/check_upstream.py --quiet  # 只出退出码

退出码：0 = 三路全通过；1 = 至少一路被拒；2 = 未配置 API key（无从探测）。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

# 三路最小探针。rerank 走的是 DashScope **原生** rerank 端点（非 OpenAI 兼容面），
# 与 RerankClient 的实现对应；LLM / embedding 走 compatible-mode。
PROBES = (
    ("LLM qwen-plus",
     "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
     {"model": "qwen-plus", "messages": [{"role": "user", "content": "ping"}], "stream": False}),
    ("embedding text-embedding-v3",
     "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings",
     {"model": "text-embedding-v3", "input": "ping", "dimensions": 1024}),
    ("rerank gte-rerank-v2",
     "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
     {"model": "gte-rerank-v2", "input": {"query": "ping", "documents": ["ping"]}}),
)


def probe(name: str, url: str, body: dict, api_key: str, opener=None,
          timeout: int = 25) -> dict:
    """打一发最小请求，返回 {name, ok, status, code}。**不回显凭据，也不打印响应体全文**。

    `opener` 可注入——单测用假 opener 覆盖 200 / 400-Arrearage / 网络异常三种形态，不触网。
    默认在**调用时**解析（而非定义时绑定为默认值），否则 monkeypatch 打不进来、单测只能触网。
    """
    opener = opener or urllib.request.urlopen
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"})
    try:
        with opener(req, timeout=timeout) as r:
            return {"name": name, "ok": True, "status": getattr(r, "status", 200), "code": None}
    except urllib.error.HTTPError as e:
        code = None
        try:
            code = (json.loads(e.read(400).decode("utf-8", "replace")).get("error") or {}).get("code")
        except Exception:
            pass
        return {"name": name, "ok": False, "status": e.code, "code": code}
    except Exception as e:                       # 网络层异常（DNS/超时/TLS）
        return {"name": name, "ok": False, "status": None, "code": type(e).__name__}


def all_ok(results: list[dict]) -> bool:
    """全通过才算通过——**不允许部分通过**：任一路断都会让核色数字无效。"""
    return bool(results) and all(r["ok"] for r in results)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="核色前置：DashScope 三路探活")
    ap.add_argument("--quiet", action="store_true", help="只出退出码")
    a = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not key:
        print("未配置 DASHSCOPE_API_KEY（先 `set -a; . ./.env; set +a`）——无从探测，"
              "此时任何核色都是在 mock 后端上做的，口径不同。", file=sys.stderr)
        return 2

    results = [probe(n, u, b, key) for n, u, b in PROBES]
    if not a.quiet:
        print("核色前置 · DashScope 三路探活：")
        for r in results:
            mark = "PASS" if r["ok"] else "FAIL"
            detail = f"HTTP {r['status']}" if r["status"] else "（无响应）"
            if r.get("code"):
                detail += f" code={r['code']}"
            print(f"  {mark}  {r['name']:<32} {detail}")
    if all_ok(results):
        if not a.quiet:
            print("→ 三路全通过，可以开始核色。")
        return 0
    print("→ 至少一路被拒：**本次核色的数字无效，停下**。降级会静默掩盖上游故障"
          "（embedding 断→检索退 es_only；LLM 断→熔断直出 SOP），别把降级态当基线。",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
