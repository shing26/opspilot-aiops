package com.opspilot.retrieval;

/**
 * 一次混合检索的分腿耗时（G2 分段耗时的检索段）。
 *
 * 为什么需要它：此前 {@link SearchOutcome} 只有 `tookMs` 一个总数——一个 17.5s 的请求
 * 答不出时间花在哪。检索内部的主要成本是**向量腿里的 embedding 调用**（配置注释记录 live
 * 实测 2.2–3.7s，`leg-timeout-ms: 4500` 正是据此取值），而 ES 腿通常是个位数毫秒；
 * 不拆开这两者，"检索慢"就只是个笼统印象。
 *
 * 口径（勿误读）：
 * - `vectorMs` **包含该腿内部的 embedding 调用**——Qdrant 腿先 embed 再检索，两者在同一腿内，
 *   本类型不拆。要单独拿 embedding 耗时须改 {@code QdrantSearchService} 的返回面，暂不做。
 * - 两腿**并行**发起、顺序 join，故 `retrievalMs` 约等于 `max(esMs, vectorMs) + rrfMs + rerankMs`，
 *   **不是四者之和**。自洽判据据此写成"≥ 关键路径"而非"= 总和"。
 * - `rerankMs` 在快路径 / `es_only` / 降级（`noRerank`）时为 0——那不是"很快"，是**没调用**。
 */
public record LegTimings(int esMs, int vectorMs, int rrfMs, int rerankMs) {}
