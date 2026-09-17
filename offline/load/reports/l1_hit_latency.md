# L1 回放延迟实测（热点命中口径的产物）

> 实测时间：2026-09-17T09:29:06+08:00 ｜ 主体 `sre-full` ｜ n=200 ｜ cache_hit 分布：{'L1': 200}
> 后端：embedding=dashscope:text-embedding-v3 / rerank=dashscope:gte-rerank-v2 / llm=dashscope:qwen-plus（回放路径不触外部 API，故后端与延迟无关）
> query：`50012_DB_TIMEOUT 下单超时怎么排查` ｜ refs 最少 3 条（回放保留溯源）

| 口径 | p50 | p90 | p95 | p99 | max | mean |
| --- | --- | --- | --- | --- | --- | --- |
| **服务端 TTFT**（系统处理，目标 <50ms） | 7.0 | 10.0 | 12.0 | **16.0** | 24.0 | 7.84 |
| 客户端端到端（含本机栈开销，参考值） | 21.77 | 34.56 | 37.99 | 46.33 | 59.16 | 24.96 |

服务端 TTFT = SSE done 帧 ttft_ms（系统处理耗时，可与目标线比较）；客户端端到端含本机解释器与 HTTP 往返开销，勿拿来当系统性能。样本全部为 L1 命中（非 L1 即中止），不触外部 API，零 LLM 成本。

复现：`cd offline && python load/l1_latency.py`（需活体栈 + `.env`；每次请求消耗 1 次当日配额，n 次即 n 次）。
