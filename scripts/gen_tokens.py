"""生成演示 JWT（HS256 预签发，无登录接口）。

用法: python scripts/gen_tokens.py
读取环境变量 JWT_SECRET，输出演示 Token（auth_level=1 受限 / auth_level=3 全量）
与红队回归 Token（auth_level=0 / -1 非法密级，用于验证入口 401 拒绝）。
凭据只从环境变量读取，本脚本不写入任何密钥字面量。
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
    exp = int(time.time()) + 365 * 24 * 3600  # 一年有效（演示；P2 账号体系上线后本工具退役为应急件）
    common = {"iss": "opspilot", "exp": exp, "tenant_id": "tenant-internal"}  # 与 chunker DEFAULT_TENANT 对齐
    tokens = {
        "sre_l1": sign_jwt({**common, "sub": "sre-limited", "role": "sre", "auth_level": 1}, secret),
        "sre_l3": sign_jwt({**common, "sub": "sre-full", "role": "sre", "auth_level": 3}, secret),
        # 红队回归（P0 auth_level<=0 绕过）：非法低密级 token 必须在入口被 401 拒绝
        "sre_l0": sign_jwt({**common, "sub": "attacker-l0", "role": "sre", "auth_level": 0}, secret),
        "sre_neg": sign_jwt({**common, "sub": "attacker-neg", "role": "sre", "auth_level": -1}, secret),
        # 红队回归（P1 租户显式化）：跨租户账号可登录但检索必须零命中；
        # 畸形 tenant claim 必须 401（tenant_id 缺省 "" 与在线词法永不相等 → 空集，但入口即拒）
        "sre_acme": sign_jwt({**common, "sub": "attacker-acme", "role": "sre",
                              "auth_level": 3, "tenant_id": "tenant-acme"}, secret),
        "tenant_none": sign_jwt({"iss": "opspilot", "exp": exp, "sub": "attacker-no-tenant",
                                 "role": "sre", "auth_level": 1}, secret),
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
