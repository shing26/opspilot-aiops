"""报告与语料「同代」的机器判据——把台账 E1 教训从人工纪律变成 CI 门闩。

事故史（同一类错误两次，两次都靠人发现）：
  1. `docs/qa/2026-09-13-module-verification.md` E1：评测报告系语料扩充（+52 篇 acme）前的版本，
     `es_only` 语义 Top-1 实测 72%→64%（hybrid 逐位不变）——README 引用的仍是旧数字。
  2. 2026-09-16 复现：语料 303→423，`es_only` 语义 Hit@3 由 100% 降至 92%，hybrid 逐位不变。
同一类事故复现两次而约束仍是"人工纪律"，就是该上机器的信号。本模块是那个机器。

**为什么不用 mtime（"报告不得早于语料"的时间戳关系）**：CI 的第一步是 `git checkout`，
而 checkout 会把**所有**文件 mtime 刷成检出时刻——"报告不早于语料"在 CI 里恒真，
是个**空门闩**。本项目对同类误判已有记录（`scripts/demo.sh:60`：批量刷新 mtime 会误报）。
内容摘要判据与检出顺序、系统时钟、时区全都无关：语料字节变了而报告没重生成，摘要必然对不上。

判据的三个输入（任一变化即视为报告过期）：
  - `corpus/*/` 语料文档字节集 —— 人改语料的入口
  - `corpus/chunks.jsonl`     —— 切分产物，即**实际被索引**的东西
  - `eval/golden_dataset.jsonl` —— 评测集，由 chunks 派生（改语料不重生成它，评测就在测旧集合）

另含一条自洽性检查：已入库的 golden 必须仍能从当前 chunks **逐字节复现**。
它拦住的是"语料与 golden 各自漂移、但两者都被登记为同代"这种最隐蔽的形态。

只用 stdlib，不触网；`--check` 可在 CI 零凭据运行。
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent
BUILD_GOLDEN = REPO / "eval" / "build_golden.py"

CORPUS_DOC_DIRS = ("corpus/openapi", "corpus/runbooks", "corpus/postmortems")
CHUNKS_REL = "corpus/chunks.jsonl"
GOLDEN_REL = "eval/golden_dataset.jsonl"
PROVENANCE_REL = "eval/reports/PROVENANCE.json"
REPORT_JSON_REL = "eval/reports/eval_report.json"

# 门闩只比对这三个摘要——其余字段（时间戳、后端名）是给人读的记录，不参与判定，
# 否则"重新登记一次"就会因为时间戳变化而红，门闩会把正常流程也拦掉。
GATED_INPUTS = ("corpus_docs", "chunks", "golden")


def _load_build_golden():
    """按路径加载 build_golden 模块。

    不用 `import eval.build_golden`：`eval` 是 Python 内建名，作为包名会让读者（与静态检查）
    误判。样本表的唯一来源就是这个文件——复算方复用它，不另抄一份表。
    """
    spec = importlib.util.spec_from_file_location("_opspilot_build_golden", BUILD_GOLDEN)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载 {BUILD_GOLDEN}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_bg = _load_build_golden()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# 摘要必须反映**内容**，不反映检出约定。本仓开发机 core.autocrlf=true，而 .gitattributes
# 只钉了 .sh/.py/.yml——.jsonl 与 .md 在 Windows 工作区是 CRLF、committed blob 是 LF。
# 若直接对工作区字节取摘要，同代的两个检出（Windows 本地 vs Linux CI）会算出不同值，
# 门闩就在 CI 里假红——一个会假红的门闩比没有门闩更快被关掉，正是本模块要避免的失效方式。
# （同类跨平台坑本项目已踩过一次：.sh 的 CRLF 曾让 bash 报 "bad interpreter"。）
TEXT_SUFFIXES = frozenset({".md", ".jsonl", ".json", ".yaml", ".yml", ".txt", ".csv"})


def _content_bytes(path: Path) -> bytes:
    """取"内容等价"字节：文本类文件统一归一到 LF，其余按原字节。"""
    data = path.read_bytes()
    if path.suffix.lower() in TEXT_SUFFIXES:
        return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    return data


def _sha256_content(path: Path) -> str:
    return sha256_bytes(_content_bytes(path))


def corpus_docs_digest(root: Path = REPO) -> dict:
    """语料文档字节集的摘要。

    摘要里掺入相对路径与长度再各自加分隔符，是为了让"文件改名"与"内容变长"都能改变结果，
    且不受目录遍历顺序影响（不同文件系统的 readdir 顺序不同，直接顺序拼接会假红）。
    """
    h = hashlib.sha256()
    files: list[Path] = []
    for rel in CORPUS_DOC_DIRS:
        d = root / rel
        if d.is_dir():
            files.extend(p for p in d.rglob("*") if p.is_file())
    for p in sorted(files, key=lambda x: x.as_posix()):
        rel = p.relative_to(root).as_posix()
        data = _content_bytes(p)
        h.update(rel.encode("utf-8"))
        h.update(b"\0")
        h.update(str(len(data)).encode("ascii"))
        h.update(b"\0")
        h.update(data)
        h.update(b"\0")
    return {"sha256": h.hexdigest(), "files": len(files)}


def chunks_digest(root: Path = REPO) -> dict:
    p = root / CHUNKS_REL
    if not p.is_file():
        return {"sha256": None, "lines": 0}
    with p.open(encoding="utf-8") as fh:
        lines = sum(1 for _ in fh)
    return {"sha256": _sha256_content(p), "lines": lines}


def golden_digest(root: Path = REPO) -> dict:
    p = root / GOLDEN_REL
    if not p.is_file():
        return {"sha256": None, "samples": 0}
    with p.open(encoding="utf-8") as fh:
        samples = sum(1 for line in fh if line.strip())
    return {"sha256": _sha256_content(p), "samples": samples}


def report_facts(root: Path = REPO) -> dict:
    """从评测报告里读它自报的后端真相（报告不硬编码，本模块也不猜）。"""
    p = root / REPORT_JSON_REL
    if not p.is_file():
        return {"backend": None, "mode": "unknown"}
    try:
        rep = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"backend": None, "mode": "unknown"}
    backend = rep.get("backend") or {}
    emb = backend.get("embedding") or ""
    return {"backend": backend, "mode": "live" if str(emb).startswith("dashscope:") else "mock"}


def golden_is_derivable(root: Path = REPO) -> tuple[bool, str]:
    """已入库的 golden 是否仍能从当前 chunks 逐字节复现。"""
    chunks = root / CHUNKS_REL
    golden = root / GOLDEN_REL
    if not chunks.is_file():
        return False, f"缺 {CHUNKS_REL}（先跑 chunkers/build_chunks.py）"
    if not golden.is_file():
        return False, f"缺 {GOLDEN_REL}（先跑 eval/build_golden.py）"
    try:
        expected = _bg.render_jsonl(_bg.build_samples(chunks))
    except AssertionError as exc:
        return False, f"当前语料无法派生全部精确样本：{exc}"
    actual = golden.read_text(encoding="utf-8")
    if expected != actual:
        exp_lines = expected.count("\n")
        act_lines = actual.count("\n")
        return False, (f"{GOLDEN_REL} 与当前 chunks 不自洽"
                       f"（复算 {exp_lines} 行 vs 入库 {act_lines} 行）")
    return True, f"{GOLDEN_REL} 可从当前 chunks 逐字节复现"


def collect(root: Path = REPO, *, generated_at: str | None = None) -> dict:
    facts = report_facts(root)
    return {
        "generated_at": generated_at or datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "produced_by": "offline/eval/evaluate.py",
        "reproduce": ("cd offline && python eval/build_golden.py && python eval/evaluate.py"
                      "   # 需活体栈；live 数字还需 DASHSCOPE_API_KEY"),
        "report_mode": facts["mode"],
        "report_backend": facts["backend"],
        "corpus_docs": corpus_docs_digest(root),
        "chunks": chunks_digest(root),
        "golden": golden_digest(root),
    }


def diff(recorded: dict, current: dict) -> list[str]:
    """逐项比对受门闩约束的输入，返回人类可读的差异行（空列表=同代）。"""
    out: list[str] = []
    for key in GATED_INPUTS:
        rec = recorded.get(key) or {}
        cur = current.get(key) or {}
        if rec.get("sha256") == cur.get("sha256"):
            continue
        counts = " / ".join(f"{k}={rec.get(k, '?')}→{cur.get(k, '?')}"
                            for k in cur if k != "sha256")
        out.append(f"{key}: 摘要 {str(rec.get('sha256'))[:12]}… → {str(cur.get('sha256'))[:12]}…"
                   + (f"（{counts}）" if counts else ""))
    return out


def load_provenance(root: Path = REPO) -> dict | None:
    p = root / PROVENANCE_REL
    if not p.is_file():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def stamp(root: Path = REPO, *, require_derivable: bool = True) -> int:
    """把当前语料状态登记为"这份报告的出处"。"""
    if require_derivable:
        ok, detail = golden_is_derivable(root)
        if not ok:
            print(f"FAIL 拒绝登记：{detail}", file=sys.stderr)
            print("  golden 与语料不自洽时登记，等于把分叉盖章为同代。"
                  "先重生成再登记：cd offline && python eval/build_golden.py && python eval/evaluate.py",
                  file=sys.stderr)
            return 1
        print(f"OK   {detail}")
    data = collect(root)
    p = root / PROVENANCE_REL
    p.parent.mkdir(parents=True, exist_ok=True)
    # 显式 LF 落盘（write_text 在 Windows 会写 CRLF）：本文件与 chunks/golden 同属被摘要的
    # 产物族，行尾应与 blob 一致，避免"工作区与检出形态不同"这一类排查成本（同 CHECKSUMS 教训）。
    p.write_bytes((json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print(f"OK   已登记出处 → {PROVENANCE_REL}"
          f"（mode={data['report_mode']}，chunks={data['chunks']['lines']} 行，"
          f"golden={data['golden']['samples']} 样本）")
    return 0


def check(root: Path = REPO) -> int:
    """CI 门闩：报告与语料必须同代。"""
    recorded = load_provenance(root)
    if recorded is None:
        print(f"FAIL 缺 {PROVENANCE_REL}——无法判定报告与语料是否同代。", file=sys.stderr)
        print("     首次启用或确实已重跑评测后登记：cd offline && python provenance.py --stamp",
              file=sys.stderr)
        return 1
    current = collect(root)
    diffs = diff(recorded, current)
    if diffs:
        print("FAIL 报告与语料已分叉——README/文档引用的数字可能已陈旧（台账 E1 教训）。", file=sys.stderr)
        for line in diffs:
            print(f"     {line}", file=sys.stderr)
        print(f"     登记时间：{recorded.get('generated_at', '?')}", file=sys.stderr)
        print("     修法：动语料必须同批重跑评测并刷新正文数字——", file=sys.stderr)
        print(f"       {recorded.get('reproduce', 'cd offline && python eval/build_golden.py && python eval/evaluate.py')}",
              file=sys.stderr)
        print("     若确实已重跑，重新登记：cd offline && python provenance.py --stamp", file=sys.stderr)
        return 1

    ok, detail = golden_is_derivable(root)
    if not ok:
        print(f"FAIL golden 自洽性检查未过：{detail}", file=sys.stderr)
        return 1
    mode = current["report_mode"]
    print(f"OK   报告与语料同代（mode={mode}，chunks={current['chunks']['lines']} 行，"
          f"golden={current['golden']['samples']} 样本，语料 {current['corpus_docs']['files']} 篇）")
    print(f"     {detail}")
    if mode != "live":
        print("     注：报告登记为 mock 后端口径——README 的 live 数字需自备 key 复跑后才可复核。")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="报告↔语料同代判据（CI 门闩，零凭据零网络）")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--check", action="store_true", help="校验同代（CI 用；不同代返回 1）")
    g.add_argument("--stamp", action="store_true", help="登记当前语料状态为报告出处")
    g.add_argument("--status", action="store_true", help="打印两边状态，不判定")
    a = ap.parse_args(argv)

    if a.status:
        rec = load_provenance()
        cur = collect()
        print(f"登记：{json.dumps({k: rec.get(k) for k in GATED_INPUTS}, ensure_ascii=False)}"
              if rec else "登记：无")
        print(f"当前：{json.dumps({k: cur[k] for k in GATED_INPUTS}, ensure_ascii=False)}")
        return 0
    return stamp() if a.stamp else check()


if __name__ == "__main__":
    raise SystemExit(main())
