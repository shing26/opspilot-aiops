# 双轨分工：Java 在线服务 + Python 离线 ETL，以 chunks.jsonl 为接缝

上下文：7 天单人冲刺，任务书同时出现 Spring Boot 与 FastAPI 表述，且 RAG 生态（切分、评测、压测）天然偏 Python，而面试目标岗位偏 Java 后端与高并发。决策：主服务全量 Java 21 + Spring Boot 3.3（Virtual Threads + SseEmitter），Python 3.11 仅承担离线切分与评测/压测脚本；接缝定为 Python 产出带元数据的 `chunks.jsonl`（进 git、可版本化），Java 的 IngestionRunner 负责调 Embedding 并双写 ES/Qdrant。

理由：Embedding 客户端在线链路（L2 语义缓存需实时向量化 Query）必须存在于 Java，离线复用同一客户端避免双份实现；评测脚本直接读同一份 JSONL 对齐 ground truth，数据血缘单一。代价是切分逻辑无法复用 Java 侧代码，但切分是纯文本处理，Python 生态成本更低。
