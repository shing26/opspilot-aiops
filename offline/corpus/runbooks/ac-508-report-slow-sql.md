---
doc_id: ac-508
tenant: tenant-acme
service: acme-analytics
env: prod
auth_level: 2
error_codes: [52008_REPORT_SQL_SLOW]
---

# 报表查询慢（宽表膨胀与谓词下推失效）

## 适用症状

- 管理后台「经营周报」类报表加载超 30s，`52008_REPORT_SQL_SLOW`；
- 月初集中出现（月度对账报表 + 周报叠加）。

## 排查步骤

1. 取慢 SQL 执行计划：重点看 `rows examined` 与 `rows sent` 比值——比值 >100
   说明谓词下推失效，全表扫后过滤；
2. 谓词失效常见根因：报表工具对时间列包了函数（`DATE_FORMAT(created_at,...)='...'`）
   导致索引不可用——改为范围条件即可，属**报表模板缺陷**，提模板修复单而非加索引；
3. 宽表膨胀：`acme_mart.orders_wide` 每日全量重建任务近 7 天是否成功，失败则
   统计信息陈旧、优化器选错计划（先查 `last_analyze_time`）；
4. 区分「查询真慢」与「排队慢」：并发超报表库连接池时表现为 P50 正常、P99 爆炸，
   扩池优先于调 SQL。

## 止损操作

- 月度报表错峰：把月初 3 天的高消耗模板路由到只读副本（路由开关
  `acme.analytics.read-replica`）；
- 重建任务积压：手动触发单表 analyze 并跳过非关键分区。

## 常见误判

- 给函数包裹的时间列盲目加函数索引——修复模板后索引成为死重，先改模板再谈索引。
