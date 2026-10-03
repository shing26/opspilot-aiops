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

    /**
     * 非错误码无据命名条款（2026-09-28，live QA F-2 兑现 §5.1 触发线后的第一步——**软规则**）：
     * live 实测模型会编造语料中不存在的指标名（`gateway_upstream_latency_p99`）、配置开关
     * （`pay.gateway.fallback.enabled=true`）与预案名并挂引用标号——规则 1 的"禁止编造"过于笼统，
     * 对"形似合理"的具体名字没有约束力。本条款点名这几类形状。**它仍是软约束层**（同规则 6 的
     * 定位）：可验的落点是 warn-only 探针（`qa_gen_quality_probes` 对答案 token 回查语料），
     * 硬门控属"断言语态结构化门控"在案债务，触发线未到不做。
     *
     * <p><b>无条件注入</b>（区别于 HYPOTHETICAL_MODE 的条件注入）：编造发生在**带强标识符**的
     * 查询上——那时 HYPOTHETICAL_MODE 恰恰不注入，故本条款必须挂在两种模式之后。
     */
    private static final String UNGROUNDED_NAME_BAN = """

            7. 参考上下文中未出现的监控指标名、配置键（形如 a.b.c=true）、文档/预案名（书名号《》），
               一律不得具体命名或引用；如需表达该方向存在不确定性，用「可能存在与该错误相关的监控
               指标/配置项，需现场确认」这类不落具体名称的说法。
            """;

    public List<Map<String, String>> build(String query, List<ScoredChunk> chunks) {
        String context = chunks.isEmpty() ? "（无参考上下文）"
                : com.opspilot.retrieval.HybridSearchService.renderContext(chunks);
        String user = "【用户问题】\n" + query + "\n\n【参考上下文】\n" + context;
        // 规则 6 仅弱问题注入（语态条款）；规则 7（无据命名禁令）**两种模式都需要**——
        // 编造指标名/配置键的实测恰恰发生在带强标识符的查询上。
        String system = com.opspilot.retrieval.EsSearchService.hasStrongIdentifier(query)
                ? SYSTEM
                : SYSTEM + HYPOTHETICAL_MODE;
        system = system + UNGROUNDED_NAME_BAN;
        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user));
    }
}
