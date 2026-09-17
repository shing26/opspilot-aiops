#!/usr/bin/env bash
# OpsPilot 一键冷启动（容器形态；"clone 到任何机器"的可复现入口，公开前计划 S2）。
# 幂等：.env 仅缺失时生成（绝不覆盖已有凭据）/ compose up -d 复用现栈 / seed 已存在即跳过 / 红队 token 再生成（该文件不入库）。
# 默认零 LLM 成本：DASHSCOPE_API_KEY 留空 = mock 词法后端（同一代码路径）——复现的是机制全链路；
# README「核心指标」表为 DashScope live 实测口径，需自备 key 复跑，mock 环境不承诺该表数字。
set -euo pipefail
cd "$(dirname "$0")/.."

say() { echo "==> $*"; }
die() { echo "FAIL: $*" >&2; exit 1; }

docker info >/dev/null 2>&1 || die "Docker 不可用（先装 Docker Engine/ Desktop 并启动）"
# S3 实测教训：Dockerfile 用 BuildKit 缓存挂载（RUN --mount=type=cache），apt 装的 docker.io
# 不带 buildx 插件会在构建期报裸错——预检并给出可执行指引（compose v2 插件缺失同理）
docker buildx version >/dev/null 2>&1 || die "缺 docker buildx（Dockerfile 需 BuildKit）。装官方 docker 全家桶（https://docs.docker.com/engine/install/），或补插件：https://github.com/docker/buildx/releases"
docker compose version >/dev/null 2>&1 || die "缺 docker compose v2 插件（https://github.com/docker/compose/releases）"

# 1) .env：缺失才生成（强随机凭据；key 置空走 mock）。compose 的 :? 强校验缺凭据即拒启，不静默裸奔
if [ ! -f .env ]; then
  say "生成 .env（本地随机凭据，DASHSCOPE_API_KEY 置空=mock；要 live 数字请填入 key 后重跑本脚本）"
  rnd() { if command -v openssl >/dev/null 2>&1; then openssl rand -hex "$1"; else od -An -tx1 -N "$1" /dev/urandom | tr -d ' \n'; fi; }
  {
    echo "# 由 scripts/quickstart.sh 生成 $(date -Iseconds)（凭据仅本机环回面使用）"
    echo "DASHSCOPE_API_KEY="
    echo "JWT_SECRET=$(rnd 32)"
    echo "REDIS_PASSWORD=$(rnd 16)"
    echo "QDRANT_API_KEY=$(rnd 16)"
    echo "ES_PASSWORD=$(rnd 16)"
    echo "DEMO_PASSWORD=$(rnd 12)"
    echo "H2_DB_PASSWORD=$(rnd 16)"
  } > .env
else
  say ".env 已存在，沿用（如需 live 评测：填 DASHSCOPE_API_KEY 后重跑）"
fi
set -a; . ./.env; set +a
[ -n "${DASHSCOPE_API_KEY:-}" ] && say "检测到 DASHSCOPE_API_KEY → 网关将以 live 后端启动" \
                                || say "DASHSCOPE_API_KEY 为空 → mock 词法后端（零成本机制复现）"

# 2) 全栈（redis/qdrant/es + 网关镜像构建：多阶段 + BuildKit 缓存，首建约 2-5min）
say "docker compose --profile full up -d --build"
docker compose --profile full up -d --build

# 3) 等健康（容器 HEALTHCHECK 有 40s start-period；首启若别名缺失会自动灌库）
say "等待网关 UP（最长 300s）…"
up=0
for _ in $(seq 1 100); do
  sleep 3
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://localhost:8081/actuator/health || echo 000)" = "200" ]; then
    up=1; break
  fi
done
[ "$up" = "1" ] || { docker compose --profile full ps; docker logs --tail 40 opspilot-gateway 2>&1 || true; die "网关 300s 未就绪（见上日志）"; }
say "网关 UP"

# 4) 演示账号 seed（容器内 fat-jar UserAdminCli：H2 属主在同一文件域，AUTO_SERVER 同容器并存；
#    口令经容器 env_file 的 DEMO_PASSWORD 环境变量传入，绝不进 argv）
say "初始化演示账号（幂等，已存在则跳过）"
seed() {
  docker compose exec -T gateway java -cp app.jar \
    -Dloader.main=com.opspilot.auth.UserAdminCli \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    add --user "$1" --tenant "$2" --level "$3" --role "$4" --password-env DEMO_PASSWORD 2>/dev/null \
    || echo "    skip（已存在）: $1"
}
seed sre-limited tenant-internal 1 sre
seed sre-full    tenant-internal 3 platform
seed sre-acme    tenant-acme     3 sre
seed sre-watcher tenant-internal 3 platform   # 自举告警主体（ADR-0011）：DEMO 幕⑧ 与 alert_producer.py 默认用它
# seed 失败被 '|| skip' 吞掉会伪装幂等——list 终核对四个账号确实在库
LST=$(docker compose exec -T gateway java -cp app.jar \
    -Dloader.main=com.opspilot.auth.UserAdminCli \
    org.springframework.boot.loader.launch.PropertiesLauncher list 2>/dev/null || true)
for u in sre-limited sre-full sre-acme sre-watcher; do
  echo "$LST" | grep -q "$u" || die "seed 后账号 $u 不在库（前面 skip 是假幂等，查 docker compose logs gateway）"
done
say "演示账号 4/4 在库"

# 5) 红队畸形 token 样本（A2-8b/8c 与 demo 预检依赖；文件不入库）
PY=$(bash scripts/py.sh 2>/dev/null || true)
if [ -n "$PY" ]; then
  say "生成红队畸形 token 样本 → scripts/redteam_tokens.txt"
  "$PY" scripts/gen_tokens.py > scripts/redteam_tokens.txt
else
  say "WARN 未探测到 Python（跳过红队 token 生成；装 python3 后重跑本脚本）"
fi

say "完成。下一步："
echo "    bash scripts/demo.sh                     # 预检全绿 = 机制全链路就绪"
echo "    (set -a && . ./.env && set +a && cd offline && python3 acceptance_a2.py)   # 可选·机制级验收 10 项"
echo "    面板 http://localhost:8081/ ；live 数字复现需在 .env 填 DASHSCOPE_API_KEY 后重跑本脚本"
