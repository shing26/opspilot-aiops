---
doc_id: rb-007
service: inventory-service
env: prod
auth_level: 1
error_codes: [40902_INVENTORY_INSUFFICIENT]
---

# 库存不足排查手册（40902_INVENTORY_INSUFFICIENT）

## 适用症状

- 下单/扣减返回 `40902_INVENTORY_INSUFFICIENT`
- 用户反馈「明明有货却下单失败」

## 排查步骤

### 第一步：核对真实库存水位

```sql
SELECT sku_id, stock, locked_stock, version
FROM t_inventory WHERE sku_id = ?;
```

`stock - locked_stock` 才是可售数。

### 第二步：核对 Redis 预扣水位

```bash
redis-cli GET stock:{skuId}
```

Redis 与 DB 差异 >10 说明预扣未回补（释放接口异常，见 rb-002）。

### 第三步：检查超卖保护

```
com.ordercenter.inventory.StockDeductService.deduct
  -> stock >= quantity ? 扣减 : throw 40902_INVENTORY_INSUFFICIENT
```

## 止损操作

1. 真实无货：前端置灰 + 到货订阅，无需技术止损。
2. 预扣泄漏：执行对账脚本 `stock_reconcile.py --sku <id>` 回补 Redis。
3. 热点 SKU：切换队列串行扣减，避免瞬时击穿。

## 升级路径

- 大面积「有货却报不足」→ 升级库存域 Owner，疑似预扣泄漏。
- 超卖已发生（stock < 0）→ P1，启动赔付流程。
