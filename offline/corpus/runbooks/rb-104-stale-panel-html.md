---
doc_id: rb-104
service: opspilot-gateway
env: local
auth_level: 1
error_codes: []
---

# 面板改版后浏览器显示的还是旧版（2026-10-05 真实事故，连撞两次）

## 真实签名

- 后端 jar 已重新打包重启（pid 变了、日志有 `Started OpsPilotApplication`），
  但浏览器里的面板还是旧布局。
- `curl http://localhost:8081/ | grep <新版的特征串>` **能找到**（服务端是新 HTML），
  浏览器里却看不到——差异只在浏览器侧。

## 根因

静态资源没配 `Cache-Control` 时，浏览器对 `index.html` 走 **Last-Modified 启发式缓存**：
文件改动时间离得近就大胆缓存。对面板来说这是正确性问题不是观感问题——
**改完版部署上去，运维看到的仍是上一版布局，还会误以为改动没生效**。

## 处置

1. 已修（2026-10-05）：`application.yml` 配了
   `spring.web.resources.cachecontrol.no-cache + must-revalidate`，响应头应为：
   `Cache-Control: no-cache, must-revalidate`
2. 验证：`curl -s -D - -o /dev/null http://localhost:8081/ | grep -i cache-control`
3. 若仍在旧版（低版本浏览器/中间代理）：强刷（Ctrl+F5）一次即可，之后不再复现。

## 通用教训

**"服务端是对的、浏览器是旧的"这类事故，先用 `curl -D -` 看响应头再动前端代码。**
判断依据是头部字段，不是刷新几遍的感觉。
