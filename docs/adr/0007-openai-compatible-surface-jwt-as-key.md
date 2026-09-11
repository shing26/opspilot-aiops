# OpenAI 兼容面（/v1）与 LobeChat 集成：身份即密钥的协议翻译层

上下文：项目需要对外展示「完整产品」观感，行业通用做法是暴露 OpenAI 兼容端点让成熟聊天前端（LobeChat/Open WebUI/Dify）直连。外部方案建议共享静态 API Key + 自造 `/internal/health` 式端点，评审过程又给出 WebFlux/Lombok 示例——均与本仓库既定形态冲突。

决策：
1. **只增协议面，不增系统**：编排抽取为 `ChatOrchestrator`（handle/runPipeline/replay 整体平移），协议差异收敛进 `ChatSink` 接口——`SseChatSink`（委托 SseEvents，原面零变化）与 `OpenAiChatSink`（role/content/refs 注入/stop/[DONE]/异常文本帧收尾）。缓存、Single-Flight、熔断、配额、审计在两个协议面**语义完全一致**，实测 A2 抽取前后 8/8 不变。
2. **JWT 即 API Key**：`/v1` 纳入 JwtAuthFilter 守卫，客户端 Bearer 填用户自己 login 的 24h token——租户过滤、密级、token_ver 即时吊销、per-sub 配额、audit via 字段全量穿透。否决共享静态 Key：会把所有 UI 流量坍缩成单一身份，安全叙事在展示层前失效（实测：disable 后同一 Bearer /v1/models 立即 401）。
3. **CORS 只放环回 origin 模式**（localhost:*/127.0.0.1:*），且 OPTIONS 预检免凭证——否则浏览器端 fetch 死在预检。
4. **meta/ttft 丢弃、refs 以正文尾部 markdown 注入**：OpenAI 帧无元信息位，宁丢协议外字段不破格式；溯源展示优先。
5. 错误按 `{"error":{message,type,code}}` 形状（独立 advice + @Order(0)；**嵌套 advice 不参与组件扫描**，实测踩过）。

理由：面试叙事从「做了个聊天界面」升级为「同一编排暴露两个标准协议面，身份体系跨面穿透」；实现成本从「维护第二套前端」降为一个 sink。

后果：/v1 仅支持 stream=true（非流式 400）；多轮上下文由客户端负责（无状态检索语义声明）；LobeChat 侧能力（上传/语音/多模态）依赖未实现端点，README 明示不宣传；别名/编排后续变更需同时过 SSE 与 OpenAI 两面回归（A2 + OpenAiChatSinkTest 已锁）。

相关：ADR-0003（单实例）、ADR-0004（单链路——现在是单链路双协议面）、ADR-0005（账号体系）
