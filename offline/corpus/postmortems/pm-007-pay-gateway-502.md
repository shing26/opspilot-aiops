---
doc_id: pm-007
service: payment-service
env: prod
auth_level: 2
error_codes: [50041_PAY_GATEWAY_502, 50151_FEIGN_TIMEOUT]
date: 2026-04-02
severity: P1
---

# 第三方支付网关抖动引发支付链路 502 复盘（50041_PAY_GATEWAY_502）

## 事故摘要

2026-04-02 19:45，第三方网关（支付宝渠道）区域性抖动，`POST /api/v1/orders/{orderId}/pay` 返回 `50041_PAY_GATEWAY_502`，Feign 调用超时抛 `50151_FEIGN_TIMEOUT`，支付成功率 10 分钟内跌至 43%。

## 根因分析

Feign 超时配置 10s 过长，且无熔断，故障期间线程被慢调用占满，拖垮整个 payment-service 的 Tomcat 线程池：

```
com.ordercenter.payment.PaymentGatewayClient.pay(PaymentGatewayClient.java:64)
feign.RetryableException: Read timed out executing POST https://gateway.alipay.example/pay
    error code 50151_FEIGN_TIMEOUT
    -> 对外包装为 50041_PAY_GATEWAY_502
```

## 修复措施

1. Feign 超时收紧至 connect 1s / read 3s，并配置 Resilience4j 熔断（失败率 >50% 开启）。
2. 网关 502 时自动切换备用渠道（微信），并向前端返回「排队中」而非失败。
3. `50041_PAY_GATEWAY_502` 与 `50151_FEIGN_TIMEOUT` 建立关联告警规则。

## 复盘教训

- 外部依赖的超时预算必须小于自身 SLA，10s Feign 超时等于放弃防御。
- 支付渠道多活是止损的根本，单渠道 = 单点。
