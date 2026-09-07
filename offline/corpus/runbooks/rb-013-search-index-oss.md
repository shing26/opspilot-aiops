---
doc_id: rb-013
service: search-service
env: prod
auth_level: 1
error_codes: [50051_ES_INDEX_MISSING, 50061_OSS_UPLOAD_DENIED]
---

# 搜索索引与媒体资源排查手册（50051_ES_INDEX_MISSING / 50061_OSS_UPLOAD_DENIED）

## 适用症状

- 商品搜索返回 `50051_ES_INDEX_MISSING`
- 头像上传/回单下载返回 `50061_OSS_UPLOAD_DENIED`

## 搜索索引排查（50051_ES_INDEX_MISSING）

### 第一步：确认索引与别名

```bash
curl -s localhost:9200/_cat/indices/products-*?v
curl -s localhost:9200/_alias/products
```

别名指向为空或索引 `status=red` → 转第二步。

### 第二步：重建索引

```bash
curl -s -X POST localhost:8080/api/v1/search/reindex
```

### 第三步：检查分词器

`50051_ES_INDEX_MISSING` 偶发于 mapping 变更后别名未切换，核对 `ProductIndexService` 读取的索引名。

## OSS 上传排查（50061_OSS_UPLOAD_DENIED）

```bash
# 检查 STS 凭证有效期
curl -s localhost:8080/actuator/info | jq '.oss.credentialsExpire'
```

`50061_OSS_UPLOAD_DENIED` 多为凭证过期或 Bucket Policy 变更，刷新 STS 即可。

## 止损操作

1. 搜索降级：返回 DB 模糊查询结果 + 提示「搜索维护中」。
2. OSS：切换备用 Bucket 或延长 STS 刷新频率。

## 升级路径

- 索引 red 且副本丢失 → 升级搜索平台组。
- OSS 大面积失败 → 升级基础架构组。
