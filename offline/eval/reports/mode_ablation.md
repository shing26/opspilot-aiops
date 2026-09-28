# 三模式检索消融（hybrid vs vector_only vs es_only）

> 实测时间：2026-09-28T11:32:22+08:00 ｜ golden 59 样本（exact 34 + semantic 25） ｜ 后端 embedding=dashscope:text-embedding-v3
> 零 LLM token（`/search` 不调 LLM），但**每个模式每题都真走一次 embedding/rerank**。

| 模式 | 精确 Hit@1 | 精确 Hit@3 | 精确 MRR | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |
| --- | --- | --- | --- | --- | --- | --- |
| es_only | 100% | 100% | 1.000 | 64%±0.19 | 92% | 0.767 |
| vector_only | 100% | 100% | 1.000 | 88%±0.13 | 100% | 0.940 |
| hybrid | 100% | 100% | 1.000 | 88%±0.13 | 100% | 0.940 |

语义桶 Hit@1 的 95% 置信半宽：exact ±0.00、semantic ±0.13（n=34/25 的正态近似；用来判'两个模式的差异是否落在噪声里'）

## 融合相对纯向量的边际贡献（id 级对照）

| 桶 | n | top-3 逐题相同 | ES 腿在最终 top-3 里的贡献条数 | 有 ES 贡献的题数 | Δhit@1 | Δhit@3 | ΔMRR |
| --- | --- | --- | --- | --- | --- | --- | --- |
| exact | 34 | 13/34 | 102 | 34 | +0.0000 | +0.0000 | +0.0000 |
| semantic | 25 | 21/25 | 73 | 25 | +0.0000 | +0.0000 | +0.0000 |

判据读法：`identical_top3_ratio == 1.0` 说明 fusion 与纯向量在这批查询上给出**同一组** top-3；此时若 `queries_with_es_contribution > 0`，则'融合没接线'这一解释被排除（ES 腿的 chunk 确实进了候选池并参与了最终排序），剩下的唯一解释是'融合生效但未改变 top-3'。反过来，若 `queries_with_es_contribution == 0`，那是**接线问题**，不是'贡献测不出'。本报告**不改默认配置**：既有两条读数（es_only 对照 64%→88%、扩语料后 es_only 漂移而 hybrid 不变）都指向'多一条腿提抗漂'，把词法腿当'无用'拆掉的风险远大于收益。

## 与已入库报告的对照

与 `eval_report.json` 的最大绝对偏差 **0.0**（≤0.02，两处同源一致）

口径：两处都从同一份 golden 与同一个 `/search` 现算；本报告额外记录 per-query `doc_id`，那份只有聚合读数。**本报告不参与** `doc_numbers`（live 读数含波动，该表只登记可从库内产物确定性派生的数字）。

复现：`cd offline && python eval/mode_ablation.py`（需活体栈 + `.env`）。
