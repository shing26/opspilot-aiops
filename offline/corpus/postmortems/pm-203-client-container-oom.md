---
doc_id: pm-203
service: lobe-chat
env: local
auth_level: 1
error_codes: [51203_CONTAINER_MEM_BUDGET]
date: 2026-09-11
severity: P2
---

# 复盘：客户端容器 512m 限额触发 OOM 崩溃循环，表现为"服务起不来"（51203_CONTAINER_MEM_BUDGET）

## 事故摘要

给网关配 Web UI 客户端（`lobehub/lobe-chat`）时，容器进入崩溃-重启循环，`docker ps` 反复显示
`Restarting`，日志尾部没有任何异常栈——看起来像"应用启动失败"，实际是**被内核 OOM 杀掉的**。
排查方向一度跑偏到应用配置与端口占用上。

## 时间线

- 按"轻量 UI"直觉给 `mem_limit: 512m`，首次启动看似正常
- 打开一次会话（触发 pdfjs 等重型初始化）后容器反复重启
- 容器日志无异常退出信息，应用层日志停在初始化中途
- 查容器状态字段发现 `OOMKilled=true`，确认是内存被杀

## 根因分析

Node 进程的堆上限默认按物理内存推算，容器里可达 ~256M；该客户端在页面初始化时会加载
pdfjs 一类重依赖，峰值内存瞬时就顶到 512m 限额之上 → 内核 OOM Killer 直接 SIGKILL。
**崩溃不是应用错误，而是内存预算错配**；而"日志没有异常"恰恰是 OOM 的特征：
进程没有机会打印任何东西。

## 修复措施

1. 按**峰值装载**而不是空闲占用给预算：该客户端需要 2g（`mem_limit` 上调或限制堆）
2. 后续评估发现 `/v1` 协议面自足（网关自己就能对外提供 OpenAI 兼容接口），
   UI 容器属可选件 → 从基础栈撤除，内存预算回到 1.6GB 量级（ADR-0007 状态注）

## 复盘教训

- **内存限额要按峰值给**：容器里 node/python 的默认堆上限会随宿主内存浮动，"看起来给够了"经常不够
- `Restarting` + 日志无异常 = 优先怀疑 OOMKilled，而不是先怀疑应用 bug；查 `docker inspect` 的 `State.OOMKilled`
- 依赖项要问"没有它行不行"：撤掉一个可选容器，比长期给它多付 2g 预算更划算
