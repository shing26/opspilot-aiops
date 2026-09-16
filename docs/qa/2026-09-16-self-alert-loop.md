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
| 回归锁 | 生产者判定/闸门/鉴权/容错 15 用例（纯函数级，不触网）+ Java 侧 `source` 断言 | `offline/tests/test_alert_producer.py`、`metrics/AuditServiceTest.java` |

## 2. 判据与实测（逐条）

### 2.1 收敛性（防"告警风暴打爆 LLM"）— 实测通过

同一依赖故障连发 11 次（`--cooldown 0`）：

| 指标 | 实测 |
| --- | --- |
| 指纹 | 11 条全部 `162a8a422ecc8d7046e90c4bc9265a02`（逐位一致） |
| `llm_calls` | 3 → 3（**增量 0**；首条已生成，其余全部 L1 命中） |
| `l1_cache_hits` / `dedup_aggregated` | +10 / +10 |
| 配额消耗 | 10（`quota{sre-watcher, used:13, limit:5000}`，主体隔离生效） |
| 台账 `cache_hit` | 首条 `none`，其余 10 条 `L1` |

### 2.2 来源可辨识 — 实测通过

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
| `--dry-run` | 候选 1、发出 0；配额 13→13、`llm_calls` 3→3（**零请求**）；台账落 `skip=dry_run` |
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
| 单测 | `pytest offline/tests` **23 passed**（切分 8 + 生产者 15） |
| 蓝绿重建 | live 全量 **30.1s**、零空窗、`reingest_last=ok (trigger=admin-reingest)`；ES `423 docs` / Qdrant `423 pts` |
| 新码命中 | 9/9 精确码 Top-1 = 期望处置单（rb-105 / rb-201..208） |

## 4. 评测数字刷新（E1 教训第二次实测复现）

语料 303→423 后 BM25 文档频率漂移，**纯词法腿劣化、hybrid 逐位不变**：

| 模式 | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |
| --- | --- | --- | --- |
| es_only | 64%（不变） | **100% → 92%** | **0.813 → 0.767** |
| vector_only | 88%（不变） | 100%（不变） | 0.940（不变） |
| hybrid | 88%（不变） | 100%（不变） | 0.940（不变） |

已按纪律同步：`offline/eval/{golden_dataset.jsonl,reports/*}` 重生成（50→59 样本：34 精确码 + 25 语义）、
README 数字与报告同代、CONTEXT.md 术语表样本数同步。**报告自报后端**为 `dashscope:text-embedding-v3`（live）。

## 5. 意外与缺陷（本方向的实际收益）

1. **依赖重启后网关侧健康面恢复滞后**：`docker compose stop/start qdrant` 后，网关侧 `health.qdrant` 在 12s 时
   仍为 `DOWN`，**约 75s 内自行转 UP**（gRPC 通道重建）；期间 hybrid 静默退化为 es_only。外部 REST 探活早已正常。
   → 非永久缺陷、非误报，但足以误导排障：已写入 `rb-208` 的「先直连确认 + 恢复滞后量级」一节。
2. **账号库损坏（真实事故，P1）**：容器网关持有 `./data` 时，在宿主跑账号 CLI 写库 → 停容器 → 再打开即
   MVStore chunk 损坏，`org.h2.tools.Recover` 也救不回。根因（高度可疑，未插桩）：容器 bind mount 上
   H2 锁文件不可靠 → 双写。→ 已按纪律处置：OPS §1 并发口径更正（容器形态必须串行）、事故成文
   `pm-104`/`rb-105`、损坏文件留档 `data/users.mv.db.corrupt-20260916.bak`、事后补做账号库备份。

## 6. 未收敛项与已知盲区（不冒充能力）

| 项 | 现状 | 处置 |
| --- | --- | --- |
| 跨指纹 incident 关联 | 同一根因的**不同措辞**告警会得到不同 fp，仍会各自触发一次生成；同指纹域收敛（500→1）不受影响 | 前置已满足（真实告警流存在），已入 OPS §5 债务账并带触发线；证据面=`logs/alert-producer.jsonl` 的 fp 分布 |
| 网关自身不可用 | 生产者经 `/state` 无法自证，只能记 `unreachable`（单进程自举的固有边界） | 显式登记；如需覆盖须引入外部上报通道（属架构变更） |
| mock 下执行的验收行 | §2.4 全部为 mock 后端（零成本口径），§2.3 与 §4 为 live | 已逐节标注后端；mock 与 live 共用同一代码路径（双模切换） |
| 生产者未纳入 CI | 它是常驻运维工具，不是构建产物；纯函数级回归锁（15 用例）已进 `pytest` | 判定为合理边界；若纳入 CI 需先解决"CI 无常驻网关"前提 |

## 7. 复现步骤

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
