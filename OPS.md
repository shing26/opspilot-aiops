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
