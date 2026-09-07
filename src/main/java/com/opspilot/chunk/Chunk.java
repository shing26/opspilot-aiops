package com.opspilot.chunk;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 与离线 chunks.jsonl 完全同构的语义块契约（ADR-0001 接缝）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Chunk(
        String chunkId,
        String docId,
        String type,
        String text,
        String breadcrumb,
        Metadata metadata
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(
            String service,
            String endpoint,
            String method,
            List<String> errorCodes,
            int authLevel,
            String env
    ) {}
}
