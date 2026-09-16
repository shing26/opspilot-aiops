---
doc_id: pm-201
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51201_CIRCUIT_BREAKER_NO_SELFHEAL]
date: 2026-09-13
severity: P1
---

# 复盘：熔断冷却到期仍恒判 L2，降级状态机永不自愈（51201_CIRCUIT_BREAKER_NO_SELFHEAL）

## 事故摘要

生成质量包的 live 验收阶段发现：一次上游触发熔断后，即使上游恢复、冷却窗口早已过期，网关仍持续以
`degradation_level=L2` 直出静态 SOP，**LLM 一次都不再被调用**。系统看起来"在降级保护"，实际是永久躺平——
把一次 60 秒的抖动变成了无限期的能力降级，而外部观测（面板、health）只显示"有个降级档位"，看不出异常。

## 时间线

- mock 期一路正常：mock 后端不会 429，熔断分支从未被真正走完一轮
- 接真实上游后人为制造连续失败 → 失败计数达阈值 3 → 进入 L2 冷却 60s（符合预期）
- 冷却到期后再发请求：仍判 L2，且 `runtime.degradation.failures` 停在 3 不再变化
- 用探针连续复测三次结果一致，排除偶发

## 根因分析

状态机 `current()` 的判定顺序是"先看冷却截止时间、再看失败计数"，而失败计数只在 `llmSuccess()`
里归零。L2 分支**根本不调 LLM**，于是 `llmSuccess()` 永远没有机会执行——失败计数没有归零路径，
冷却到期后 `failures >= threshold` 恒成立，立刻又落回 L2。这是一个自锁环：
**进入 L2 的手段堵住了退出 L2 的唯一出口。**

## 修复措施

冷却到期时主动清零失败计数（半开放行），让下一次真实调用决定档位：

```java
if (now < until) return Level.L2;
if (until > 0 && llmConsecutiveFailures.get() >= props.degrade().llmFailureThreshold()) {
    llmConsecutiveFailures.set(0);   // 半开放行：随后由 llmSuccess/llmFailure 重定档
}
```

`until > 0` 守卫防的是反向陷阱：初值 0 被随手清零会让"从未熔断过"也走放行路径。
回归锁 = `DegradationRecoveryTest`（3 用例）。

## 复盘教训

- **每个"进入"动作都要能指出对应的"退出"动作**，指不出就是自锁环；画不出退出路径的状态转移不要合入
- 只在一端归零的计数器，必须确认失败分支也会流经该端——L2 绕开的正是唯一归零出口
- 纸面闭环不算闭环：mock 后端不会触发的分支等于未测分支，只有 live 验收能推翻它
