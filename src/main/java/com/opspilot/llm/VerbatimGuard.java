package com.opspilot.llm;

import java.util.List;

/**
 * verbatim 出口护栏的判定内核（生成质量包 Q2=C，ADR-0010）：生成文本与参考 chunk 原文的
 * 最长连续重叠（最长公共子串）。
 *
 * 纪律（台账"越狱用例"口径）：模型自觉不是验收标准，逐字导出必须在引擎层被拦。
 * 阈值 80 = 单条引语配额，与 PromptAssembler 规则 5 的「直接引语 ≤80 字」同数单一口径。
 * 纯函数零依赖，可 mock 单测（零 token）。
 */
public final class VerbatimGuard {

    /** 连续重叠上限（字符数）：超过即出口掩码。 */
    public static final int MAX_OVERLAP_CHARS = 80;

    /** 超阈句的整句替换话术（掩码后照常进缓存/审计/SF 分发）。 */
    public static final String PLACEHOLDER = "（此处原文较长，不便逐字展示，请查看引用来源文档）";

    private VerbatimGuard() {}

    /** text 与任一参考原文的最长连续公共子串长度；空输入恒 0。 */
    public static int longestOverlap(String text, List<String> references) {
        if (text == null || text.isEmpty() || references == null || references.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (String ref : references) {
            if (ref == null || ref.isEmpty()) continue;
            best = Math.max(best, lcsLen(text, ref));
            if (best > MAX_OVERLAP_CHARS) break; // 消费侧只问"是否超阈"，超了不必再精算
        }
        return best;
    }

    /** 经典 LCS 长度 DP（滚动数组）；|text|≤数百、refs≤Top-3 chunk 体量，句级频度下开销可忽略。 */
    private static int lcsLen(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                if (ca == b.charAt(j - 1)) {
                    int v = prev[j - 1] + 1;
                    cur[j] = v;
                    if (v > best) best = v;
                }
            }
            prev = cur;
        }
        return best;
    }
}
