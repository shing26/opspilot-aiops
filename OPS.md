# OpsPilot 运维速查（OPS）

> 读者 = 管理员。演示话术在 [DEMO.md](DEMO.md)，架构叙事在 README/ADR——本文只放**日常要做的事**。

## 1. 账号生命周期（不再手工签 token）

所有用户操作走 CLI。**⚠️ 并发口径按后端形态分**（2026-09-16 实测更正，事故 `51103_H2_CONCURRENT_WRITE_CORRUPTION`）：

- **宿主裸进程形态**：CLI 与运行中网关经 H2 AUTO_SERVER 并发无冲突（原口径，成立）。
- **容器形态**：`./data` 是 bind mount，**H2 的锁文件跨挂载点不可靠**——CLI 与容器内网关同时写会
  损坏账号库（本次实测：CLI 写成功后停容器，下次打开即 MVStore chunk 损坏、恢复工具也救不回）。
  **正确做法：先 `docker compose stop gateway` 再跑 CLI，或走 HTTP 管理面（`/admin/backup` 等）。**
- 动手前先备份：`bash scripts/backup.sh`（账号库是唯一不可再生数据）。

```bash
export DEMO_PASSWORD='…'   # 或任何一次性环境变量；口令绝不进命令行参数

# 入职：
DEMO_PASSWORD=<新口令> bash scripts/user_admin.sh add --user alice --tenant tenant-internal --level 1 --role sre --password-env DEMO_PASSWORD
# 调密级：改行需先 disable 再 add（CLI 不提供 UPDATE level，防误操作扩散）
# 改密（自动吊销该用户全部存量 token）：
DEMO_PASSWORD=<新口令> bash scripts/user_admin.sh passwd --user alice --password-env DEMO_PASSWORD
# 离职（即时生效，24h 内的旧 token 立刻 401）：
bash scripts/user_admin.sh disable --user alice
# 误删回滚：
bash scripts/user_admin.sh enable --user alice     # 注意：enable 也 bump token_ver，旧 token 不回活
# 审计视角的账号清单：
bash scripts/user_admin.sh list
```

> 前提：jar 已构建（`mvn package -DskipTests`）。首次使用先跑 `bash scripts/seed_demo_users.sh`。
> **容器模式**（compose full profile）：宿主机 CLI 无法共享容器持有的 H2 文件锁/网络，改用
> `docker compose exec gateway java -cp app.jar -Dloader.main=com.opspilot.auth.UserAdminCli org.springframework.boot.loader.launch.PropertiesLauncher <同参数>`；备份 zip 经 `./backup`↔`/app/backup` 卷映射落宿主 `backup/`（QA P1-3 修复后可见；映射缺失时 `backup.sh` 的存在性校验会报红）。

## 2. 口令分发纪律

- `JWT_SECRET` / `DEMO_PASSWORD` / `H2_DB_PASSWORD` / `REDIS_PASSWORD` / `QDRANT_API_KEY` / `ES_PASSWORD` / `DASHSCOPE_API_KEY` **只存在于每台部署机的 `.env`**；`.env` 已 gitignore。
- 新人拿口令：走公司密码管理器分享，**不发群、不进任何文件提交**。示例值一律是 `change-me-*` 占位（Mimosa 约束：源码/示例/测试零可用凭据字面量；单测 fixture secret 是特例，说明见 ADR-0005）。
- 换 `JWT_SECRET` = 全量 token 作废，全员重新 login，无需其它操作。

## 3. 配额与 429

- `/chat/stream` 每用户 **5000 次/天**（防失控循环，不防去重风暴——500 并发风暴只算穿透者的账）。
- 触发 429 的处理：确认是脚本失控还是真实高频；确需上调改部署 env：`-Dopspilot.quota.daily-limit=20000`（或 `OPSPLOT_QUOTA_DAILYLIMIT` 的 relaxed-binding 形式）。
- `/search` 与登录、管理端点不吃配额（评测路径专用）。

## 4. 每日用量与拒答处置 SOP

cron（Linux 部署版；本机手动跑同样有效）：

```cron
0 9 * * * cd /opt/opspilot && python3 scripts/daily_usage.py --date yesterday \
  --threshold-refuse 0.10 >> logs/usage.cron.log 2>&1
```

输出一行 JSON 进 `logs/usage.log`（schema 锁定，可 jq/Loki 消费）：

```json
{"date":"2026-09-10","total_requests":3847,"unique_users":42,"refuse_rate":0.073,"l3_hits":12,"top_user":"alice:212"}
```

**`refuse_rate > 0.10`（脚本退出码 1）时按此排查：**

1. `grep '"refused":true' logs/audit.jsonl | tail -100` 看被拒 query 原文，归类三种根因：
   - **语料落后**（新故障类型没 runbook）→ 补文档 → 触发一次 `POST /api/v1/admin/reingest`；
   - **min-relevance 误杀**（真相关但 rerank 分低）→ 调 `-Dopspilot.retrieval.min-relevance=0.15` 重启观察，两日仍高再回 0.2；
   - **Prompt/模型侧**（live 后端异常、降级期 SOP 空手）→ 看 `/metrics` 的 `degradation_level` 与 `llm_rate_limited`。
2. 24h 未收敛 → 按 ADR-0006「回滚」条目将别名指回上一版物理库（admin 端点尚未实现，属记录在案欠账；手动 curl ES `_aliases` / Qdrant `update_aliases` 即可）。
3. `l3_hits` 突增另说：那是合规视角要人肉过目的（谁在密集查生产配置级内容），找对应 sub 聊聊。

## 5. 知识库运维与债务闹钟表

- 语料改动 → `cd offline && .venv/Scripts/python chunkers/build_chunks.py` → `POST /api/v1/admin/reingest`（level≥3；busy 时 409，结果看 `/metrics` 的 `reingest_busy/reingest_last`）。零空窗，白天可操作（实测 423 chunks 22.3s，live、服务端日志口径；mock 后端 8.0s；live+账户限流最坏 ~6min）。**动语料后 `build_golden.py` 必须与 `evaluate.py` 同批跑**——只改语料不重生成评测集，会让报告与语料悄悄分叉（旧 golden 的 `exact-50012` 就是这样与入库语料不自洽的）。
- **同代性不必靠记性**（2026-09-18 起）：`cd offline && python provenance.py --check` 当场判定"报告是否仍是当前语料的报告"（内容摘要判据，零凭据零网络），CI 的 `provenance` job 跑的就是它。漏跑评测时它会点名哪个输入变了并给出修复命令：`python eval/build_golden.py && python eval/evaluate.py`；`evaluate.py` 会自动刷新出处登记 `eval/reports/PROVENANCE.json`。
- **交接/对外给证据**：`python scripts/pack_evidence.py`（加 `--zip` 出压缩包）产出自述快照到 `_archive/evidence/`，入口 `MANIFEST.md` 逐项写明出处与"当前模式下能否复核"。默认**不含**本机运行态证据（`logs/`）；要看运行史用 `--include-local-evidence`——**该档含查询内容与租户标识，别原样对外发**。
- `scripts/gen_tokens.py` **是红队畸形 token 签发器**（A2-8b/8c 与 demo.sh 预检依赖）——不是遗留脚本，勿删。
- 旧 `demo_tokens.txt` 体系已死；合法凭据一律 login 换取。

| 债务 | 触发线（到点必须做，未触发不做） | 当下缓解 |
|---|---|---|
| 真增量 Ingest | chunks > 2000 或 live 全量重灌 > 10min（`reingest_last` 时间戳可测） | blue/green 原子切流支撑每日任意时刻全量重建；`refuse_rate` 为前置健康信号 |
| LLM 供应商抽象（`llm/LlmProvider` 接口） | 出现**真实的第二家供应商**需求（mock/live 双模不算），且其检索栈可同规格替代（embedding 维度/rerank 变更需重灌评估，走 ADR-0006 blue/green） | LLM 腿本就 OpenAI 兼容：baseUrl/model 走配置、硬编码仅路径段 `/compatible-mode`，换供应商≈改配置；数据层耦合由 [ADR-0002](docs/adr/0002-dashscope-one-stop-1024-dim.md) 在案（2026-09-19 外部评审"抽接口"建议经核实不采纳，理由见台账） |
| 中间件 TLS | 过等保/ISO 审查，或中间件跨机/跨 VPC 部署 | 凭据认证 + 127.0.0.1 环回绑定（P3 已落） |
| 多实例 | 团队 >200 人或持续峰值 QPS > 50 | 单实例虚拟线程（实测 500 并发收敛）；先调 JVM 堆压榨单机 |
| 外部 IdP | 公司强推统一 SSO / 禁用自建口令 | H2+bcrypt 结构下加 `/auth/login/oidc` 映射入口即可，不侵入校验链 |
| 断言语态结构化门控 | 任何一次**被真人/QA 复现、且落在探针正则覆盖之外**的断言式事故编号回答（防断言语态现为软约束层：prompt 规则 6 + 正则探针，上限=探针选词，见 ADR-0010） | 立项输出结构化门控：模型按标注输出置信、渲染层依标注 gating，不再依赖句式正则；泛化症状探针在 `offline/qa_gen_quality_probes.py` 锁最恶劣形态 |
| 处置闭环（对目标系统的自动执行/自愈） | 两组析取项：①产品方向从"建议"转"执行"；②接入真实告警流 **+** 可审计的 runbook 执行通道（审批链/HITL 门，见 ADR-0009 只读面扩展） | 只读立场为承诺非疏漏：拒答门控 + 全链路溯源保证人在回路。**2026-09-16 状态注**：②中的"告警流"一半已满足（自举源，ADR-0011），但"执行通道"未建、①也未发生 → **本项未触发**，仍按待还债记录。**注**：AIOps 谱系 ①遥测采集/②异常检测=**永久非目标**，不列本表（边界声明见 README/面板谱系块） |
| 跨指纹 incident 关联（同一根因、多条不同指纹告警的归并） | 自举告警源上线后**前置已满足**（ADR-0011，真实告警流已存在）；触发线=真实告警流中出现"同一根因、多措辞/多服务"的告警且人工归并成本可测——证据面即 `logs/alert-producer.jsonl` 的 fp 分布（同事故不同措辞会给出不同 fp） | 同指纹域收敛已覆盖（500 并发 → LLM 1 次）；跨指纹目前仍靠人读告警文本，台账如实登记"未收敛项"，不冒充能力 |
| L1 降级期的门控降级（摘除 Rerank 后无相关性分数可依，相关性门控失效、只剩零召回拒答） | 出现一次"L1 期**误拒或漏拒**造成真实事故"且被真人/QA 复现 → 立项标定 RRF 分数分布并给出 L1 专用阈值 | 取舍与理由见 [ADR-0012](docs/adr/0012-degradation-cost-semantics.md)：RRF 分数无量纲（只依赖排名），照搬 L0 在 Rerank 分布上标定的 `min-relevance: 0.2` 会变成拍脑袋拒答，而未标定阈值造成的**误拒是静默的**（用户只看到拒答，看不到本可作答）——比现状更糟。零召回仍拒答，故并非"完全不门控"；L1≡es_only 的同构关系已由单测锁定 |
| L2 写入重复 embed（同一请求 embed 两次：检索腿一次、L2 写入一次） | 出现两组析取项任一：① **embedding 调用成本成为瓶颈**（日调用量翻倍触账单异常或上游限流）；② 单请求 `stage_ms.l2_store` 占比 **> 10%**（`/state` 与审计行可测）→ 立项把检索腿算出的向量回传给 L2 写入复用 | 成本已量化：实测一条 10.2s 请求里 `l2_store=232ms`（约 2.3%），延迟上不划算；真正的动机在**上游调用次数减半**。修法要向 `QdrantSearchService` 的返回面回传查询向量（并在 es_only/降级无向量时回退到 embed），接口面确有改动，故按触发线挂起不预防施工。**注**：原代码注释曾写"复用检索时已算好的向量"而实际未复用——那是**宣称失实**，已于 2026-09-25 改为如实描述（那个才是缺陷，已修） |

| SSE 异步上下文错误后继续写（2026-09-28 压测窗口观测到 2 次） | 出现一次**客户可见**的"响应已开始但中途静默截断"且被复现——判据：能指到具体请求的 SSE 流在 done 帧前断掉，且伴随 `IllegalStateException: AsyncContext after error` | 观测到的 2 次都发生在**客户端断开之后**（locust 收尾那一秒；同轮 2700 请求里 2 次），不影响任何已成功响应；属"错误后仍尝试写"的收尾竞态，非数据正确性问题（审计行与缓存写入都在错误之前完成）。原文见 `logs/run-113138.log`；本轮为"记录 + 触发线"而非修复（不在批次二范围内，且无客户可见影响） |

### 5.1 历史遗留项的触发线收敛（2026-09-17）

> 下面这批此前只有"另排期 / 待酌情 / 记录在案"而**没有可到点执行的触发条件**——按本项目纪律，
> 没有触发线的欠账等于"永远不做且无人知道"。本次逐条补触发线或明确降级为当前非目标。
> （CORS 注释失实一项属"宣称必须真"，已当场修正 `config/CorsConfig.java`，不占表位。）

| 项（来源） | 触发线 | 当下缓解 |
|---|---|---|
| 别名回滚 admin 端点（ADR-0006 承诺"可秒级回退"，端点未实现） | 首次出现"重灌后需要回滚"的真实需求，或任何一次 reingest 质量事故 | 物理库名带时间戳 + 手工改别名指向即秒级回退（见上表 SOP 第 2 步）；**无端点 ≠ 无退路** |
| 外部监控 adapter（评估 M3 剩余项） | 三组析取项任一成立即到期：① 拿到**可读且被授权**的外部告警/监控 API（Alertmanager / 云监控 webhook，有文档化契约与可用测试凭据）；② 产品方向转为含外部集成，且 ≥1 个外部消费者书面要求以 webhook 接入；③ 一次真实故障因缺外部告警接入而被漏掉（判据：该故障落在自举源盲区内，且复盘结论指向"没接外部源"）。**2026-09-21 改写**：原触发线"出现真实告源"不可判读——没有任何时刻会让它到期，等于没有到期日，违反本表自订纪律 | 自举源已满足"真实输入"诉求（ADR-0011）；外部集成明确为当前非目标 |
| 复盘 → 知识回灌（真实排障结论沉淀为可检索语料） | 两组析取项任一成立即到期：① **同一根因的故障被人工复盘 ≥2 次**（判据：台账或审计里出现两次人工撰写的同一根因复盘——第二次就该沉淀复用，而不是再写一遍）；② 外部监控 adapter 触发后同批处理（见上一行）。**2026-09-21 登记**：此环此前**不在任何账上**——既非"已覆盖"、也非在案债务，是复核"业务闭环了没有"时查出的账外边界。它之所以还没造成问题，只是因为今天系统里还没有真实故障流可沉淀 | 语料为人工撰写的合成 fixture（63 篇复盘/Runbook），运行期**无"结论→语料"写路径**：`IngestionRunner` 只读 `chunks.jsonl` 写派生索引，全仓唯一写 `chunks.jsonl` 的是 `offline/chunkers/build_chunks.py`。自举闭环那 17 篇语料即"人工合上闭环"的实证。**陷阱提醒**：这批复盘/Runbook 是喂检索的 fixture，不是做过的故障 |
| 拒答路径 TTFT（persona-eval P2-6） | 拒答延迟进入对外 SLO，或被真人/QA 作为体验问题复现 | 拒答在 LLM 前 return（不烧 token）；慢在检索自身耗时，属检索优化非生成链 |
| metrics 观测缺口：5xx 计数 / 延迟分位 / 别名指向 / H2 健康 / 最近成功备份时间 | 任一项成为排障瓶颈——判据：再次出现"**只能靠翻日志才能判断**"的事故（2026-09-17 的登录 500 正是此类） | 面板已吸收依赖灯 / 降级档位 / 配额水位 / 在途组；`daily_usage` 有磁盘哨兵 |
| 超长 query 软上限（persona-eval P2） | 出现超长输入导致的成本或延迟事故，或对外提供公网入口 | 拒答门控 + 单主体日配额 5000 + LLM 调用超时 |
| 引用样式漂移（`[参考1]` 与 `【参考2】`并存） | 下游客户端需要按样式解析引用时（当前形态是给人读） | 溯源以结构化 `refs` 数组为准，样式只在呈现层 |
| cache / ingest / chunk / config 四包无直接单测（台账 §1 如实记录） | 四包任一出现回归，且现有间接证据（依赖探活 / 集成活体 / 逐包验收）未能定位到该包 | 该四包的运行证据由探活与集成活体承担，非零覆盖 |
| "网关整体不可用时生产者无法自证"（自举已知盲区） | 需要"网关挂了也要告警"的场景出现（多实例 / 生产 SLA）——与上表"多实例"同批处理 | 显式登记为已知盲区；单机形态下由"面板不可达"这一外部信号兜底 |
| 告警生产者未纳入 CI（台账 §7 判定为合理边界） | CI 引入常驻服务栈（compose 进 CI）时 | 纯函数级回归锁 23 用例已在 CI 内，覆盖判定 / 闸门 / 鉴权 / 容错 |
| 文档数字陈旧（README/OPS/DEMO 引用的数字与随附产物分叉） | **已收敛（2026-09-21）**：`offline/doc_numbers.py` 按 `doc_numbers.json` 每次**现算**产物真值比对文档字面量，已进 CI 的 `provenance` job。立项依据是实测：README 写「121 用例」而 `@Test` 声明与 surefire 执行数**同为 131**——面一（报告↔语料）上线后同一缺陷类又发生了一次而无人看见。**已知边界**：live 实测时长（如重建 22.3s）与并发实测值含波动、无法确定性派生，刻意不登记，仍属人工纪律 | 面一 `offline/provenance.py` 管"报告↔语料"；面二管"文档↔产物"（术语见 `CONTEXT.md` 评测域） |
| 真增量 Ingest 的阈值口径（ADR-0006 曾写"语料上千"） | **已收敛（2026-09-17）**：以本表"真增量 Ingest"行为准（chunks > 2000 **或** live 全量 > 10min）；ADR-0006 已加注指向本表 | 与上表同项，不再单列 |
| 单测偶发红：`ChatOrchestratorTest` 的审计断言（2026-09-20 CI 一次，2026-09-27 CI 又一次——换了用例名 `verbatimDumpFromLlmIsMaskedAtExitAndCacheStaysClean`） | **已收敛（2026-09-27）**——触发线兑现：第二次出现即定位真实根因并修掉，不再挂起 | 真实根因**不是**"审计没写"，而是**竞态**：编排里 `sink.done()` **先于** `audit.log()`（三条出口路径皆然），而 `awaitSuccess` 只等到 sink 的 latch（由 `done()` 落下）⇒"等 sink 收尾 → verify 审计"必然可能输给调度。2026-09-20 那次只修了**误导性报错**（先断言 `sink.error` 为空再 verify），**没修竞态本身**，所以它必然会再来。**修法**：审计断言改用 `verify(audit, timeout(2s))` 轮询等待交互。**本机复现 + 变异验证**：在 `done→audit` 之间插 400ms 延时后，不轮询 → 3 个用例红且消息与 CI 一字不差；改轮询后同一延时下 9/9 绿 |
| 接地判据 V9 的覆盖边界（只管错误码，不管步骤编号/配置名/服务名的无据断言） | 出现一次"答案里**非错误码**的断言无据"且被真人/QA 复现——判据：该事故落在 V9 词法盲区（`pm-*`/`rb-*` 步骤编号、配置项名等），即现有判据结构上抓不到 | 已覆盖错误码：它是精确符号、可做集合运算、无"有据与否"的解释歧义。步骤编号/配置名要纳入，须先给出"有据"的**可判读**定义，否则会引入误报噪音、把门闩变成噪声源（本表纪律：不可判读的触发线等于没有）。**引用正确率**（标号是否真的支撑该句）另属一类，需异构裁判模型或人工标注，不并入本项——判据与裁判同源则无证据价值 |
| 引用洗白 / 出口引用-内容对齐校验（来源：`docs/qa/2026-09-11-persona-eval.md` P1-5，标"第五轮未复现，待复现再立项"） | 再次出现"答案的 `[参考N]` 标号与所指段落**并不支撑**该句断言"且被真人/QA 复现——判据：能指到具体某条答案的某个标号与它引的段落不匹配（不是"答案整体偏了"，那类归 V9 与门控） | **此前不在任何账上**（2026-09-27 复核时查出：QA 台账标了"待复现"，但本表没有对应行——正是本表纪律所指的"没有触发线的欠账"）。第五轮之后未再复现，故按触发线挂起而非现在做：该判据要判"标号是否支撑句子"，**判据本身需要裁判**，与 V9 的集合运算不是一类；且裁判与选手同源则无证据价值（需异构模型或人工标注小集），成本远高于 V9 |

| Console 档位切换历史面板（**2026-09-28 定形**：只做离线时间线 + 录屏） | 需要在**无录屏场合**按时间回放档位切换——判据：有人要在现场/无录像环境复盘"切换发生在第几秒"，而看实时面板已经不够 | 面板已有实时档位灯 + 审计事件游标流，实验与演示现场的变化由录屏捕获；档位切换的**权威历史**是 14 天滚动的 `logs/audit.jsonl`（`ev=degrade_transition` 行，见 `CONTEXT.md` 降级域）。面板 ring 仅 200 条（`AuditService.RING_CAP`），在高并发那一刻**必然轮转**——用它做历史是选错量具。本项属 ADR-0009 只读面的扩展，届时须连 `scripts/check_panel_contract.sh` 一并扩 |
| 解析 W3C `traceparent`（OpenTelemetry 生态） | 出现**真实的 OTel 消费方**——判据：有调用方发 `traceparent` 且要求按它串链路，或需要跨服务传播 trace | 现只认自述型 `X-Trace-Id`（8–64 位词表，校验成本 ≈ 0）。W3C 格式固定 55 字符、含版本/标志位，多一层解析与校验分支而当前**无任何消费方**——按"不预防性施工"挂起 |

## 6. 公开 push 门闩（已执行记录：2026-09-10 清洗并首推 private）

**已了结**：历史 token 残留已于首推前用 `git filter-repo --invert-paths --path scripts/demo_tokens.txt` 抹除（验证 `git log --all -S "eyJhbGci"` 空），仓库以 **private** 推至 `github.com/shing26/opspilot-aiops`。**转公开前动作**：人工过一遍 README/ADR/报告渲染（本项目文档惯例是数字必须真），确认后用 `gh repo edit --visibility public` 切换。以下命令保留作再犯时的标准程序。

> **复核口径注（2026-09-13 转公开前门闩）**：自本节把验证串写进正文起，上述 `-S` 命令会命中本文件自身（命中物是命令示例文本，非凭据）。再复核改用：`git grep "eyJhbGci[A-Za-z0-9_-]\{40,\}" $(git rev-list --all)` ——期望**空输出**（真凭据必带长签名段；本行示例含字符类，不满足该模式故不自匹配）。

**背景**：`scripts/demo_tokens.txt`（合法 demo token）自初始提交 `75d0aea` 起被跟踪、`fc8317f` 摘除。这些 token 在现网**已实战失效**——P2 起过滤器要求 `tver` claim 匹配 DB，旧 token 无此 claim 一律 401——但公开仓库里躺着"形似有效凭据"的字符串本身就是钓鱼素材与审计事故，物理抹除是观感与合规要求，不只是风控要求。

```bash
# ① 本地 bundle 备份（此时尚无 remote；操作不可逆，先备份）
git bundle create ../opspilot-backup-$(date +%F).bundle --all

# ② 一次性安装
pip install git-filter-repo

# ③ 从全部历史外科手术式移除该文件（token 只存在于此文件，无需 replace-text）
git filter-repo --invert-paths --path scripts/demo_tokens.txt

# ④ 验证归零（必须无输出；注意 filter-repo 重写全部 hash，v1.0.0/v1.1.0 tag 会重指引，属预期）
git log --all -S "sre_l1=" -- scripts/demo_tokens.txt

# ⑤ 首次推送正常 push 即可（清洗先于任何 push，因此不存在 force 场景）。
#    filter-repo 会移除 remote 记录：重新 git remote add 后再推。
```

> 若已有他人 clone（本文件编写时尚无）：通知全员**重新 clone**，禁止在旧历史上继续 merge。

## 7. OpenAI 兼容面（/v1）

**用途定位**：/v1 是网关的标准协议出口（ADR-0007），价值在"任何标准客户端可直连"的兼容性证明与
"两协议面共享一条编排链路"的架构叙事——**它不依赖任何特定前端**。演示主形态是 curl 直播
（DEMO 幕⑦）；LobeChat/Dify 等如临时起意想点验兼容性，按下面连接配置自行接入即可（本仓库不再捆绑 UI 容器，2026-09-11 评估：UI 壳仅呈现"会答对的对话框"，与"运维系统可视化"诉求零交集，撤除止损）。

**API Key = 用户 JWT**（不是共享密钥）：

```bash
set -a && . ./.env && set +a    # DEMO_PASSWORD 只从 .env 来，缺这行必报 traceback
cd offline && .venv/Scripts/python.exe -c "import sys;sys.path.insert(0,'.');import localapi;print(localapi.login('sre-full'))"
```

**curl 直播**：`curl -N http://localhost:8081/v1/chat/completions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"model":"opspilot","stream":true,"messages":[{"role":"user","content":"how to fix 50012_DB_TIMEOUT"}]}'` → role 首帧 → content 增量 → 「## 参考来源」→ stop → `[DONE]`。浏览器型客户端 Base URL 填 `http://localhost:8081/v1`（宿主视角，容器服务名仅对 server 模式有意义）。

**故障排查**：401→token 过期或账号被禁用（重新 login；这正是吊销跨面生效的表现，其错误体为 OpenAI 标准 error JSON——QA P2-3 修复后不再回显 path）；连接被拒→`docker compose ps gateway`；无流式→看 `/api/v1/admin/metrics`（平台凭证）的 `reingest_busy` 与 audit `via=openai` 行；CORS→确认环回 origin（CorsConfig 仅放行 localhost/127.0.0.1 任意端口 + compose 内 `gateway:*`）。

**audit `via` 字段**：`sse`（原生面）/`openai`（/v1 面）/`search-api`（检索端点）；旧日志行无此字段，`daily_usage.py` 按 `.get()` 解析天然兼容。按面统计：`grep -c '"via":"openai"' logs/audit.jsonl`。

**调用方关联键 `X-Trace-Id`（2026-09-28）**：任何面都可带此请求头——8–64 位 `[A-Za-z0-9_-]`，**空白按未提供**处理，**非法即 400** 并落 `ev=invalid` 审计行（形状按面分流：`/v1` 保持 OpenAI error 信封）。它经 MDC 落到审计行 `trace_id`，用途是把上层 agent 的多步调用串成一次 incident——**只做关联，不改检索、不注入上下文、不存会话**（本系统无状态单轮，见 ADR-0007 修订注 / ADR-0013）。两个 id 各管一段，别混：

```bash
grep '"request_id":"a1b2c3d4"' logs/audit.jsonl      # 单次请求回查（服务端自生 8 位，服务端权威）
grep '"trace_id":"agent-run-7f3a"' logs/audit.jsonl   # 跨步链路（调用方给的，串一次 incident）
```

**档位切换复核**（不复盘不宣称）：档位每变一次落 `ev=degrade_transition`（`from`/`to`/`cause` 三字段，`cause` 有限词表见 `CONTEXT.md` 降级域），审计业务行另有 `degrade_level` 列标明该请求所处档位。**`mode=es_only` 不能单独当降级证据**——那个字符串有两个来源（L1 降级 / 检索腿超时）：

```bash
python scripts/audit_timeline.py          # 时间线：切换序列 + cause 词表校验 + 链式连续性
grep '"ev":"degrade_transition"' logs/audit.jsonl
grep '"cause":"inflight"\|"cause":"llm_failure"' logs/audit.jsonl   # 只看负载/上游导致的
# L1 的机器判据：mode=es_only **且** stage_ms.vector==0（单看 mode 会把"腿超时"读成"降级生效"）
grep '"degrade_level":"L1"' logs/audit.jsonl | grep '"mode":"es_only"' | grep '"vector":0'
```

**2026-09-28 实测结果（本轮首次真正触发）**：`offline/load/reports/l1_trigger.md`——唯一指纹场景 48 并发 30s，在途峰值 **137**（触发线 40），审计落 `L0→L1 cause=inflight`；同轮上游对 48 并发返回 429，再落 `L1→L2 cause=llm_failure`；60s 冷却到期回 `L0 cause=cooldown_expired`。**两条复现配方**（都要活体栈 + live key）：

```bash
# ① L1：唯一指纹场景（nonce 必须**纯字母**——数字/UUID 会被指纹归一化掩码掉，那样所有请求同指纹、
#    被 Single-Flight 折成一个 leader，在途永远到不了触发线）
cd offline && python load/sweep.py --levels 48 --duration 30s --scenario unique --report l1_trigger
# ② L2：可控失败注入（`LLM_MODEL` 覆写 → 不存在的模型名 → 上游 404 → 连续失败达阈）
#    **必须**先清缓存，否则近似 query 会被 L2 语义缓存回放掉、根本不走到 LLM（实测踩过：failures 卡在 2）
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/admin/cache/flush
LLM_MODEL=__opspilot_probe_invalid__ java -jar target/opspilot-gateway-1.0.0.jar
# ③ 回滚闸门（在 ①②之间必做）：等 /state 的 cooldown_s==0 且 level=="L0" 再起压，
#    否则残留熔断态会让压测的 worst 直接读到 L2、L1 实验作废
```

**能力边界（勿对外宣传）**：/v1 目前只有 `chat/completions`（stream=true）与 `models`；文件上传/语音/多模态对应端点未实现，任何客户端里点了即报错——协议面的演示只用文本对话。**归因纪律**：外部客户端出现"响应已返回但界面异常"时，先 `tail logs/audit.jsonl` 定位归属（有行且正常=该客户端渲染层的事），再排查——2026-09-11 在嵌入 webview 实测过此判据。

**环境注记（历史踩坑，若重接 LobeChat 会用到）**：`lobehub/lobe-chat` 在 `mem_limit:512m` 下 node 堆 ~256M、pdfjs 初始化 OOM 崩溃循环，需 2g；Windows Git Bash 的 `curl -d` 发中文按 GBK 出局（客户端 locale 陷阱），脚本发中文一律走 python。

## 8. 备份与恢复（只备份不可再生的东西）

| 数据 | 性质 | 策略 |
|---|---|---|
| H2 `./data/users.mv.db`（口令散列 + token_ver 吊销状态） | **唯一不可再生** | `scripts/backup.sh` 每日（网关活着走 `POST /admin/backup`、DB 所有者在线 `BACKUP TO` 事务一致；网关停了走 CLI 嵌入式。**禁止 cp 热拷运行中的库文件**） |
| `logs/audit.jsonl` | 合规留痕，logback 14 天滚动会回收 | backup.sh 一并 tar（保 14 天） |
| ES / Qdrant | **派生索引，不备份**——chunks.jsonl 在 git（ADR-0001），恢复=reingest（实测 423 chunks 22.3s，live、服务端日志口径；mock 8.0s） | 无需动作 |

cron（Linux 部署）：

```cron
0 2 * * * cd /opt/opspilot && bash scripts/backup.sh >> logs/backup.cron.log 2>&1
```

（Windows 本机：任务计划程序调 `C:\Program Files\Git\bin\bash.exe -lc "cd /d/OpsPilot\ —\ AIOps && bash scripts/backup.sh"`，或想起来手敲一行——个人项目别把自动化做成负担。）

**恢复流程（H2 2.x Restore 要求目标库不存在=天然防误覆盖）**：

```bash
# 容器模式（compose full；./data 卷映射下宿主 CLI 与容器共用同一物理库文件，网关必须真停以让出文件锁）
docker compose stop gateway
mv data/users.mv.db data/users.broken-$(date +%F)            # 移走损坏库（留取证现场）
bash scripts/user_admin.sh restore --from backup/users-<日期>.zip
docker compose start gateway
# 裸进程模式同理：停宿主 java 进程 → mv → restore → 重启 jar
# 起服务后验证：/api/v1/admin/health 正常 + login 成功
```

> QA P2-8 注：本节此前通篇裸机命令（`Get-Process java | Stop-Process`），容器化部署照抄必炸且
> 与 §1 的容器声明自相矛盾——恢复前先确认自己跑的是哪种模式，停错对象等于没停。

**恢复完整性演练（不需要等灾难，网关可不停）**：`bash scripts/user_admin.sh restore --from backup/users-<今天>.zip --to-db restore-drill` 恢复到演练库后加 `--to-db` 同款连接串 `list`，应见全部账号与各自的 token_ver（如 sre-limited ver=7）；验证后删 `data/restore-drill.*`。本仓库已实测通过（commit 记录在案）。

**网络注**：AUTO_SERVER 走 Windows 防火墙常拦（LAN IP+随机端口），CLI 已内建「嵌入式优先、AUTO_SERVER 兜底」双路；两个都不通时报错会指路本节。Windows Git Bash 里跑 `docker compose exec gateway ls /app/backup` 这类容器内绝对路径命令需前置 `MSYS_NO_PATHCONV=1`（否则 MSYS 会把 /app/... 改写成宿主路径）。

## 9. Ops Console 运维面板（只读可观测面，ADR-0009）

**它是什么**：浏览器开 `http://localhost:8081/` 即得（Boot 默认资源处理器同源 serve，**零构建、零 CDN、无外部依赖**）。把已有的 `/admin/state` 快照与 `/admin/audit/recent` 事件流渲染成五块：「运行状态 / 降级状态机 / 数据流 / 能力边界 / AIOps 谱系定位」（分区数以 `index.html` 的 `<h2>` 为准，README「Ops Console」节同源描述）。**它是 LobeChat 撤壳的反面答案**：撤的是聊天壳（承载不了运维真相），建的是仪表盘（把 JSON 真相摆出来）——不新增状态源、不承载对话交互。

**接入**：面板需 **role=platform** 的 JWT（数据端点全部受守卫；HTML 壳本身匿名可开、零信息）。取 token：

```bash
set -a && . ./.env && set +a    # DEMO_PASSWORD 只从 .env 来
cd offline && .venv/Scripts/python.exe -c "import sys;sys.path.insert(0,'.');import localapi;print(localapi.login('sre-full'))"
```

粘进面板凭证框→localStorage 持久（仅存本浏览器，不入库；清理=面板右上退出或 `localStorage.removeItem('opspilot.jwt')`）。

**三态排障**（文案各不同，别互窜）：
- 401 → token 过期/无效，重新 login（面板降饱和 + 提示"OPS §9"）。
- 403 → 该 token 账号**不是 platform 角色**（密级高≠可信，见 ADR-0008）；sre-acme 是 level 3 但 role=sre，正属此态，不是 bug。换 sre-full。
- 网关不可达 → 面板降频轮询并显"网关不可达"，先 `docker compose ps gateway`。

**读它的关键位（演示底幕）**：
- 顶栏 build 指纹（version·jvm·uptime·pid）——`build-info` goal 注入，传达"这是被部署过的服务"。
- 「降级状态机」区：L0/L1/L2 灯 + 熔断 `K/3` + 冷却倒计时 + `MANUAL LOCK` 徽标（区分手动锁 vs 自动熔断）。幕⑥指着它讲。
- 「数据流」区：`Single-Flight 在途组` + 计数墙（相邻两次真值求差显示 `+n`，速率是算出来的、非动画）。幕④风暴瞬间指"在途组跳 1、收敛墙爬、LLM 只 +1"。
- 配额水位条：当日 `used/limit`（读 `quota:<sub>:<date>`，**只 GET 不 INCR**——打开面板看不消耗配额）。

**契约防漂移**（改名即红，两道闸）：`scripts/check_panel_contract.sh`（CI 独立 job，零服务）比对 HTML fetch 路径↔Controller `@GetMapping`、OpsMetrics 键↔面板 tiles、`/state` 键集双向；`demo.sh` 第 7 检是 live 键集合断言。Java 侧改任一被面板消费的键而不同步 HTML，CI 点名 `面板引用了后端不存在的键`。

**运维预期**：面板是进程内 ring buffer（最近 200 条审计事件，重启清零）+ 游标轮询（前台 2s / 标签页隐藏自动降 30s）——**成功读路径不落审计**（实测轮询 60s，audit.jsonl 行增量 0），所以面板自身不会污染它展示的证据流；事件缓冲轮转/服务重启导致的缺口以 `truncated=true` 显式标 ⚠️，绝不做连续假象。轮询周期是前端常量 `cadence()`，调它不改后端。

## 10. 中间件异常后的恢复动作（2026-09-17 实测）

Docker Desktop 重启（升级/崩溃自恢复）会给本机留下两类**看起来正常、实际不可用**的坑，
两者都不报错、都让"容器健康"与"服务可用"背离，各有一条固定恢复动作：

| 症状 | 判据（怎么确认不是应用 bug） | 恢复动作 |
| --- | --- | --- |
| 宿主端口代理失效 | 容器内 `redis-cli ping` 通、`docker exec … curl localhost:9200` 通，但**宿主**连 `127.0.0.1:6379/9200/6334` 被接受后零字节返回（裸 socket `PING` 收到 `b''`）；网关启动报 `RedisTimeoutException: Command execution timeout for command: (AUTH)` | `docker compose restart <service>`（实测 redis / elasticsearch / qdrant 三个都需各自重启一次；`restart` 会重建端口映射） |
| 长跑网关的中间件连接不自愈 | 中间件起来之后网关仍 `health=DOWN`、检索返回空、**登录 500**（根因是登录路径要写 Redis 的 `auth:fail` 计数器，连接已失效 → `WriteRedisConnectionException`），实测持续 3 分钟以上未自愈 | 重启网关进程（客户端连接重建）；数据在命名卷里，重启后核对 `health.es.value` / `health.qdrant.value` 是否仍等于语料数即可确认无损 |

**顺序**：先修端口代理（`restart` 中间件）→ 确认宿主侧协议可达 → **再**重启网关。
反过来做会得到"起来了但仍然 DOWN"的假象（客户端在端口还是坏的时候完成初始化）。

**注**：登录 500 这个形态曾经把人往"账号库坏了"上带——判别法是看 `logs/app.log` 里那条异常的类名：
`WriteRedisConnectionException` 指向 Redis 连接，不是 `JdbcSQLNonTransientConnectionException`（那才是账号库）。

## 11. SLI/SLO 承诺表（承诺线 + 多久核一次、怎么核）

> 与 README「核心指标（实测）」的分工：README 给访客看**实测值**，本表给管理员**承诺线与核色动作**。
> 表里凡可从库内产物确定性派生的数字，由 `offline/doc_numbers.py` 在 CI 守着（改表不改产物即红）；
> live 实测类（TTFT / 可用性 / 幻觉率）含波动，按既有纪律**不登记**，靠人工按频次核色。

| SLI | SLO（承诺线） | 实测 | 核色方式与频次 |
| --- | --- | --- | --- |
| 精确符号 Top-1 命中率 | 100% | **100%** | 每次动语料或换 embedding 后端：`cd offline && python eval/build_golden.py && python eval/evaluate.py`，看报告 `modes.hybrid.exact.hit@1` |
| 语义 Top-3 召回率 | 100% | **100%** | 同上，看 `modes.hybrid.semantic.hit@3` |
| 权限泄漏率 | 0 | **0** | 每次动权限链（filter / 租户 / 密级）：跑 `evaluate.py` 的 jailbreak 段，看 `jailbreak.leaked` |
| 幻觉率（错误码接地） | 0 | 待 live 补 | 每次 live 回归：`cd offline && python qa_gen_quality_probes.py V9`（复用 V2/V4 答案，零额外 chat 预算） |
| 降级期失败率 | 0 | 待 live 补 | 每次压测：`offline/load/sweep.py` 六档，看各档 Failure Count |
| TTFT P95 | < 3s | 待 live 补 | 同上；热点命中口径另见 `offline/load/reports/l1_hit_latency.md` |
| 可用性 | ≥ 99.5% | 待 live 补 | 同上（失败请求数 / 总请求数） |

**核色前置（每次核色前先跑，不通过就别开始）**：

```bash
set -a; . ./.env; set +a          # 让 DASHSCOPE_API_KEY 进入环境
python scripts/check_upstream.py  # 三路（LLM / embedding / rerank）全通过才 exit 0
```

**为什么这一步不是可选自检**：本系统的降级设计会**静默掩盖**上游故障，两条路径都返回 HTTP 200
且都不报错——embedding 断 → 向量腿 `degraded` → 检索退化为 `es_only`；LLM 连续失败达阈 → 熔断 L2
→ 全部请求直出静态 SOP。**只有 `mode` 字段才看得出**。于是核色会在一个已经降级的系统上跑完，
产出一整套看似正常、实则无效的数字。2026-09-24 真踩过：`cache_savings` 首跑报出"命中率 0.0%"，
看着像"缓存无效"，实际是 26 条请求全走了 L2 SOP 兜底（该路径按设计不写 L1）——该报告已作废。
账户欠费时三路返回 `code=Arrearage`，脚本会点名到该 code 并以非 0 退出。

**已有随附产物的 SLI**（核色时直接看产物，不靠记忆）：门控的误拒/漏拒率与阈值扫描 → `offline/eval/reports/gate_matrix.md`；真实输入上的零召回率与快路径命中率 → `offline/eval/reports/observed_probe.md`；热点命中延迟 → `offline/load/reports/l1_hit_latency.md`。表内"待 live 补"的几项属**生成面**指标，三路探活通过后按右侧命令补跑。

**为什么是这几条**：每条各对应一条核心主张——检索得准、不越权、不编造、过载不拒服务、快。
刻意**不承诺"平均延迟"**这类聚合量：聚合会把长尾藏起来，而排障场景的体验恰恰由长尾决定。

**降级期的核色口径**（见 [ADR-0012](docs/adr/0012-degradation-cost-semantics.md)）：L1 期的检索质量按
`es_only` 那列核（语义 Hit@1 由 88% 退到 64%，同构关系有单测锁定），且门控退为**零召回级**——
故"幻觉率 = 0"这条在 L1 期的保障强度是弱化的，核色时必须注明当时档位，否则数字会被读成 L0 口径。
