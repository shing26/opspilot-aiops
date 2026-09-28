"""三模式检索消融（OP-R5）：回答"融合到底贡献了什么"。

**为什么单独成脚本而不改 `evaluate.py`**：后者的产物（`eval_report.json`）已入库、被面二门闩登记、
并被 provenance 同代锁钉住——改它的输出等于动证据面。本脚本只**读**同一份 golden、走同一个 `/search`，
产出**独立报告**；且零 LLM 调用（`/search` 不调 LLM，只走 embedding/rerank）。

**它要回答的问题来自一次实测**：报告里 `hybrid` 与 `vector_only` 的读数**逐位相同**
（exact 1.0/1.0/1.0；semantic hit@1 0.88 / hit@3 1.0 / MRR 0.940）。而指标是**粗量具**
（final-top-3 截断 + RRF k=60 会把尾部差异吃掉），故"逐位相同"同时兼容两种解释：

  (a) 融合生效，只是在这批查询上没改变 top-3；        ← 正面结论，可讲
  (b) ES 腿的结果根本没进融合（接线/空结果/被覆盖）。  ← 那是缺陷，会被问穿

本脚本用 **id 级对照**把它们分开：逐题比 `hybrid` 与 `vector_only` 的 top-3 `doc_id`，
并统计最终 top-3 里"由 ES 腿贡献"（`es_score > 0`）的条数。**注意**：这条 id 级对照
无法从已入库报告里得到（那份只有聚合读数、无 per-query `doc_id`），所以它必须重跑一次 `/search`——
"零成本"指的是零 LLM token，不是零上游调用。

用法（offline/ 目录，需活体栈）:
  python eval/mode_ablation.py            # 出数并写 eval/reports/mode_ablation.{json,md}
  python eval/mode_ablation.py --quiet
"""
from __future__ import annotations

import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import localapi as api  # noqa: E402

GOLDEN = Path("eval/golden_dataset.jsonl")
REPORTS = Path("eval/reports")
MODES = ("es_only", "vector_only", "hybrid")
# 融合对比的一对：hybrid 相对纯向量，多出来的那部分就是"融合+词法腿"的边际贡献
PAIR = ("vector_only", "hybrid")


def ci95(p: float, n: int) -> float:
    """hit@1 的 95% 置信半宽（正态近似）：n=25、p=0.88 → ≈0.13。

    加它是为了堵住"25 条样本太少"的质疑——0.88 vs 0.64（显著）与 0.88 vs 0.88（不显著）
    在这个半宽下**一眼可辨**，不必争论。
    """
    return 0.0 if n == 0 else round(1.96 * math.sqrt(max(p * (1 - p), 0.0) / n), 4)


def probe(samples: list[dict], token: str, mode: str) -> dict:
    """一次遍历同时得出聚合指标与 id 级明细（不重复查询上游）。

    口径与 `eval/evaluate.py::metrics_for` 一致：hit@1=Top-1 命中、hit@3=前三命中、
    MRR=首个命中的倒数排名（全排名，不限前三）。
    """
    rows = []
    hit1 = hit3 = 0
    rr = 0.0
    for s in samples:
        res = api.search_docs(s["query"], mode, token)
        docs = [x["doc_id"] for x in res]
        expected = set(s["expected_docs"])
        h1 = bool(docs and docs[0] in expected)
        h3 = any(d in expected for d in docs[:3])
        rank = next((i for i, d in enumerate(docs, 1) if d in expected), 0)
        hit1 += h1
        hit3 += h3
        rr += (1.0 / rank) if rank else 0.0
        rows.append({
            "q": s["query"],
            "top3": docs[:3],
            "hit@1": h1,
            "hit@3": h3,
            # 最终 top-3 里由 ES 腿贡献的条数（es_score>0 表示该 chunk 出现在 ES 腿结果里）；
            # vector_only 模式下它恒为 0，这正是对照组的意义。
            "es_contributed": sum(1 for x in res[:3] if (x.get("es_score") or 0) > 0),
        })
    n = len(samples)
    return {
        "n": n,
        "hit@1": round(hit1 / n, 4),
        "hit@3": round(hit3 / n, 4),
        "mrr": round(rr / n, 4),
        "ci95_hit@1": ci95(hit1 / n, n),
        "rows": rows,
    }


def fusion_marginal(base: dict, fused: dict) -> dict:
    """把 (a)/(b) 两种解释分开：逐题比 top-3 id，并统计 ES 腿的贡献条数。"""
    identical = sum(1 for a, b in zip(base["rows"], fused["rows"]) if a["top3"] == b["top3"])
    es_items = sum(r["es_contributed"] for r in fused["rows"])
    # 只有当"两条腿都在结果里留下痕迹"时，融合的接线才算在活体内被证明过
    both_legs = sum(1 for r in fused["rows"] if r["es_contributed"] > 0)
    return {
        "n": base["n"],
        "identical_top3": identical,
        "identical_top3_ratio": round(identical / base["n"], 4) if base["n"] else 0.0,
        "es_contributed_items_in_top3": es_items,
        "queries_with_es_contribution": both_legs,
        "hit@1_delta_vs_base": round(fused["hit@1"] - base["hit@1"], 4),
        "hit@3_delta_vs_base": round(fused["hit@3"] - base["hit@3"], 4),
        "mrr_delta_vs_base": round(fused["mrr"] - base["mrr"], 4),
    }


def cross_check(out: dict) -> dict:
    """与已入库的 `eval_report.json` 对照——两处口径若分叉，报告本身就不可信。

    不比 detail、只比聚合；并且**不**用"必须相等"当门闩（live 读数含真实检索，允许极小抖动），
    而是把差异显式写进报告，由人判读。这样两处记录不会静默分叉。
    """
    committed = REPORTS / "eval_report.json"
    if not committed.is_file():
        return {"available": False, "note": "eval_report.json 不在库内，跳过对照"}
    ref = json.loads(committed.read_text(encoding="utf-8")).get("modes", {})
    deltas = {}
    for mode in MODES:
        for bucket in ("exact", "semantic"):
            got = out["modes"][mode][bucket]
            want = (ref.get(mode) or {}).get(bucket) or {}
            for k in ("hit@1", "hit@3", "mrr"):
                if k in want:
                    deltas[f"{mode}.{bucket}.{k}"] = round(got[k] - want[k], 4)
    worst = max((abs(v) for v in deltas.values()), default=0.0)
    return {"available": True, "max_abs_delta": worst,
            "matches_within_0.02": worst <= 0.02, "deltas": deltas,
            "note": "两处同源同语料，差异应≈0；>0.02 说明语料/配置已分叉，先查再引用"}


def main(argv: list[str] | None = None) -> int:
    quiet = "--quiet" in (argv if argv is not None else sys.argv[1:])
    tokens = api.load_tokens()
    token = tokens["sre_l3"]
    samples = [json.loads(line) for line in GOLDEN.open(encoding="utf-8") if line.strip()]
    exact = [s for s in samples if s["type"] == "exact"]
    semantic = [s for s in samples if s["type"] == "semantic"]
    backend = api.get_json("/api/v1/admin/metrics", token)["backend"]

    out = {
        "measured_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "backend": backend,
        "golden_samples": len(samples),
        "modes": {},
    }
    for mode in MODES:
        out["modes"][mode] = {"exact": probe(exact, token, mode),
                              "semantic": probe(semantic, token, mode)}
        if not quiet:
            e, s = out["modes"][mode]["exact"], out["modes"][mode]["semantic"]
            print(f"  {mode:12s} exact {e['hit@1']:.2f}/{e['hit@3']:.2f}/{e['mrr']:.3f} "
                  f"| semantic {s['hit@1']:.2f}/{s['hit@3']:.2f}/{s['mrr']:.3f}")

    base, fused = PAIR
    out["fusion_marginal"] = {
        b: fusion_marginal(out["modes"][base][b], out["modes"][fused][b])
        for b in ("exact", "semantic")}
    out["cross_check"] = cross_check(out)
    out["note"] = (
        "判据读法：`identical_top3_ratio == 1.0` 说明 fusion 与纯向量在这批查询上给出**同一组** top-3；"
        "此时若 `queries_with_es_contribution > 0`，则'融合没接线'这一解释被排除（ES 腿的 chunk 确实"
        "进了候选池并参与了最终排序），剩下的唯一解释是'融合生效但未改变 top-3'。反过来，若 "
        "`queries_with_es_contribution == 0`，那是**接线问题**，不是'贡献测不出'。"
        "本报告**不改默认配置**：既有两条读数（es_only 对照 64%→88%、扩语料后 es_only 漂移而 hybrid 不变）"
        "都指向'多一条腿提抗漂'，把词法腿当'无用'拆掉的风险远大于收益。")

    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "mode_ablation.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")

    def pct(x):
        return f"{x:.0%}"

    fm_e, fm_s = out["fusion_marginal"]["exact"], out["fusion_marginal"]["semantic"]
    md = [
        "# 三模式检索消融（hybrid vs vector_only vs es_only）",
        "",
        f"> 实测时间：{out['measured_at']} ｜ golden {out['golden_samples']} 样本"
        f"（exact {len(exact)} + semantic {len(semantic)}） ｜ 后端 embedding={backend.get('embedding')}",
        "> 零 LLM token（`/search` 不调 LLM），但**每个模式每题都真走一次 embedding/rerank**。",
        "",
        "| 模式 | 精确 Hit@1 | 精确 Hit@3 | 精确 MRR | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for mode in MODES:
        e, s = out["modes"][mode]["exact"], out["modes"][mode]["semantic"]
        md.append(f"| {mode} | {pct(e['hit@1'])} | {pct(e['hit@3'])} | {e['mrr']:.3f} "
                  f"| {pct(s['hit@1'])}±{s['ci95_hit@1']:.2f} | {pct(s['hit@3'])} | {s['mrr']:.3f} |")
    md += [
        "",
        f"语义桶 Hit@1 的 95% 置信半宽：exact ±{out['modes']['hybrid']['exact']['ci95_hit@1']:.2f}、"
        f"semantic ±{out['modes']['hybrid']['semantic']['ci95_hit@1']:.2f}"
        "（n=34/25 的正态近似；用来判'两个模式的差异是否落在噪声里'）",
        "",
        "## 融合相对纯向量的边际贡献（id 级对照）",
        "",
        "| 桶 | n | top-3 逐题相同 | ES 腿在最终 top-3 里的贡献条数 | 有 ES 贡献的题数 | Δhit@1 | Δhit@3 | ΔMRR |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
        f"| exact | {fm_e['n']} | {fm_e['identical_top3']}/{fm_e['n']} | {fm_e['es_contributed_items_in_top3']} "
        f"| {fm_e['queries_with_es_contribution']} | {fm_e['hit@1_delta_vs_base']:+.4f} "
        f"| {fm_e['hit@3_delta_vs_base']:+.4f} | {fm_e['mrr_delta_vs_base']:+.4f} |",
        f"| semantic | {fm_s['n']} | {fm_s['identical_top3']}/{fm_s['n']} | {fm_s['es_contributed_items_in_top3']} "
        f"| {fm_s['queries_with_es_contribution']} | {fm_s['hit@1_delta_vs_base']:+.4f} "
        f"| {fm_s['hit@3_delta_vs_base']:+.4f} | {fm_s['mrr_delta_vs_base']:+.4f} |",
        "",
        out["note"],
        "",
    ]
    cc = out["cross_check"]
    md += ["## 与已入库报告的对照",
           "",
           (f"与 `eval_report.json` 的最大绝对偏差 **{cc['max_abs_delta']}**"
            + ("（≤0.02，两处同源一致）" if cc.get("matches_within_0.02") else "（**>0.02：语料/配置已分叉，先查再引用**）")
            if cc.get("available") else cc.get("note", "")),
           "",
           "口径：两处都从同一份 golden 与同一个 `/search` 现算；本报告额外记录 per-query `doc_id`，"
           "那份只有聚合读数。**本报告不参与** `doc_numbers`（live 读数含波动，该表只登记可从库内产物"
           "确定性派生的数字）。",
           "",
           f"复现：`cd offline && python eval/mode_ablation.py`（需活体栈 + `.env`）。",
           ""]
    (REPORTS / "mode_ablation.md").write_text("\n".join(md), encoding="utf-8", newline="")

    if not quiet:
        print(f"  fusion marginal: exact top3 相同 {fm_e['identical_top3']}/{fm_e['n']}、"
              f"ES 贡献 {fm_e['es_contributed_items_in_top3']} 条；"
              f"semantic 相同 {fm_s['identical_top3']}/{fm_s['n']}、ES 贡献 {fm_s['es_contributed_items_in_top3']} 条")
        print(f"  与已入库报告最大偏差 {cc.get('max_abs_delta')}")
        print(f"OK -> {REPORTS / 'mode_ablation.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
