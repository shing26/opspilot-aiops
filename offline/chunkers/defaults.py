"""离线语料的公共默认值（与在线侧约定对齐；不含任何凭据）。"""

# 定义 A（团队内部工具）的单租户显式标识：chunker 缺省归属租户。
# 在线 IngestionRunner 对缺 tenant 的 chunk fail-closed 拒绝入库；
# 多租户演示矩阵用 tenant-acme（仅签测试 token，语料不归属它 → 跨租户查询必空）。
DEFAULT_TENANT = "tenant-internal"
