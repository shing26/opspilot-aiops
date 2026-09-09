# OpsPilot 生产化计划 v2（定义 A：5-50 人团队内部工具，单租户显式化）
> 已吸收外部评审 3 条修正：H2 用户主库、reingest 别名原子切流、过滤方向断言入矩阵。

**锁定决策**：① 团队内部+租户全链路显式化；② 自建账号（H2 主库版）；③ 中间件凭据+环回绑定（不上 TLS）；④ 锁死单实例；⑤ 知识库重建走 blue/green 别名（零感知）。基线 `v1.0.0`，main 分支逐 Sprint 小步提交。预计 **2 周**。

## Sprint P1：租户显式化 + tenant 边界收口（头号雷，TDD）
- 离线：`chunkers` metadata 加 `tenant`（默认 `tenant-internal`，front matter 可覆盖）；重建 chunks.jsonl；pytest 不变量：每 chunk tenant 非空。
- ingest：ES mapping / Qdrant payload 落 `metadata.tenant`；chunk 缺 tenant → 拒绝入库（fail-closed）。
- 检索过滤：`EsSearchService` 加 `term metadata.tenant`；`QdrantSearchService.authFilter(authLevel)` → `authFilter(tenant, authLevel)` = `must(term tenant 相等) + must(range doc_level lte user_level)`（方向保持现有语义，**回归断言入测试**：user=1 搜到文档必 ≤1、user=3 可看 3、doc=0/负值不可达）。
- 边界：tenant claim 缺失/空串/超 64 字符 → JwtAuthFilter 401（消灭 `null 进 L1 key` 雷）；L2 store/lookup 加 tenant；AnswerPayload 加 tenant；L1 兜底断言。
- 验收：JUnit `QdrantAuthFilterTest` 扩展 + 新 `TenantFilterTest`；A2-8c 租户矩阵（`tenant-acme` 签发 token × {/search,/chat/stream} 跨租户零命中、L2 无污染、null/blank/超长 → 401、方向用例）；mvn/pytest/A2/A3 全绿提交。

## Sprint P2：自建账号体系（**H2 主库 + Redis 仅易失**）
- **主用户库**：H2 file 模式（`./data/users.mv.db`，compose 卷持久化），`schema.sql` 自动建表：users(sub PK, pass_bcrypt, tenant, auth_level, role, disabled, token_ver, updated_at)；访问层 `JdbcUserStore`，**全部参数绑定 SQL**。Redis 不再存任何身份数据。
- 新增依赖：`com.h2database:h2` + `spring-security-crypto`（仅 bcrypt，不引整个 security 栈）。
- **/api/v1/auth/login**：免鉴权放行路径 +1；bcrypt 校验；签 24h JWT；暴力防护用 **Redis 计数器**（限流数据丢了无所谓）：同 sub 5 失败/15min → 429。
- **吊销实时**：JwtAuthFilter 每请求 `SELECT disabled, token_ver, tenant, auth_level FROM users WHERE sub=?`（内嵌 H2，µs 级）——不存在/disabled/token_ver 不符 → 401；吊销=UPDATE 一行即全局生效；无黑名单膨胀。
- 用户管理 CLI `scripts/user_admin.py`（HTTP 直连 /login 不可行→脚本直接改 H2 或经 admin 端点二选一：**定为 CLI 经 H2 Server 模式**，不开 HTTP 用户端点）；`gen_tokens.py` 退役为应急工具；localapi 加 `login()`；a2/a3/locust/console/DEMO.md 全切 login 取 token。
- 验收：错密码 401 / disabled 后存量 24h token 即时 401 / token_ver+1 全端失效 / 过期 401 / 暴力 429 / H2 文件重启后用户仍在（重启容器实测）。

## Sprint P3：中间件凭据 + 环回绑定
- compose：redis `requirepass`、qdrant `QDRANT__SERVICE__API_KEY`、ES `xpack.security.enabled=true`+密码，端口全改 `127.0.0.1:` 前缀；H2 数据目录挂卷。凭据一律 env 注入（.env + .env.example 模板，源码/测试零凭据字面量）。
- 客户端管道补认证：Redisson password、Qdrant apiKey、ES Basic CredentialsProvider（现三处全部写死无认证）。
- 验收：无凭据匿名探测 redis/9200/6333/6334 全被拒（留命令输出）；带凭据全量回归绿。

## Sprint P4：运维面收口（**含 blue/green 重建**）
- **reingest 原子切流**：配置指向**别名**（ES `opspilot-chunks-read`→物理 `…-<ts>`；Qdrant `opspilot-vectors`→物理集合，用 updateAlias API）。流程：写 staging → 行数+冒烟查询验收 → **原子换别名**（ES aliases 单 actions swap / Qdrant DeleteAlias+CreateAlias 一次提交）→ 删旧物理库。重建全程零停机零空窗（修正"降级 ES 但 ES 也被清空"的空窗假象）。首次启动走同一路径。
- 触发：`POST /api/v1/admin/reingest`（level≥3）+ `scripts/reingest.sh`。
- 审计：每请求一行 JSON（ts/sub/tenant/level/query 截断/fingerprint/cache_hit/refused/result_max_level）→ `logs/audit.jsonl`（gitignore 已有）。
- 配额：per-sub 日限（Redis INCR，默认 200 → 429）。SOP 直出答案不写 L1（修 review 遗留）。
- CI：一期 mvn test+pytest（mock 零 key 可跑）；二期可选 services+A2。
- 文档：ADR-0005（账号体系+H2 选型+租户显式化）、ADR-0003 生产口径修订注记、ADR-0006（别名切流）；CONTEXT.md 词条（租户隔离/吊销版本/审计事件/blue-green 重建）；README/DEMO.md login 流。

## 总验收（每 Sprint 提交前强制门禁）
| 门 | 标准 |
|---|---|
| 单测/回归 | mvn 全绿（含 tenant 方向断言+账号用例）· pytest · A2≥7/7 · A3 8/8 |
| 攻击矩阵 | tenant{缺失/空/超长/他租户} × auth_level{0/负/他级} × {错密码/暴力/disabled/过期/吊销版本} 全拒 |
| 数据持久 | compose 重启 + reingest 期间检索连续有结果 + users.mv.db 重启存活 |
| 中间件 | 匿名直连矩阵全拒绝，命令输出留档 |

## 明确不做（ADR 记欠账）
多实例分布式化、TLS、真增量 ingest、外部 IdP、SaaS 合规。