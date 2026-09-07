# DashScope 一站式云端能力，向量维度 1024 锁死

上下文：链路需要 Embedding、Rerank、LLM 三类云端能力，分散供应商意味着多 Key、多重试逻辑。决策：全部采用阿里云 DashScope——`text-embedding-v3`（1024 维）、`gte-rerank`、`qwen-plus`（OpenAI 兼容模式流式）。

理由：单 Key 管理、国内网络稳定、成本低；Embedding 维度在建库后不可逆（换供应商=全量重灌），故在 Sprint 1 前锁定。LLM 流式走 OpenAI 兼容端点，用 JDK HttpClient 手写 SSE 解析，不引入重量级 SDK（DoD 要求无黑盒依赖）。

后果：Qdrant 两个集合（主检索 + 语义缓存）均固定 1024 维；供应商限流风险由客户端信号量 + 退避重试兜底。
