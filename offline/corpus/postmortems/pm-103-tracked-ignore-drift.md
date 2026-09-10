---
doc_id: pm-103
service: devops-local
env: local
auth_level: 1
error_codes: [51102_TRACKED_IGNORE_DRIFT]
date: 2026-09-09
severity: P3
---

# 复盘：gitignore 对已跟踪文件无效造成的凭据入库漂移（51102_TRACKED_IGNORE_DRIFT）

## 事件概述

仓库自述"演示 token 不入库、可再生"，实际 `scripts/demo_tokens.txt`（HS256 签名的合法 token）躺在 git 跟踪里。根因时序：先提交入库 → 后加 ignore 规则 → ignore 对**已跟踪**文件不生效 → 文档自述与现实悄悄分叉。

## 排查陷阱

- `git check-ignore <file>` 对已跟踪文件默认**返回空**（它查 index），会误判成"规则没写对"甚至"没被跟踪"
- 正确三板斧：`git ls-files <path>`（是否被跟踪）→ `git log --all --oneline -- <path>`（何时进入）→ `git log --all -S "<内容指纹>"`（哪些提交含字面量）

## 处置

1. `git rm --cached` 摘除跟踪（磁盘文件保留，读盘脚本不受影响）
2. 历史残留评估：泄漏 token 是否**实战可用**才是优先级——本项目 P2 上线的 token_ver 校验顺带让全部旧 token 失效（缺 tver claim → 入口 401），"必须清洗"降级为"观感合规"
3. 但公开 push 前仍做物理抹除：`git filter-repo --invert-paths --path <文件>`（token 只在该文件时，外科式移除优于 replace-text），清洗**先于**首推则无需 force-push

## 防复发教训

- gitignore 是**入口管制**不是**清除命令**；加规则那一刻就要检查目标是否已被跟踪
- "文档声称不入库"必须配一条可执行验证（预检脚本里 `git ls-files | grep -c 凭据路径` 应恒为 0）
- 密钥泄漏的处置顺序：先让泄漏物**失效**（吊销/版本化），再谈抹历史——止血永远先于善后
