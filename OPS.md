# OpsPilot 运维速查（OPS）

> 读者 = 管理员。演示话术在 [DEMO.md](DEMO.md)，架构叙事在 README/ADR——本文只放**日常要做的事**。

## 1. 账号生命周期（不再手工签 token）

所有用户操作走 CLI（需服务同机可访问 `./data/`，与运行中网关经 H2 AUTO_SERVER 并发无冲突）：

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

- 语料改动 → `cd offline && .venv/Scripts/python chunkers/build_chunks.py` → `POST /api/v1/admin/reingest`（level≥3；busy 时 409，结果看 `/metrics` 的 `reingest_busy/reingest_last`）。零空窗，白天可操作（实测 27s，live+账户限流最坏 ~6min）。
- `scripts/gen_tokens.py` **是红队畸形 token 签发器**（A2-8b/8c 与 demo.sh 预检依赖）——不是遗留脚本，勿删。
- 旧 `demo_tokens.txt` 体系已死；合法凭据一律 login 换取。

| 债务 | 触发线（到点必须做，未触发不做） | 当下缓解 |
|---|---|---|
| 真增量 Ingest | chunks > 2000 或 live 全量重灌 > 10min（`reingest_last` 时间戳可测） | blue/green 原子切流支撑每日任意时刻全量重建；`refuse_rate` 为前置健康信号 |
| 中间件 TLS | 过等保/ISO 审查，或中间件跨机/跨 VPC 部署 | 凭据认证 + 127.0.0.1 环回绑定（P3 已落） |
| 多实例 | 团队 >200 人或持续峰值 QPS > 50 | 单实例虚拟线程（实测 500 并发收敛）；先调 JVM 堆压榨单机 |
| 外部 IdP | 公司强推统一 SSO / 禁用自建口令 | H2+bcrypt 结构下加 `/auth/login/oidc` 映射入口即可，不侵入校验链 |
| 断言语态结构化门控 | 任何一次**被真人/QA 复现、且落在探针正则覆盖之外**的断言式事故编号回答（防断言语态现为软约束层：prompt 规则 6 + 正则探针，上限=探针选词，见 ADR-0010） | 立项输出结构化门控：模型按标注输出置信、渲染层依标注 gating，不再依赖句式正则；泛化症状探针在 `offline/qa_gen_quality_probes.py` 锁最恶劣形态 |

## 6. 公开 push 门闩（已执行记录：2026-09-10 清洗并首推 private）

**已了结**：历史 token 残留已于首推前用 `git filter-repo --invert-paths --path scripts/demo_tokens.txt` 抹除（验证 `git log --all -S "eyJhbGci"` 空），仓库以 **private** 推至 `github.com/shing26/opspilot-aiops`。**转公开前动作**：人工过一遍 README/ADR/报告渲染（本项目文档惯例是数字必须真），确认后用 `gh repo edit --visibility public` 切换。以下命令保留作再犯时的标准程序。

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

**能力边界（勿对外宣传）**：/v1 目前只有 `chat/completions`（stream=true）与 `models`；文件上传/语音/多模态对应端点未实现，任何客户端里点了即报错——协议面的演示只用文本对话。**归因纪律**：外部客户端出现"响应已返回但界面异常"时，先 `tail logs/audit.jsonl` 定位归属（有行且正常=该客户端渲染层的事），再排查——2026-09-11 在嵌入 webview 实测过此判据。

**环境注记（历史踩坑，若重接 LobeChat 会用到）**：`lobehub/lobe-chat` 在 `mem_limit:512m` 下 node 堆 ~256M、pdfjs 初始化 OOM 崩溃循环，需 2g；Windows Git Bash 的 `curl -d` 发中文按 GBK 出局（客户端 locale 陷阱），脚本发中文一律走 python。

## 8. 备份与恢复（只备份不可再生的东西）

| 数据 | 性质 | 策略 |
|---|---|---|
| H2 `./data/users.mv.db`（口令散列 + token_ver 吊销状态） | **唯一不可再生** | `scripts/backup.sh` 每日（网关活着走 `POST /admin/backup`、DB 所有者在线 `BACKUP TO` 事务一致；网关停了走 CLI 嵌入式。**禁止 cp 热拷运行中的库文件**） |
| `logs/audit.jsonl` | 合规留痕，logback 14 天滚动会回收 | backup.sh 一并 tar（保 14 天） |
| ES / Qdrant | **派生索引，不备份**——chunks.jsonl 在 git（ADR-0001），恢复=reingest 36s | 无需动作 |

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

**它是什么**：浏览器开 `http://localhost:8081/` 即得（Boot 默认资源处理器同源 serve，**零构建、零 CDN、无外部依赖**）。把已有的 `/admin/state` 快照与 `/admin/audit/recent` 事件流渲染成「运行状态 / 数据流 / 能力边界」三块。**它是 LobeChat 撤壳的反面答案**：撤的是聊天壳（承载不了运维真相），建的是仪表盘（把 JSON 真相摆出来）——不新增状态源、不承载对话交互。

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
