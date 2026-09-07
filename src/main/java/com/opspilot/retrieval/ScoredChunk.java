package com.opspilot.retrieval;

import java.util.List;

/** 检索结果：chunk 全文 + 元数据 + 各路分数。 */
public record ScoredChunk(
        String chunkId,
        String docId,
        String type,
        String text,
        String breadcrumb,
        String service,
        List<String> errorCodes,
        int authLevel,
        double esScore,
        double vectorScore,
        double rrfScore,
        double rerankScore
) {
    public ScoredChunk withRrf(double s) {
        return new ScoredChunk(chunkId, docId, type, text, breadcrumb, service, errorCodes,
                authLevel, esScore, vectorScore, s, rerankScore);
    }
    public ScoredChunk withRerank(double s) {
        return new ScoredChunk(chunkId, docId, type, text, breadcrumb, service, errorCodes,
                authLevel, esScore, vectorScore, rrfScore, s);
    }
}
