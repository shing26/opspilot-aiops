"""离线语料的公共默认值（与在线侧约定对齐；不含任何凭据）。"""

# 定义 A（团队内部工具）的单租户显式标识：chunker 缺省归属租户。
# 在线 IngestionRunner 对缺 tenant 的 chunk fail-closed 拒绝入库。
# 2026-09-11（QA P3 判别性）：tenant-acme 已播种私有语料（ac-* 文档，52xxx 段 +
# 一条 50012 变体），跨租户验收从"查空"升级为"只回自己、绝不回对方"的双向判别。
# front matter 写 `tenant:` 即覆盖本缺省值。
DEFAULT_TENANT = "tenant-internal"
