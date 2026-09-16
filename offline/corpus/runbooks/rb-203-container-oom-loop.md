---
doc_id: rb-203
service: lobe-chat
env: local
auth_level: 1
error_codes: [51203_CONTAINER_MEM_BUDGET]
date: 2026-09-11
---

# 容器反复重启（疑似 OOMKilled）排查手册（51203_CONTAINER_MEM_BUDGET）

## 适用症状

- `docker ps` 显示 `Restarting (n)` 且 n 持续增长，服务始终不可用
- 容器日志尾部停在初始化中途，**没有异常栈、没有退出码说明**
- 宿主内存吃紧，或同机其他容器开始变慢
- 打开某个重型页面/触发某类请求后才开始循环（空闲时正常）

## 排查步骤

### 第一步：先判定是不是被内核杀掉

1. `docker inspect <容器> --format '{{.State.OOMKilled}} {{.State.ExitCode}}'`：`true` 即 OOM（退出码通常 137）
2. `docker inspect <容器> --format '{{.HostConfig.Memory}}'` 看内存限额；`0` 表示未限制（此时要查宿主整体内存）
3. 看宿主事件：`docker events --filter container=<名>` 或系统日志里的 oom-kill 记录
4. **关键区分**：日志无异常 ≠ 应用没问题，但"无异常 + 循环重启 + ExitCode 137"基本可锁定被杀

### 第二步：定位峰值在哪里

5. 容器内进程的堆上限：Node 看 `NODE_OPTIONS` 与 `--max-old-space-size`，JVM 看 `-Xmx`
6. 复现触发点：是空闲就被杀（配置太小）还是某个功能一开就被杀（峰值装载）
7. 对比限额与峰值：限额必须大于峰值 + 余量，而不是大于空闲占用

### 第三步：判断这个容器是否必须存在

8. 它对外的能力是否有替代面（本系统 `/v1` OpenAI 兼容面自足，UI 容器属可选）
9. 若可选 → 撤除比长期扩容更省；若必须 → 按峰值上调限额

## 止损操作

1. 立即恢复：`mem_limit` 上调到峰值以上（示例：客户端 UI 从 512m 提到 2g），或限制进程堆：
   `environment: NODE_OPTIONS=--max-old-space-size=384`（配合 512m 限额）
2. 重启后确认：`docker ps` 状态稳定为 `Up`，且触发原来那个重型操作不再重启
3. 宿主层面观察一会：`docker stats` 看实际峰值是否贴近新限额（贴近说明还得留更多余量）
4. 临时降载：若与关键服务抢内存，先停掉该可选容器，保证网关与中间件稳定

## 升级路径

- 调大后仍被杀 → 排查内存泄漏（长跑观察 RSS 曲线），或换更轻的替代组件
- 属"可选依赖拖垮主链路"→ 提交架构评估：可选件是否应移出基础栈、按需启动
