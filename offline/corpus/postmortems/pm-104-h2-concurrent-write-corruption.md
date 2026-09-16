---
doc_id: pm-104
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51103_H2_CONCURRENT_WRITE_CORRUPTION]
date: 2026-09-16
severity: P1
---

# 复盘：账号库损坏——容器与宿主进程共写 H2，锁没跨过挂载点（51103_H2_CONCURRENT_WRITE_CORRUPTION）

## 事故摘要

网关在一次重启后无法启动：`JdbcSQLNonTransientConnectionException: File corrupted while reading
record: data/users.mv.db`，MVStore 报"无法恢复有效 chunk 集合"。触发链路很清楚：网关（容器形态）
正持有账号库，随后在**宿主**上跑了账号 CLI 写库（新增一个账号，命令返回成功、列表读回正常），
接着停容器——下一次打开即损坏。H2 自带的恢复工具也救不回来，账号表不可恢复。

## 时间线

- 网关容器已连续运行 34 小时，持有 `./data/users.mv.db`（compose 挂载 `./data:/app/data`）
- 宿主执行 `scripts/seed_demo_users.sh` → 走 `user_admin.sh` 直连同一文件，新增账号成功
- `docker compose stop gateway`（优雅停止）后，宿主启动 jar → 报库文件损坏
- 尝试 `org.h2.tools.Recover`：转储出的 chunk 位置与长度均为垃圾值（如单页长度 2GB），确认不可恢复
- 影响评估：H2 内只有 `users` 一张表、4 个演示账号，全部可由播种脚本重建 → 重建库并重灌账号

## 根因分析

H2 的 `AUTO_SERVER=TRUE` 用**数据库旁的锁文件**做多进程协调（首个进程当服务端，其余经 TCP 接入）。
本项目的账号 CLI 与服务端因此被文档认为"可并发"（OPS.md §1）。但在容器形态下，
该锁文件位于 Docker Desktop 的 bind mount 上：跨这条边界，文件锁与可见性都不可靠，
第二个进程可能**直接打开同一文件**而不是接入服务端 → 两个写者 → MVStore 页/chunk 结构被破坏。
容器停止时它自己的页缓存再落盘一次，把破坏固化下来。

判据：① 时间线严格对应"共写 → 停止 → 下次打开即损坏"；② H2 官方对 AUTO_SERVER 的适用前提是
**同一台机器上的进程共享文件系统语义**，而 bind mount 不保证这一点；③ 无任何其他进程在写该文件。
（未做进程级插桩，故记为"高度可疑的真实根因"，但无论哪一细节成立，结论都是"跨挂载点双写不可用"。）

## 修复措施

1. 短期（本次）：删损坏文件重建库 + 重播种四个账号；损坏文件留存为证据
   （`data/users.mv.db.corrupt-20260916.bak`），不做"删掉就当没事"
2. 流程约束：**账号 CLI 只在网关未运行时执行**（容器形态下先 `docker compose stop gateway`）；
   或改走 HTTP 管理面（容器内路径由服务端自己写，不跨边界）
3. 文档更正：OPS.md 的"CLI 与运行中网关并发无冲突"声明必须标注适用形态——
   宿主裸进程形态下成立，**容器 bind mount 形态下不成立**
4. 备份前置：账号库是唯一不可再生数据，动手前先备份（本次事后补做了 `users-2026-09-16.zip`）

## 复盘教训

- **跨边界的文件锁不可信**：容器 bind mount / 网络盘 / 共享目录上的文件锁一律按"不存在"处理，
  多进程写同一文件必须靠应用层协调（HTTP 接口）或串行化（先停服务）
- 文档里的"并发安全"是**强声明**，必须写明适用形态与验证方式；否则它会把风险伪装成已解决的问题
- 恢复工具救不回来的东西，只能靠备份；本次能重建纯属幸运（表可重建），
  若损坏的是不可再生表就是事故级。**先备份，再动手**
