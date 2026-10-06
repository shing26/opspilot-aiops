---
doc_id: rb-102
service: opspilot-gateway
env: local
auth_level: 1
error_codes: []
---

# 容器被 OOM 杀 → 中间件连接拒绝 → 网关 500/503（本机 2026-10-04 真实事故，两连发）

## 真实签名（不是演练，当天发生了两次）

```
docker ps -a
  opspilot-redis   Exited (0)    # 或 Exited (137)
  opspilot-qdrant  Exited (143)
  opspilot-es      Exited (137)  # 137 = SIGKILL，即被内核 OOM 杀
```

网关日志（进程本身通常还活着，端口仍在 LISTENING）：

```
RedisConnectionException: Connection refused: localhost/127.0.0.1:6379
io.netty.channel.StacklessClosedChannelException
WriteRedisConnectionException / RedisTimeoutException: PING
```

对外表现：`chat` 全 500；`/actuator/health` 可能 503 也可能还是 200；
**Ops Console 面板报「网关不可达：HTTP 500」**——注意是 500 不是 401，
别误判成凭证/权限问题。

## 为什么会发生

宿主内存不足时内核按 OOM score 杀容器。本机 16GB 内存，Docker 栈
（OpsPilot 1.6GB + ShopPilot 栈 + nexus 栈）加桌面应用很容易把可用内存压到 1GB 以下。
**Redis 和 Qdrant 无卷（数据可再生）**，被杀后重启即恢复，但 Redis 重启会清空
当日配额与 SOP 预热（L1/L2 缓存随之冷启动），审计 JSONL 在盘上不受影响。

## 处置顺序（照做，别跳步）

1. **先看宿主可用内存**：`powershell -NoProfile -c "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1KB"`
   ——低于 2GB 先腾（停不用的栈），否则拉起来还会被杀。
2. `docker compose up -d redis qdrant elasticsearch`（在 OpsPilot 仓根）。
3. 等 redis/es 转 healthy。**此时 health 仍可能短暂 503**——Redisson 重连需要十几秒，
   属正常，**别在这时候去重启网关**（那是把正常恢复流程误判成故障）。
4. Qdrant 无 healthcheck，`Up` 即可；网关日志里它的报错是
   `Get collection info operation failed`，恢复后自停。
5. 用 `python scripts/liveness.py --once` 复核（身份+存活双信号）。

## 易混项

- **JVM 被外部杀掉时日志没有 OOM 异常**——日志停在启动横幅就断，说明是进程被杀而非内部错误。
- 端口仍 LISTENING ≠ 网关健康：进程活着但中间件全断时，health 503、chat 500。
  先 `netstat` 确认端口在听，再看日志找中间件错误，**不要因为 health 不通就以为网关没了**。
