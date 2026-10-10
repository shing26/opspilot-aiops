# 指标面：从"面板快照"到"可被抓取"——Micrometer + /actuator/prometheus

状态：accepted（2026-10-10，随「四个深度优化点」ROI 排序第三项落地）

上下文：`OpsMetrics` 自 A2-5/A3-5 起用裸 `AtomicLong` 手工计 13 个数，唯一出口是
`snapshot()` 写进 `/api/v1/admin/state` 的 `metrics` 字段，供 `index.html` 控制台的
磁贴渲染。这带来三个具体缺口：

1. **没有机器可读的时序面**。所有数字只在被请求的那一刻被读一次，Prometheus/Grafana
   无从抓取；"24 小时内风暴抑制率的变化"这类问题，快照答不了。
2. **比率不在库里**。抑制率、缓存命中率是演示与面试里最常被问的两个数，但每次都要
   人工拿分子分母现算——`sliding_window_storm_real.json` 那类离线报告即是这么来的。
3. **降级档位只能靠审计事件反推**。`ev=degrade_transition` 记的是"何时切换"（OP-A5），
   而"此刻在第几档"需要读请求里的 `degrade_level` 或轮询 `/state`。

三条事实同时为真：
- 已有的 13 个计数器语义是对的（Storm/缓存/降级/护栏四个域的实证口径），要的是
  **换量具不改语义**；
- Spring Boot 已依赖 `spring-boot-starter-actuator`（`/actuator/health` 是 demo.sh
  的存活判据），Micrometer 核心已在 classpath 上，只差一个 Prometheus registry；
- `snapshot()` 的键名被 `check_panel_contract.sh` 逐字锁死（面板磁贴 ↔ 后端键双向
  对齐），任何"顺手重构"都会打断控制台。

决策：**以 Micrometer 为唯一事实源，一份数供两个出口；Prometheus 侧独立命名，不经 snapshot。**

- `OpsMetrics` 的 13 个 `AtomicLong` 换成 `io.micrometer.core.instrument.Counter`，
  构造时按 `aiops.*` 层级名注册到 `MeterRegistry`；公开方法签名不变（调用方零改动）。
  `snapshot()` 改为从 counter 读值并 `(long)` 收敛后 `put`——13 个 `put("<snake_case>"`
  字面量原样保留，面板契约的 grep 判据继续命中。
- 派生比率在 `OpsMetrics` 构造时以 `Gauge` 注册，值函数读 counter 实时求值：
  `aiops.storm.suppression.ratio`、`aiops.cache.hit.rate`，以及拆分 L1/L2 的
  `aiops.cache.l1.hit.rate` / `aiops.cache.l2.hit.rate`（L1 是指纹精确命中、L2 是向量
  语义命中，混成一个数会掩盖"精度换召回"的取舍）。
- 降级档位另起一个 `MeterBinder`（`metrics/DegradationStateMetric`）注册
  `aiops.degradation.state`（0=L0 全链路 / 1=L1 仅 ES / 2=L2 SOP 兜底）。gauge 的值来自
  另一个 bean，注册权归指标侧，`DegradationStateMachine` 的构造签名因此不必带上量具
  依赖（三个测试直接 new 它）。
- `application.yml` 打开 `management.endpoints.web.exposure.include: prometheus`。

| 需求里的指标名 | 落地名 | 说明 |
| --- | --- | --- |
| `aiops.storm.suppression.ratio` | 同名 | 告警风暴抑制率 = 被合并请求数 / 总请求数 |
| `aiops.cache.hit.rate` | 同名 + L1/L2 拆分 | 总命中率 = (L1+L2) / 总请求数；拆开后 L1≈0 而 L2 不低 = 指纹键设计过严（精确命中打不中却语义命中，常见于键里混进了易变字段） |
| `aiops.degradation.state` | 同名 | 档位序数而非三个布尔——时序告警规则要写"状态从 0 迁到 1" |
| `aiops.guard.violation.count` | `aiops.guard.verbatim_masked` | 护栏拦截数按**句**累计（`verbatimMasked(n)`），改叫 `count` 会与"每条请求一次"混淆；语义即拦截统计 |

**关键约束**：
- 比率在零分母时给 `0.0` 不给 `NaN`：NaN 写进抓取体会让部分 Prometheus 客户端解析
  失败，而空负载恰是新实例的首次抓取状态（`OpsMetricsTest.ratiosAreZeroBeforeAnyRequest`）。
- snapshot 值必须是 `Long` 不是 `Double`：面板做整数累加与阈值比较（`m[k]|0` 之外的
  展示路径），`(long)` 收敛在写入前完成。
- `/actuator/prometheus` 不带凭证（`JwtAuthFilter` 只守 `/api/v1/copilot|admin` 与
  `/v1`）——拉模型端点的正常形态；多租户公网部署时该端点应交网络策略或 scraper 凭证
  兜住，不在本 ADR 范围内加鉴权。

否决项：
- **在 `/state` 的 metrics 字段里补比率再让 Grafana 抓它**：`/state` 是鉴权端点
  （admin 凭证），抓取侧要持用户 token；且它返回的是整个面板的快照 JSON，不是
  Prometheus  exposition 格式，抓了也要自己转。
- **另起一套计数器专供 Prometheus**：两份计数要保证同步，任何一处漏加即出现"面板与
  监控对不上"的哑巴亏——所以本次特意让两个出口共用同一份 counter，`snapshot()` 与
  `/actuator/prometheus` 读的是同一处自增。
- **顺手把 `aiops.*` 也塞进 `snapshot()`**：snapshot 的键是面板契约面，塞进去就得
  同步改面板磁贴，把观测需求变成 UI 需求。

后果：
- ADR 总数 15 → 16；README 的 ADR 计数由 `offline/doc_numbers.py` 现算比对。
- 单测 190 → 197（`OpsMetricsTest` 6 条：契约键集/Long 值型/双出口一致/零分母/抑制率/
  L1·L2 拆分；`DegradationStateMetricTest` 1 条：gauge 实时读而非 bind 时刻快照）。
- `ChatOrchestratorTest` 的 `new OpsMetrics()` 改为传 `SimpleMeterRegistry()`——这是
  全仓唯一直接 new 它的地方，其余测试走 mock。
- 面试话术从"指标写进了面板"升级为"指标按 Micrometer 分层命名暴露，面板与 Prometheus
  共用一个事实源，比率与档位是 gauge"——可对着 `/actuator/prometheus` 的抓取体逐行讲。

落地时的实测回归（2026-10-10，活体网关，jar 形态）：
- `/actuator/prometheus` 18 条 `aiops_*` 全部到位，counter 带 Prometheus 约定的 `_total`
  后缀；零请求时四个比率是 `0.0` 而非 `NaN`。
- 连发两条同一查询后两个出口逐数一致：`total_requests=2`、`l1_cache_hits=1`、
  `l2_cache_hits=1`、`dedup_aggregated=1` ⇒ `aiops_storm_suppression_ratio 0.5`、
  `aiops_cache_hit_rate 1.0`（两条都由缓存服务，`llm_calls=0`，故命中率 1.0 语义正确）。
- **踩到并修掉一个自己造的坑**：`exposure.include: prometheus` 把默认的 `health` 一起
  覆盖掉，`/actuator/health` 当场 404——而它是 demo.sh 预检 / backup.sh / quickstart.sh /
  liveness.py 四个消费方的存活判据。已改为 `health,prometheus`，并在
  `check_panel_contract.sh` 增加第五层 grep 断言（两个方向各做一次变异验证：漏 health 红、
  漏 prometheus 红）。静态检查在这里是必要的：改配置的人不会想到四个脚本在等一个
  他没听过的端点。

