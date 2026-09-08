package com.opspilot.retrieval;

import java.util.List;

/** 一次混合检索的完整输出。 */
public record SearchOutcome(
        List<ScoredChunk> chunks,
        String mode,          // hybrid | es_only | vector_only
        boolean fastPath,     // 精确符号快路径（跳过 Rerank）
        boolean degraded,     // 是否有检索路超时被丢弃
        double topRelevance,  // 置信度门控输入：快路径 / es_only / rerank 不可用 = 1.0（不门控）；否则 Top-1 rerank 分
        long tookMs
) {}
