"""报告↔语料「同代」门闩的回归锁（`offline/provenance.py`）。

为什么这些用例必须存在：这个门闩的失效方式不是"报错"，而是**恒真**——
一个永远返回 OK 的检查比没有检查更糟，因为它会让人以为有人在看着。
本项目对同类事故已有两次记账（台账 E1：语料扩充后评测报告未重生成，es_only 语义
Top-1 72%→64%；2026-09-16 复现：语料 303→423，Hit@3 100%→92%）。

于是每个受门闩约束的输入都要有一条"改了它就必须红"的用例；反之，重新登记
（`--stamp` 只刷新 generated_at）必须**不红**——否则门闩会把正常流程也拦下，
下一个人就会把它关掉。两个方向都锁，门闩才算立住。

不触网、零凭据：全部在 tmp_path 里造语料，用合成 chunks 驱动。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))
import provenance as pv  # noqa: E402

# 与 build_golden.EXACT_CODES 同源——语料必须覆盖全部精确码，否则 build_samples 会断言失败
# （这正是"语料与评测集同代"该有的强度：缺一个码就该炸，不该静默少测一条）。
from provenance import _bg  # noqa: E402


def _write_chunks(root: Path, codes: list[str]) -> None:
    """一套能派生完整 golden 的最小语料。

    一律以 LF 落盘（显式 write_bytes）——夹具若跟着平台写 CRLF，
    用例的判定就随开发机漂移，而本文件恰好有一条用例专门在测行尾无关性。
    """
    lines = [json.dumps({"doc_id": f"rb-{i:03d}", "chunk_id": f"c{i}", "text": f"doc {code}",
                         "metadata": {"error_codes": [code]}}, ensure_ascii=False)
             for i, code in enumerate(codes, 1)]
    (root / "corpus").mkdir(parents=True, exist_ok=True)
    (root / "corpus" / "chunks.jsonl").write_bytes(("\n".join(lines) + "\n").encode("utf-8"))


def _write_derivable_golden(root: Path) -> None:
    (root / "eval" / "reports").mkdir(parents=True, exist_ok=True)
    samples = _bg.build_samples(root / "corpus" / "chunks.jsonl")
    (root / "eval" / "golden_dataset.jsonl").write_bytes(_bg.render_jsonl(samples).encode("utf-8"))


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    """一个自洽的最小仓库：语料 + 可复现的 golden + 已登记出处。"""
    codes = list(_bg.EXACT_CODES)
    _write_chunks(tmp_path, codes)
    _write_derivable_golden(tmp_path)
    (tmp_path / "corpus" / "runbooks").mkdir(parents=True, exist_ok=True)
    (tmp_path / "corpus" / "runbooks" / "rb-001.md").write_bytes("# 一个 runbook\n".encode("utf-8"))
    assert pv.stamp(tmp_path) == 0, "夹具自身必须能登记成功"
    assert pv.check(tmp_path) == 0, "夹具登记后必须同代"
    return tmp_path


def test_gate_passes_when_same_generation(repo: Path, capsys) -> None:
    """基线：没动任何东西时必须是绿的（否则其余用例的"红"无法归因）。"""
    assert pv.check(repo) == 0
    assert "同代" in capsys.readouterr().out


def test_added_corpus_doc_turns_gate_red(repo: Path, capsys) -> None:
    """清单验收原文：人为加一篇语料而不重生成报告，门闩必须转红。"""
    (repo / "corpus" / "runbooks" / "rb-999.md").write_text("# 新增 runbook\n", encoding="utf-8")
    assert pv.check(repo) == 1
    err = capsys.readouterr().err
    assert "报告与语料已分叉" in err
    assert "corpus_docs" in err, "错误信息必须点名是哪个输入变了，否则无法据此修复"
    assert "files=1→2" in err, "差异行必须带上可读的计数变化，便于人判断影响面"
    assert "python eval/build_golden.py" in err, "必须给出可执行的修复命令"


def test_edited_corpus_doc_turns_gate_red(repo: Path) -> None:
    """改字节（不是加文件）也要红——摘要是内容判据，不是文件计数。"""
    (repo / "corpus" / "runbooks" / "rb-001.md").write_text("# 改了内容\n", encoding="utf-8")
    assert pv.check(repo) == 1


def test_chunks_change_turns_gate_red(repo: Path) -> None:
    """切分产物变了（即"实际被索引的东西"变了）也必须红。"""
    p = repo / "corpus" / "chunks.jsonl"
    p.write_text(p.read_text(encoding="utf-8") + json.dumps(
        {"doc_id": "rb-extra", "chunk_id": "x", "text": "t",
         "metadata": {"error_codes": []}}, ensure_ascii=False) + "\n", encoding="utf-8")
    assert pv.check(repo) == 1


def test_golden_change_turns_gate_red(repo: Path) -> None:
    """评测集变了也必须红：在旧集合上跑出的数字，不能冒充当前语料的数字。"""
    p = repo / "eval" / "golden_dataset.jsonl"
    p.write_text(p.read_text(encoding="utf-8").replace("sem-01", "sem-99"), encoding="utf-8")
    assert pv.check(repo) == 1


def test_restamp_alone_does_not_turn_gate_red(repo: Path) -> None:
    """反向锁：只刷新登记时间不得导致红。

    没有这条，门闩会被"重新登记就变红"的正常流程逼到被关掉——
    generated_at 是给人读的记录，不是判据。
    """
    first = json.loads((repo / pv.PROVENANCE_REL).read_text(encoding="utf-8"))
    assert pv.stamp(repo, require_derivable=False) == 0
    second = json.loads((repo / pv.PROVENANCE_REL).read_text(encoding="utf-8"))
    assert second["generated_at"] >= first["generated_at"], "重登记只会让登记时间前进"
    assert pv.check(repo) == 0, "generated_at 是给人读的记录，不是判据——刷新它不得导致红"


def test_diverged_golden_is_reported_not_derivable(repo: Path) -> None:
    """语料与 golden 各自漂移（最隐蔽形态）：自洽性检查必须识别。"""
    p = repo / "corpus" / "chunks.jsonl"
    p.write_text(p.read_text(encoding="utf-8") + json.dumps(
        {"doc_id": "rb-extra", "chunk_id": "x", "text": "t",
         "metadata": {"error_codes": ["51103_H2_CONCURRENT_WRITE_CORRUPTION"]}},
        ensure_ascii=False) + "\n", encoding="utf-8")
    ok, detail = pv.golden_is_derivable(repo)
    assert not ok and "不自洽" in detail


def test_stamp_refuses_when_golden_not_derivable(repo: Path) -> None:
    """登记的分叉 = 把分叉盖章为同代，必须拒绝写入。"""
    p = repo / "corpus" / "chunks.jsonl"
    p.write_text(p.read_text(encoding="utf-8").replace("rb-001", "rb-XXX"), encoding="utf-8")
    stale = (repo / pv.PROVENANCE_REL).read_text(encoding="utf-8")
    assert pv.stamp(repo) == 1
    assert (repo / pv.PROVENANCE_REL).read_text(encoding="utf-8") == stale, \
        "拒绝登记时不得留下半成品/覆盖旧登记"


def test_redefined_eval_set_turns_gate_red(repo: Path, monkeypatch) -> None:
    """评测集被重新定义（改 build_golden 的样本表并重生成）但不重跑评测 → 必须红。

    这是**只有内容摘要判据能拦住**的形态：golden 与 chunks 自洽（自洽性检查会放行），
    报告的算法也没变，但报告是在旧样本集上跑的——数字与当前口径已不是同一件事。
    单靠 golden_is_derivable 会漏报，故这条同时锁住 GATED_INPUTS 里的 golden。
    """
    monkeypatch.setattr(_bg, "SEMANTIC", _bg.SEMANTIC[:-1])
    samples = _bg.build_samples(repo / "corpus" / "chunks.jsonl")
    (repo / "eval" / "golden_dataset.jsonl").write_text(_bg.render_jsonl(samples), encoding="utf-8")
    assert pv.golden_is_derivable(repo)[0], "本形态的前提是 golden 与语料自洽（不靠自洽性检查兜住）"
    assert pv.check(repo) == 1


def test_digest_is_line_ending_agnostic(repo: Path) -> None:
    """摘要必须与检出约定无关：CRLF 工作区与 LF 工作区要算出同一摘要。

    本仓开发机 core.autocrlf=true，.jsonl/.md 在 Windows 工作区是 CRLF、blob 是 LF。
    若摘要直接吃工作区字节，Windows 本地登记的值在 Linux CI 上必然对不上——门闩假红。
    （同类跨平台坑本项目已踩过一次：.sh 的 CRLF 让 bash 报 "bad interpreter"。）
    """
    inputs = ("corpus/chunks.jsonl", "eval/golden_dataset.jsonl", "corpus/runbooks/rb-001.md")
    # 先统一成 LF，取一份基线
    for rel in inputs:
        p = repo / rel
        p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n"))
    lf = pv.collect(repo)
    # 再把同一内容换成 CRLF（模拟 Windows 检出）；注意先归一再加 \r，避免 \r\r\n
    for rel in inputs:
        p = repo / rel
        p.write_bytes(p.read_bytes().replace(b"\n", b"\r\n"))
    crlf = pv.collect(repo)
    for key in pv.GATED_INPUTS:
        assert lf[key]["sha256"] == crlf[key]["sha256"], \
            f"{key} 摘要随行尾变化 = 门闩会跨平台假红"
    assert pv.check(repo) == 0


def test_missing_provenance_is_a_failure_not_a_pass(repo: Path) -> None:
    """没登记 ≠ 同代：缺文件必须红，否则删掉它就能让门闩静音。"""
    (repo / pv.PROVENANCE_REL).unlink()
    assert pv.check(repo) == 1


def test_gate_is_content_based_not_mtime_based(repo: Path) -> None:
    """CI 里 git checkout 会把所有 mtime 刷成同一时刻——门闩不得依赖 mtime。

    造一个"报告比语料旧"的 mtime 关系但内容同代：必须判为同代（mtime 判据会在这里假红），
    再改内容而不动 mtime：必须判为分叉（mtime 判据会在这里漏报）。
    两个方向合起来证明该门闩在 CI 里不是空门闩。
    """
    import os
    old = 1_600_000_000
    for f in ("corpus/chunks.jsonl", "eval/golden_dataset.jsonl", pv.PROVENANCE_REL):
        os.utime(repo / f, (old, old))
    assert pv.check(repo) == 0, "内容同代时，mtime 陈旧不应假红"

    os.utime(repo / pv.PROVENANCE_REL, (old + 10_000, old + 10_000))
    (repo / "corpus" / "chunks.jsonl").write_text(
        (repo / "corpus" / "chunks.jsonl").read_text(encoding="utf-8").replace("rb-002", "rb-002x"),
        encoding="utf-8")
    os.utime(repo / "corpus" / "chunks.jsonl", (old, old))
    assert pv.check(repo) == 1, "内容分叉但 mtime 更旧时，mtime 判据会漏报"


def test_real_repo_is_same_generation() -> None:
    """对本仓库自身的门闩：入库的报告必须能从入库的语料复现。

    这条把"CI 里也会跑的那个判定"同时钉进单测——本地 pytest 就能发现分叉，
    不必等到 push。
    """
    assert pv.check(pv.REPO) == 0
