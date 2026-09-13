# 模块独立 + 集成验证台账 — 2026-09-13

> 触发：外部结构化梳理评估（根目录报告，不入本仓）建议"转公开"后，先做一轮全模块独立验证与集成活体验证。
> 方法：三层——①逐包单测（外部依赖全 mock，真独立）②依赖面逐个探活 ③集成端到端（手工最小闭环 + 全套件 + 蓝绿重建活体）。
> 对象：运行容器（镜像建于 2026-09-13 03:44，已含 `e966feb` 熔断半开修复；构建后 src 仅差 1 行日志增强，行为等价）。
> 结论：**无"跑不通"模块**；发现并修复 **F1/F2 两处客户端错误错落 5xx**（见 §4）。

## 1. 逐包独立单测（mvn test -Dtest="com.opspilot.<pkg>.*Test"）

| 包 | 用例 | 结果 |
| --- | --- | --- |
| auth | 20 | ✅ |
| gateway | 34 | ✅ |
| llm | 22 | ✅ |
| retrieval | 14 | ✅ |
| storm | 8 | ✅ |
| metrics | 8 | ✅ |
| resilience | 5 | ✅ |
| health | 4 | ✅ |

**115 全绿**。`cache`/`ingest`/`chunk`/`config` 四包无直接单测（如实记录），其独立运行证据由 §2 探活与 §3 活体承担：L1/L2 命中见 A2-3/A2-4/V5，ingest 见 §3.3 蓝绿重建。

## 2. 依赖面独立探活

- ES 8.14.3：status yellow（单节点预期）、读别名 `opspilot-chunks-read` → 303 docs ✅
- Qdrant v1.12.4：healthz passed、别名 `opspilot-vectors-live` → 303 点 green ✅（`/collections` 列表不并显别名属 API 行为，勿误判为空挂）
- Redis 7：requirepass 下 PONG ✅
- H2：经 login/账号操作间接验证（口令登录 + 平台门禁查库正常）✅
- DashScope 上游三面（此前欠费阻断项）：embeddings 200（dim=1024）/ qwen-plus chat 200 / gte-rerank-v2 200——**欠费已解除，复测戳在此** ✅

## 3. 集成活体（容器 live，mock→live 后端=dashscope）

### 3.1 手工最小闭环
login(2.6s) → `/api/v1/copilot/search` hybrid Top-1=rb-001::50012 复盘 ✅ → SSE 全链路（TTFT 8.2s、答案含 [参考1][参考2] 溯源）✅ → `/v1` 流式（32 chunks + `[DONE]`）✅ → `/admin/state` 键集齐全 + 非 platform 403 拦截 ✅ → 面板契约四层 `check_panel_contract.sh` OK ✅

### 3.2 全套件串跑（顺序执行防单飞/缓存互扰）
- `qa_gen_quality_probes.py`：**11/11 PASS**（V2×5 verbatim / V3 /v1 面 / V5 L1 回放卫生 / V4 语态×2 / V7 长日志 10069 字），chat 实调 14≤预算 15
- `acceptance_a2.py`：**10/10 PASS**（L1/L2/风暴 500→1/降级/权限矩阵/跨租户双向判别/Single-Flight 并发/拒绝留痕 auth_denied≥8、src_rows=501、跨租户归属告警=0）
- `evaluate.py`：hybrid 精确 Hit@1 **100%**、语义 Hit@3 **100%**、语义 Hit@1 88%/MRR 0.940；es_only 语义 Hit@1 64%（双路论据复现）；越狱 5 条**零泄漏**；报告自报后端 dashscope ✅
- `acceptance_a3.py`：**8/8 PASS**（A3-5 报告存在性为文件检查，非当次重打；A3-6 双源 TTFT client 6.4s/server 4.3s 一致）

### 3.3 蓝绿重建活体（ingest 模块独立证据）
`POST /admin/reingest`（admin 触发）→ 重建期间 **27 个在线 hybrid 探针：0 失败 0 空结果** → 别名原子切至 `opspilot-chunks-20260913105928`（旧库按设计删除）→ 切后 ES/Qdrant 均 303、查询正常，全程 ~40s。`/admin/metrics` 回显 `reingest_last: ok @ …11:00:01 (trigger=admin-reingest)` ✅

## 4. 发现 → 修复 → 复验（公开前置门闩）

### F1：copilot 面请求体反序列化失败落 500（不落 400、不留 invalid 审计）
- **表现**：`{"query":"x","mode":{}}` 打 `/api/v1/copilot/search` → 500 `INTERNAL_ERROR`；日志 `HttpMessageNotReadableException → GlobalExceptionHandler unhandled`
- **根因**：第五轮 QA 建错误分层时只治了 URL 参数侧 `MethodArgumentTypeMismatchException`，body 侧同型变体无分支，落 `onOther` 500 兜底——与本类 javadoc"客户端错误绝不能落 500"自相矛盾；`/v1` 面因 `OpenAiErrorAdvice`（assignableTypes+Order(0)）不受影响
- **修复**：`GlobalExceptionHandler.onUnreadable` → 400 `INVALID_REQUEST` + `logInvalid`，cause 细节仅 WARN 一行不带凭据；2 新用例（含 /v1 形状纵深锁）
- **复验**：§5 矩阵——copilot/auth 面全部畸形体 400，审计 `ev=invalid` 进账

### F2：`Accept: application/json` 打 SSE 端点落 500（协商异常进不了方法级 advice）
- **表现**：矩阵首跑 `/v1/chat/completions` 带 `Accept: application/json`（OpenAI SDK 非流式默认头）→ 500 `server_error`；日志 `HttpMediaTypeNotAcceptableException @ lookupHandlerMethod`
- **根因**：协商失败发生在 handler **mapping 阶段**，早于 controller 调用——`OpenAiErrorAdvice` 形同虚设；全局处理器第五轮补了 405/415/404 分支，**漏了 406**（javadoc 自己写明"协商异常进不了专属 advice，由本类分流"，属同一族）
- **修复**：`onNotAcceptable` → 406 + 指向 stream=true 的双语话术；/v1 形状自动分流；按 404/405 先例不落审计（协议噪音）
- **复验**：`/v1` 与 `/api/v1/copilot/chat/stream` 双面 406 确认，同 body 换 `Accept: text/event-stream` 仍 400（advice 抢序未破坏）

### 教训（验收工具侧）
1. **矩阵禁打有副作用的 admin POST**：首跑用 `{"unexpected":"body"}` 探 `/admin/reingest`——该端点无 `@RequestBody` 静默忽略 body，**真实触发了一次全量重建**（幂等蓝绿，无害，但属意外 LLM 成本）。此类端点回归探针改打 GET 断 405。
2. `console_client.stream_chat(query, token)` 参数序、`/v1` 流式专属（stream=false 的 400 是设计而非缺陷）两坑已在脚本注释面，操作时勿再踩。

## 5. 修复后攻击参数面矩阵（活体复跑）

13 用例 ×（字段类型错/截断 JSON/顶层数组/错误 Accept/错误方法）×（copilot/search、copilot/stream、/v1、auth/login、admin）→ **全 4xx，500 计数 = 0**；`mvn test` **118 全绿**（115+3）；合法路径回归（search 3 hits、SSE 全链路 done_refs=3）无误伤。矩阵输出逐条留档于本文件提交版。

## 6. 公开前状态
F1/F2 修复 + 本台账为"转公开"硬前置（评估文档 §9-A 决策）。余项：quickstart 三条命令入口、Linux 客串实测、README 口径文案——见实施计划 S2/S3（本台账不展开）。

## 7. E1：评测报告陈旧漂移取证（公开"数字必须真"门闩的当轮产出）

**现象**：本轮两次 evaluate（跨索引重建）稳定得 `es_only` 语义 Hit@1 **64%** / MRR 0.813，与仓内已提交报告（72% / 0.853）不符；`hybrid` 行（100/88/100/0.940）逐位一致。首轮 `vector_only` 精确 84% 系旧物理库临时态，重建后回到 100%（与提交值一致）。

**归因**（非缺陷，是文档时效债）：已提交报告生成于 `0e40e5b`（2026-09-09），而语料在 d3ac1c8（2026-09-11 20:50）+52 篇 acme 双向判别文档（251→303）后**从未重生成报告**——BM25 文档频率统计随语料漂移，纯词法腿首位命中率 72→64。hybrid 融合与 MRR 高位数字未受影响。

**待办指针（并入 S5 公开门闩）**：README"从 72% 拉到 88%""16% 口语化漏首""MRR 0.853→0.940""逐位一致"四处表述按本报告新版（64%→88%、24 个百分点、0.813→0.940）修正；本报告文件随本台账一并提交为当前真值。


