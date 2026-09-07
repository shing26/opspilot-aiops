---
doc_id: rb-014
service: payment-service
env: prod
auth_level: 2
error_codes: [50111_CERT_EXPIRING]
---

# 证书过期排查手册（50111_CERT_EXPIRING）

## 适用症状

- HTTPS 握手失败，日志 `PKIX path validation failed`
- 错误码 `50111_CERT_EXPIRING`（到期前 30 天预警）

## 排查步骤

### 第一步：查证书有效期

```bash
echo | openssl s_client -connect gateway.alipay.example:443 2>/dev/null \
  | openssl x509 -noout -dates
```

`notAfter` 临近或已过 → 确认问题。

### 第二步：查本地信任库

```bash
keytool -list -keystore $JAVA_HOME/lib/security/cacerts \
  -storepass changeit | grep -i alipay
```

### 第三步：mTLS 客户端证书

```bash
openssl x509 -in /etc/certs/payment-client.pem -noout -enddate
```

## 止损操作

1. 服务端证书过期：紧急续签 + 滚动替换 Ingress Secret。
2. 客户端证书过期：替换 mTLS 证书并重启 payment-service。
3. 信任链问题：导入新 CA 至 cacerts。

## 预防机制

- 证书到期前 30/7/1 天三级告警，`50111_CERT_EXPIRING` 对应 30 天档。
- 接入 cert-manager 自动轮换。

## 升级路径

- 支付渠道证书过期导致全渠道不可用 → P1。
- 内部 mTLS 证书过期 → 升级安全组。
