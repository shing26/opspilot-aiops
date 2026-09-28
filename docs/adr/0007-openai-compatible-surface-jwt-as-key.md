# OpenAI 兼容面（/v1）与 LobeChat 集成：身份即密钥的协议翻译层

状态：accepted（协议面部分持续有效）。**2026-09-11 修订**：LobeChat 集成部分撤除——聊天壳只呈现"会答对的对话框"，对"运维系统可视化"诉求零增益，且其能力（上传/语音/多模态）依赖未实现端点，属演示负资产；`/v1` 协议面、JWT 即 API Key、ChatSink 架构**全部保留**并升为演示主形态（curl 直播，DEMO 幕⑦），compose 不再捆绑 UI 容器。兼容性结论保留有效：第三方客户端确可零适配直连（这正是 /v1 的卖点，无需再用特定 UI 证明）。

上下文：项目需要对外展示「完整产品」观感，行业通用做法是暴露 OpenAI 兼容端点让成熟聊天前端（LobeChat/Open WebUI/Dify）直连。外部方案建议共享静态 API Key + 自造 `/internal/health` 式端点，评审过程又给出 WebFlux/Lombok 示例——均与本仓库既定形态冲突。

决策：
1. **只增协议面，不增系统**：编排抽取为 `ChatOrchestrator`（handle/runPipeline/replay 整体平移），协议差异收敛进 `ChatSink` 接口——`SseChatSink`（委托 SseEvents，原面零变化）与 `OpenAiChatSink`（role/content/refs 注入/stop/[DONE]/异常文本帧收尾）。缓存、Single-Flight、熔断、配额、审计在两个协议面**语义完全一致**，实测 A2 抽取前后 8/8 不变。
2. **JWT 即 API Key**：`/v1` 纳入 JwtAuthFilter 守卫，客户端 Bearer 填用户自己 login 的 24h token——租户过滤、密级、token_ver 即时吊销、per-sub 配额、audit via 字段全量穿透。否决共享静态 Key：会把所有 UI 流量坍缩成单一身份，安全叙事在展示层前失效（实测：disable 后同一 Bearer /v1/models 立即 401）。
3. **CORS 只放环回 origin 模式**（localhost:*/127.0.0.1:*），且 OPTIONS 预检免凭证——否则浏览器端 fetch 死在预检。
4. **meta/ttft 丢弃、refs 以正文尾部 markdown 注入**：OpenAI 帧无元信息位，宁丢协议外字段不破格式；溯源展示优先。
5. 错误按 `{"error":{message,type,code}}` 形状（独立 advice + @Order(0)；**嵌套 advice 不参与组件扫描**，实测踩过）。

理由：面试叙事从「做了个聊天界面」升级为「同一编排暴露两个标准协议面，身份体系跨面穿透」；实现成本从「维护第二套前端」降为一个 sink。

后果：/v1 仅支持 stream=true（非流式 400）；多轮上下文由客户端负责（无状态检索语义声明）；LobeChat 侧能力（上传/语音/多模态）依赖未实现端点，README 明示不宣传；别名/编排后续变更需同时过 SSE 与 OpenAI 两面回归（A2 + OpenAiChatSinkTest 已锁）。

相关：ADR-0003（单实例）、ADR-0004（单链路——现在是单链路双协议面）、ADR-0005（账号体系）、ADR-0013（定位：agent 的可信底座）

> 修订注（2026-09-28，调用方关联键 `X-Trace-Id`）：本文第 16 行的"多轮上下文由客户端负责"**继续有效**
> ——本次**没有**加会话态，加的是**调用方关联键**，两者不可混。动因：`.workbuddy` 之外的消费者
> （被测 agent）会分多步调用本网关，而服务端自生的 `request_id` 只存在于 MDC 与审计行，**调用方拿不到**，
> 于是三步调用在审计里串不成一次 incident；既有的 `fingerprint` 是**内容派生**的"问题身份"（三步问不同
> 错误码即散成三个），也担不起调用链身份。
>
> 落地形态：请求头 `X-Trace-Id`（8–64 位 `[A-Za-z0-9_-]`，空白按未提供处理，**非法即 400 + 留痕**）
> → `RequestIdFilter` 写入 MDC → `AuditService.writeEvent` 落到审计行。选头不选请求体字段：本过滤器覆盖
> 全部 servlet 路径，一处改动即覆盖 copilot / `/v1` / `/search` / feedback 四个面，且**`/v1` 的请求体
> 一个字节都不动**（往 `ChatCompletionsReq` 加字段才是真动了协议面）。
>
> **两个 id 的权威语义（互不覆盖）**：`request_id`（服务端自生 8 位，单次请求回查，**服务端权威**，
> 不因调用方输入而变）与 `trace_id`（调用方提供，跨多步调用整段取出）。两者并存于同一审计行，
> 排障时按问题选：查"这一行是哪个请求产生的"用 `request_id`；查"这次 incident 分几步问过、都问了什么"
> 用 `trace_id`。禁把二者合成一个——强合必然丢一边。

