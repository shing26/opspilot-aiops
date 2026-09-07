# 单机单实例部署，Single-Flight 用进程内 CompletableFuture

上下文：任务书要求「Redis 分布式滑动窗口 + Single-Flight」，但 7 天工期 + 本机压测场景下多实例部署无收益。决策：Java 服务单实例运行；滑动窗口去重走 Redisson（Redis ZSET，跨进程语义正确），Single-Flight 结果共享用进程内 `ConcurrentHashMap<String, CompletableFuture>`，key 为 `fingerprint + authLevel`。

理由：多实例需 Redis Pub/Sub 广播生成流，复杂度翻倍且本机压测看不出差异；单实例下进程内 Future 即可实现「500 并发 LLM 触发 1 次」。掺 authLevel 进 key 是为防止低权限等待者复用高权限首条结果造成越权。

后果：面试话术明确「预留 Redis Pub/Sub 扩展点支持水平扩展」，当前实现为单 JVM 语义。
