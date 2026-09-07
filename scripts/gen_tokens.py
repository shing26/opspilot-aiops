"""生成演示 JWT（HS256 预签发，无登录接口）。

用法: python scripts/gen_tokens.py
读取环境变量 JWT_SECRET，输出两个演示 Token（auth_level=1 受限 / auth_level=3 全量）。
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
    exp = int(time.time()) + 365 * 24 * 3600  # 一年有效（演示）
    common = {"iss": "opspilot", "exp": exp, "tenant_id": "tenant-demo"}
    tokens = {
        "sre_l1": sign_jwt({**common, "sub": "sre-limited", "role": "sre", "auth_level": 1}, secret),
        "sre_l3": sign_jwt({**common, "sub": "sre-full", "role": "sre", "auth_level": 3}, secret),
    }
    for name, tok in tokens.items():
        print(f"{name}={tok}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
