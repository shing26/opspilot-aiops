package com.opspilot.retrieval;

import java.util.List;

/** 一次混合检索的完整输出。 */
public record SearchOutcome(
        List<ScoredChunk> chunks,
        String mode,          // hybrid | es_only | vector_only
        boolean fastPath,     // 精确符号快路径（跳过 Rerank）
        boolean degraded,     // 是否有检索路超时被丢弃
        double topRelevance,  // 置信度：快路径=1.0；否则 Top-1 rerank 分；es_only 无 rerank 用 esScore 归一
        long tookMs
) {}
