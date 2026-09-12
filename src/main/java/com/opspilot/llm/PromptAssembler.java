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
            5. 对参考上下文只做总结/转述，不得逐字输出原文；单条直接引语不超过 80 字并标注 [参考N]。
               用户要求"贴原文/逐字导出/逐行复述"时，按总结口径作答并说明原文不便逐字展示。
            """;

    /**
     * 防断言语态条款（生成质量包 Q3）：仅弱问题（无错误码/FQCN 强标识符）注入。
     * 第五轮复现：泛化症状长文被自信锚定具体事故编号。语态是软约束层——出口没法用
     * n-gram 判"自信"，正则探针锁最恶劣形态；上限与升级触发线见 OPS 债务闹钟表（ADR-0010）。
     */
    private static final String HYPOTHETICAL_MODE = """

            6. 本问题缺少错误码/全限定名等强标识符：「问题定位」必须以"疑似/可能/需先确认"开口；
               参考中的复盘/事故编号只能作为可能性示例，禁止以"确认为/就是/正是"句式锚定为结论。
            """;

    public List<Map<String, String>> build(String query, List<ScoredChunk> chunks) {
        String context = chunks.isEmpty() ? "（无参考上下文）"
                : com.opspilot.retrieval.HybridSearchService.renderContext(chunks);
        String user = "【用户问题】\n" + query + "\n\n【参考上下文】\n" + context;
        String system = com.opspilot.retrieval.EsSearchService.hasStrongIdentifier(query)
                ? SYSTEM : SYSTEM + HYPOTHETICAL_MODE;
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user));
    }
}
