# 生产就绪度评估 — 2026-09-12（HEAD a2f41f0）

> 评估口径：双定义制。**定义 A** = 5-50 人团队的单机内部工具（可上线线）；**定义 B** = 多租户
> 企业级 SaaS（季度级）。结论按定义 A 给出，定义 B 差距全部在债务闹钟表有触发线（ADR-0003/0005/
> 0009、OPS 债务表），是**记录在案的取舍**而非疏忽。
> 本文件同时作为差距修补的台账：每项修复落地后回填状态与 commit（见文末修补记录）。

## 总判

**不是玩具级 Demo，已达到"单机生产就绪"（定义 A 可上线）**。玩具的标志——无鉴权、无降级、
配置硬编码、错误裸抛、print 调试——五项全部相反。距企业级（定义 B）的差距真实存在但均带触发线。

## 五维度评估

### 1. 容错机制 — 部分具备（偏强）

| 项 | 状态 | 代码依据 |
|---|---|---|
| 降级 | 已具备 | `DegradationStateMachine`：inflight 超阈→L1 纯 ES、LLM 连败 3 次→L2 熔断 SOP 直出、60s 冷却自愈、手动锁（A2-6 锁） |
| 超时 | 已具备（三层） | 检索 leg 4500ms 双路隔离（`HybridSearchService`，`retrieval_timeouts` 计数）；LLM 60s+连接 5s（`LlmClient`）；HealthProbe 3s 自吞；SseEmitter 120s/300s |
| 异常捕获 | 已具备 | `ChatOrchestrator.submit` try/catch→`sink.error` 协议化收尾；SSE 客户端断开容错（RST 三连实测 inflight 零泄漏）；全局异常处理器按面分流 |
| 失败切换 | 已具备 | blue/green 失败保旧库在线（ADR-0006）；live/mock 双模自动切换 |
| 重试 | **缺失** | 全仓 grep retry/backoff 零命中——瞬时网络抖动无退避直接计熔断；429 与网络错未分类 |

修补：**H3**（单次退避重试 + 429/网络错分类计数）。

### 2. 日志体系 — 部分具备

| 项 | 状态 | 代码依据 |
|---|---|---|
| 分级 | 已具备（基础） | `application.yml:68-70` root/com.opspilot INFO |
| 结构化 | 部分 | 审计面每请求一行 JSON（AUDIT appender，14 天滚动，五类事件全留痕）；**应用日志人读 pattern 非结构化** |
| 持久化 | 部分 | audit.jsonl 落盘 ✓；**应用日志仅 CONSOLE——容器重建即丢**，无 totalSizeCap |
| 关键链路 | 已具备（缺关联） | 五类事件覆盖业务/鉴权/运维/校验；缺请求级 request_id 把生成链日志与审计行关联 |

修补：**H1**（APP_FILE 滚动 appender）、**H2**（request_id 贯穿审计与生成链）。

### 3. 配置管理 — 部分具备（取舍已记录）

| 项 | 状态 | 代码依据 |
|---|---|---|
| 外部化 | 已具备 | 全量 `${VAR:default}`；compose `:?` 缺凭据 fail-fast；`.env` gitignore；口令仅 env/stdin |
| 环境区分 | 部分（有意） | 无 profile 文件——mock/live 由 key 存在自动切换（同代码路径双模）；单机下 env 即环境差异（ADR 级取舍） |
| 安全默认 | 已具备 | actuator `show-details: never`；端口环回绑定；中间件全认证 |

修补：**M1**（启动时脱敏配置摘要）。

### 4. 监控与告警 — 部分具备

| 项 | 状态 | 代码依据 |
|---|---|---|
| 健康检查 | 已具备（双面） | `/actuator/health` 聚合 + `/admin/health` 鉴权明细探针（5s TTL 双检防惊群）；断 ES 实测 DEGRADED |
| 指标采集 | 已具备（自研） | OpsMetrics 10 计数器 + 运行态（在途组/熔断/冷却/配额），Ops Console 实时可视化；**进程内存态，重启清零，无趋势** |
| 指标导出 | 缺失 | 无 micrometer/prometheus——ADR-0009 触发线（多实例/>5 人日常用）后升级，约 1 天 |
| 告警 | 部分 | daily_usage 拒答率阈值退出码（cron MAILTO）；实时 webhook 按硬约束挂起（无真实告警源不做 adapter） |

修补：**M2**（logs 目录磁盘哨兵并入 daily_usage）；导出线不动。

### 5. 错误处理 — 已具备（2026-09-12 当轮闭环）

| 项 | 状态 | 代码依据 |
|---|---|---|
| 业务/系统分离 | 已具备 | 4xx（INVALID_REQUEST/HTTP_405/415/404）与 5xx 分离，四专属分支杜绝客户端错误污染 5xx（V2 十探针） |
| 形状规范化 | 已具备（双面） | /api `{code,message}`；/v1 OpenAI `{"error":{message,type}}`；filter 短路亦保形状 |
| 输入校验 | 已具备 | @Valid + 类型/方法/媒体类型全分支，文案直写必达 + 400 类留痕 |
| 卫生 | 已具备 | 无栈泄漏（多轮红队实证）；对内留栈对外屏蔽 |

剩余小项：错误码字符串散落，随下一需求收拢为枚举（M4，1h，未入本轮）。

## 差距清单与实施顺序

**高（上线前，~1 工作日）**：H1 应用日志持久化+滚动+cap（0.5h）→ H2 request_id 贯穿（1-2h）→
H3 LLM 单次退避重试+429/网络错分类（2-3h）。
**中（上线后随手）**：M1 启动脱敏配置摘要（0.5h）→ M2 磁盘哨兵（1h）→ M4 错误码枚举（1h，另轮）。
M3 告警 webhook：等真实告源（硬约束）。
**低（企业级触发线，ADR/债务闹钟在案，勿提前做）**：L1 Micrometer 导出（~1 天）→ L2 多实例
RS256+分布式 SF（周级，>200 人/QPS50）→ L3 OIDC（公司强推）→ L4 TLS/等保（跨机）→
L5 增量 ingest（语料 >2000 条或更新 <10min）。

## 修补记录（落地后回填）

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| H1 应用日志持久化 | **已修** | logback APP_FILE（按天+100MB 滚动，7 天/500MB cap，pattern 带 request_id） | 容器实测 logs/app.log 56KB 落盘；启动摘要在场且凭据全部 set(len=N)/absent 形态、无凭据值 |
| H2 request_id 贯穿 | **已修** | RequestIdFilter（MDC，@Order 最高先于守卫）→ 编排虚拟线程任务内重挂 → 审计行 writeEvent 读取；审计行 additivity=false 不入 app.log（关联语义=编排 warn/error 行与审计行同 id） | mvn 单测锁 MDC 同源；容器实测部署后审计行 100% 带 8 位唯一 request_id。**踩坑**：类名不得叫 RequestContextFilter（与 Boot 内置 bean 冲突启动失败，实测） |
| H3 LLM 单次退避重试 | **已修** | LlmClient.streamChat 重试循环：仅首 token 吐出前的瞬时错（429/网络 IOException/HTTP 5xx）单次退避（1s/400ms）；OpsMetrics 增 llm_retries/llm_network_errors；终态 429 仍 llm_rate_limited | 单测 5 例（重试/耗尽/429 类型传播/吐 token 后禁重试/非瞬时禁重试）全绿；/state 暴露新键；面板 tiles 同步（契约四层仍绿） |
| M1 启动配置摘要 | **已修** | StartupConfigSummary（ApplicationRunner）：凭据全部 set(len=N)/absent 归约后拼接，无字面量 | 容器实测摘要在 app.log；Mimosa 扫描器对 apiKey() 方法名两次误报"硬编码凭据"——实为脱敏读取，已留档待人工复核 |
| M2 磁盘哨兵 | **已修** | daily_usage.py 输出增 logs_disk_mb，>500MB stderr 告警（不占 refuse 退出码） | 实测 logs_disk_mb=1.7 正常输出 |
| M3 告警 webhook | 挂起 | 无真实告警源（硬约束） | — |
| M4 错误码枚举 | 待修（另轮） | 现状字符串 <10 个 | — |
| L1-L5 | 挂起（企业级触发线） | ADR-0003/0005/0006/0009、OPS 债务表 | — |
