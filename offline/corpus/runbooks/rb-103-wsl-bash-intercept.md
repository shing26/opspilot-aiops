---
doc_id: rb-103
service: devops-local
env: local
auth_level: 1
error_codes: [51003_WSL_BASH_INTERCEPT]
date: 2026-09-10
---

# 脚本里 bash 被解析到 WSL 的排查手册（51003_WSL_BASH_INTERCEPT）

## 适用症状

- Python/Node 里 `subprocess.run(["bash", "script.sh"])` 报 `execvpe(/bin/bash) failed` 或 WSL 字样的 Relay 错误
- 同一条命令在 Git Bash 终端里手跑完全正常——"我这边能跑"的经典分裂

## 排查步骤

1. 看报错原文：出现 `WSL (...) Relay ERROR` / `/bin/bash: No such file` = 子进程 PATH 解析到 WSL 的 bash 垫片，不是脚本问题
2. Windows 上 `where bash` 通常有多项：Git\bin\bash.exe 与 WSL 垫片并存，**不同父进程的 PATH 顺序不同**，解析结果就不同
3. 确认父进程环境：从 IDE/服务里 spawn 的子进程 PATH 往往和你手动开的终端不一样

## 止损操作

1. 首选：编排逻辑放回 Git Bash 里直接跑（bash 调 python，而不是 python 调 bash）——方向反着调最省事
2. 必须程序内调用时用绝对路径：`C:\Program Files\Git\bin\bash.exe`
3. 或临时清掉 PATH 里的 WSL shim（`System32\wsl.exe` 相关条目），慎用以免影响其它工具

## 教训

跨进程边界的调用，解释器/运行时一律**绝对路径或固定方向**；"手动能跑"不构成对 spawn 的证明。
