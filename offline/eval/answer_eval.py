"""答案接地报告的机器门闩（OP-R1，2026-09-28）。

判据只断言两件事，都**必须可机器验**：

  1. **同代**：报告里的 `chunks_sha256` 必须等于 `eval/reports/PROVENANCE.json` 的 `chunks.sha256`
     ——否则这份接地结论是在**另一份语料**上得出的，不能用来谈现在的系统。
  2. **零无据**：`codes_ungrounded == 0`。接地判据本身（`offline/grounding.py`）是集合运算，
     不做"有据与否"的解释性判断。

**不断言接地率的具体数值**：那是 live 读数、含波动，写进 `doc_numbers.json` 会让门闩假红
（该表 docstring 明写"含波动的读数纳入会假红"，而会假红的门比没有门更快被关掉）。

**它为什么能进 CI**：报告由 live 探针（`qa_gen_quality_probes.py V9`）产出后**入库**，
CI 只读这份入库产物做同代与零无据断言——零凭据、零中间件，与 CI 的既有纪律一致。

用法（offline/ 目录）:
  python eval/answer_eval.py --check     # exit 0 = 同代且零无据
  python eval/answer_eval.py --show      # 打印报告全文与关键读数
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import provenance  # noqa: E402  # 同代判据的单一事实源

REPORT = Path("eval/reports/grounding_report.json")
PROVENANCE = Path("eval/reports/PROVENANCE.json")
REQUIRED = ("measured_at", "answers", "codes_total", "codes_ungrounded", "grounding_rate",
            "chunks_sha256", "backend")


def check() -> int:
    fails: list[str] = []
    if not REPORT.is_file():
        print(f"FAIL 找不到接地报告 {REPORT}——它由 live 探针产出（"
              f"`cd offline && python qa_gen_quality_probes.py V2 V4 V9`）。"
              f"报告不在库内时本判据无从行使。")
        return 1
    rep = json.loads(REPORT.read_text(encoding="utf-8"))
    missing = [k for k in REQUIRED if k not in rep]
    if missing:
        fails.append(f"报告缺字段 {missing}——形状漂移会让下面的断言变成空转")

    if rep.get("answers", 0) <= 0:
        fails.append("answers=0：判据**未被行使**（没有答案素材）。这不算绿，是空跑")
    if rep.get("codes_total", 0) <= 0:
        fails.append("codes_total=0：本组答案没出现任何错误码，接地判据未被行使——"
                     "需换含强标识符的素材，或如实标注'不适用'而不是当通过")
    if rep.get("codes_ungrounded") != 0:
        fails.append(f"codes_ungrounded={rep.get('codes_ungrounded')}（应 0）："
                     f"答案里出现了 refs 未覆盖的错误码——那是幻觉候选，样例 "
                     f"{rep.get('ungrounded_samples')}")

    if not PROVENANCE.is_file():
        fails.append(f"找不到 {PROVENANCE}——无同代基线，本判据的第一条断言无从行使")
    else:
        want = ((json.loads(PROVENANCE.read_text(encoding="utf-8")).get("chunks") or {})
                .get("sha256"))
        got = rep.get("chunks_sha256")
        if got is None:
            fails.append("报告没有 chunks_sha256：无同代戳的接地结论不能引用")
        elif got != want:
            fails.append(f"chunks_sha256 不符：报告 {got} vs 同代基线 {want}——"
                         f"这份接地结论是在**另一份语料**上得出的（或语料已变而未重跑探针）")
        # 顺带用当前语料现算一次：基线自己也可能陈旧
        live = (provenance.chunks_digest() or {}).get("sha256")
        if live and live != want:
            fails.append(f"同代基线自身陈旧：PROVENANCE {want} vs 当前语料 {live}"
                         f"——先 `python provenance.py --stamp` 或重跑评测")

    if fails:
        print("FAIL 答案接地门闩未通过：")
        for f in fails:
            print(f"     - {f}")
        return 1
    print(f"OK   答案接地同代且零无据（answers={rep['answers']} codes={rep['codes_total']} "
          f"rate={rep['grounding_rate']:.0%}，语料摘要与 PROVENANCE 相符）")
    print("     判据：错误码 ⊆ 本轮 refs 的 error_codes 并集；只断'同代 + 零无据'，"
          "不断言 rate 数值（live 读数含波动，纳入 doc_numbers 会假红）")
    return 0


def show() -> int:
    if not REPORT.is_file():
        print(f"FAIL 找不到 {REPORT}")
        return 1
    rep = json.loads(REPORT.read_text(encoding="utf-8"))
    print(json.dumps(rep, ensure_ascii=False, indent=2))
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="答案接地报告门闩")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--show", action="store_true")
    a = ap.parse_args(argv)
    if a.show:
        return show()
    return check()


if __name__ == "__main__":
    raise SystemExit(main())
