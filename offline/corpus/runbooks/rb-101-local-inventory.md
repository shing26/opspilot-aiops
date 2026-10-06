---
doc_id: rb-101
service: docker-host
env: local
auth_level: 1
error_codes: []
---

# 本机辖区清单：这台机器上跑着什么（2026-10-05 实测核对）

## 为什么需要这篇

排障的第一问永远是"这是谁的服务、端口归谁"。本机是**多项目共存的开发机**，
端口与容器名分属不同项目——不知道这张表，就会把别人的进程当成自己的旧实例
（实测把 ShopPilot 的 503 误判成"OpsPilot 半死"，绕了两小时）。

## 端口占用表（实测，改布局须同步更新）

| 端口 | 归属 | 说明 |
| --- | --- | --- |
| 8080 | nexus-web（另一项目） | 别当 OpsPilot 的口 |
| 8081 | **OpsPilot 网关（规范端口）** | application.yml 默认值，与 compose/README 同口径 |
| 8091 | **ShopPilot 的 biz-mock JVM** | 它的 `/actuator/health` 回 **503**——极易被误读成自己网关半死 |
| 8092 | 被占 | 归属未查明 |
| 6379 / 6333-6334 / 9200 | OpsPilot 中间件 | opspilot-redis / opspilot-qdrant / opspilot-es |
| 16379 / 16333 / 19200 | ShopPilot 中间件 | shoppilot-* 整体错开，不与 OpsPilot 冲突 |
| 5433 / 6380 | moa-gateway 的 postgres/redis | |

## 容器与进程

- **OpsPilot**：中间件走容器（`opspilot-redis/qdrant/es`，compose 管理），网关走**宿主裸进程 jar**
  （本机 Docker 拉不到 base 镜像，容器网关形态不可复现——2026-09-16 实测取舍）。
  起停一律 `bash scripts/start_gateway.sh`（端口占用预检 + 以日志 Started 为唯一成功判据）。
- **ShopPilot**：三个 JVM 是宿主裸进程（shoppilot-gateway / shoppilot-ticket / shoppilot-biz-mock，
  命令形如 `-jar D:\ShopPilot\...\target\*.jar`），中间件走容器。
- **nexus 栈**：nexus-web(8080) / grafana / prometheus / alert-bridge / es 7.17 / ollama。

## 判"这个端口上是谁"的固定动作

```bash
netstat -ano | grep :<端口>        # 取 LISTENING 的 pid
powershell "Get-CimInstance Win32_Process -Filter 'ProcessId=<pid>'"   # 看命令行
```

命令行里是哪个项目的 jar/镜像名，端口就归谁。**不要用 `/actuator/health` 判断归属**——
任何 Spring Boot 应用的 health 都返回同样的 `{"status":"UP"}`；身份锚点用面板壳：
`GET /` 的 HTML 里含 `OpsPilot` 才是本系统（`scripts/liveness.py` 已固化此判据）。
