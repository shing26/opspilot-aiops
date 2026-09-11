# OpsPilot 演示手册（DEMO）

> 六幕演示脚本。服务、中间件、live 后端就绪后全程约 5 分钟；也可作为面试现场的可复现操作单。
> 前置自检：`bash scripts/demo.sh`（见文末）。**管理员日常操作（开号/离职/配额/告警）看 [OPS.md](OPS.md)，不是本文。**

## 0. 启动

**Git Bash**：

```bash
cd "/d/OpsPilot — AIOps" && set -a && . ./.env && set +a && /e/java/jdk21/bin/java -jar target/opspilot-gateway-1.0.0.jar
```

**CMD**（无 `set -a`，手动注入 .env）：

```bat
cd /d "D:\OpsPilot — AIOps"
for /f "tokens=1,* delims==" %i in ('findstr /b "DASHSCOPE_API_KEY= JWT_SECRET=" .env') do set "%i=%j"
E:\java\jdk21\bin\java -jar target\opspilot-gateway-1.0.0.jar
```

看到 `Started OpsPilotApplication` 即就绪（`DASHSCOPE_API_KEY` 已配 → 自动 DashScope live；留空自动 mock 词法后端，机制不变）。

所有演示命令在**另一个终端**执行：

```bash
cd "D:/OpsPilot — AIOps/offline" && set -a && . ../.env && set +a
PY=.venv/Scripts/python.exe
# P2 账号体系：合法 token 实时 login（口令只来自 .env 的 DEMO_PASSWORD），红队畸形 token 读文件
L3=$($PY -c "import sys;sys.path.insert(0,'.');import localapi;print(localapi.login('sre-full'))")
L0=$(grep -m1 '^sre_l0=' ../scripts/redteam_tokens.txt | cut -d= -f2)
```

> 前置：新环境需先 `bash scripts/seed_demo_users.sh`（建 sre-limited/sre-full/sre-acme）
> 并 `python scripts/gen_tokens.py > scripts/redteam_tokens.txt`（红队畸形样本）。
> 两者都不入库、可再生；仓库内没有任何可用凭据。

**开演前复位**（保证幕①是冷启动、TTFT 呈现真实秒级链路；否则上次演示的缓存会让幕①直接毫秒级、讲不出冷路径）：

```bash
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush \
  -H "Authorization: Bearer $L3" -H "Content-Type: application/json" -d '{}'
```

## 1. 六幕脚本

### 幕① 主流式链路（讲点：SSE 三事件 + 混合检索 + 溯源）

```bash
$PY console_client.py
```

预期：打字机流式输出排障答案；`[meta]` 含 `fingerprint/cache_hit/degradation_level/fast_path`；`[TTFT]` 秒级（live 冷路径真实延迟：embedding+rerank+LLM）；`[refs]` 列出面包屑溯源。

### 幕② 缓存秒回（讲点：L1 掺 tenant+authLevel 的 Key 设计；热点 P99≈37ms 现场版）

同一条命令**立刻再跑一次**：

```bash
$PY console_client.py
```

预期：`cache_hit: "L1"`，TTFT 从秒级掉到 ~20ms。⚠️ 若被问"缓存是不是作弊"——口径：缓存 Key 含权限维度，回放内容与首次一致且永不跨密级（幕⑤反证）。

### 幕③ 语义改写命中 L2（讲点：神经向量余弦>0.95 语义缓存）

```bash
$PY console_client.py "下单接口报 50012_DB_TIMEOUT 怎么排查啊"
```

预期：`cache_hit: "L2"`——换了个说法，精确缓存 miss，但语义缓存命中（live 下这是 text-embedding-v3 的真余弦）。

### 幕④ 风暴收敛——核心幕（讲点：指纹归一化 + 进程内 Single-Flight，ADR-0003）

`llm_calls` 是累计计数，必须取前后差值（直接读绝对值会误判）：

```bash
BEFORE=$(curl -s http://localhost:8081/api/v1/admin/metrics -H "Authorization: Bearer $L3" \
  | grep -o '"llm_calls":[0-9]*' | cut -d: -f2)
.venv/Scripts/python.exe console_client.py --storm
AFTER=$(curl -s http://localhost:8081/api/v1/admin/metrics -H "Authorization: Bearer $L3" \
  | grep -o '"llm_calls":[0-9]*' | cut -d: -f2)
echo "500 并发 → LLM 增量 = $((AFTER-BEFORE))"        # 期望输出：增量 = 1
```

预期：500 并发同指纹打完，`llm_calls` 增量 =1（演示全程 5000+ 请求累计仅 ~17 次 LLM 调用）。可指 `dedup_aggregated` 同步 +500。
主动亮取舍：Single-Flight 是进程内 `ConcurrentHashMap`（ADR-0003 明写）——多实例部署需换 Redis 分布式实现，当前口径是单实例。

### 幕⑤ 权限硬隔离 + 提权拒止（讲点：引擎层过滤，Prompt 越狱不可跨越；P0 修复的边界矩阵）

```bash
$PY console_client.py "50042_PAY_SIGN_INVALID 密钥配置" --token sre_l1   # 结果全部 auth_level<=1
$PY console_client.py "50042_PAY_SIGN_INVALID 密钥配置" --token sre_l3   # L3 可见密级文档
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8081/api/v1/copilot/search \
  -H "Authorization: Bearer $L0" -H "Content-Type: application/json" \
  -d '{"query":"任意","mode":"hybrid"}'                                  # 红队 level-0 → 401
```

讲点：密级恒取 JWT claim，无参数可覆盖（历史上 `authLevelOverride` 提权面已被红队发现并移除）；`auth_level<=0` token 在 filter 层 401、即便绕过也在检索层失败关闭（双保险，`/search` 与 `/chat/stream` 共 4 路验收锁）。

### 幕⑥ 降级与拒答（讲点：三级自适应降级 + 置信度空态门控）

**必须先 flush**：链路是 L1→L2→降级判定，若目标 query 已被前面幕次缓存，会走回放而非 SOP 直出，演示失真。另注意现状行为：**SOP 直出的答案会写回 L1**，恢复 auto 后同 query 仍返回缓存 SOP——所以幕⑥结尾必须再 flush 复位：

```bash
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{}'
curl -s -X POST http://localhost:8081/api/v1/admin/degrade -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{"level":"L2"}'
$PY console_client.py "支付回调积压怎么止损"     # meta: degradation_level=L2，静态 SOP 直出，零 LLM
curl -s -X POST http://localhost:8081/api/v1/admin/degrade -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{"level":"auto"}'   # 恢复 auto
$PY console_client.py "asdfgh junk 乱码"          # 置信度门控：显式拒答，跳过 LLM
# 收场复位（清掉 SOP/拒答缓存残留，下次开演从干净状态开始）
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{}'
```

## 2. 故障排查

| 症状 | 处置 |
| --- | --- |
| 端口 8081 被占 / 启动即退 | `powershell "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force"` 重试；`8080` 被本机 nexus-web 占用是既定事实 |
| `JWT_SECRET 环境变量未设置` | .env 未 source，或行内有前导空格/值带引号 |
| 检索结果异常少 / mode 一直 es_only | 中间件没起来：`docker compose up -d`；Qdrant 集合空（曾 `down -v`）→ 启动参数加 `--opspilot.ingest=true` 重灌（~40s） |
| rerank 403 | 旧 `gte-rerank` 已停授权；确认 `${RERANK_MODEL:gte-rerank-v2}`（ADR-0002 有修订注记） |

## 3. 成本口径

演示全程（含六幕）约 **15-20 次 LLM 调用 + 40 次 embedding**，live 下人民币几分到几角。风暴幕 500 并发只打 1 次 LLM——这本身就是产品卖点。

## 4. 完整回归（面试前 3 分钟自测）

```bash
.venv/Scripts/python.exe acceptance_a2.py    # 期望 A2: 7/7 PASS（~2min，含 500 并发风暴）
.venv/Scripts/python.exe acceptance_a3.py    # 期望 A3: 8/8 PASS（~5min，含评测集检索）
```

## 5. 3 分钟一镜到底录屏讲解稿

> 原则：不剪辑、不回头；每幕敲命令前先说这句口播。开演前完成 §0 复位。

| 时点 | 口播（照读即可） |
|---|---|
| 0:00 开场 | "这是 OpsPilot——一个把运维告警接进 LLM 的排障网关。它要解决两件事：告警风暴把 LLM 打爆，以及向量检索把 5 位错误码当噪音。全部指标 DashScope live 实测。" |
| 幕① | "一条口语化的 DB 超时问题。SSE 流式，meta 携带指纹和降级态；注意 TTFT 是秒级——embedding、rerank、LLM 三段真实外网调用，口径是双源校验过的。" |
| 幕② | "同样的问题再来一次——TTFT 从 6 秒掉到 20 毫秒，L1 精确缓存。缓存 key 掺了租户和密级，回放内容不可能跨权限。" |
| 幕③ | "多加了一个'啊'字，精确缓存 miss，但 1024 维神经向量的余弦过 0.95——L2 语义缓存命中，答案直接回放，零检索零 LLM。" |
| 幕④ | "核心幕。500 个并发的同指纹告警打进去——看这个计数：llm_calls 前后差值是 1。滑动窗口在 Redis 计数，Single-Flight 在进程内收敛，指纹归一化把唯一 ID、时间戳全部掩掉。" |
| 幕⑤ | "同一个敏感问题，L1 工号查不到支付密钥配置，L3 查得到——这不是 Prompt 约束，是 ES 和 Qdrant 查询里注入的硬过滤。再用畸形租户的签名 token 打接口：401。密级越权和租户越权在入口与引擎层各封死一次。" |
| 幕⑥ | "熔断态手动打到 L2：LLM 挂了也能直出静态止损清单，零外网调用；恢复后问一个乱码——置信度门控显式拒答，宁可说不知道，不烧 token 编答案。" |
| 收尾 | "仓库里 README 有完整证据链：A2/A3 全绿、蓝绿在线切流 27 秒零中断、每条债务都有触发线。谢谢。" |

### 幕⑦（+40 秒，UI 观感加分，主线仍是上面六幕）

浏览器开 `localhost:3210`（LobeChat，输入 ACCESS_CODE 进主界面，见 OPS.md §7）：设置→自定义模型服务填 Base URL `http://localhost:8081/v1` + Key=login 的 JWT。挑 `opspilot` 模型提问 "50012_DB_TIMEOUT how to fix"——口播："后端暴露标准 OpenAI 兼容面，界面上这个'API Key'其实是账号体系签的 JWT——刚才幕⑤那个 disable，在这层同样秒生效，因为两个协议面共享同一条编排链路。"
（前置：`docker compose --profile full --profile ui up -d`，lobe 需 2g 内存——512m 会 OOM，见 OPS 环境注记。若 UI 不可用，降级预案为一条 curl 直播 OpenAI chunk 流，口播词不变。）
