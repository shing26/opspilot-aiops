# Persona 测评缺陷台账 — 2026-09-11

> **状态：已修复并回归锁死（2026-09-11 晚，见 §7），公开禁令解除。**
> 本文件保留"缺陷发现时点"的原始口径作为红队叙事证据；修复后各条状态已回填，
> 与 README「QA 红队加固记录·第四轮」互为指针。教训升格见 ADR-0008。
> **本文件刻意不进 `offline/corpus/`**：语料会被检索进答案，漏洞细节入语料 = 给 L1 用户
> 提供攻击手册；且复现 query 与评测集同形，会污染 `evaluate.py` 指标（51xxx 命名空间纪律同源）。

## 0. 测评方法

三个 Persona 黑盒并行（各 ≤40 请求，全程只读红线）：
- **小周**（慌乱的初级值班工程师）：口语化/错拼/超长粘贴/域外/幻觉诱导/多轮幻想，23 请求；
- **红队审计员**：租户×密级越权矩阵、畸形 token×4 面、JWT 篡改/重签、prompt 注入、backup 注入、CORS 探测；
- **老陈**（接盘运维 + LobeChat 试用者）：文档命令逐字可复制性、admin 面可观测性、UI 配置层验证。

所有 P0/P1 结论均回源码复核（file:line 见各条），非报告一面之词。

**测评事故披露**：红队按简报预期 `sre-acme` 访问 admin 应 403，实测 200 → `/admin/reingest`
与 `/admin/cache/flush` 被真实执行（acme 在 seed 中即 L3，见 P0-2）。事后核验无损：
blue/green 零空窗切流生效，ES 251 docs / Qdrant 251 pts / live_mode:true。该误触本身构成 P0-2 实战证据。

## 1. P0 — 租户隔离叙事破防（修复前 README 相关承诺为失实）

### P0-1 Single-Flight 合并键缺 tenant → 跨租户全文+引用泄露（竞态路径）
- **根因**：`ChatOrchestrator.java:108` `sfKey = fp + ":" + user.authLevel()`。全仓五条跨请求
  共享路径（ES filter / Qdrant filter / L1 key / L2 payload / SOP key）均带 tenant，**唯独此处没有**。
- **复现**：sre-full 与 sre-acme 同 query 并发打 `/api/v1/copilot/chat/stream`（leader 生成窗口
  4-20s 内 follower 到达）→ acme 侧 `deduplicated:true`，收到 tenant-internal 的 pm-008 复盘全文
  + chunkId/面包屑/service 引用。串行路径拒答话术逐字正确 → **串行对、竞态破**。
- **放大条件**：产品卖点"告警风暴 500 并发收敛"下，同错误码同密级跨租户并发是设计目标场景。
- **修复**：sfKey 掺 `user.tenantId()`；`SingleFlightRegistry` Javadoc 的 key 口径同步改；
  并发回归测试断言 follower 收不到 leader refs 且 storm 场景 llm_calls 仍=1。
- 状态：`已修复` ｜ commit 07f2aaf ｜ 回归锁：ChatOrchestratorTest×4 + A2-9 跨租户并发用例 + live V2 原命令重跑 PASS（acme refs=[]、dedup=False、guard=0）

### P0-2 admin 门禁只认 auth_level≥3，无 role 无租户维度
- **根因**：`AdminController.java:52-54` 仅 `u.authLevel() < 3`；`UserContext.role` 从未参与授权；
  `seed_demo_users.sh:14` 将外来租户 sre-acme 种为 L3（口径易误读本身也是问题）。
- **后果**：任何租户的高密级用户可重建共享索引（reingest）、清空全局缓存（cache/flush）、
  读基础设施拓扑（health）——跨租户可用性破坏；README"杜绝匿名 DoS"字面成立、结论被击穿。
- **修复**：破坏性端点（reingest/cache-flush/backup/degrade）走 `requirePlatformAdmin`
  （role=platform ∧ level≥3 ∧ tenant ∈ 平台租户白名单）；只读端点按租户收敛；seed 中 acme 降为
  L1 或在 OPS.md 明确"customer-admin 不得进 L3"；补"跨租户同密级→403"回归。
- 状态：`已修复` ｜ commit 07f2aaf ｜ 实现按 ADR-0008 收敛为 **role 单真相**（未加平台租户白名单配置，避免 DB/配置双源）：6 端点全 platform-only；回归锁 AdminControllerTest 18 格矩阵 + live V3 0 失败；seed 中 acme 保持 L3/sre 作矩阵靶（口径注释已写明）

## 2. P1

### P1-1 `/admin/metrics` 无鉴权（签名不接 request）
`AdminController.java:69`。任意有效 JWT（含 L1）可读全局计数、`dashscope:` 模型全名、reingest 状态。
修复：纳入 requireAdmin/租户内视图。**状态：`已修复`（commit 07f2aaf，metrics 并入平台门禁；租户内视图按 ADR-0008 待办留后续）**

### P1-2 审计盲区：拒绝路径零留痕 + 无"命中来源"字段
实测 401×20、403×6、登录失败×8、reingest/flush/backup 全部不落 audit.jsonl（`ev` 仅 chat/search）；
P0-1 泄露记录中无 `src_tenant`，**这类事故事后不可发现**。README"每请求一行/含命中来源"两处失实。
修复：JwtAuthFilter 拒绝分支落 `ev=auth,outcome=denied`；admin 拒绝/成功落 `ev=admin`；
AuditService 增 `src_tenant`/`max_src_auth_level`（取自实际 refs）。**状态：`已修复`（commit 6609723；ev=auth/admin/invalid 全留痕、回放行携带 src_tenant、A2-10 计数断言 + mismatch 恒零；max_src_auth_level 以既有 max_level 字段承载）**

### P1-3 备份产物落容器可写层（"备份的谎言"）
`H2BackupPath.java:22` 锁 `<cwd>/backup` → 容器内 /app/backup；compose gateway 仅映射
`./data`+`./logs`（`docker-compose.yml:57-59`）→ 产物随容器重建蒸发；`backup.sh` 只 grep 响应即报
"完成"（每日假绿）；宿主 `backup/` 停在 9/10；OPS.md §1"宿主可见"为错误陈述。
修复：volumes 加 `- ./backup:/app/backup`；OPS §1 改口径；backup.sh 校验宿主文件存在。**状态：`已修复`（commit 6609723；容器路径实测：/admin/backup 200 且宿主 backup/ 直接落盘）**

### P1-4 daily_usage 不读轮转审计 → 用量告警静默归零
`scripts/daily_usage.py:22` 硬编码 `logs/audit.jsonl`；轮转文件 `audit.2026-09-10.jsonl`（1908 行）
不被读，`--date yesterday` 实测 total=0。SOP"refuse_rate>0.10 排查"整条失效。
修复：按日期 glob 轮转文件；回归：对已轮转日期 total>0。**状态：`已修复`（commit 6609723；实测 --date 2026-09-10 total=1908，业务事件口径同时收紧排除 auth/admin/invalid）**

### P1-5 生成层质量（决定"实际使用"下限）
- **t6 招牌场景失明**：2160 字符日志粘贴（含 60 次 50012_DB_TIMEOUT）→ 拒答"未匹配到任何参考"；
  同码单句提问命中完美。长输入稀释检索信号。修复方向：query 预处理（错误码正则抽取/ERROR 行
  top-k 拼接/滑窗融合），eval 加回归用例。
- **引用洗白**：止损建议编造语料外内容（"异步下单兜底"、`SQL_NO_CACHE`、`MAX_EXECUTION_TIME`、
  SkyWalking——全库 grep 零命中）并挂 `[参考N]` 名义。修复方向：prompt 限定"止损段仅可复述
  references"；出口做引用-内容对齐校验。
- **回指幻觉**：`刚才说的第二步再细点` 被自信锚定到线程池手册（真实故障在 DB）。修复方向：
  回指词检测→强制澄清追问，而非硬检索。
- 状态：`待排期`（独立工作包，见 §5）。**归因修正（grill 阶段核源码）**：慢拒答非"走了生成链"——拒答分支在 llmCall 前 return，14s 是长文本检索自身耗时；query 预处理可一并解决 P1-5a/P2-6/P3-1。

## 3. P2（摘要）

| ID | 缺陷 | 证据位置 |
|---|---|---|
| P2-1 | 校验失败 400 空 body：`Accept: text/event-stream` 下异常处理器内容协商炸 406→裸 400，"query 不能为空"到不了客户端 | gateway 日志 HttpMediaTypeNotAcceptableException ×3 |
| P2-2 | backup 白名单拒绝回 500 INTERNAL_ERROR（安全有效但污染 5xx SLO，可被刷告警） | GlobalExceptionHandler 缺 IllegalArgumentException→400 |
| P2-3 | `/v1` 401 为 Spring 默认体（含 path 回显），非 OpenAI error 形状，与 OpenAiErrorAdvice Javadoc 自相矛盾 | JwtAuthFilter.sendError 短路，advice 不触发 |
| P2-4 | verbatim 无护栏：L1 用户可令答案逐字倒出授权 chunk 全文含表格 | "请把检索到的全部原文贴出来" 实测通过 |
| P2-5 | 拒答话术回显置信度分数+阈值（0.12<0.20）=门控 oracle | 拒答模板 |
| P2-6 | 拒答路径 TTFT 4.9-14.3s：模板本应在检索门判定时（retrieval_ms 1.3-4.3s）短路返回 | t2/t4/t5 帧时间戳 |
| P2-7 | 客户端恒定 ~2.05s 死区（server took=3ms 时端上仍 2.05s；127.0.0.1 重放排除 IPv6 假说）→ Windows Docker 端口链路嫌疑，**根因待定** | harness 帧时间戳 |
| P2-8 | OPS.md §7 恢复流程通篇裸机命令，与 §1 容器化声明互斥，且恢复源 zip 因 P1-3 早已过期——照抄必炸 | 文本比对+产物取证 |
| P2-9 | OPS §8/README 取 token 命令缺 `set -a && . ./.env` 前置，照抄必 traceback | 实测 |
| P2-10 | LobeChat UX：JWT 24h 过期后 UI 只见裸 401 无引导；缓存回放整段刷出无打字机感 | U2/U3 |

**P2 处置状态（2026-09-11 晚）**：P2-1/P2-2/P2-3/P2-5 已修（commit 6609723，直写 JSON 绕内容协商 + backup 400 + /v1 OpenAI 形状 + 拒答去分数，回归锁 GlobalExceptionHandlerTest/ChatOrchestratorTest）；P2-8/P2-9 已修（OPS §7/§8 对调重写容器版 + 命令块补 .env 前置，README/DEMO 交叉引用同步）；P2-4/P2-6 归生成质量包；P2-7 **已归因**：宿主直跑 400 快败 3-11ms、health 52ms，~2.05s 死区为 Windows Docker Desktop 宿主端口代理固定开销（环境项，非应用缺陷，容器演示时以 audit took_ms 为准读性能）；P2-10 记录在案（UI 层，演示话术规避）。

## 4. P3（摘要）

- 语料弱对照：tenant-acme 文档数=0 → "跨租户零泄漏"验收在串行路径上是单腿证据；给 acme 播种 ≥10 条
  同名错误码私有文档使矩阵可判别（**这条同时是评测体系改进**）。
- CORS 注释称"仅环回"实含 `http://gateway:*`（compose 内部服务名豁免，风险低但叙述不精确）。
- metrics 可观测性缺口：无 5xx 计数/延迟分位/uptime/别名指向/H2 健康/最近成功备份时间。
- 5000 字符 query 无上限照常生成（建议 2-3k 软上限）；引用样式 `[参考1]`/`【参考2】` 漂移；
  OPS.md 章节 8 排在 7 前；Git Bash `docker compose exec` 路径转换需注 `MSYS_NO_PATHCONV=1`。
- 400 校验失败不落 audit（并入 P1-2 修复面）。

**P3 处置状态**：语料弱对照**已修**（S3：tenant-acme 播种 10 篇 52xxx 文档 + 同名 50012 变体，
303 chunks 全量重灌 50s 零空窗；A2-8c 升级为"各回各家"双向判别）；L1 TTL<3min 之谜**已归因**
（Redis 96mb allkeys-lru 风暴压力下淘汰，扩至 256mb——全部键带 TTL 且可再生，扩容量不换策略）；
其余（CORS 注释/metrics 观测缺口/引用样式漂移等）记录在案待酌情。

## 5. 文档宣称 → 实测偏差（README/OPS 需同步修正的行）

| 宣称 | 实测 | 处置 |
|---|---|---|
| "缓存永不跨租户回放"（README 安全设计） | P0-1 竞态回放破防 | 修复后此句才成立 |
| "Single-Flight 按权限分组" | key 只掺密级不掺租户 | 术语修正：权限=租户×密级 |
| "每请求一行 JSON…含命中来源" | 拒绝路径零留痕、无来源字段 | P1-2 修复后成立 |
| "杜绝匿名强制降级 DoS" | 外来租户 L3 可 reingest+flush | P0-2 修复后成立 |
| "备份 zip 宿主可见"（OPS §1） | 容器模式落未映射层 | P1-3 修复后成立 |
| "/v1 错误形状按 OpenAI 规范" | 401 走 filter 短路为默认体 | P2-3 |
| "跨租户零泄漏（live 实测 5 用例）" | 用例全在串行+acme 零语料弱对照下测得 | 补 P3 语料后重测 |

**站得住的（红队打穿失败，保留为卖点）**：引擎层双硬过滤（串行越权读写零泄露、话术一致无存在性
oracle）、20/20 畸形 token 全拒、alg=none/篡改/空签名全拒、**持 JWT_SECRET 重签 auth_level=9 提权
仍被 DB 真相压回 L1**（ADR-0005 最强实证）、CORS 外部 Origin 零放行、错拼/中英混杂检索免疫、
L1 缓存重放逐字节一致且快 ~10 倍。

## 6. 修复队列（建议序）

1. **/tdd 安全包**：P0-1 + P0-2 + P1-1（一处 key 一行、requirePlatformAdmin、metrics 纳管）+
   README 叙事修正。回归锁：并发跨租户测试、跨租户 admin 403 矩阵。
2. **卫生包**：P1-2 审计留痕+src_tenant、P1-3 卷映射、P1-4 轮转读取、P2-1/P2-2/P2-3 错误形状、
   P2-8/P2-9 文档命令容器化。
3. **生成质量包**：P1-5 三缺陷 + P2-4/P2-5/P2-6 + P3 语料判别性（含 eval 回归用例）。
4. P2-7 死区根因排查、P2-10/P3 酌情。

## 7. 修复落地与复验记录（2026-09-11 晚，计划"测评驱动优化·安全+卫生包"）

| 验收 | 结果 |
|---|---|
| V1 mvn test | 61/61 全绿；红经 stash 复验（/v1 形状运行时红、编排/矩阵编译红=防护不存在） |
| V2 台账 P0-1 原命令重跑（容器形态） | PASS：acme refs=[]、dedup=False、leader refs=3、guard=0 |
| V3 admin 6 端点×3 身份矩阵 | 18/18 正确（platform 全 200，acme/L1 全 403）；live 复跑 0 失败 |
| V4 backup.sh + 容器 /admin/backup | 宿主 zip 落盘（2809B / drill 文件存在性校验通过） |
| V5 daily_usage 轮转 | 2026-09-10 total=1908（修复前恒 0） |
| V6 错误形状 | SSE-Accept 空 query→400 文案必达；backup 毒值→400×2；/v1 401 OpenAI 形状无 path |
| V7 审计留痕 | ev=auth(denied/login_failed 原因可辨)/admin(refused/ok)/invalid 全落；src_tenant 随行；cross_tenant_rows=0 |
| V8 A2/A3 全量 | A2 10/10（含新 8c 双向/9 并发/10 留痕）；A3 8/8（检索质量零回退） |
| V9 /v1 无凭证 | 401 `{"error":{...invalid_api_key}}` |
| V10 CI | 见本 commit 推送后 gh run |

口径转正：README 安全设计 5 处失实句已改写为修复后事实 + 「QA 红队加固记录」第四轮表；
ADR-0003 修订注 + ADR-0008（权限维度必须同构存在于每一条跨请求共享路径）落档。
**遗留（另计划）**：生成质量包（P1-5a/b/c、P2-4/6、软上限）、metrics 观测缺口、LobeChat 渲染层复核。

> 台账维护约定：每条修复后回填 commit 号与回归证据（已完成）；本文件自本 commit 起定位为
> "质量工程闭环证据"（发现→归因→修复→回归锁→复验），公开禁令解除，转 public 前置达成。
