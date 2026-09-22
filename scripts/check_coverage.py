#!/usr/bin/env python3
"""覆盖率棘轮：读 JaCoCo 产物，低于门槛就红（B9 / 2026-09-22）。

为什么是「报告进构建 + 独立脚本判定」而不是 jacoco:check：本项目既有的门闩都是这个形状
（check_panel_contract.sh / offline/provenance.py / offline/doc_numbers.py），脚本能把
实测值与门槛一起打出来供文档引用，jacoco:check 只给一句失败。

口径：
  * **LINE 设闸、BRANCH 只报不设闸**。
  * 门槛 = 首次实测值向下取整再留 1pp。**1pp 是抖动余量，不是目标值**——棘轮要防的是
    「覆盖率掉下来没人发现」，不是「任何一次新增分支都必须同时补测」。贴着实测值设闸会
    让合法的防御性分支当场变红，然后这条红就被学会忽略。

首次实测（2026-09-22）：LINE 47.83%（971/2030）／BRANCH 42.67%（358/839）→ 门槛 46.0。

0 token、无网络、只用标准库。用 `--report` 只打印不判定。
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 2026-09-22 首次实测 LINE 47.83% → floor(47.83) - 1.0 = 46.0。
# 改这个数要写明理由，别为了过闸降线。
LINE_FLOOR = 46.0

REPORT_REL = Path("target") / "site" / "jacoco" / "jacoco.xml"


def main(argv: list[str]) -> int:
    report_only = "--report" in argv
    artifact = Path(__file__).resolve().parent.parent / REPORT_REL

    if not artifact.is_file():
        # 缺产物不是「跳过」而是「没验证」——静默放过等于把门闩变成摆设。
        print(f"COVERAGE FAIL: 找不到 {REPORT_REL}（先跑 mvn -B test）")
        return 1

    counters: dict[str, tuple[int, int]] = {}
    for counter in ET.parse(artifact).getroot().findall("counter"):
        counters[counter.get("type", "")] = (
            int(counter.get("missed", "0")),
            int(counter.get("covered", "0")),
        )

    def pct(kind: str) -> float:
        missed, covered = counters.get(kind, (0, 0))
        total = missed + covered
        return 100.0 * covered / total if total else 0.0

    line_pct, branch_pct = pct("LINE"), pct("BRANCH")
    ok = line_pct + 1e-9 >= LINE_FLOOR

    print("COVERAGE 棘轮（LINE 设闸 / BRANCH 只报）")
    print(f"  LINE   {line_pct:6.2f}% (门槛 {LINE_FLOOR:5.2f}%)   BRANCH {branch_pct:6.2f}%（只报不设闸）")

    if report_only:
        print("COVERAGE REPORT-ONLY：只打印，未判定")
        return 0
    if not ok:
        print(f"COVERAGE FAIL：LINE {line_pct:.2f}% < 门槛 {LINE_FLOOR:.2f}%")
        print("  （门槛在 scripts/check_coverage.py；调门槛要写明理由，别为了过闸降线。）")
        return 1
    print("COVERAGE OK")
    return 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main(sys.argv[1:]))
