---
doc_id: pm-205
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51205_SINGLEFLIGHT_TENANT_GAP]
date: 2026-09-11
severity: P0
---

# 复盘：并发风暴下跨租户回放全文——单飞组键缺租户（51205_SINGLEFLIGHT_TENANT_GAP）

## 事故摘要

多角色黑盒测评（SRE / 平台管理员 / 外部租户三个 Persona 并行）中，外部租户账号在并发风暴场景下
**收到了内部租户的答案全文与引用列表**。这是权限泄漏级缺陷：引擎层的租户过滤完全正常，
串行测试全绿，唯独并发时出现——因为泄漏发生在"共享在途结果"这条旁路上。

## 时间线

- 单请求与串行用例：跨租户零泄漏，权限矩阵全绿
- 三 Persona 并发测评：外部租户偶发看到内部答案（可复现，但只在同指纹并发时）
- 定位到 Single-Flight：同指纹同密级、不同租户的两个请求被并入同一组，
  follower 直接复用 leader 的结果
- 修复组键并加回放绊线后，跨租户并发用例复验零泄漏

## 根因分析

Single-Flight 的组键是 `fingerprint + authLevel`，**没有租户**。而 fingerprint 由
`service + env + 归一化 query` 算出——同一条告警文案在两个租户下会得到同一个指纹，
密级又恰好相同，于是两个租户的请求被判定为"同一个飞行"：
leader（内部租户）命中高密级知识后，follower（外部租户）**原样回放**了它的答案与引用。

为什么串行永远测不出来：串行时每个请求各自走完整链路，引擎层的 `tenant` 硬过滤逐一拦下；
而并发时短路的正是"根本不走引擎层"的那条路。**权限维度只在引擎层做过滤，
就等于给共享旁路留了后门。**

## 修复措施

1. 组键改为 `租户:指纹:密级`（租户设为第一字段），与 L1 缓存键 `cache:l1:<tenant>:<level>:` 同构：

```java
static String singleFlightKey(UserContext user, String fp) {
    return user.tenantId() + ":" + fp + ":" + user.authLevel();
}
```

2. 加**回放绊线**（fail-closed，纵深防御）：回放前校验载荷租户与请求者租户，
   不等则拒绝回放、error 收尾、审计告警——即使将来又出现别的无租户旁路，也不会静默泄露
3. 审计补 `src_tenant`：`src_tenant != tenant` 即为跨租户共享旁路的可 grep 告警面
4. 回归锁：跨租户并发用例（leader 在途时 follower 必须拿到自己的答案）+ 组键口径用例

## 复盘教训

- **权限模型的每一维都必须在每条跨请求共享路径上同构存在**（缓存键、单飞组键、回放载荷、
  去重窗口），漏一条 = 没有；"引擎层过滤了"不等于"所有出口都过滤了"（ADR-0008）
- 串行全绿**不能**证明并发安全：竞态类缺陷要靠并发用例、靠换维度（多租户并行）去撞
- 修复要带绊线：改对 key 是治本，但**加上 fail-closed 的断言**才能防下一处同类漏洞
