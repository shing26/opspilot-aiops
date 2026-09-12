package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;

/** P 收尾：OpenAI 帧协议正确性锁——role 首帧/内容帧/refs 注入/stop/[DONE]/异常收尾/断开容错。 */
class OpenAiChatSinkTest {

    /** 捕获型 emitter：记录每个 data 值与 complete 调用，不触碰真实 HTTP。 */
    static class Capturing extends SseEmitter {
        final List<String> frames = new ArrayList<>();
        boolean completed = false;

        @Override
        public void send(SseEventBuilder builder) {
            // 一个逻辑帧的 build() 会展开成多个载体 entry（event 前缀/data/mediaType），
            // 测试只收 JSON chunk 与 [DONE] 两类载荷
            for (Object o : builder.build()) {
                Object d = ((DataWithMediaType) o).getData();
                if (d instanceof String s && (s.startsWith("{") || s.equals("[DONE]"))) {
                    frames.add(s);
                }
            }
        }
        @Override
        public void complete() { completed = true; }
    }

    @Test
    void frameSequenceMatchesOpenaiContract() {
        Capturing em = new Capturing();
        OpenAiChatSink sink = new OpenAiChatSink(em, "opspilot");

        sink.delta("你好");
        sink.delta("，同学");
        sink.done(0L, 1L, List.of(new AnswerPayload.Ref("rb-001::x", "订单超时手册", "order-service")));

        assertEquals(5, em.frames.size(), "role+content / content / refs / stop / [DONE]");
        String first = em.frames.get(0);
        assertTrue(first.contains("\"role\":\"assistant\""), "首帧必须携带 role");
        assertTrue(first.contains("\"object\":\"chat.completion.chunk\""));
        assertTrue(em.frames.get(1).contains("，同学"));
        // QA 第五轮线卫生：内容帧不得携带 finish_reason:null（Choice 层 NON_NULL）
        assertFalse(em.frames.get(1).contains("finish_reason"), "内容帧无 finish_reason 键");
        assertTrue(em.frames.get(2).contains("## 参考来源"), "refs 必须以 markdown 注入正文尾部（溯源保留）");
        assertTrue(em.frames.get(2).contains("order-service"));
        assertTrue(em.frames.get(3).contains("\"finish_reason\":\"stop\""), "stop 帧");
        assertFalse(em.frames.get(3).contains("\"content\""), "stop 帧 delta 规范为 {}（空串是私货）");
        assertFalse(em.frames.get(3).contains(":null"), "stop 帧无 null 泄漏");
        assertEquals("[DONE]", em.frames.get(4));
    }

    @Test
    void doneAndFailFastFrames() {
        Capturing em = new Capturing();
        OpenAiChatSink sink = new OpenAiChatSink(em, "opspilot");
        sink.delta("半句");
        sink.error(new RuntimeException("upstream boom"));

        String joined = String.join("|", em.frames);
        assertTrue(joined.contains("【系统提示】"), "异常必须以文本帧呈现而非裸断流");
        assertTrue(joined.contains("\"finish_reason\":\"stop\""));
        assertTrue(em.frames.get(em.frames.size() - 1).equals("[DONE]"));
        assertTrue(em.completed);
        // error 收尾后再投递必须静默（finished 闩）
        int before = em.frames.size();
        sink.delta("late");
        assertEquals(before, em.frames.size(), "[DONE] 后不得再发帧");
    }

    @Test
    void metaIsDroppedWithoutSideEffects() {
        Capturing em = new Capturing();
        new OpenAiChatSink(em, "opspilot")
                .meta("fp", "none", com.opspilot.resilience.DegradationStateMachine.Level.L0, false, false, 0);
        assertTrue(em.frames.isEmpty(), "meta 在 OpenAI 帧无位置：丢弃且不产生输出");
    }
}
