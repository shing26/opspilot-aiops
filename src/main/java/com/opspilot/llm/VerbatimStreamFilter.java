package com.opspilot.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * 句级出口护栏（生成质量包 Q2=C）：LLM token 攒到句末再下发；下发前把
 * 「已吐文本尾部(carry) + 待发句」与参考原文做连续重叠检测，超阈整句替换占位话术。
 *
 * carry 参与检测是为堵"表格式漏算"：每行单独 ≤80 字、逐行拼接却超阈——拦截后
 * carry 被占位符打断，逐字连续体外在流上恒 ≤80+配额。安全冲刷上限防无句读长块憋死客户端。
 *
 * 设卡一处即可（runPipeline LLM 完成前）：L1/L2 写入与 Single-Flight 分发消费的
 * 都是 emitted() 掩码版——缓存回放与 follower 天然干净（ADR-0010）。
 */
public final class VerbatimStreamFilter {

    /** 已吐尾部参与连续性检测的长度：必须 > 阈值，否则跨句拼接漏检。 */
    private static final int CARRY_CHARS = 160;
    /** 无句读长块的安全冲刷上限：再攒下去客户端整段黑屏。 */
    private static final int SAFETY_FLUSH_CHARS = 200;

    private final List<String> references;
    private final Consumer<String> onEmit;
    private final StringBuilder emitted = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private int maskedCount;

    public VerbatimStreamFilter(List<String> references, Consumer<String> onEmit) {
        this.references = references;
        this.onEmit = onEmit;
    }

    /** 喂入一个 token；凑满的整句即刻经护栏判定后下发。 */
    public void accept(String token) {
        if (token == null || token.isEmpty()) return;
        pending.append(token);
        int cut;
        while ((cut = sentenceEnd(pending)) >= 0) {
            emit(pending.substring(0, cut + 1));
            pending.delete(0, cut + 1);
        }
        if (pending.length() >= SAFETY_FLUSH_CHARS) {
            emit(pending.toString());
            pending.setLength(0);
        }
    }

    /** 流末冲刷残句；返回掩码后的完整答案（缓存/审计/SF 分发唯一消费口径）。 */
    public String finish() {
        if (pending.length() > 0) {
            emit(pending.toString());
            pending.setLength(0);
        }
        return emitted.toString();
    }

    public int maskedCount() {
        return maskedCount;
    }

    private void emit(String candidate) {
        int carry = Math.min(emitted.length(), CARRY_CHARS);
        String window = emitted.substring(emitted.length() - carry) + candidate;
        if (VerbatimGuard.longestOverlap(window, references) > VerbatimGuard.MAX_OVERLAP_CHARS) {
            maskedCount++;
            push(VerbatimGuard.PLACEHOLDER);
        } else {
            push(candidate);
        }
    }

    private void push(String text) {
        emitted.append(text);
        onEmit.accept(text);
    }

    /** 句末判据：中英文句读/分号/换行；英文 "." 仅在跟随空白时算（保护版本号、小数）。 */
    private static int sentenceEnd(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("。！？；!?;\n".indexOf(c) >= 0) return i;
            if (c == '.' && i + 1 < s.length()
                    && (s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\n')) return i;
        }
        return -1;
    }
}
