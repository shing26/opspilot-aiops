"""答案接地门闩的回归锁（OP-R1）。

门闩现在进 CI（`provenance` job 加一步），故必须证明它**会红**：
  ① 无据错误码 > 0 → 红；② 语料摘要与同代基线不符 → 红；③ 判据未被行使（answers=0 / codes_total=0）→ 红；
  ④ 正常形态 → 绿（反向：不得假红）。

变异验证（改坏必红）：把 `check()` 里的 `codes_ungrounded != 0` 判断去掉 → ① 必红；
把同代比较换成 `pass` → ② 必红。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(OFFLINE))
sys.path.insert(0, str(OFFLINE / "eval"))
import answer_eval as ae  # noqa: E402
import provenance  # noqa: E402

CHUNKS_SHA = "a" * 64


def _write(tmp_path: Path, report: dict, baseline_sha: str = CHUNKS_SHA) -> None:
    r = tmp_path / "grounding_report.json"
    p = tmp_path / "PROVENANCE.json"
    r.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
    p.write_text(json.dumps({"chunks": {"sha256": baseline_sha, "lines": 423}}), encoding="utf-8")


def _report(**over) -> dict:
    base = {"measured_at": "2026-09-28T11:33:00+08:00", "answers": 11, "codes_total": 16,
            "codes_ungrounded": 0, "grounding_rate": 1.0, "chunks_sha256": CHUNKS_SHA,
            "backend": {"llm": "dashscope:qwen-plus"}}
    base.update(over)
    return base


@pytest.fixture
def gate(tmp_path, monkeypatch):
    monkeypatch.setattr(ae, "REPORT", tmp_path / "grounding_report.json")
    monkeypatch.setattr(ae, "PROVENANCE", tmp_path / "PROVENANCE.json")
    monkeypatch.setattr(provenance, "chunks_digest", lambda root=None: {"sha256": CHUNKS_SHA, "lines": 423})
    return tmp_path


def test_green_on_well_formed_report(gate) -> None:
    _write(gate, _report())
    assert ae.check() == 0, "正常形态必须绿（反向：门闩不得假红）"


def test_red_on_ungrounded_codes(gate) -> None:
    _write(gate, _report(codes_ungrounded=1, ungrounded_samples=["V2:50099_FAKE"]))
    assert ae.check() == 1, "出现无据错误码（幻觉候选）必须红"


def test_red_on_stale_corpus_digest(gate) -> None:
    _write(gate, _report(chunks_sha256="b" * 64))       # 报告基于另一份语料
    assert ae.check() == 1, "与同代基线不符必须红（否则这份接地结论不能引用）"


def test_red_when_judgement_not_exercised(gate) -> None:
    """判据未被行使 ≠ 通过：没有答案素材、或答案里没出现任何错误码，都不是绿。"""
    _write(gate, _report(answers=0))
    assert ae.check() == 1, "answers=0（没素材）必须红，不得当通过"
    _write(gate, _report(codes_total=0))
    assert ae.check() == 1, "codes_total=0（判据未行使）必须红，不得当通过"


def test_red_when_report_missing(gate) -> None:
    assert ae.check() == 1, "报告不在库内时必须红并说明怎么产出，而不是静默通过"
