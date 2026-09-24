# 门控混淆矩阵 + 阈值扫描

> 实测时间：2026-09-24T16:07:35+08:00 ｜ 主体 `sre-full` ｜ mode=hybrid
> 样本：应作答 59（golden）｜ 应拒答 24（refuse_set）

| 阈值 | 误拒率（应答却拒） | 漏拒率（应拒却答） | 拒对 | 漏拒 | 误拒 | 答对 | 其中零召回误拒 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0.1 | 0.0% | 58.3% | 10 | 14 | 0 | 59 | 0 |
| 0.2 | 6.8% | 8.3% | 22 | 2 | 4 | 55 | 0 |
| 0.3 | 16.9% | 0.0% | 24 | 0 | 10 | 49 | 0 |
| 0.4 | 23.7% | 0.0% | 24 | 0 | 14 | 45 | 0 |

门控判据 = chunks.isEmpty() || (!fastPath && topRelevance < minRel)，与 ChatOrchestrator 同构；走 /search 故不含 L1/L2 缓存（排除'缓存回放绕过门控'的混淆）。误拒伤可用性、漏拒伤可信度，两者方向相反，须一起看。zero_recall_among_false_refusals 单列：零召回导致的误拒属检索问题，不是阈值标定问题。

复现：`cd offline && python eval/gate_matrix.py`（需活体栈 + `.env`；/search 不调 LLM，故零 token 成本且**配额豁免**——配额只挂 /chat/stream 与 /v1/chat/completions）。
