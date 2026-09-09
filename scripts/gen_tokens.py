"""红队畸形 token 生成器（P2 起合法账号一律走 /api/v1/auth/login，本工具只签非法凭证）。

用法: python scripts/gen_tokens.py > scripts/redteam_tokens.txt
读取环境变量 JWT_SECRET，输出 5 个攻击样本：auth_level 0/-1、tenant 缺失/空白/超长。
它们必须被 JwtAuthFilter 以 401 拒绝（A2-8b/A2-8c 验收依据）。凭据只从环境变量读取。
"""
import hmac
import base64
import json
import os
import sys
import time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def sign_jwt(payload: dict, secret: str) -> str:
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = b64url(json.dumps(payload).encode())
    sig = b64url(hmac.new(secret.encode(), f"{header}.{body}".encode(), "sha256").digest())
    return f"{header}.{body}.{sig}"


def main() -> int:
    secret = os.environ.get("JWT_SECRET")
    if not secret:
        print("ERROR: JWT_SECRET 环境变量未设置", file=sys.stderr)
        return 1
    exp = int(time.time()) + 365 * 24 * 3600  # 红队样本用长期限：验证吊销靠 claim 校验而非过期
    common = {"iss": "opspilot", "exp": exp, "tenant_id": "tenant-internal", "tver": 1}
    tokens = {
        # P0 回归：auth_level<=0 提权边界（claim 卫生检查即应 401）
        "sre_l0": sign_jwt({**common, "sub": "attacker-l0", "role": "sre", "auth_level": 0}, secret),
        "sre_neg": sign_jwt({**common, "sub": "attacker-neg", "role": "sre", "auth_level": -1}, secret),
        # P1 回归：tenant 缺失/空白/超长（入口 401）
        "tenant_none": sign_jwt({"iss": "opspilot", "exp": exp, "sub": "attacker-no-tenant",
                                 "role": "sre", "auth_level": 1, "tver": 1}, secret),
        "tenant_blank": sign_jwt({**common, "sub": "attacker-blank-tenant",
                                  "role": "sre", "auth_level": 1, "tenant_id": "   "}, secret),
        "tenant_long": sign_jwt({**common, "sub": "attacker-long-tenant", "role": "sre",
                                 "auth_level": 1, "tenant_id": "t" * 65}, secret),
    }
    for name, tok in tokens.items():
        print(f"{name}={tok}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
