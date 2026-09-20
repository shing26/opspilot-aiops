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
| 告警 | 部分 | daily_usage 拒答率阈值退出码（cron MAILTO）；实时 webhook 按硬约束挂起（无真实告警源不做 adapter）。**状态注（2026-09-16，ADR-0011）**：已有**自举告警源**（`offline/alert_producer.py`，读运行态真相面 → `source=alert` 回打自身链路），外部监控系统的 adapter 仍挂起 |

修补：**M2**（logs 目录磁盘哨兵并入 daily_usage）；导出线不动。

### 5. 错误处理 — 已具备（2026-09-12 当轮闭环）

| 项 | 状态 | 代码依据 |
|---|---|---|
| 业务/系统分离 | 已具备 | 4xx（INVALID_REQUEST/HTTP_405/415/404）与 5xx 分离，四专属分支杜绝客户端错误污染 5xx（V2 十探针） |
| 形状规范化 | 已具备（双面） | /api `{code,message}`；/v1 OpenAI `{"error":{message,type}}`；filter 短路亦保形状 |
| 输入校验 | 已具备 | @Valid + 类型/方法/媒体类型全分支，文案直写必达 + 400 类留痕 |
| 卫生 | 已具备 | 无栈泄漏（多轮红队实证）；对内留栈对外屏蔽 |

剩余小项：错误码字符串散落，随下一需求收拢为枚举（M4，1h，未入本轮）。

> **状态注（2026-09-17，两处口径更正 + 一项降级）**：
> 1. **本文件的状态表是修复前快照**：上表"重试｜缺失"与"应用日志仅 CONSOLE"两行在**本文件自己的修补段**已记 H3/H1 修完——读表时请以修补段为准，勿把快照当现行缺口。
> 2. **M4 不立项（降级）**：后出的实测证词（`docs/qa/2026-09-13-module-verification.md` §8）grep 全仓主源码+资源，错误码字符串**恰好 1 处**且是拒答话术里的示例码（文案非配置）——"<10 处"的原判不实。故 M4 从"待修"降级为**不立项**；若将来演进到需要按枚举做类型化处理，再按新需求重开。
> 3. **计数器数目**：本文件写"OpsMetrics 10 计数器"，现为 **11 键**（新增 `verbatim_masked`，生成质量包 Q2=C 的产物）。
> 4. M3 告警接入的状态更新见 §"告警"行与末段（自举源已接入，ADR-0011）。

## 差距清单与实施顺序

**高（上线前，~1 工作日）**：H1 应用日志持久化+滚动+cap（0.5h）→ H2 request_id 贯穿（1-2h）→
H3 LLM 单次退避重试+429/网络错分类（2-3h）。
**中（上线后随手）**：M1 启动脱敏配置摘要（0.5h）→ M2 磁盘哨兵（1h）→ M4 错误码枚举（1h，另轮）。
M3 告警 webhook：**2026-09-16 状态更新（ADR-0011）**——自举告警源已上线（换源非 adapter）；面向外部监控系统的 webhook adapter 仍等真实告源（硬约束）。
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
| M3 告警 webhook | 部分接入 | 自举源已上线（ADR-0011）；外部监控 adapter 仍无真实告源（硬约束） | `offline/alert_producer.py`、`docs/qa/2026-09-16-self-alert-loop.md` |
| M4 错误码枚举 | **不立项（2026-09-17 降级）** | 原判"字符串 <10 个"不实：主源码+资源 grep 实测**恰好 1 处**且是拒答话术里的示例码（文案非配置），见 `docs/qa/2026-09-13-module-verification.md` §8 | 不立项；若演进到需按枚举做类型化处理再按新需求重开 |
| L1-L5 | 挂起（企业级触发线） | ADR-0003/0005/0006/0009、OPS 债务表 | — |

## 修补记录（2026-09-18：证据链与文档时效，来自外部补齐清单 O1/O2）

> 这两项不是"缺功能"，而是**卖点的交付方式**：本项目的差异化是"每个出口都可核验"，
> 而证据此前散在五个落点、且"数字与产物同代"只有人工纪律兜底（E1 教训两次，两次都靠人发现）。

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| O2 报告↔语料同代门闩 | **已修** | `offline/provenance.py`：对"语料文档字节集 + `chunks.jsonl` + `golden_dataset.jsonl`"取内容摘要（行尾归一），CI 新增 `provenance` job 跑 `--check`；`evaluate.py` 落盘报告后自动 `--stamp` 刷新出处登记 | `offline/tests/test_provenance.py` 13 例全绿；**双向锁**——"加一篇语料不重生成报告 → 红"与"只重新登记 → 不红"都有用例；变异验证：把 `diff()` 改成恒空则 4 例失败、把自洽性检查放水则 2 例失败（证明门闩非空转） |
| O1 证据链一键打包 | **已修** | `scripts/pack_evidence.py`：一条命令产出自述快照（目录 + 可选 zip），档内 `MANIFEST.md` 逐项写明出处文件/产生命令/复核前提三档（干净检出｜需活体栈｜需 live key）；`CHECKSUMS.sha256` 供 `sha256sum -c` | 干净检出形态（隐藏 `logs/`）实测一条命令产 35 产物、35 项校验全 OK；`.env` 全部 8 个值 grep 归档零命中；`offline/tests/test_pack_evidence.py` 10 例全绿（含"非 LOCAL 登记项不得是 gitignore 路径"与"MANIFEST 不得含 key 值"） |

**两处实测踩坑（已修，均属跨平台行尾一类）**：
1. 内容摘要最初直接吃工作区字节 → 本机 CRLF 与 CI（LF）会算出不同摘要、门闩在 CI **假红**。改为按内容归一（文本类 CRLF→LF）后再摘要；并把 `*.jsonl`/`*.md` 一并钉进 `.gitattributes`（该文件此前只覆盖 `.sh/.py/.yml`）。实测等价性：同一语料按 LF 复制后三路摘要逐位相同。
2. `CHECKSUMS.sha256` 最初用 `write_text` 落盘 → Windows 写出 CRLF，`sha256sum -c` 把行尾 `\r` 当文件名、**35 项全部失败**（归档自带的完整性命令直接失效）。改为显式 LF 落盘，实测 35/35 OK。

## 外部框架评审核实（2026-09-19，来源：code-review-agent《全项目框架补强建议》§3.6 + §四）

> 该评审对 OpsPilot 的指控均标**【勘察】未复核**。逐条实测（HEAD bf9b1ad）后：2 条属实采纳、
> 2 条不采纳。不采纳项按纪律登记触发线（见 OPS §5 债务表）——不留"没有到期日的欠账"。

| 指控 | 实测 | 处置 |
|---|---|---|
| 错误分类靠 `startsWith("LLM HTTP 5")` 文本判别（§四#1） | **属实**：`LlmClient.java:104` 拼文案、`:140` 按前缀分类；全测试域 grep `LLM HTTP 5` 零命中——5xx→transient 分支无回归锁（LlmClientTest 只锁了 IOException 与 401 两路） | **采纳（P1）**：类型化异常带 status 字段，`isTransient` 判 status 不读文案；用例双向锁 |
| 配置无 fail-fast（§四#2） | **属实**：`OpsPilotProperties` 全 record 零校验注解；实测锐边——`inflight-threshold=-1` 使 `DegradationStateMachine.java:42` 恒真 = **永久 L1 且无告警**；`llm-timeout-seconds=0` = 每次调用立即超时→熔断常开 | **采纳（P2）**：`@Validated` + 约束注解，非法值启动即拒并点名变量；application.yml 默认值逐项核对不会误伤 |
| `LlmClient` 无接口、"换供应商须重构核心" | **夸大**：baseUrl/model/key 全走配置，硬编码仅 URL 路径段 `/compatible-mode`；LLM 腿换 OpenAI 兼容供应商≈改配置。真供应商耦合在数据层（1024 维向量 + rerank，[ADR-0002](../../docs/adr/0002-dashscope-one-stop-1024-dim.md) 在案决策） | **不采纳**：接口只有一个真实实现（mock 是测试模式非供应商），为它建抽象是投机泛化。触发线入 OPS §5 债务表 |
| `Level` 枚举被 sink 层耦合，挪出可减改动点 | **诊断反了**：Level 属 resilience（`DegradationStateMachine.java:18`），gateway→resilience 依赖方向正确；"加一档改 ≥6 处"是带 UI 呈现的状态机的固有扇出，挪文件减少 0 个改动点；漏改风险已被面板契约 CI job + `DegradationRecoveryTest` + `AdminControllerTest` 18 格矩阵兜住（有报错，非评审所称"漏改不报错"） | **不采纳**（扇出≠错位） |

> 口径注：评审 §2 给的"83 文件 / 7,506 行"与实测 **82 文件 / 7,056 行**（main+test Java）不符（其扫描脚本口径不同，`IngestionRunner 371 行`一条倒是精确）。外部二手数字引用前须按本表对账——与 E1 教训同源：不采信未复核的他方数字。

## 修补记录（2026-09-19：外部评审采纳项落地回填）

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| P1 5xx 分类类型化 | **已修** | `LlmHttpException`（带 `status` 字段 + `isServerSide()` 收口瞬时性判定）替换 `"LLM HTTP n"` 文案异常；`isTransient` 读字段不读文案；`attemptOnce` 对其直传不包裹（一旦被包裹就丢 status，分类退化回读文案）。message 保留同文案仅供人读 | LlmClientTest 7 例全绿：503 重试耗尽→计 llm_retry + llm_network_error 且 `status=503` 原样上抛（新增）；401→不重试（改类型化）；**反向锁**——文案含 "LLM HTTP 503" 的普通异常→不重试（证明文案不再承重，倒退回字符串匹配时该例必红）；mvn 123 全绿 |
| P2 配置 fail-fast | **已修** | `OpsPilotProperties` 加 `@Validated` + 嵌套 record 逐字段约束（`@Min`/`@Max`/`@Pattern`）。约束只挡"会让系统静默走错"的值：degrade 三值、llm-timeout-seconds、leg-timeout-ms、各 topK、embedding-dim、grpc-port 1-65535、min-relevance/l2-threshold ∈ [0,1]、mode 白名单 auto\|live\|mock（写错此前会静默退回按 key 判定——演示时 key 在场就悄悄 live 烧真钱）。合法边界（如 min-relevance=0 关闭门控）不挡；application.yml 默认值逐项核对不会误伤 | OpsPilotPropertiesValidationTest 8 例（ApplicationContextRunner；负例一律以全量合法值打底、注入单个非法值，保证失败原因唯一）：`inflight-threshold=-1`/`llm-timeout-seconds=0`/`min-relevance=1.5`/`mode=LIVA`/`grpc-port=0` 各自**启动失败且报错含字段名**；全量默认值绑定成功、mode 大小写不敏感、auto 无 key 回落 mock（双模既有行为未被校验破坏）。mvn 131 全绿（CI ubuntu 同绿）。**活体复验（2026-09-19，打包 jar）**：① 负向——`java -jar … --opspilot.degrade.inflight-threshold=-1` → exit=1 + `APPLICATION FAILED TO START` + `Property: opspilot.degrade.inflightThreshold` + `Reason: 最小不能小于1`；② 正向对照——真实 yml 合法值启动**零校验告警**，推进到 Tomcat 8081 与 H2 连接，仅因本机未起 Redis（`RedisConnectionException: localhost/127.0.0.1:6379`）中止 ⇒ 真实 yml 在约束下合法，门闩不误伤正常流程。**顺带钉死一条语义**（首跑实测发现）：构造器绑定下缺省 int 字段=0，必被 @Min(1) 拒绝——这是刻意设计，谁删了 yml 一行就应在启动期被点名，而不是带 0 阈值静默跑（判据见测试类 Javadoc） |

## 修补记录（2026-09-21：O2 面二 + 证据包自检互锁 + LICENSE）

> 触发：外部补齐清单 `05-opspilot-aiops` 复核（结论：O1/O2 已于 09-19 落地、O3 挂起合理、只剩 O4）。
> 复核中实测出一条**已发生但无人看见**的同族事故，故本轮补的不是新功能，而是 O2 漏掉的那一半：
> README 写「121 用例」，而 `src/test` 下 `@Test` 声明与 surefire 执行数**同为 131**
> （差 10 条 = `RequestIdFilterTest` 2 + `OpsPilotPropertiesValidationTest` 8 两次修复新增）。
> **面一（报告↔语料）同代 ≠ 面二（文档↔产物）同代**：报告是对的，人抄进文档时抄错，面一完全看不见。

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| O2 面二：文档数字↔产物门闩 | **已修** | `offline/doc_numbers.py` + `offline/doc_numbers.json`（21 条登记：Java `@Test` 数、ADR 份数、chunks/语料/golden 计数、评测报告各命中率与 MRR、越狱用例数、L1 延迟 p99、Locust 吞吐/请求/失败/P50）。判据三条：派生自产物**现算**（不存快照、**无 `--stamp`**，故不存在"重新登记即掩盖漂移"）、每个 `cited_in` **至少命中一次**（0 命中＝门闩指不到人，报红而非静默放过）、命中**全部**须等于派生值（同一数字多处出现，任一处漂移即红） | `offline/tests/test_doc_numbers.py` 15 例全绿；**变异验证**：改文档数字（131→132）→ 红且点名 `README.md` 的具体行号 + 期望/实际（该行号由用例现算，不硬编码）；改产物（chunks 加一行）→ 红且点名 `chunks_lines`；改写文案（"131 单测"→"131 个单测"）→ 红且报"0 命中"。**反向锁**：未登记数字（"22.3s"）与历史叙事口径（"0.813→0.767"）改动**不得红**（钉住"只对登记数字负责、不做全文扫描"的边界）；连跑两次同结果 |
| O1 增强：出包前同代自检 | **已修** | `scripts/pack_evidence.py` 出包前复用 `offline/provenance.py` 的判据自检（同一份实现，不另写一套——两套判据分叉后读者无从判断哪个算数）。分叉时**默认拒绝出包**（挡在任何产物落地之前，不留半个归档目录）；`--allow-stale` 显式放行时档内显著标注 + `MANIFEST.json` 记 `same_generation.ok=false`（CI 从不传该开关） | `test_pack_evidence.py` 16 例全绿（新增 6 例）：分叉→退出 1、`out/` 未创建、stderr 点名分叉项并给出修法；`--allow-stale`→退出 0 且 MANIFEST 含"同代自检：未通过""不可作为「当前能力」引用"与差异明细、JSON 记 `ok=false`；同代→档内记"通过"；未携带结论时 MANIFEST 显式说"未记录"（静默缺席会被读成"查过了"） |
| O1 进 CI（验收从人工一次性→每次复现） | **已修** | `provenance` job 内加两步（**不新增 job**，故 README「五个 job」仍成立）：`Doc numbers ⟷ artifacts gate` + `Evidence pack smoke (clean checkout, mock)`（出包 → 断言三件套存在 → `sha256sum -c` 全 OK → MANIFEST 含同代自检结论） | 本地等价复现：干净形态出包 35 产物、`sha256sum -c` 35/35 OK、MANIFEST 含"同代自检：通过" |
| README 数字改准 | **已修** | `README.md:36`/`:337` 的 121 → **131**，并写明口径是「`@Test` 声明数」（避免"声明 vs 执行"歧义） | `mvn -B test` 实测 `Tests run: 131, Failures: 0, Errors: 0`、BUILD SUCCESS（35s）；surefire XML 汇总 131/0/0/0（25 文件）——声明数=执行数，两种口径同为 131 |
| O4 LICENSE | **已做** | 新增 MIT `LICENSE`（`Copyright (c) 2026 shing26`；与同批 7 仓中 CodeCompass / truetailor 一致）；README 加目录表一行 + §许可一节（含"为何选 MIT 而非 Apache-2.0"） | `ls LICENSE*` 命中；`grep -c $'\r' LICENSE` = 0（LF 落盘，避免跨平台假红）；README 两处指向。**决定**：`LICENSE` 刻意**不纳入**证据快照 REGISTRY——快照收录"可核验的能力声明"，许可是元数据不是声明，纳入只会改变 `CHECKSUMS.sha256` 项数而不增加可核验信息 |

**本轮明确不做（登记为触发线，不新开票）**：`docs/adr/` 0001–0006 与 0011 无 `状态：` 行（体例统一留到下次新增 ADR 时一并做）；ADR-0006 的旧数字「216 条」（ADR 是历史决策快照，不为数字改历史）；O3 外部监控 adapter（触发线已改写为可判读的三组析取项，见 `OPS.md` §5.1）；不新增 ADR——本轮无"难以反转 + 结果意外"的决策，取舍按本仓范式写进模块 docstring（`provenance.py` 即此形态）。
