---
doc_id: rb-105
service: opspilot-gateway
env: local
auth_level: 1
error_codes: []
---

# 打包后起的是"错的东西"：JVM 锁 jar 导致截断的 jar（本机 2026-09-28 真实事故）

## 真实签名

```
mvn package 输出里 repackage 静默失败（或被忽略）
ls -l target/opspilot-gateway-1.0.0.jar   →  217 KB   ← 正常应约 80 MB
java -jar ...                              →  起得来，但起的不是完整应用
```

当晚实测：84MB 的 jar 被打成 217KB——Windows 上**旧 JVM 还锁着 target/*.jar** 时，
`spring-boot:repackage` 覆盖不了它，留下一个被截断的 jar。此后怎么起都是错的东西，
且没有明显报错指向根因，极易顺着错误方向排查很久。

## 处置（顺序是硬要求）

1. **先按端口停服务**：`netstat -ano | grep :8081` 取 pid → `taskkill //F //PID <pid>`
2. `mvn -q package -DskipTests`
3. **打包后核一眼大小**：约 80MB 才对；两百来 KB 必是截断件，重来
4. `bash scripts/start_gateway.sh`

## 连带坑：别信 /actuator/health 判断"起没起来"

只看 health 或 /state 可能读到**上一个还活着的实例**——当晚因此把"三种配额覆写方式都无效"
当成了结论，实际是旧实例幽灵（新进程早已因端口占用而 `APPLICATION FAILED TO START`）。
**启动成功以日志里的 `Started OpsPilotApplication` 为唯一判据**（start_gateway.sh 已固化）。

## 为什么 CLI 会话里 jar 会被锁

Windows 的文件锁是进程级的：任何 java 进程 `-jar` 这个文件期间它都被锁。
后台起的网关（nohup/disown）、IDE 的运行配置、上次没杀干净的孤儿 JVM，都会是锁的来源。
`Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 逐个核对命令行再决定杀谁。
