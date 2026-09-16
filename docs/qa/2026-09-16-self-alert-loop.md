# 自举告警源闭环验收台账（2026-09-16）

> 目标：把"系统自己的运行史"变成真实告警输入（方向 A / [ADR-0011](../adr/0011-self-bootstrapped-alert-source.md)），
> 并验证 ①系统能自己发现问题 → ②自己发告警 → ③指纹收敛 → ④检索命中自举语料 → ⑤给出可照做的处置 → ⑥恢复后停止上报。
> 读者 = 复核者。凡宣称皆附可复现命令与现场产物；**未收敛项与已知盲区单列，不夹带在结论里**。

## 1. 交付物

| 层 | 交付 | 位置 |
| --- | --- | --- |
| 可辨识性 | 审计业务行新增 `source` 字段（与 `via` 正交）；`AuditService` 三档重载收敛为**单一入口**，杜绝新字段在旧调用点静默缺省 | `metrics/AuditService.java`、6 处调用点、用例 +2 |
| 生产者 | 自举告警生产者 + SSE 原语（单一事实源）+ 独立告警主体 | `offline/alert_producer.py`、`offline/localapi.py:stream_chat`、`scripts/seed_demo_users.sh`(sre-watcher) |
| 语料 | 7 起真实事故复盘+处置（`pm/rb-201..207`）、依赖不可用处置单（`rb-208`）、账号库损坏事故（`pm-104`/`rb-105`） | `offline/corpus/{postmortems,runbooks}/` |
| 回归锁 | 生产者判定/闸门/鉴权/容错/锁/清洗 22 用例（纯函数级，不触网）+ Java 侧 `source` 断言 | `offline/tests/test_alert_producer.py`、`metrics/AuditServiceTest.java` |

## 2. 判据与实测（逐条）

### 2.1 收敛性（防"告警风暴打爆 LLM"）— 实测通过（后端 = mock）

同一依赖故障连发 11 次（`--cooldown 0`）：

| 指标 | 实测 |
| --- | --- |
| 指纹 | 11 条全部 `162a8a422ecc8d7046e90c4bc9265a02`（逐位一致） |
| `llm_calls` | 序列首条 **Δ+1**（台账 `cache_hit=none`，真实生成），随后 10 条 **Δ0**（全部 L1 命中）；批量快照前=3、后=3，故 10 条重复的净增为 0 |
| `l1_cache_hits` / `dedup_aggregated` | +10 / +10 |
| 配额消耗 | 10（`total_requests` 3→13；探毕 `quota{sre-watcher, used:13, limit:5000}`，主体隔离生效） |
| 台账 `cache_hit` | 首条 `none`，其余 10 条 `L1` |

> 口径说明：`llm_calls` 是**增量**判据（同指纹重复不线性增长），不是"一次都不调"。
> 首条必调一次——那是这条告警的真实生成成本。

### 2.2 来源可辨识 — 实测通过（后端 = mock）

```json
{"ev":"chat","via":"sse","source":"alert","sub":"sre-watcher","tenant":"tenant-internal",
 "level":3,"fp":"162a8a422ecc8d7046e90c4bc9265a02","cache_hit":"none","mode":"es_only","took_ms":17506}
```

注意 `mode=es_only`：这条告警发出时 qdrant 正停着，系统**如实降级为单路**并被审计记下——
告警内容与系统状态在同一个证据面上对得上。非法值 (`source=alert;DROP`) 仍 400（`@Pattern` 未放宽）；
`source` 不参与鉴权与配额，伪造它不获得任何权限（ADR-0011 已写明该边界）。

### 2.3 闭环（发现 → 上报 → 命中 → 处置 → 恢复）— live 实测通过

| 环节 | 实测 |
| --- | --- |
| 自检 | `docker compose stop qdrant` 后 14s 内检出（健康面自身 5s TTL + 3s 探活超时） |
| 上报 | `source=alert`、`service=qdrant`，http 200 |
| 检索命中 | refs = `rb-208`（含「止损操作」小节、排除「排查步骤」子节为 Top-2/3）；`/search 51208_DEPENDENCY_DOWN` 直连 Top-3 全为 `rb-208` |
| 生成 | live（dashscope qwen-plus）：TTFT 0.962s、总 17.551s；答案含止损段（「含止损段: True」） |
| 恢复 | `start qdrant` + 80s 后单轮探测 `candidates=0`——**恢复即停止上报** |

### 2.4 护栏与容错矩阵 — 实测通过（mock 后端下执行）

| 用例 | 结果 |
| --- | --- |
| `--dry-run` | 候选 1、发出 0；配额 13→13、`llm_calls` 3→3（**零告警请求**；仍有 1 次 login + 1 次 `/state` GET——那是拿真相面的必要开销）；台账落 `skip=dry_run` |
| 配额打满（limit=5，第 6 次） | `http=429` 如实记账、进程继续；第 7 次由配额保留闸门主动停手（`skip=quota_reserve`） |
| 401 token 过期 | 自动重登后重试（单测锁定 `login` 1 次 / `get` 2 次） |
| 403 主体权限错 | `[fatal]` + 退出码 2，不空转重试（用 `sre-acme` 实测：level3 但 role=sre） |
| 网关不可达 | 台账记 `unreachable`（WinError 10061）两行，进程存活继续探测 |
| 计数器复位 | 网关重启后记 `counter_reset` 一行，**未据复位造告警** |
| 初始登录失败 | 登录移入循环（首轮失败 = 一轮失败，不是进程猝死）——设计期间自查发现的缺口，已修 |

## 3. 语料与索引

| 项 | 实测 |
| --- | --- |
| 切分 | `chunks=423`（原 303，+120）· `by_type={api_endpoint:25, markdown_section:398}` · 无 chunk_id 重复 |
| 单测 | `pytest offline/tests` **30 passed**（切分 8 + 生产者 22） |
| 蓝绿重建 | live 全量 **22.3s**（`logs/app.log`：`入库完成: 423 chunks … 耗时 22290ms`）、零空窗、`reingest_last=ok (trigger=admin-reingest)`；ES `423 docs` / Qdrant `423 pts`（mock 后端同语料 8.0s） |
| 新码命中 | 9/9 精确码 Top-1 = 期望处置单（`/search` 逐码直查，命令见 §7；命令输出 `Top1=rb-105/rb-201..rb-208` 与期望逐位一致）；评测面同批复核：`eval_report.json` exact hit@1 = 1.0（34/34，ground truth 为文档集，故精确码的"是否 rb 而非 pm"以直查为准） |

> 口径说明：入库耗时取**服务端日志**（`IngestionJob` 自报），不取调用方轮询窗口——
> 轮询粒度 5-10s 会把 22.3s 读成 30s 量级。凡"重建耗时"类数字一律以日志为准。

## 4. 评测数字刷新（E1 教训第二次实测复现）

语料 303→423 后 BM25 文档频率漂移，**纯词法腿劣化、hybrid 逐位不变**：

| 模式 | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |
| --- | --- | --- | --- |
| es_only | 64%（不变） | **100% → 92%** | **0.813 → 0.767** |
| vector_only | 88%（不变） | 100%（不变） | 0.940（不变） |
| hybrid | 88%（不变） | 100%（不变） | 0.940（不变） |

已按纪律同步：`offline/eval/{golden_dataset.jsonl,reports/*}` 重生成（50→59 样本：34 精确码 + 25 语义）、
README 数字与报告同代、CONTEXT.md 术语表样本数同步。**报告自报后端**为 `dashscope:text-embedding-v3`（live）。

> **顺带修正**：`build_golden.py` 重跑还暴露出上一版 golden 与已入库语料**本就不自洽**——
> `exact-50012_DB_TIMEOUT` 的 `expected_docs` 新增了 `ac-501`（acme 私有语料的同名变体）。
> 即：旧 golden 是语料扩充前生成的，只是没人重跑所以没人发现（与 E1 同源的老化机制）。
> 本次一并消除；后续凡动语料，`build_golden` 必须与 `evaluate` 同批执行。

## 5. 意外与缺陷（本方向的实际收益）

1. **依赖重启后网关侧健康面恢复滞后**：`docker compose stop/start qdrant` 后，网关侧 `health.qdrant` 在 12s 时
   仍为 `DOWN`，**约 75s 内自行转 UP**（gRPC 通道重建）；期间 hybrid 静默退化为 es_only。外部 REST 探活早已正常。
   → 非永久缺陷、非误报，但足以误导排障：已写入 `rb-208` 的「先直连确认 + 恢复滞后量级」一节。
2. **账号库损坏（真实事故，P1）**：容器网关持有 `./data` 时，在宿主跑账号 CLI 写库 → 停容器 → 再打开即
   MVStore chunk 损坏，`org.h2.tools.Recover` 也救不回。根因（高度可疑，未插桩）：容器 bind mount 上
   H2 锁文件不可靠 → 双写。→ 已按纪律处置：OPS §1 并发口径更正（容器形态必须串行）、事故成文
   `pm-104`/`rb-105`、损坏文件留档 `data/users.mv.db.corrupt-20260916.bak`、事后补做账号库备份。

## 6. 双轴评审与同批修复

交付前按惯例走了一次独立安全/代码评审（评审代理只读全量 diff + 实跑 pytest 与面板契约）。**发现 3 个阻断项、6 个建议项，全部在本批处置**：

| # | 发现 | 处置 |
| --- | --- | --- |
| B1（P1） | 登录失败无退避、未与"网络不可达"分类 → 口令错时每轮重试，叠加 `AuthService` 5 次失败锁 15 分钟 = **自锁循环**；且 `sre-watcher` 是公开用户名，第三方 5 次错口令即可压制告警源 | `LoginFailed` 独立异常 + 指数退避 + 连续 3 次即退出码 2；台账 `skip=login_failed` 与 `unreachable` 分离（凭据错 vs 网络错可直接查） |
| B2（P1） | 验收记录"mvn 123 全绿"与产物不符——真值 **121**：多出的 2 例来自 `target/surefire-reports` 里 2026-09-12 的陈旧报告（`RequestContextFilterTest`，该类早已改名） | 已 `mvn clean test` 复测 = **121 全绿**；本台账与提交信息均改按 121；教训：**验收前必须清 `target/`**，否则聚合计数会把历史报告算进来 |
| B3（P1） | README ⑤ 的证据指针指向 `docs/ops/production-readiness-2026-09-12.md`，而该文件仍写"M3 挂起/无真实告警源" | 该文件三处补同批状态注（自举源已接入、外部 adapter 仍挂起） |
| A1（P2） | `stream_chat` 只有 socket 超时（挡不住慢速滴流），且无条件全量收集答案增量 | 加整体 deadline + `truncated` 标记 + `collect_deltas` 开关（生产者默认关） |
| A2（P2） | 非 2xx 也记成功、401 不触发重登 | 台账加 `sent_ok`；401 立即清 token 走重登自愈；非 2xx 仍占冷却与预算（保守取舍：409/429/5xx 时"立刻重发"更危险，已写注释） |
| A3（P2） | 无单实例守卫：跑两个实例 = 双倍告警速率 + 台账交错 | 锁文件（mtime 判活、僵死锁 120s 后可接管、`--force` 显式接管），实测第二实例退出码 3 |
| A4（P2） | "配额保留线（留给人用）"与实现不符：配额按 sub 计数，而生产者是独立主体——它保护的是**自己**的余量 | 代码注释、README、ADR-0011 三处口径改为"本主体配额保留线" |
| A5（P2） | 组件 `detail` 是全链路唯一"非人工撰写"的 prompt 输入面，无字符限制 | 加白名单清洗（去控制字符、限 120 字、剔除 `<>\`$` 等拼接原料），并用例锁住 |
| A6（P2） | OpenAI 面硬编码 `source="manual"` 无边界注释 | 补注释锁定"该面恒 manual，告警链路只走 `/chat/stream`" |
| — | 评审同时触发本机安全插件对 `localapi.py` 的 SSRF 复核 | 借机**真实加固**：`assert_local` 增加"解析后 IP 必须仍是环回"（防 hosts 篡改/DNS rebinding）与 userinfo 拒绝；新增禁止跟随越界重定向的 opener，全部出口统一走它 |

**评审确认无 P0**：无凭据泄漏、无未授权访问、无注入；`source` 字段经全仓 grep 证实**只被窗口分档读取**，不参与鉴权/配额。回归锁随之增至 **30 用例**（切分 8 + 生产者 22）。

## 7. 未收敛项与已知盲区（不冒充能力）

| 项 | 现状 | 处置 |
| --- | --- | --- |
| 跨指纹 incident 关联 | 同一根因的**不同措辞**告警会得到不同 fp，仍会各自触发一次生成；同指纹域收敛（500→1）不受影响 | 前置已满足（真实告警流存在），已入 OPS §5 债务账并带触发线；证据面=`logs/alert-producer.jsonl` 的 fp 分布 |
| 网关自身不可用 | 生产者经 `/state` 无法自证，只能记 `unreachable`（单进程自举的固有边界） | 显式登记；如需覆盖须引入外部上报通道（属架构变更） |
| mock 下执行的验收行 | §2.1 / §2.2 / §2.4 为 mock 后端（零成本口径），§2.3 与 §4 为 live | 已逐节标注后端；mock 与 live 共用同一代码路径（双模切换） |
| 生产者未纳入 CI | 它是常驻运维工具，不是构建产物；纯函数级回归锁（22 用例）已进 `pytest` | 判定为合理边界；若纳入 CI 需先解决"CI 无常驻网关"前提 |

## 8. 复现步骤

```bash
set -a; . ./.env; set +a                       # 中间件已起：docker compose up -d（不含 gateway）
/e/java/jdk21/bin/java -jar target/opspilot-gateway-1.0.0.jar &
python offline/alert_producer.py --once        # 健康态：candidates=0
docker compose stop qdrant && sleep 15
python offline/alert_producer.py --once        # 期望：该次检出并发出（台账新增一行 emit=true，ref_docs 含 rb-208）
docker compose start qdrant && sleep 80
python offline/alert_producer.py --once        # 期望：candidates=0（恢复即停报）
```

> 复核旁证：`grep '"source":"alert"' logs/audit.jsonl | tail`（来源可辨）、
> `tail logs/alert-producer.jsonl`（生产者侧决策链）、`git show <本次提交>`（代码与文档同批）。
