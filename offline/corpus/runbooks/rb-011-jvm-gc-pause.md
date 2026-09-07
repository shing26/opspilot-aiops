---
doc_id: rb-011
service: user-service
env: prod
auth_level: 2
error_codes: [50091_JVM_GC_PAUSE]
---

# JVM GC 停顿排查手册（50091_JVM_GC_PAUSE）

## 适用症状

- 接口周期性卡顿，日志 `request took 4100ms, suspected GC pause`
- 错误码 `50091_JVM_GC_PAUSE`

## 排查步骤

### 第一步：看 GC 日志

```bash
grep -E "Full GC|Evacuation Pause" /var/log/app/gc.log | tail -20
```

Full GC 频繁 + 回收后老年代仍高水位 → 内存泄漏或缓存无上限。

### 第二步：堆使用分布

```bash
jstat -gcutil <pid> 1000 5
```

`O`（老年代）持续 >90% 且 `FGC` 增长 → 确认问题。

### 第三步：定位大对象

```bash
jmap -histo:live <pid> | head -20
```

## 止损操作

1. 临时扩容实例分摊堆压力。
2. 无上限本地缓存紧急设 `maximumSize`（见 pm-012）。
3. 长期：G1 → ZGC 迁移，停顿目标 <1ms。

## 升级路径

- Full GC 后老年代不下降 → 确认泄漏，抓 heap dump 给应用 Owner。
- 停顿 >5s 影响 SLA → P2 升级。
