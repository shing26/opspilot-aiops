package com.opspilot.storm;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 进程内 Single-Flight（ADR-0003）：key = tenant + fingerprint + authLevel。
 * leader 内联执行全链路并 complete future；follower 等待并复用结果。
 * 掺 authLevel 防低权限等待者复用高权限答案；掺 tenant 防跨租户回放
 * （2026-09-11 QA P0-1 修订：key 曾缺 tenant，告警风暴并发下外来租户拿到内部租户全文）。
 * 口径由 ChatOrchestrator.singleFlightKey 单点构造，本类不感知语义。
 */
@Service
public class SingleFlightRegistry {

    public record Registration(CompletableFuture<String> future, boolean leader) {}

    private final ConcurrentHashMap<String, CompletableFuture<String>> flights = new ConcurrentHashMap<>();

    public Registration getOrCreate(String key) {
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> existing = flights.putIfAbsent(key, mine);
        if (existing != null) {
            return new Registration(existing, false);
        }
        return new Registration(mine, true);
    }

    /** leader 完成后必须调用，移除 in-flight 记录。 */
    public void finish(String key, CompletableFuture<String> future) {
        flights.remove(key, future);
    }

    /** 只读视图（Ops Console）：当前在途组数。无副作用。 */
    public int inFlightGroups() {
        return flights.size();
    }

    /** 只读视图：在途 key 的指纹前 8 位（组键=tenant:fp:level，tenant 展示、fp 截断防广播内部内容）。 */
    public java.util.List<String> peekKeys(int n) {
        return flights.keySet().stream().limit(n).toList();
    }
}
