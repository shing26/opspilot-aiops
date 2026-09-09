"""错误码词法（离线侧单一事实源）。

必须与在线 EsSearchService.ERROR_CODE（Java）逐字符一致——本目录两个 chunker
与在线 fast-path/指纹共用同一形状，一旦漂移，chunk 元数据 error_codes 与检索
快路径静默不匹配（miss 不报错）。护栏：tests/test_chunkers.py 的
errorCodeLexiconMatchesJava 直接比对两侧字面量。
"""
from __future__ import annotations

import re

# Java 侧镜像常量：src/main/java/com/opspilot/retrieval/EsSearchService.java
ERROR_CODE_RE = re.compile(r"\b\d{5}_[A-Z][A-Z0-9_]*\b")
