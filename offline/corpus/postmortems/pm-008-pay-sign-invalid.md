---
doc_id: pm-008
service: payment-service
env: prod
auth_level: 3
error_codes: [50042_PAY_SIGN_INVALID]
date: 2026-04-18
severity: P2
---

# 支付验签批量失败复盘（50042_PAY_SIGN_INVALID）

## 事故摘要

2026-04-18 10:12，渠道升级签名算法（RSA2 → SM2 灰度），我方密钥版本未同步，回调验签批量失败 `50042_PAY_SIGN_INVALID`，持续 35 分钟。

## 根因分析

验签公钥配置在 Nacos，但灰度期间渠道新旧两套签名并存，我方只配了单版本：

```
com.ordercenter.payment.exception.PaySignVerifyException:
    sign verify failed for notify id=20260418101233, error code 50042_PAY_SIGN_INVALID
    expected algo=RSA2 actual=SM2
```

生产密钥配置（auth_level=3，密级管控）：

```yaml
# nacos: payment-service/prod/sign-config.yaml
pay:
  channels:
    alipay:
      public-key-path: /etc/kms/alipay_pubkey_v2.pem   # 事故时缺 v3(SM2) 版本
      verify-algo: RSA2
```

## 修复措施

1. 验签支持多版本公钥轮询（按 notify 中 `sign_type` 字段路由）。
2. 渠道算法变更纳入变更评审清单，提前 7 天双跑验证。
3. `50042_PAY_SIGN_INVALID` 突增（>100/min）自动告警并暂停自动重试。

## 复盘教训

- 外部协议变更是「静默故障」高发区，必须有双跑机制。
- 密钥路径等配置属生产机密，本手册定级 auth_level=3。
