# 单机极限与分布式一致性之间：三个风暴/降级原语的接口抽象

状态：accepted（2026-10-10，随「四个深度优化点」ROI 排序第二项落地）

上下文：`SingleFlightRegistry`、`DegradationStateMachine` 当前是进程内单机实现
（`ConcurrentHashMap` + `CompletableFuture` / `AtomicInteger` + `volatile`），
ADR-0003 已记录"单实例部署 + 预留 Pub/Sub 扩展点"。但扩展点此前只活在文档里，
**没有接口层**——面试与架构演进都绕不开同一个追问："三台网关集群下，告警风暴打到
不同机器，SingleFlight 与降级状态机怎么生效？"

三条事实同时为真：
1. **单机形态有真实性能收益**：进程内 future 无网络往返，500 并发同指纹 LLM 触发
   1 次的实测就是单 JVM 语义；上 Redis 走 Redlock + Pub/Sub 会把每次 leader 交接
   变成两个 RTT，对本场景是净亏。
2. **分布式形态是迟早要回答的问题**：告警风暴天然是入口负载不均的问题，hash 路由
   不能保证同指纹落同实例。
3. **推倒重写是高风险的坏答案**：本仓的回归锁（190 单测 + A2 + live 探针）都锁在
   现有行为上，重写 = 让所有锁失效重来。

决策：**提取接口，默认保留单机实现，分布式演进原语写进契约注释与本文档，不写死实现**。

三个接口（`storm/SingleFlight`、`storm/SlidingWindow`、`resilience/DegradationState`）
各含单一职责，`key`/`Level`/`WindowResult` 等契约类型随接口走。当前
`SingleFlightRegistry`/`SlidingWindowService`/`DegradationStateMachine` 变为
`implements` 后的默认实现，构造注入与单测对实现的引用不变（纯重构，37 个受影响
测试逐字通过）。

| 原语 | 当前默认（单机） | 分布式演进路径（契约注释已写） |
| --- | --- | --- |
| `SingleFlight` | `ConcurrentHashMap` + `CompletableFuture` | Redlock 选主 + Pub/Sub 广播结果；`finish(key,future)` 双参即 leader 交接点，follower 订阅频道补完本地 future |
| `SlidingWindow` | 已用 Redisson ZSET（天然分布式） | 无需演进；接口仅为一致性抽象 |
| `DegradationState` | `AtomicInteger/AtomicLong/volatile` | inflight/失败计数走 Redis 原子计数（`RAtomicLong` 或 Lua CAS），冷却/手动锁走 `RAtomicLong/RString`，档位判定整体改 Lua 脚本保证全局原子；半开语义（清零失败计数放行探测）不得退化为死锁态 |

**关键约束（演进时不得破坏的不变量）**：
- 权限三元组（`tenant:fingerprint:authLevel`）在 SingleFlight key 层内置——分布式
  Redlock 的 key 必须是同一字符串，拆散即破 ADR-0008。
- 转移原因 `cause` 是有限词表，分布式实现不得引入自由文本（DegradationTransitionTest
  `causeVocabularyStaysClosed` 是回归锁）。
- `TransitionSink` 已隔离为审计副作用，分布式实现保持"各实例本地落痕"即可，不要求
  全局有序事件流。

否决项：
- **直接上分布式实现**：性能净亏 + 全部回归锁失效 + live 探针要重打，收益（本机
  demo 用不到多实例）为负。
- **只写文档不提接口**：扩展点无法类型化、无法 mock、无法被面试官对着代码核——
  仓内纪律是"承诺必须可被外部逐句复核"（ADR-0013 同源立场），散在散文里的演进
  设想不满足这一点。

后果：
- ADR 总数 14 → 15；README 的 ADR 计数由 `offline/doc_numbers.py` 现算比对，改字
  是被门闩要求的，不靠人记得。
- 三个服务类的公共 API 从"具体类型"变"接口类型"，但它们仍是默认实现、仍是注入的
  bean——Spring 与调用方零感知。
- 面试话术从"预留了扩展点"升级为"契约层已就位，分布式实现的 key/原子性方案已写进
  接口注释与 ADR-0015"——可对着代码逐行讲 trade-off。
