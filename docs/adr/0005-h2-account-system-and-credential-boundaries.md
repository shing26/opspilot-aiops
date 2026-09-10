# 生产账号体系：H2 主库 + 短时 JWT + token_ver 实时吊销；凭据全环境化；中间件认证不上 TLS

上下文：项目从演示转「团队内部真实使用」（5-50 人内网）。原身份形态是一年期预签发静态 JWT（无登录、无吊销、每号手工签），外部评审同时否决"用户数据放 Redis"（内存淘汰/FLUSHALL 会蒸发全部账号）。多租户 SaaS 的完整 ACL/OIDC 体系对本画像过度投资。

决策：
1. **主用户库 H2 file**（`./data/users.mv.db`，卷持久化、gitignore）：`users(sub, pass_bcrypt, tenant, auth_level, role, disabled, token_ver)`，全参数绑定 SQL。Redis 退回纯易失角色（限流计数、缓存）。
2. **凭据口令**：bcrypt（spring-security-crypto 单工件，不拉 security 过滤器栈）。
3. **登录**：`POST /api/v1/auth/login` 发 **24h** JWT（含 `tver` claim）；未知用户/错密码/禁用统一 401 防枚举；同 sub 失败 5 次/15min → 429（Redis 计数）。
4. **实时吊销**：`JwtAuthFilter` 每请求查 H2（内嵌 µs 级）——账号不存在/disabled/token_ver 不符即 401；`UserContext` 的 tenant/auth_level **以 DB 为唯一真相**，claim 仅作定位。吊销 = UPDATE 一行（disable 或 bump token_ver），无黑名单膨胀。
5. **用户管理仅 Java CLI**（`UserAdminCli` 经 PropertiesLauncher，口令只走 `--password-env`/stdin，不进 argv），**不开 HTTP 用户端点**（最小攻击面）。
6. **中间件（P3）**：redis requirepass、qdrant api-key、ES xpack security（basic 档），端口全部 `127.0.0.1` 绑定；**不上 TLS**——单实例内网同机部署，TLS 只增运维税不改变威胁模型（本机 root/同容器网络可窃听场景 TLS 也防不住）。凭据一律 env 注入，源码/示例/测试零字面量。

备选与否决：接外部 OIDC（团队无现成 IdP，引入运维依赖）；黑名单式 JWT 吊销（膨胀且需 TTL 兜底，劣于版本号）；用户表进 Redis（易失，评审否决）；Spring Security 全家桶（与自建 filter 冲突，仅需其 bcrypt 工件）。

后果：HS256 对称密钥意味着持 `JWT_SECRET` 者可伪造 token 过签名校验——但过不了 ④ 的 DB 存在性校验（伪造 sub 无账号 → 401），提权面收敛为"知道真实 sub + 拿到 secret"双条件；多实例部署需换 RS256 非对称（记欠账）。`auth_level` 单整数密级模型沿用（spec SLO 口径），企业级 ABAC 不在本画像。admin CLI 依赖与网关同机的 H2 文件 + AUTO_SERVER。单测 fixture 常量（JwtAuthFilterTest 的 32 字节测试密钥）为**刻意设计**：只存在于测试 JVM、签名只在测试内消费、真实服务配置中永不出现该值，属 Mimosa"零可用凭据"约束的显式豁免；审计问起指向本段。运维边界与推送门闩见根目录 OPS.md。

相关：ADR-0003（单实例前提仍成立）、ADR-0006（重建切流）
