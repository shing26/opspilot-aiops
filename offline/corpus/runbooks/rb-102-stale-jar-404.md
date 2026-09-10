---
doc_id: rb-102
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51002_STALE_JAR_404, 51004_PORT_SQUATTER]
date: 2026-09-10
---

# 端口上跑着旧 jar 的 404 排查手册（51002_STALE_JAR_404）

## 适用症状

- 代码明明加了新端点，curl 返回 404；但 `git log` 显示改动已提交
- 更隐蔽的变体：端点存在、返回的却是**旧版本行为**（如新字段不在响应里）——404 反而算好事

## 排查步骤

1. 先验证"服务身份"而不是"端口活着"：`GET /api/v1/admin/metrics` 看响应结构里**有没有你新加的那个字段**（本项目曾因旧 jar 占 8081，新端点全 404，而 health 却 200）
2. `netstat -ano | grep :8081` 拿到 LISTENING 的 PID，`Get-Process -Id <pid>` 确认是谁——多 session 并行开发时，旧进程可能比你的新构建先启动
3. 确认 jar 构建时间 vs 源码时间：`ls -la target/*.jar` 对照最近一次 `git commit` 时间；Windows 下 `mvn package` 失败常被 java 进程占用 jar 导致（rename .original 失败），**构建失败≠没构建**
4. 改完代码后的标准动作：先杀旧进程再打包（jar 被占）→ 启动 → **验证到身份级**（新字段/新端点在场），health 200 不代表新代码

## 止损操作

1. `powershell "Get-Process java | Stop-Process -Force"` 清场 → 重新 `mvn package` → 启动新 jar
2. 若 8080 被其它服务占用是既定环境事实：OpsPilot 固定 8081（`server.port=${SERVER_PORT:8081}`），不要往 8080 上改
3. 演示/验收前必跑 `bash scripts/demo.sh`——预检第 5 项专查 jar 是否早于源码

## 适用变体

任何"改了没生效"的服务端排障通用：**先证伪旧进程占端口，再怀疑自己的代码。**
