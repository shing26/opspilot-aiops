# OpsPilot — AIOps 智能排障与契约检索网关

面向微服务与云原生环境的混合检索（Hybrid RAG）与智能故障自查系统：SRE 粘贴报错堆栈或口语化描述，系统经「指纹去重 → 双路召回 → RRF 融合 → 精排裁剪 → LLM 流式生成」输出可溯源的排障步骤。

## Language

### 检索域

**排障请求（Query）**:
一次用户/告警发起的排障输入，可以是口语化描述或错误堆栈。_Avoid_: 问题、提问

**语义块（Chunk）**:
切分产物的原子单位——一个 OpenAPI Endpoint 或一段标题感知的 Markdown 小节，自包含且携带元数据。_Avoid_: 片段、分块、文档

**双路召回（Hybrid Retrieval）**:
ES 倒排（精确符号）与 Qdrant 向量（语义泛化）并行检索后融合的模式。_Avoid_: 混合搜索

**RRF**:
Reciprocal Rank Fusion，倒数排名融合（k=60），将双路排名无量纲合并。_Avoid_: 加权融合

**精排（Rerank）**:
对 RRF Top-20 调用云端 Reranker 二次过滤，裁剪至 Top-3 上下文。_Avoid_: 重排序

**快路径（Fast Path）**:
查询含精确错误码/全限定名且 ES keyword 精确命中 Top-1 时跳过 Rerank 的捷径。

### 风暴防线域

**指纹（Fingerprint）**:
`SHA256(service + env + normalized_error_msg)`，归一化剥离时间戳/数字/ID 后的错误签名。_Avoid_: 签名、哈希键

**滑动窗口（Sliding Window）**:
Redis ZSET 实现的 30s 去重窗口（alert 来源可配更长），窗口内同指纹仅首条穿透。_Avoid_: 防抖（口语可用，代码中统一 Sliding Window）

**单飞（Single-Flight）**:
同指纹并发请求挂起等待首条结果并复用的进程内合并机制，key 掺 authLevel 防跨权限复用。_Avoid_: 请求合并

**来源（Source）**:
排障请求的发起方标记：`alert`（告警系统转发）或 `manual`（SRE 手动粘贴），影响去重窗口配置。

### 缓存域

**L1 精确缓存**:
`tenant + authLevel + SHA-256(normalize(query))` 为 Key 的 Redis 哈希缓存，TTL 2h。_Avoid_: 热缓存

**L2 语义缓存**:
Qdrant 专属 Collection，余弦相似度 > 0.95 判定命中，跳过检索与 LLM 直接回放答案；payload 携带 `max_auth_level` 防权限泄漏。_Avoid_: 向量缓存

**回放（Replay）**:
等待者/缓存命中者收到完整答案一次性写入自身 SSE 流的行为，区别于实时流式。

### 降级域

**Level 0（正常）**: ES+Qdrant 双路 + Rerank + LLM 全链路。
**Level 1（高负载）**: 摘除向量路与 Rerank，纯 ES 关键词召回 + 缩减 Prompt。
**Level 2（熔断）**: 切断 LLM，直出 Redis 预热的静态 SOP 止损清单。
_Avoid_: 熔断级别、降级档位（统一 Level 0/1/2）

**静态 SOP**:
入库时按 `error_code/service` 预热进 Redis 的止损步骤清单，Level 2 兜底数据源。

### 权限域

**auth_level**:
数据密级与用户凭证等级，整数 1/2/3（1=公开 SOP，2=内部复盘，3=含生产配置）。硬过滤规则：`chunk.auth_level <= user.auth_level`，在 ES 与 Qdrant 引擎层强制注入。_Avoid_: 角色（role 是另一维度，见下）

**role**:
用户职能（sre/dev/manager），仅用于审计与 Prompt 个性化，不参与数据过滤。

**租户（Tenant）**:
数据归属与隔离的第一维度，与 auth_level 同级在 ES/Qdrant/L2/SOP 四面引擎硬过滤（`metadata.tenant` term 等值 + range lte 双 must）。内部工具形态恒 `tenant-internal`；tenant claim 缺失/空白/超 64 在入口 401。_Avoid_: 工作区、命名空间（缓存前缀是另一回事）

**账号（Account）**:
H2 主库中的一行用户记录（sub + bcrypt 口令 + tenant + auth_level + disabled + token_ver），权限维度的唯一真相源；JWT 只是账号身份的短时载体（24h）。_Avoid_: 用户（user 指运行时 UserContext）

**吊销版本（token_ver / tver）**:
账号行上的整数游标，签进 JWT 的 `tver` claim；`JwtAuthFilter` 每请求比对 DB 值，不等即 401。disable/passwd/rotate 均 bump——改一行即全局失效该用户所有存量 token，无需黑名单。_Avoid_: JWT 黑名单（明确否决的方案）

**审计事件（Audit Event）**:
每 chat/search 请求落一行 JSON 到 `logs/audit.jsonl`（sub/tenant/level/query 截断/fp/cache_hit/mode/refused/max_level/took_ms），回答"谁查过什么、答案触到哪个密级"，是权限引擎层的可核查闭环。_Avoid_: 应用日志（业务日志非合规留痕）

### 重建域

**blue/green 重建（Staging Reingest）**:
知识库更新的安全流程：写时间戳物理库 `<base>-<ts>` → 计数硬验收 → 单请求原子换 ES/Qdrant 别名 → 删旧库；任何失败保留旧别名指向。查询侧永远只认别名（`opspilot-chunks-read` / `opspilot-vectors-live`），零空窗。_Avoid_: 热更新、滚动重建（旧"先删后建"是被否决的反模式）

**回滚（Alias Rollback）**:
改别名指回上一版物理库（若未清理）即秒级回退，重建失败无需恢复数据。

### 评测域

**Golden Dataset**:
50 组标注样本（25 精确错误码 + 25 口语化语义），与合成语料同源，ground truth 为期望 chunk_id 集合。

**越狱用例（Jailbreak Case）**:
诱导模型输出越权内容的对抗 Prompt，验收标准是引擎层过滤使其召回为空，而非模型自觉。

**TTFT**:
双口径——`connect`（SSE 连接建立）与 `first_token`（首个内容 token），报告分别记录。
