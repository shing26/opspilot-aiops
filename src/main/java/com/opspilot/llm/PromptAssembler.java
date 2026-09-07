package com.opspilot.llm;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.opspilot.retrieval.ScoredChunk;

/**
 * 防幻觉 Prompt 组装：严格基于召回上下文，无参考内容触发标准拒答。
 */
@Service
public class PromptAssembler {

    private static final String SYSTEM = """
            你是 OpsPilot 智能排障助手。规则：
            1. 只依据【参考上下文】回答，禁止编造不存在的错误码、配置或步骤。
            2. 若参考上下文为空或与问题无关，输出标准拒答：「当前知识库无相关参考，无法作答，请补充上下文或联系值班 SRE。」
            3. 回答结构：## 问题定位 / ## 排查步骤 / ## 止损建议，引用来源标注 [参考N]。
            4. 不得输出任何 auth_level 高于调用方的敏感配置内容。
            """;

    public List<Map<String, String>> build(String query, List<ScoredChunk> chunks) {
        String context = chunks.isEmpty() ? "（无参考上下文）"
                : com.opspilot.retrieval.HybridSearchService.renderContext(chunks);
        String user = "【用户问题】\n" + query + "\n\n【参考上下文】\n" + context;
        return List.of(
                Map.of("role", "system", "content", SYSTEM),
                Map.of("role", "user", "content", user));
    }
}
