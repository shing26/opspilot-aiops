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

# 3) 凭据就绪：口令在环境（P2 login 体系）+ 红队畸形样本文件可再生
[ -f .env ] && { set -a; . ./.env; set +a; }
[ -n "${DEMO_PASSWORD:-}" ] && ok "DEMO_PASSWORD 已设置" || bad "DEMO_PASSWORD 未设置（.env，seed/login 依赖）"
t=scripts/redteam_tokens.txt
if [ -f "$t" ]; then
  have=0; for k in sre_l0 sre_neg tenant_none tenant_blank tenant_long; do grep -q "^$k=" "$t" && have=$((have+1)); done
  [ "$have" = "5" ] && ok "红队畸形样本 5/5" || bad "红队样本不全（python scripts/gen_tokens.py > $t）"
else
  bad "缺 scripts/redteam_tokens.txt（gen_tokens.py 生成，不入库）"
fi

# 4) 鉴权面：无 token 的 admin 必须 401（裸 URL 不泄漏指标）
a=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/v1/admin/metrics || echo 000)
[ "$a" = "401" ] && ok "admin 匿名 401" || bad "admin 匿名返回 $a（期望 401）"

# 5) jar 与源码一致性（近似判据：源码/配置/pom 任一比 jar 新即提醒重建；
#    注意 git checkout 会批量刷新 mtime，可能误报——以验收实跑为准）
if [ -f target/opspilot-gateway-1.0.0.jar ]; then
  src=$(find src/main pom.xml -type f \( -name '*.java' -o -name '*.yml' -o -name 'pom.xml' \) \
        -newer target/opspilot-gateway-1.0.0.jar | head -1)
  [ -z "$src" ] && ok "jar 不早于源码/配置" || bad "jar 可能落后（$src 更新过）：mvn package -DskipTests 后重启"
else
  bad "缺 target jar（mvn package -DskipTests）"
fi

echo "---"
[ "$fails" = "0" ] && echo "READY：按 DEMO.md 六幕开演" || echo "预检 $fails 项失败，先修复"
exit "$fails"
