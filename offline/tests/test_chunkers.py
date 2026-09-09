"""切分不变量测试（A1-4 验收依据）。

1. 代码块不腰斩：任意 chunk 内 ``` 围栏计数为偶数；全库围栏总数守恒
2. 面包屑正确：每个 chunk 的 breadcrumb 以源文档 H1 标题开头
3. OpenAPI：每个 endpoint 恰好一个 chunk，error_codes 提取非空
4. 错误码注册表全覆盖：25 个码均出现在某 chunk 元数据中
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "chunkers"))
import openapi_ast_chunker as oa  # noqa: E402
import markdown_tree_chunker as md  # noqa: E402

CORPUS = Path(__file__).parent.parent / "corpus"

ERROR_REGISTRY = [
    "50012_DB_TIMEOUT", "50013_DB_DEADLOCK", "50021_REDIS_TIMEOUT",
    "50022_REDIS_CONN_REFUSED", "50031_MQ_CONSUME_LAG", "50032_MQ_SEND_FAILED",
    "50041_PAY_GATEWAY_502", "50042_PAY_SIGN_INVALID", "40901_ORDER_STATE_CONFLICT",
    "40902_INVENTORY_INSUFFICIENT", "42901_RATE_LIMIT_EXCEEDED", "50051_ES_INDEX_MISSING",
    "50061_OSS_UPLOAD_DENIED", "50071_CONFIG_CENTER_UNREACHABLE", "50081_K8S_POD_OOMKILLED",
    "50082_K8S_NODE_NOT_READY", "50091_JVM_GC_PAUSE", "50092_THREAD_POOL_EXHAUSTED",
    "50101_SLOW_SQL_DETECTED", "50111_CERT_EXPIRING", "50121_DNS_RESOLUTION_FAILED",
    "50131_NTP_DRIFT", "50141_CONNECTION_POOL_EXHAUSTED", "50151_FEIGN_TIMEOUT",
    "50161_SENTINEL_BLOCKED",
]


def _all_md_chunks():
    out = []
    for sub in ("postmortems", "runbooks"):
        for m in sorted((CORPUS / sub).glob("*.md")):
            out.append((m, md.chunk_file(str(m))))
    return out


def test_code_fence_even_in_every_chunk():
    for m, chunks in _all_md_chunks():
        for c in chunks:
            n = len(re.findall(r"^\s*(```|~~~)", c["text"], re.MULTILINE))
            assert n % 2 == 0, f"代码块腰斩: {c['chunk_id']} fence={n}"


def test_code_fence_total_conserved():
    for m, chunks in _all_md_chunks():
        raw = m.read_text(encoding="utf-8")
        total_src = len(re.findall(r"^\s*(```|~~~)", raw, re.MULTILINE))
        total_chunks = sum(
            len(re.findall(r"^\s*(```|~~~)", c["text"], re.MULTILINE)) for c in chunks
        )
        assert total_src == total_chunks, f"围栏丢失: {m.name} {total_src}!={total_chunks}"


def test_breadcrumb_starts_with_h1():
    for m, chunks in _all_md_chunks():
        raw = m.read_text(encoding="utf-8")
        _, body = md.parse_front_matter(raw)
        h1 = re.search(r"^#\s+(.+)$", body, re.MULTILINE)
        assert h1, f"缺 H1: {m.name}"
        for c in chunks:
            assert c["breadcrumb"].startswith(h1.group(1)), (
                f"面包屑不含 H1: {c['chunk_id']}"
            )


def test_openapi_one_chunk_per_endpoint_and_error_codes():
    spec = json.loads((CORPUS / "openapi" / "order-center-api.json").read_text(encoding="utf-8"))
    n_ops = sum(
        1 for item in spec["paths"].values() for meth in item
        if meth.lower() in oa.HTTP_METHODS
    )
    chunks = oa.chunk_openapi(spec)
    assert len(chunks) == n_ops, f"endpoint 数不符: {len(chunks)}!={n_ops}"
    ids = [c["chunk_id"] for c in chunks]
    assert len(ids) == len(set(ids))
    for c in chunks:
        assert c["metadata"]["error_codes"], f"error_codes 为空: {c['chunk_id']}"
        assert c["metadata"]["service"] != "unknown"


def test_error_registry_full_coverage():
    spec = json.loads((CORPUS / "openapi" / "order-center-api.json").read_text(encoding="utf-8"))
    chunks = oa.chunk_openapi(spec)
    for _, cs in _all_md_chunks():
        chunks.extend(cs)
    seen = set()
    for c in chunks:
        seen.update(c["metadata"]["error_codes"])
    missing = set(ERROR_REGISTRY) - seen
    assert not missing, f"错误码未覆盖: {missing}"


def test_auth_level_distribution():
    chunks = []
    for _, cs in _all_md_chunks():
        chunks.extend(cs)
    levels = {c["metadata"]["auth_level"] for c in chunks}
    assert {1, 2, 3} <= levels, f"auth_level 分级不全: {levels}"


def test_error_code_lexicon_matches_java():
    """跨语言护栏：离线 chunker 的错误码词法必须与在线 EsSearchService.ERROR_CODE
    逐字符一致——漂移会让 chunk 元数据与检索快路径静默不匹配（miss 不报错）。"""
    import errorcode
    java_src = (Path(__file__).parent.parent.parent
                / "src" / "main" / "java" / "com" / "opspilot" / "retrieval"
                / "EsSearchService.java").read_text(encoding="utf-8")
    m = re.search(r'ERROR_CODE\s*=\s*Pattern\.compile\("([^"]+)"\)', java_src)
    assert m, "EsSearchService.java 未找到 ERROR_CODE 字面量"
    # Java 字符串字面量 \\b → 正则 \b；Python r"\b" 同源还原后必须逐字符相等
    java_pattern = m.group(1).replace("\\\\", "\\")
    assert errorcode.ERROR_CODE_RE.pattern == java_pattern, (
        f"词法漂移: py={errorcode.ERROR_CODE_RE.pattern!r} java={java_pattern!r}")


def test_tenant_explicit_on_every_chunk():
    """P1 租户显式化：每个 chunk 的 metadata.tenant 必须非空——
    在线 IngestionRunner 对缺 tenant 的 chunk fail-closed，产出不完整 JSONL 会被拒。"""
    all_chunks = []
    for _, cs in _all_md_chunks():
        all_chunks.extend(cs)
    for j in sorted((CORPUS / "openapi").glob("*.json")):
        all_chunks.extend(oa.chunk_file(str(j), doc_id=j.stem))
    assert all_chunks, "语料为空"
    bad = [c["chunk_id"] for c in all_chunks
           if not str(c["metadata"].get("tenant") or "").strip()]
    assert not bad, f"缺 tenant 的 chunk: {bad[:5]}"
