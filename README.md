# OpsPilot — AIOps 智能排障与契约检索网关

> 基于 **Java 21 虚拟线程** 的混合 RAG（Hybrid Retrieval）排障网关：ES 倒排 + Qdrant 向量双路召回、RRF 融合、多级缓存、告警风暴指纹收敛与三级自适应降级。

## 它为什么存在（STAR）

- **S**：微服务告警风暴瞬时数千条同质告警打垮 LLM 链路；通用向量检索丢失错误码等精确符号，Top-1 不足 60%。
- **T**：7 天交付高并发混合检索排障网关：精确符号 Top-1 100%、热点 TP99<50ms、500 并发 LLM 降为 1 次、权限泄漏绝对 0；随后追加生产化硬化（租户隔离、账号体系、中间件加固、零空窗重建）。
- **A**：① Python AST 切分保护代码块/表格不腰斩，面包屑入元数据；② ES keyword + Qdrant 向量双路并行（虚拟线程+超时隔离），自研 RRF k=60 无量纲融合，精确符号快路径跳过 Rerank 压 TTFT；③ Redisson 滑动窗口 + 进程内 Single-Flight 收敛风暴；④ 三级降级状态机 LLM 429 熔断直出静态 SOP；⑤ auth_level + tenant 双维引擎层硬过滤，缓存/回放全链路权限维度；⑥ 四轮 QA 红队 + 双轴 code-review 闭环（P0 提权绕过 / TDD 锁；第四轮三 Persona 全系统测评揪出 Single-Flight 缺租户与 admin 信任域两处 P0）→ v1.0.0 冻结 → DashScope live 实测 → 四 Sprint 生产化（H2 账号+实时吊销、中间件凭据+环回、blue/green 原子切流、审计/配额/CI），债务带触发线记录在案。
- **R**：精确 Top-1 100%、语义 Hit@3 100%（hybrid 较纯 ES 把语义 Top-1 从 72% 拉到 88%）、热点 TP99 36.6ms、500 并发 LLM 仅 1 次、越狱与跨租户零泄漏（双向判别语料 + 跨租户并发用例锁死）、场景 A 0 失败——均以 DashScope live 实测；生产化改造后 live 评测**逐位一致（零质量回退）**，蓝绿在线切流实测 27s 零中断（第四轮修复后 303 文档全量重灌 50s 零空窗）。

## 核心指标（实测）

| 维度 | 目标 | 实测 | 口径 |
| --- | --- | --- | --- |
| 精确符号 Top-1 命中率 | 100% | **100%** | hybrid，25 错误码用例（live） |
| 语义 Top-3 召回率 | >90% | **100%** | hybrid，25 口语化用例（live） |
| 热点命中 TP99 | <50ms | **36.6ms** | 纯 L1 命中 n=500（live，回放不触外部 API） |
| 风暴 LLM 触发次数 | 500→1 | **1** | 500 并发同指纹，llm_calls Δ=1（live） |
| 权限泄漏率 | 0 | **0** | 5 条越狱用例，引擎层过滤（live） |
| 场景 A 吞吐 | — | **83 req/s · 0 失败 · P50=16ms** | Locust 50 并发 30s（live，冷请求走真实 API） |

> **Embedding 后端**：以上为 **DashScope live** 后端实测——`text-embedding-v3`（1024 维神经语义）+ `gte-rerank-v2`（精排）+ `qwen-plus`（流式生成）。无 `DASHSCOPE_API_KEY` 时服务自动切换本地词法 mock 后端，用于零成本机制验证与 CI 验收（同一代码路径，双模自动切换）。
>
> **live 独有的论证价值**：评测集显示语义查询的 **Top-1 命中率 `es_only` 仅 72% → hybrid 88%**（MRR 0.853→0.940）——向量路把纯词法在首位漏掉的 16% 口语化查询捞了回来。这是 mock 词法后端无法暴露、也只有接入神经向量后才成立的混合检索核心卖点。

## 架构

```mermaid
flowchart TD
    C[控制台客户端 / 告警源 source=alert] -->|POST /chat/stream SSE| G[Spring Boot 3.3 网关<br/>Java 21 虚拟线程]
    G --> JWT[JwtAuthFilter<br/>auth_level 凭证]
    JWT --> L1{L1 精确缓存<br/>Redis SHA256 Key}
    L1 -->|miss| L2{L2 语义缓存<br/>Qdrant 余弦>0.95}
    L2 -->|miss| SW[滑动窗口去重<br/>Redisson ZSET 30s]
    SW --> SF[Single-Flight<br/>fingerprint+authLevel]
    SF -->|leader| RS[双路召回]
    RS --> ES[ES 倒排 Top-50<br/>keyword 精确符号]
    RS --> QD[Qdrant 向量 Top-50<br/>语义泛化]
    ES --> RRF[RRF 融合 k=60]
    QD --> RRF
    RRF --> RK[Rerank Top-20→3<br/>gte-rerank-v2]
    RK --> LLM[qwen-plus 流式<br/>SSE 打字机]
    LLM --> EM[SseEmitter meta/delta/done]
    DG[三级降级状态机] -.-> RS
    DG -.-> LLM
```

### 三级自适应降级状态机

```mermaid
stateDiagram-v2
    [*] --> L0
    L0: Level 0 全链路(双路+Rerank+LLM)
    L1: Level 1 高负载(纯ES+缩减Prompt)
    L2: Level 2 熔断(静态SOP直出,零LLM)
    L0 --> L1: inflight超阈 / 向量路超时
    L1 --> L0: 负载恢复
    L0 --> L2: LLM 连续失败/429
    L2 --> L0: 冷却窗口结束
    L1 --> L2: LLM 仍失败
```

## 技术栈

| 分层 | 选型 |
| --- | --- |
| 网关 | Java 21 + Spring Boot 3.3（Virtual Threads + SseEmitter） |
| 并发 | CompletableFuture + 虚拟线程执行器（双路并行、超时隔离） |
| 缓存/防线 | Redisson + Redis 7（滑动窗口、Single-Flight、L1） |
| 检索 | Elasticsearch 8（keyword 倒排）+ Qdrant（gRPC 向量） |
| 融合 | 自研 RRF（k=60）+ DashScope gte-rerank-v2 |
| 推理 | DashScope qwen-plus（OpenAI 兼容 SSE，JDK HttpClient 手写解析） |
| 离线 ETL | Python 3.11（OpenAPI AST 切分 + Markdown 标题树切分） |

## 快速开始

```bash
# 1. 环境变量（凭据只从环境读取，.env 不入库）
cp .env.example .env   # DASHSCOPE_API_KEY（留空走 mock）、JWT_SECRET、DEMO_PASSWORD、H2_DB_PASSWORD

# 2. 中间件
docker compose up -d   # Redis + Qdrant + ES（总内存 ≤2GB）

# 3. 离线切分 → chunks.jsonl
cd offline && python -m venv .venv && .venv/Scripts/pip install pytest
.venv/Scripts/python chunkers/build_chunks.py
.venv/Scripts/python -m pytest tests/

# 4. 红队畸形 token（验收用；合法账号不再预签，走 login）
python ../scripts/gen_tokens.py > ../scripts/redteam_tokens.txt

# 5. 入库 + 启动（项目根目录；用户主库 H2 文件自动建表；首启若读别名缺失会自动入库）
java -jar target/opspilot-gateway-1.0.0.jar

# 6. 初始化演示账号（幂等，读取 DEMO_PASSWORD）：sre-limited/sre-full/sre-acme
bash scripts/seed_demo_users.sh

# 7. 演示（客户端自动 login 换 24h token）
.venv/Scripts/python.exe console_client.py "下单报 50012_DB_TIMEOUT 怎么排查"
.venv/Scripts/python.exe console_client.py --storm --storm-n 500   # 告警风暴
```

### 一键部署（容器形态，可复现性入口）

上面是宿主裸进程模式（开发/演示用）；**clone 到任何机器**的复现路径：

```bash
cp .env.example .env   # 填 JWT_SECRET / DEMO_PASSWORD / 三中间件凭据（Key 可留空走 mock）
docker compose --profile full up -d --build   # 三件套+网关全栈，首启自动灌库（镜像内含版本化 chunks.jsonl）
curl -s http://localhost:8081/actuator/health # {"status":"UP"} 即就绪
bash scripts/demo.sh                          # 9 项预检全绿
```

宿主模式与容器模式二选一（都占 8081）；容器模式下账号/备份操作走 `docker compose exec`，见 OPS.md。

## Ops Console 运维面板（只读可观测面）

浏览器开 **`http://localhost:8081/`** 即得（同源静态单页，零构建/零 CDN/离线可开；ADR-0009）。把已存在的 metrics/health/audit 真相渲染成三块——**运行状态**（build 指纹 + 三依赖灯 + live/mock + 降级交通灯与熔断倒计时）、**数据流**（真计数墙 + Single-Flight 在途组 + 审计事件游标流）、**能力边界**（每条"不做的事"带依据与最后验证日期）。接入需 role=platform 的 JWT（=login token，即密钥），取法见 OPS §9。

设计口径与 LobeChat 撤壳（ADR-0007）互为注脚：**UI 壳证明兼容性，面板承载运维真相**——本页零写操作、零模拟动画（速率=前端对真实累计值求差）、成功读路径不落审计（实测轮询 60s audit 行增量 0）；事件游标用全局 seq 且轮转/重启以 `truncated` 显式告警，禁静默空洞。键名契约由 `scripts/check_panel_contract.sh` 在 CI 双向钉死（后端改名不同步面板即红）。演示用法见 DEMO 幕④⑥口播。

## OpenAI 兼容面（/v1）

网关自带 OpenAI 规范端点，任何标准客户端（LobeChat / Dify / OpenAI SDK）可直连：

```bash
# 身份即密钥：API Key = 用户自己 login 换来的 24h JWT（租户/密级/吊销/配额/审计全穿透）
set -a && . ./.env && set +a   # 口令只从 .env 的 DEMO_PASSWORD 来
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' -d "{\"username\":\"sre-full\",\"password\":\"$DEMO_PASSWORD\"}" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -N http://localhost:8081/v1/chat/completions -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"model":"opspilot","stream":true,"messages":[{"role":"user","content":"how to fix 50012_DB_TIMEOUT"}]}'
# → role 首帧 → content 增量帧 → 「## 参考来源」溯源注入 → finish_reason=stop → data:[DONE]
```

**接入方式**：上面 curl 即最小可用闭环；任何 OpenAI 标准客户端（LobeChat / Dify / SDK）填 Base URL `http://localhost:8081/v1` + API Key=上面的 TOKEN 即可直连。本仓库不捆绑 UI 前端——2026-09-11 曾以 LobeChat 壳实测全链路穿透（见 ADR-0007 状态注），后评估聊天壳对"运维系统可视化"零增益而撤除；协议面保留为展品本体，演示主形态为 curl 直播（DEMO 幕⑦）。

**协议取舍声明**（详见 ADR-0007）：无状态单轮——取最后一条 user 消息，忽略 system/历史（检索按单 query 指纹设计，多轮请由客户端并入单条消息）；自定义 meta/ttft 在 OpenAI 帧无位置，丢弃；溯源以正文尾部 markdown 保留。注意 Windows Git Bash 的 `curl -d` 发中文有 GBK locale 坑，测试请含 ASCII 或用脚本。

## 评测与压测

```bash
cd offline
.venv/Scripts/python eval/build_golden.py     # 50 样本
.venv/Scripts/python eval/evaluate.py         # 3 模式对比 + 越狱 → eval/reports/
.venv/Scripts/locust -f load/locustfile.py --headless -u 50 -t 30s --html load/reports/locust_a.html
SCENARIO=storm .venv/Scripts/locust -f load/locustfile.py --headless -u 500 -t 15s --html load/reports/locust_b.html
```

## 安全设计

- **凭据零入库**：`DASHSCOPE_API_KEY`/`JWT_SECRET`/`DEMO_PASSWORD`/`H2_DB_PASSWORD` 仅从环境变量读取，`.env` 已 gitignore。
- **账号体系（P2）**：H2 文件主库 + bcrypt 口令 + 24h 短时 JWT；吊销实时——每请求校验账号存在性/disabled/token_ver，禁用或轮换版本即时全局失效旧 token（无黑名单膨胀）；登录失败 5 次/15min → 429；用户管理仅 CLI（口令只经 env/stdin，不进 argv），无 HTTP 用户端点。权限维度以 DB 为唯一真相，旧高等级 token 不残留权限。
- **租户隔离（P1）**：`tenant` 与 `auth_level` 同为 ES/Qdrant/L2/SOP 四面的引擎硬过滤维度（term 等值 + range lte 双 must）；tenant claim 缺失/空白/超长在入口 401。**每一条跨请求共享路径同构携带全部权限维度**（ADR-0008）：L1 key 掺租户、L2 payload 带租户、SOP key 分租户、Single-Flight 组键掺租户且回放带 `src_tenant` 绊线——第四轮 QA 曾证实并修复"引擎过滤完备但并发共享旁路漏掺"的 P0 类缺陷（回归锁 `ChatOrchestratorTest` + A2-9 跨租户并发用例 + acme 自有语料双向判别）。
- **权限引擎层硬隔离**：`auth_level <= user_level` 注入 ES Query DSL 与 Qdrant Filter，Prompt 越狱无法跨越数据级过滤（实测 5 用例零泄漏）。检索密级恒等于 token 的 auth_level，**客户端不可通过参数覆盖**（QA 红队发现 `authLevelOverride` 提权面后已移除）。
- **运维端点鉴权**：`/api/v1/admin/**` **全部要求平台管理员**（DB `role=="platform"` ∧ `auth_level>=3`）——密级≠信任域，任何单一租户的高密级用户不得重建共享索引/清全局缓存/读运营指标（第四轮 QA 修正旧"level≥3 即管理员"口径；回归锁 AdminControllerTest 18 格矩阵）。
- **缓存不成为泄漏通道**：L1 Key 掺 authLevel；L2 payload 携带 `max_auth_level` 并在检索时过滤；L2 命中回放携带完整 refs 保证溯源。
- **Single-Flight 按权限分组**：key=**tenant**+fingerprint+authLevel，防低权限等待者复用高权限答案、更防跨租户共享（第四轮 QA 修复：key 曾缺 tenant，告警风暴并发下外来租户可回放内部租户全文——恰好是最引以为傲的场景；回放入口另有 fail-closed 租户绊线）。
- **指纹归一化抗噪**：掩码时间戳/UUID/traceId/msgId/引号串/数字（保留错误码身份），使真实告警变体（每条带唯一 ID）收敛到同一指纹——实测 10 条变体并发 → LLM 仅 1 次。
- **输入校验与错误卫生**：空/null query 在流开始前返回 400 **且错误文案必达客户端**（SSE 端点的 produces 会吞掉常规 advice 响应体——第四轮 QA 修复为直写 JSON）；备份路径白名单拒绝呈现 400 可操作文案而非 500；`/v1` 面 401 输出 OpenAI 标准 error 形状（filter 短路不经 advice，已直写）；全局异常处理器屏蔽 JVM 内部文案。
- **置信度空态门控**：检索 Top-1 相关度（Rerank 分数）低于 `min-relevance`（默认 0.2）或零召回时，显式拒答并**跳过 LLM 调用**（省算力、不误导）；精确符号快路径天然高置信，豁免门控。mock 后端用 IDF 词元覆盖率作相关度信号，live 模式自动切换为 `gte-rerank-v2` 校准分数。
- **SSRF 防护**：离线客户端/脚本仅允许 localhost 白名单（`localapi.py` 单一事实源）。
- **知识库零空窗重建（P4）**：blue/green 别名原子切流——staging 灌库 + 计数硬验收 + 单请求换 ES/Qdrant 别名，失败保留旧库在线（`POST /api/v1/admin/reingest`，ADR-0006）；实测在线把 mock 向量集零中断切到 live。
- **合规审计**：业务请求每请求一行 JSON 落 `logs/audit.jsonl`（谁/何租户/何密级/查了什么/结果最高密级；回放路径另携带 `src_tenant` 命中来源）；鉴权拒绝/登录失败/运维动作同样留痕（`ev=auth|admin|invalid`，原因可辨）——"攻击探测事后不可查"曾是第四轮 QA 立案的 P1，现已补全并接入 A2-10 计数断言。14 天滚动。
- **成本护栏（P4）**：`/chat/stream` 每用户日配额（Redis INCR，默认 5000/天可 env 覆盖）——防失控循环；评测/检索路径不受限。
- **CI（P4）**：`mvn test`（零 key 零中间件）+ chunkers 不变量与**跨语言词法护栏**（Python 正则与 Java `EsSearchService.ERROR_CODE` 逐字符比对，防离线/在线漂移导致快路径静默 miss）。
- **哈希升级**：缓存 Key 由任务书原 MD5 升级为 SHA-256（安全扫描建议，语义不变）。
- **命名口径为刻意决策**：缓存存储 JSON 用 Jackson 默认 camelCase（`AnswerPayload`，从不上线），对外 SSE 帧用 snake_case（`SseEvents` 手工构 Map）——两域两制不做统一，理由与成本分析见 `AnswerPayload` Javadoc。

## QA 红队加固记录

首轮验收全绿后，经 Persona-QA-Simulator 黑盒红队发现并修复：
| 缺陷 | 等级 | 修复 |
| --- | --- | --- |
| search `authLevelOverride` 客户端提权 | P0 | 移除该参数，密级恒取 token |
| `/api/v1/admin/**` 零鉴权 | P0 | JWT + auth_level≥3 门禁 |
| 指纹归一化不足致真实风暴不收敛 | P1 | 强化噪声掩码管道 + 回归单测 |
| 缺 query 时 SSE 错误帧损坏 + NPE 泄露 | P1 | @Valid 前置校验 + 全局异常卫生 |
| degrade 非法枚举 500 | P2 | 白名单校验返回 400 |
| L2 命中丢 refs / 引用标号错位 | P2 | 缓存存完整 payload + 修正切块 |

第四轮（2026-09-11，三 Persona 全系统测评：体验/红队/运维+UI，详见 [docs/qa/2026-09-11-persona-eval.md](docs/qa/2026-09-11-persona-eval.md)）：

| 缺陷 | 等级 | 修复 |
| --- | --- | --- |
| Single-Flight 组键缺 tenant，跨租户并发回放全文+引用（风暴场景窗口最宽） | P0 | key 掺 tenant + 回放 src_tenant 绊线 + A2-9 并发回归锁 |
| admin 门禁"level≥3 即管理员"，外来租户 L3 可重建共享索引/清全局缓存（测评中被真实误触发） | P0 | role=platform 平台门禁（DB 单真相）+ 18 格矩阵锁 + seed 口径修正 |
| `/admin/metrics` 无门禁，L1 可读全局计数与内部模型名 | P1 | 并入平台门禁 |
| 401/403/登录失败/运维动作零审计留痕，"命中来源"字段缺失 | P1 | ev=auth/admin/invalid 全留痕 + src_tenant 随行 + A2-10 计数断言 |
| 容器模式备份落未映射层，cron 每日假绿（"备份的谎言"） | P1 | compose 增 ./backup 卷映射 + 脚本宿主产物存在性校验（容器路径实测宿主落盘） |
| daily_usage 不读轮转文件，跨天用量告警永远归零 | P1 | 按日期 glob 轮转+当前文件并集，业务事件口径收紧 |
| SSE 端点 400 空 body（内容协商吞文案）/backup 拒绝回 500/`/v1` 401 非 OpenAI 形状 | P2 | 三处错误形状直写修复 |
| 拒答话术回显置信度分数与阈值（门控 oracle） | P2 | 分数只进日志/metrics |
| OPS §7 恢复流程裸机命令与容器部署互斥、取 token 命令缺 .env 前置 | P2 | 容器版重写 + 命令块补全（照抄可执行） |
| acme 零语料致"跨租户零泄漏"验收单腿证据 | P3 | 播种 tenant-acme 私有语料（52xxx 段+同名 50012 变体），矩阵升级为"各回各家"双向判别 |

正向确认（红队打穿失败，保留为卖点）：串行越权读写零泄露、20/20 畸形 token 全拒、alg=none/篡改/空签名全拒、**持 JWT_SECRET 重签 auth_level=9 提权仍被 DB 真相压回**、CORS 外部 Origin 零放行、存在性 oracle 话术一致、错拼/中英混杂检索免疫。

## 文档

- [DEMO.md](DEMO.md) — 六幕演示手册 + 预检脚本（`scripts/demo.sh`）+ 3 分钟录屏讲解稿
- [OPS.md](OPS.md) — 管理员日常速查：账号生命周期/配额/用量 SOP/债务闹钟/推送门闩
- [CONTEXT.md](CONTEXT.md) — 领域术语表
- [docs/adr/](docs/adr/) — 9 项架构决策记录
- [offline/eval/reports/](offline/eval/reports/) — 评测报告
- [offline/load/reports/](offline/load/reports/) — Locust 压测 HTML
