---
doc_id: rb-104
service: devops-local
env: local
auth_level: 1
error_codes: [51004_PORT_SQUATTER]
date: 2026-09-10
---

# 本地端口被第三方服务占用的处置手册（51004_PORT_SQUATTER）

## 适用症状

- 启动报 `Port 8080 was already in use`，或"启动成功"但响应来自别的服务（接口形状对不上）
- 本机常驻 nexus-web 等第三方占 8080——不是异常，是环境事实，别硬抢

## 排查步骤

1. `netstat -ano | grep ":8080"` 看 LISTENING PID → `Get-Process -Id` 认进程；必要时 `wmic process where processid=<pid> get commandline` 看启动命令
2. 判断性质：别人的常驻服务（让位）/ 自己上一轮的僵尸（杀掉）/ 系统保留段（`netsh interface ipv4 show excludedportrange protocol=tcp`，Hyper-V 会吃端口段）
3. 让位后全链路同步改口径：服务配置、验收脚本 BASE、文档、防火墙规则——**grep 一遍旧端口号防漏网**，这是"改代码不改文档"翻车高发点

## 止损操作

1. 默认策略：换端口而不是抢端口（本项目固定 8081 并在 README 注明原因，比每天搏一把稳）
2. 必须回收时：杀占用进程前确认无数据写入中
3. 端口段被 Hyper-V 保留：`net stop winnat → 启动服务 → net start winnat`，或永久排除该端口

## 环境事实备忘

OpsPilot 开发机：8080 = nexus-web（勿动），网关一律 8081。
