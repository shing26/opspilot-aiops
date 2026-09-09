# 知识库重建走 blue/green 别名原子切流，取代「先删后建」

上下文：ingest 原实现是 drop 后全量重建 ES 索引 + Qdrant 集合。P1 前评估认为"40s 重建窗口靠双路超时降级 ES 兜底"即可；外部评审指出该降级是假象——全删全建同时清空了 ES，窗口内两条路皆空，用户拿到的是空态拒答而非降级回答。知识库日常更新（改一篇 runbook）必然触发重建，此窗口就是日常故障。

决策：查询侧只认**别名**（ES `opspilot-chunks-read`、Qdrant `opspilot-vectors-live`，物理库名 `<base>-<yyyyMMddHHmmss>`）。`IngestionRunner.ingest()` 统一流程：写 staging 物理库 → 计数硬验收（ES count == 语料数、Qdrant points_count == 语料数，不符即抛错**不碰别名**）→ 单请求原子换 ES `updateAliases` / Qdrant `updateAliasesAsync`（delete+create 一次提交）→ best-effort 删旧物理库。L2 缓存集合（`semantic-cache`）是运行时数据，**不在重建链路清空**（ensure-exists 语义）。触发：启动 flag 或 `POST /api/v1/admin/reingest`（level≥3，AtomicBoolean 单飞，busy→409，结果见 `/metrics` 的 `reingest_busy/reingest_last`）。

理由：别名层是 ES/Qdrant 原生能力，实现成本低于任何"边删边灌"方案，且把"重建失败"从全站事故降级为 staging 残留清理问题——绿库永不服务时旧库保持在线，这正是评审要求的"零空窗"。

备选与否决：双写在线迁移（复杂度不匹配收益）；真增量 ingest（doc 级 diff/upsert，语料 216 条、40s 全量面前属过度工程——记欠账，语料上千再启用）；接受重建窗口（空窗=日常故障，否决）。

后果：别名名进入 `application.yml`（es.index / qdrant.collection），物理库名带时间戳可回滚（改指向前一版 alias 即秒级回退）；embedding 无去重，live 重灌按 216×embedOne 计（正常 ~1 分钟，账户限流时更久）。A3-8 的 ADR 计数断言由 `==4` 改为 `>=6`。

相关：ADR-0001（JSONL 接缝不变）、ADR-0005（生产边界）
