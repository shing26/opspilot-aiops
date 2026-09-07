---
doc_id: pm-012
service: user-service
env: prod
auth_level: 2
error_codes: [50091_JVM_GC_PAUSE]
date: 2026-06-15
severity: P3
---

# 用户服务长 GC 停顿复盘（50091_JVM_GC_PAUSE）

## 事故摘要

2026-06-15 下午，user-service 周期性出现 2-4s 接口卡顿，登录与资料查询超时，GC 日志确认 Full GC 停顿，错误码 `50091_JVM_GC_PAUSE`。

## 根因分析

本地缓存（Caffeine）未设上限，用户画像对象堆积至老年代，G1 频繁 Mixed GC 后仍触发 Full GC：

```
[GC pause (G1 Evacuation Pause) 1842.31 ms]
[Full GC (Allocation Failure) 3921.77 ms]   error code 50091_JVM_GC_PAUSE
```

```
com.ordercenter.user.ProfileService.getProfile(ProfileService.java:41)
WARN  request took 4100ms, suspected GC pause
```

## 修复措施

1. Caffeine 设 `maximumSize=10000` + `expireAfterWrite=10m`。
2. 切换 ZGC（`-XX:+UseZGC`），停顿目标 <1ms。
3. GC 停顿 >500ms 自动上报 `50091_JVM_GC_PAUSE`。

## 复盘教训

- 无上限本地缓存 = 慢性内存泄漏，`50091_JVM_GC_PAUSE` 是晚期症状。
- 大堆低延迟场景应直接上 ZGC，G1 调参收益有限。
