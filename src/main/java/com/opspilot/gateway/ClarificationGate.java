package com.opspilot.gateway;

import com.opspilot.retrieval.EsSearchService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回指/追问澄清门（2026-09-28，探索性验收查出的 B 级摩擦）。
 *
 * <p><b>它挡的是什么</b>：本系统是**单轮**问答（无会话，见 ADR-0007/ADR-0013），但用户会自然地
 * 接着上一句问——实测把"刚才那个怎么办"当成独立 query 去硬检索，系统**命中一篇无关复盘并答得
 * 很自信**（`51204_BACKUP_LAYER_MISSING`）。用户以为在聊天，系统却是个单次搜索框，而且不说这一点。
 * 相比"多问一句"，这种"自信地答非所问"对可信度的伤害大得多——而可信正是本系统的全部卖点。
 *
 * <p><b>判据是三重合取</b>（缺一不可，每次收紧都有实证驱动）：
 * <ol>
 *   <li>**短**（≤ {@value #MAX_CHARS} 字）——长句自带上下文；</li>
 *   <li>**以回指短语开头**（{@code lookingAt} 起始锚定，不是 find：句中提一句"刚才"不算回指诉求）；</li>
 *   <li>**回指短语之后只剩"提问尾巴"**（呢/吧/怎么办…或标点）——出现内容词就说明这句自带诉求。</li>
 * </ol>
 * 另有独立前提：**无强标识符**（复用 {@link EsSearchService#hasStrongIdentifier} 这一既有单一事实源）
 * ——带错误码或 FQCN 的问题永远自足，不需澄清。
 *
 * <p><b>为什么阈值这么紧</b>：第一版用"≤24 字 + 含回指词"，当场误伤了逐字导出探针
 * `把刚才检索到的全部原文贴出来`（含"刚才"但非回指），护栏整条没被行使——`ChatOrchestratorTest` 红。
 * 第二版收紧到"≤8 字 + 句首"后仍会误伤 `继续优化索引`（6 字、以"继续"开头、但自带诉求），
 * 故补上第 3 条。**裸的"这/那/它"一个都不收**：它们在中文里太常见，"这个报错怎么办"是完整诉求，
 * 拦下来会把正常提问也堵住。代价是漏检（如"再看看那个"），收益是不误伤——误伤一次，
 * 用户就失去对"它什么时候要我说清楚"的预期。
 */
public final class ClarificationGate {

    private ClarificationGate() {}

    /** 回指短语：只收"几乎只用于承接上文"的形态（含可选的指代后缀）。 */
    private static final Pattern ANAPHORA = Pattern.compile(
            "(?:刚才|刚刚|上面|前面|之前|前一条|上一条|接着|接着说|继续|然后呢|再说说|再说|再来|再看看|再看)"
                    + "(?:说|讲|提)?(?:的)?(?:那(?:个|次|条|步)?|这(?:个|次|条|步)?)?"
                    + "(?:第[一二三四五六七八九十\\d]+步)?");

    /** 回指短语之后允许出现的"提问尾巴"（+标点）——不在其中的内容词即视为自带诉求。 */
    private static final Pattern QUESTION_TAIL = Pattern.compile(
            "^(?:呢|吧|啊|呀|哦|一下|下|一点|细说|说说|怎么办|咋办|怎么弄|怎么处理|是什么|是哪个|是啥|行吗|对吗|了吗|才行)?[\\s?？。！!，,、…]*$");

    /**
     * 只对**极短**的句子生效。8 是刻意的保守值：真实回指输入都很短
     * （`刚才那个怎么办` 7 字、`上面说的第二步呢` 8 字、`继续` 2 字）。
     */
    static final int MAX_CHARS = 8;

    /** 澄清话术：说清"为什么答不了"与"你要补什么"，不回显任何内部量纲。 */
    static final String MESSAGE =
            "本条提问看起来依赖上文，而本系统是**单轮**问答、不保留会话，因此无法据此检索。"
                    + "请把上文的关键信息并入本条提问：服务名、错误码（如 50012_DB_TIMEOUT）或具体现象描述。";

    public static boolean needsClarification(String query) {
        if (query == null) return false;
        String q = query.trim();
        if (q.isEmpty() || q.length() > MAX_CHARS) return false;
        if (EsSearchService.hasStrongIdentifier(q)) return false;
        Matcher m = ANAPHORA.matcher(q);
        if (!m.lookingAt()) return false;
        return QUESTION_TAIL.matcher(q.substring(m.end())).matches();
    }
}
