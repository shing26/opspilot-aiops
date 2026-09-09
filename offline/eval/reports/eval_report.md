# OpsPilot 检索评测报告

> Embedding 后端：**dashscope:text-embedding-v3**（神经语义（DashScope live））（Rerank：dashscope:gte-rerank-v2 | LLM：dashscope:qwen-plus）

| 模式 | 精确 Hit@1 | 精确 Hit@3 | 精确 MRR | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |
| --- | --- | --- | --- | --- | --- | --- |
| es_only | 100.00% | 100.00% | 1.000 | 72.00% | 100.00% | 0.853 |
| vector_only | 100.00% | 100.00% | 1.000 | 88.00% | 100.00% | 0.940 |
| hybrid | 100.00% | 100.00% | 1.000 | 88.00% | 100.00% | 0.940 |

越狱用例：5 条，泄漏 0 条（PASS 零泄漏）
