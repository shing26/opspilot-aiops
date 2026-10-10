package com.opspilot.storm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Single-Flight 契约（ADR-0003/0008/0015）：同 key 并发请求只有一个 leader 穿透，
 * follower 等待并复用其结果。key 口径由 ChatOrchestrator 单点构造
 * （tenant:fingerprint:authLevel），本接口不感知语义。
 *
 * <p><b>实现双轨</b>（ADR-0015）：
 * <ul>
 *   <li><b>进程内</b>（当前默认，{@code SingleFlightRegistry}）：
 *       {@link ConcurrentHashMap} + {@link CompletableFuture}，单 JVM 极限性能，
 *       无跨进程语义。</li>
 *   <li><b>分布式</b>（预留演进）：Redis Redlock 选主 + Pub/Sub 广播结果。
 *       leader 租约到期自动接管，follower 订阅频道完成本地 future。
 *       接口形状为此形态设计——{@link #finish} 的 key/future 双参即 leader 交接点。</li>
 * </ul>
 * 权限三元组（tenant×fingerprint×authLevel）在 key 层内置，分布式实现不得拆散。
 */
public interface SingleFlight {

    /** 同 key 并发的共享结果句柄；{@code leader=true} 表示本次调用承担执行职责。 */
    record Registration(CompletableFuture<String> future, boolean leader) {}

    /** 取或建在途组。首到者为 leader，其余为 follower（复用既有 future）。 */
    Registration getOrCreate(String key);

    /** leader 完成后必须调用（finally），移除在途记录并释放 future。 */
    void finish(String key, CompletableFuture<String> future);

    /** 只读视图（Ops Console）：当前在途组数。无副作用。 */
    int inFlightGroups();

    /** 只读视图：在途 key 的指纹前 8 位（组键=tenant:fp:level，tenant 展示、fp 截断防广播内部内容）。 */
    List<String> peekKeys(int n);
}
