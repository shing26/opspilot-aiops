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
> `docker compose exec gateway java -cp app.jar -Dloader.main=com.opspilot.auth.UserAdminCli org.springframework.boot.loader.launch.PropertiesLauncher <同参数>`；备份 zip 落在容器卷 `./data`↔`/app/data` 映射内，宿主可见。

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

## 8. LobeChat 前端与 OpenAI 面（/v1）

**启动**：`docker compose --profile full --profile ui up -d`（gateway 容器模式；宿主裸进程模式则只起 ui profile 的 lobe-chat，base URL 仍指 localhost:8081）。访问 `http://localhost:3210`，ACCESS_CODE 见 `.env` 的 `LOBE_ACCESS_CODE`。

**连接配置（关键）**：LobeChat 浏览器端直接 fetch 上游——**Base URL 填 `http://localhost:8081/v1`**（宿主视角），不是容器服务名（服务名 URL 仅对其 server 模式有意义，浏览器解析不了）。API Key 填**用户自己 login 的 JWT**（不是共享密钥）：

```bash
cd offline && .venv/Scripts/python.exe -c "import sys;sys.path.insert(0,'.');import localapi;print(localapi.login('sre-full'))"
```

**故障排查**：401→token 过期或账号被禁用（重新 login；这正是吊销跨面生效的表现）；连接被拒→`docker compose ps gateway` 或宿主网关进程检查；无流式→看 `/api/v1/admin/metrics` 的 `reingest_busy`（重灌窗口 live 依赖慢）与 audit `via=openai` 行；CORS 报错→确认访问的是环回 origin（CorsConfig 只放行 localhost/127.0.0.1 任意端口）。

**audit `via` 字段**：`sse`（原生面）/`openai`（/v1 面）/`search-api`（检索端点）；早于该版本的旧日志行无此字段，`daily_usage.py` 按字典 `.get()` 解析天然兼容。想按调用面统计时：`grep -c '"via":"openai"' logs/audit.jsonl`。

**能力边界（勿对外宣传）**：文件上传/语音/多模态依赖未实现的 embeddings/audio 端点，LobeChat 里点了会报错——演示只用文本对话。

**环境注记（已修复的踩坑史）**：`lobehub/lobe-chat` 初起崩溃循环根因是 **compose `mem_limit:512m` 下 node 堆 ~256M、pdfjs 初始化 OOM**（Next.js 自身 Ready 正常）——调到 2g 后稳定（Windows Docker Desktop 实测可用，无需 Linux）。另外 Windows Git Bash 的 `curl -d` 发中文按 GBK 出局（客户端 locale 陷阱），浏览器端 fetch 无此问题。

**UI 集成实测结论（2026-09-11）**：设置页填 JWT + 代理地址后真实提问，网关 audit 确认全链路穿透（`via:openai`、租户/密级/生成/审计全部正常）。若遇到"回答已返回但气泡不渲染"（本会话在 IAB webview 中观察到）：优先换**真实 Chrome** 打开 `localhost:3210` 验证，或在 provider 设置里关闭「客户端请求模式」改走 LobeChat 服务端代理。用户消息正常、后端日志正常时，问题在渲染层而非集成层——先用 audit 定位归属再排查。

## 7. 备份与恢复（只备份不可再生的东西）

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
powershell 'Get-Process java | Stop-Process -Force'          # 停网关
mv data/users.mv.db data/users.broken-$(date +%F)            # 移走损坏库（留取证现场）
bash scripts/user_admin.sh restore --from backup/users-<日期>.zip
# 起服务后验证：/api/v1/admin/metrics 正常 + login 成功
```

**恢复完整性演练（不需要等灾难，网关可不停）**：`bash scripts/user_admin.sh restore --from backup/users-<今天>.zip --to-db restore-drill` 恢复到演练库后加 `--to-db` 同款连接串 `list`，应见全部账号与各自的 token_ver（如 sre-limited ver=7）；验证后删 `data/restore-drill.*`。本仓库已实测通过（commit 记录在案）。

**网络注**：AUTO_SERVER 走 Windows 防火墙常拦（LAN IP+随机端口），CLI 已内建「嵌入式优先、AUTO_SERVER 兜底」双路；两个都不通时报错会指路本节。
