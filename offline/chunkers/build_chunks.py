"""切分入口：扫描 corpus，产出 chunks.jsonl（进 git 的离线产物）。

用法: python chunkers/build_chunks.py [--corpus offline/corpus] [--out offline/corpus/chunks.jsonl]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import openapi_ast_chunker as oa  # noqa: E402
import markdown_tree_chunker as md  # noqa: E402


def build(corpus_dir: Path) -> list[dict]:
    chunks: list[dict] = []
    for j in sorted((corpus_dir / "openapi").glob("*.json")):
        chunks.extend(oa.chunk_file(str(j), doc_id=j.stem))
    for sub in ("postmortems", "runbooks"):
        for m in sorted((corpus_dir / sub).glob("*.md")):
            chunks.extend(md.chunk_file(str(m)))
    return chunks


def _contained(p: Path, root: Path, label: str) -> Path:
    """解析路径并强制落在 offline/ 根内，拒绝 .. 越界与任意文件写。"""
    rp = p.resolve()
    if root not in rp.parents and rp != root:
        raise ValueError(f"{label} 路径越出允许目录 {root}: {p}")
    return rp


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default=str(Path(__file__).parent.parent / "corpus"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    offline_root = Path(__file__).parent.parent.resolve()
    corpus = _contained(Path(args.corpus), offline_root, "corpus")
    chunks = build(corpus)

    ids = [c["chunk_id"] for c in chunks]
    assert len(ids) == len(set(ids)), f"chunk_id 重复: {[i for i in ids if ids.count(i) > 1]}"

    out = _contained(Path(args.out), offline_root, "out") if args.out else corpus / "chunks.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for c in chunks:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    by_type: dict[str, int] = {}
    for c in chunks:
        by_type[c["type"]] = by_type.get(c["type"], 0) + 1
    print(f"OK chunks={len(chunks)} -> {out}")
    print(f"by_type={by_type}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
