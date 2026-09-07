package com.opspilot.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 引擎（无云端 Key 时的机制验证后端）：
 * - 向量：字符 bigram + 词元 哈希到 1024 维，TF 加权 + L2 归一化。
 *   同文本 → 同向量；词法重叠 → 余弦相似。确定性、零网络、零 Token。
 * - Rerank：查询与文档的词法重叠度。
 * - LLM：从召回上下文抽取「止损操作/修复措施」小节组装答案（严格基于检索内容）。
 * 注意：这是词法相似度，不是神经语义——评测报告必须标注后端为 mock。
 */
public final class MockEngine {

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+|[\\u4e00-\\u9fa5]");
    private static final Pattern SECTION = Pattern.compile(
            "^#{1,4}\\s*(止损操作|修复措施|排查步骤|止损建议)[^\\n]*\\n([\\s\\S]*?)(?=^#{1,4}\\s|\\Z)",
            Pattern.MULTILINE);

    /** 语料级 IDF 表（BM25 式词法加权）：启动时从 chunks.jsonl 计算一次。 */
    private static volatile Map<String, Double> idf = null;

    private MockEngine() {}

    /** 从语料构建 IDF：idf(t)=log(1+(N-df+0.5)/(df+0.5))。缺失文件则退化为均匀权重。 */
    public static synchronized void initIdf(java.util.List<String> docTexts) {
        Map<String, Integer> df = new HashMap<>();
        int n = docTexts.size();
        for (String d : docTexts) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            Matcher m = WORD.matcher(d.toLowerCase());
            while (m.find()) seen.add(m.group());
            for (String t : seen) df.merge(t, 1, Integer::sum);
        }
        Map<String, Double> table = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            table.put(e.getKey(), Math.log(1 + (n - e.getValue() + 0.5) / (e.getValue() + 0.5)));
        }
        idf = table;
    }

    static float[] embed(String text, int dim) {
        Map<String, Double> tf = new HashMap<>();
        Matcher m = WORD.matcher(text.toLowerCase());
        List<String> tokens = new ArrayList<>();
        while (m.find()) tokens.add(m.group());
        for (int i = 0; i < tokens.size(); i++) {
            tf.merge(tokens.get(i), 1.0, Double::sum);
            if (i + 1 < tokens.size()) tf.merge(tokens.get(i) + "#" + tokens.get(i + 1), 1.0, Double::sum);
        }
        float[] v = new float[dim];
        Map<String, Double> idfTable = idf;
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            double w = e.getValue();
            if (idfTable != null) {
                double idfVal = idfTable.getOrDefault(e.getKey(), 1.0);
                w *= Math.max(idfVal, 0.1);
            }
            int bucket = Math.abs(e.getKey().hashCode() % dim);
            v[bucket] += (float) w;
        }
        double norm = 0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < dim; i++) v[i] /= (float) norm;
        return v;
    }

    static double rerankScore(String query, String doc) {
        float[] a = embed(query, 1024);
        float[] b = embed(doc, 1024);
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    /** 从召回 chunk 文本组装排障答案（抽取止损/修复小节，带来源引用）。 */
    static String answer(List<String> chunkTexts) {
        if (chunkTexts.isEmpty()) {
            return "当前知识库无相关参考，无法作答，请补充上下文或联系值班 SRE。";
        }
        StringBuilder locate = new StringBuilder();
        StringBuilder steps = new StringBuilder();
        StringBuilder fixes = new StringBuilder();
        int ref = 0;
        for (String t : chunkTexts) {
            ref++;
            Matcher s = SECTION.matcher(t);
            while (s.find()) {
                String head = s.group(1);
                String body = s.group(2).trim();
                if (body.isEmpty()) continue;
                String quoted = body.length() > 400 ? body.substring(0, 400) + "…" : body;
                switch (head) {
                    case "排查步骤" -> steps.append(quoted).append("\n（来源 [参考").append(ref).append("]）\n\n");
                    case "止损操作", "修复措施", "止损建议" ->
                            fixes.append(quoted).append("\n（来源 [参考").append(ref).append("]）\n\n");
                    default -> { }
                }
            }
            String firstLine = t.lines().skip(1).findFirst().orElse("");
            if (!firstLine.isBlank() && locate.isEmpty()) {
                locate.append(firstLine).append("（来源 [参考").append(ref).append("]）");
            }
        }
        return "## 问题定位\n" + (locate.isEmpty() ? "依据召回上下文综合判断" : locate)
                + "\n\n## 排查步骤\n" + (steps.isEmpty() ? "参考上述来源文档逐步排查" : steps)
                + "\n## 止损建议\n" + (fixes.isEmpty() ? "优先执行来源文档中的止损操作" : fixes);
    }
}
