# 缓存节省账（命中率 × 延迟差）

> 实测时间：2026-09-25T03:37:00+08:00 ｜ 主体 `sre-full` ｜ 负载 hot=20 / cold=5
> cache_hit 分布：{'L1': 20, 'L2': 1, 'none': 4} ｜ 命中率 **84.0%**

| 分组 | 服务端 TTFT p50 / p95（ms） |
| --- | --- |
| 命中（L1+L2） | 8.0 / 10.0（n=21） |
| 未命中（none） | 833.0 / 1276.0（n=4） |

**单请求平均节省**：693.0 ms　＝（未命中 p50 − 命中 p50）× 命中率

交叉核对（全系统计数增量，**非**本负载归因）：{'total_requests': 26, 'llm_calls': 4, 'l1_cache_hits': 21, 'l2_cache_hits': 1, 'dedup_aggregated': 20, 'low_confidence_refusals': 0}

命中率与延迟差均取自**本脚本自己的负载**（可归因）；global_counters_delta 是全系统口径（含他人流量与 Single-Flight follower），仅作交叉核对，不可用来算命中率——total_requests 含 follower，hits/total 会把去重流量当成 miss。节省账用服务端 TTFT 做差；客户端端到端含本机栈开销，不参与。

复现：`cd offline && python load/cache_savings.py`（需活体栈 + `.env`；每次请求消耗 1 次当日配额，冷 query 另真调 LLM）。
