---
doc_id: rb-005
service: payment-service
env: prod
auth_level: 2
error_codes: [50041_PAY_GATEWAY_502, 50042_PAY_SIGN_INVALID, 50151_FEIGN_TIMEOUT]
---

# 支付网关异常排查手册（50041_PAY_GATEWAY_502）

## 适用症状

- 发起支付返回 `50041_PAY_GATEWAY_502` 或 `50151_FEIGN_TIMEOUT`
- 回调验签失败 `50042_PAY_SIGN_INVALID`

## 排查步骤

### 第一步：确认渠道侧状态

```bash
# 探测第三方网关连通性
curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" https://gateway.alipay.example/health
```

- 返回 5xx/超时 → 渠道故障，转第二步。
- 返回 200 但业务仍 502 → 我方签名/证书问题，转第三步。

### 第二步：Feign 超时与熔断状态

```bash
curl -s localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

`50151_FEIGN_TIMEOUT` 持续出现说明熔断未生效或超时预算过长。

### 第三步：验签失败排查（50042_PAY_SIGN_INVALID）

```bash
# 比对签名算法版本
grep -r "verify-algo" /etc/nacos/snapshot/payment-service/
```

若渠道灰度新算法（如 SM2），按 pm-008 启用多版本公钥轮询。

## 止损操作

1. 渠道故障：切换备用支付渠道（微信/银联）。
2. 前端降级为「支付排队中」，避免用户重复扣款。
3. 验签批量失败：暂停自动重试，防止死信队列膨胀。

## 升级路径

- 双渠道同时不可用 → P1，通知支付域 Owner + 客服口径同步。
- 出现重复扣款 → 立即冻结对账并升级资金安全组。
