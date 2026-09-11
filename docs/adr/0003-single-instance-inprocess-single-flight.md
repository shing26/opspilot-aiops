# 单机单实例部署，Single-Flight 用进程内 CompletableFuture

上下文：任务书要求「Redis 分布式滑动窗口 + Single-Flight」，但 7 天工期 + 本机压测场景下多实例部署无收益。决策：Java 服务单实例运行；滑动窗口去重走 Redisson（Redis ZSET，跨进程语义正确），Single-Flight 结果共享用进程内 `ConcurrentHashMap<String, CompletableFuture>`，key 为 `fingerprint + authLevel`。

理由：多实例需 Redis Pub/Sub 广播生成流，复杂度翻倍且本机压测看不出差异；单实例下进程内 Future 即可实现「500 并发 LLM 触发 1 次」。掺 authLevel 进 key 是为防止低权限等待者复用高权限首条结果造成越权。

后果：面试话术明确「预留 Redis Pub/Sub 扩展点支持水平扩展」，当前实现为单 JVM 语义。

## 修订记录

- **2026-09-11（QA 台账 P0-1）**：key 口径由 `fingerprint + authLevel` 修订为
  `tenant + fingerprint + authLevel`。原设计把"权限"默认为"密级"，掺 authLevel 防了低权复用
  高权答案，却没防**跨租户同密级**并发回放——告警风暴（本产品核心场景）下外来租户 follower
  会拿到内部租户 leader 的全文+引用。教训升格为 ADR-0008：权限维度（租户×密级×role）必须在
  **每一条**跨请求共享路径上同构存在；回归锁见 `ChatOrchestratorTest`。
