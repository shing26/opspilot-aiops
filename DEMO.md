# OpsPilot 演示手册（DEMO）

> 六幕演示脚本。服务、中间件、live 后端就绪后全程约 5 分钟；也可作为面试现场的可复现操作单。
> 前置自检：`bash scripts/demo.sh`（见文末）。

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
cd "D:/OpsPilot — AIOps/offline" && set -a && . ../.env && set +a \
 && export $(grep -v '^#' ../scripts/demo_tokens.txt | tr -d '\r' | xargs -d '\n')
PY=.venv/Scripts/python.exe
```

> token 文件不入库（凭证），新环境先 `python scripts/gen_tokens.py > scripts/demo_tokens.txt` 生成（含 2 个演示号 + 2 个红队号）。

**开演前复位**（保证幕①是冷启动、TTFT 呈现真实秒级链路；否则上次演示的缓存会让幕①直接毫秒级、讲不出冷路径）：

```bash
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush \
  -H "Authorization: Bearer $sre_l3" -H "Content-Type: application/json" -d '{}'
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
BEFORE=$(curl -s http://localhost:8081/api/v1/admin/metrics -H "Authorization: Bearer $sre_l3" \
  | grep -o '"llm_calls":[0-9]*' | cut -d: -f2)
.venv/Scripts/python.exe console_client.py --storm
AFTER=$(curl -s http://localhost:8081/api/v1/admin/metrics -H "Authorization: Bearer $sre_l3" \
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
  -H "Authorization: Bearer $sre_l0" -H "Content-Type: application/json" \
  -d '{"query":"任意","mode":"hybrid"}'                                  # 红队 level-0 → 401
```

讲点：密级恒取 JWT claim，无参数可覆盖（历史上 `authLevelOverride` 提权面已被红队发现并移除）；`auth_level<=0` token 在 filter 层 401、即便绕过也在检索层失败关闭（双保险，`/search` 与 `/chat/stream` 共 4 路验收锁）。

### 幕⑥ 降级与拒答（讲点：三级自适应降级 + 置信度空态门控）

**必须先 flush**：链路是 L1→L2→降级判定，若目标 query 已被前面幕次缓存，会走回放而非 SOP 直出，演示失真。另注意现状行为：**SOP 直出的答案会写回 L1**，恢复 auto 后同 query 仍返回缓存 SOP——所以幕⑥结尾必须再 flush 复位：

```bash
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush -H "Authorization: Bearer $sre_l3" \
  -H "Content-Type: application/json" -d '{}'
curl -s -X POST http://localhost:8081/api/v1/admin/degrade -H "Authorization: Bearer $sre_l3" \
  -H "Content-Type: application/json" -d '{"level":"L2"}'
$PY console_client.py "支付回调积压怎么止损"     # meta: degradation_level=L2，静态 SOP 直出，零 LLM
curl -s -X POST http://localhost:8081/api/v1/admin/degrade -H "Authorization: Bearer $sre_l3" \
  -H "Content-Type: application/json" -d '{"level":"auto"}'   # 恢复 auto
$PY console_client.py "asdfgh junk 乱码"          # 置信度门控：显式拒答，跳过 LLM
# 收场复位（清掉 SOP/拒答缓存残留，下次开演从干净状态开始）
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush -H "Authorization: Bearer $sre_l3" \
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
