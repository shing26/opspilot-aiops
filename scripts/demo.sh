#!/usr/bin/env bash
# 演示预检：只读检查，零 LLM/embedding 成本。全 PASS 后按 DEMO.md 开演。
set -u
cd "$(dirname "$0")/.."
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; fails=$((fails+1)); }

# 1) 中间件容器
for c in opspilot-redis opspilot-qdrant opspilot-es; do
  st=$(docker ps --format '{{.Names}} {{.Status}}' | grep "^$c " || true)
  [ -n "$st" ] && ok "$c 运行中" || bad "$c 未运行（docker compose up -d）"
done

# 2) 服务健康
h=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health || echo 000)
[ "$h" = "200" ] && ok "网关 UP (8081)" || bad "网关未响应（见 DEMO.md §0 启动）"

# 3) 凭据文件四 token
t=scripts/demo_tokens.txt
if [ -f "$t" ]; then
  have=0; for k in sre_l1 sre_l3 sre_l0 sre_neg; do grep -q "^$k=" "$t" && have=$((have+1)); done
  [ "$have" = "4" ] && ok "demo_tokens 4/4" || bad "demo_tokens 缺 token（python scripts/gen_tokens.py > $t）"
else
  bad "缺 scripts/demo_tokens.txt（不入库，需生成，见 DEMO.md）"
fi

# 4) 鉴权面：无 token 的 admin 必须 401（裸 URL 不泄漏指标）
a=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/v1/admin/metrics || echo 000)
[ "$a" = "401" ] && ok "admin 匿名 401" || bad "admin 匿名返回 $a（期望 401）"

# 5) jar 与 HEAD 一致性（防止演示旧构件）
if [ -f target/opspilot-gateway-1.0.0.jar ]; then
  src=$(find src/main -name '*.java' -newer target/opspilot-gateway-1.0.0.jar | head -1)
  [ -z "$src" ] && ok "jar 不早于源码" || bad "jar 落后于源码（$src 更新过）：mvn package -DskipTests 后重启"
else
  bad "缺 target jar（mvn package -DskipTests）"
fi

echo "---"
[ "$fails" = "0" ] && echo "READY：按 DEMO.md 六幕开演" || echo "预检 $fails 项失败，先修复"
exit "$fails"
