# -*- coding: utf-8 -*-
"""答案接地（Grounding）一致性校验——补上防幻觉链路的**事后环**。

为什么需要它：在线三道闸门全部落在"事前"（置信度空态门控拒答）或"通用文本层"
（逐字导出护栏）。于是系统能保证"没证据就不答"，却不能保证"答了的内容都有证据"——
答案里可以出现 refs 里根本不存在的错误码，而全链路无人察觉、无指标报警。本模块把这条
缺口变成一个可计算的数：**幻觉率 = 答案中出现的错误码未命中参考集合的比例**。

与 ADR-0010 的关系（勿混淆）：ADR-0010 否决的是"用事后检测**拦截**逐字导出"——理由是
已渲染字符收不回、对症状零改善。本模块不做拦截、不设卡、不改协议面，只**度量**：产物是
一个数，由 live 回归（`qa_gen_quality_probes.py` 的 V9）断言其为 0。判据是客观的——错误码
是精确符号，可做集合运算——不是句式猜测，故不受"软约束上限"那条限制。

词法单一事实源：错误码正则**不在此处另写一份**，直接复用 `chunkers/errorcode.py`。那份已由
`tests/test_chunkers.py::errorCodeLexiconMatchesJava` 与在线 Java `EsSearchService.ERROR_CODE`
逐字符锁死；在此复制第三份会让跨语言护栏失去意义（漂移时静默不匹配）。本文件由
`tests/test_grounding.py` 两条结构锁守着：①本模块词法与 errorcode 的 pattern/flags 必须相等
（**不能用 `is` 判身份**——`re.compile` 按 (pattern, flags) 有内部缓存，同串副本会返回同一对象，
`is` 对副本恒真、是假绿）；②本文件源码内不得出现 `compile(`，即词法只能"导入"不能"就地编译"。
"""
from __future__ import annotations

import sys
from pathlib import Path

# 经 sys.path 正常导入（与 probe / 各用例一致）：importlib 按路径各自加载会造出**新的模块对象**，
# 于是"把不变量改坏再跑用例"这种变异验证传导不进去，等于门闩没被验证过。
_CHUNKERS = Path(__file__).resolve().parent / "chunkers"
if str(_CHUNKERS) not in sys.path:
    sys.path.insert(0, str(_CHUNKERS))

from errorcode import ERROR_CODE_RE  # noqa: E402


def error_codes_in(text: str | None) -> set[str]:
    """文本中出现的全部错误码（去重）。空/None 安全。"""
    return set(ERROR_CODE_RE.findall(text or ""))


def ref_error_codes(refs, chunk_error_codes) -> set[str]:
    """本轮参考集合覆盖的错误码并集。

    refs：done 帧的引用数组，元素含 `chunkId` 或 `chunk_id`（见 `AnswerPayload.Ref`——
    注意它只带 chunkId/breadcrumb/service，**不含** error_codes，故必须回查语料元数据）。
    chunk_error_codes：chunk_id → 错误码集合（由 chunks.jsonl 的 metadata.error_codes 建立）。

    引用查不到的 chunk **静默跳过**：它不贡献授权码，于是答案里凡来自该 chunk 的码都会被判为
    无据——方向刻意偏保守（宁可误报，不放过）。这与"宁可拒答"的门控取向一致。
    """
    allowed: set[str] = set()
    for r in refs or ():
        cid = r.get("chunkId") or r.get("chunk_id")
        allowed |= set(chunk_error_codes.get(cid) or ())
    return allowed


def ungrounded_error_codes(answer: str | None, allowed) -> set[str]:
    """答案中出现、但未被参考集合覆盖的错误码——幻觉候选。**空集即接地成立**。"""
    return error_codes_in(answer) - set(allowed or ())


def grounding_rate(answer: str | None, allowed) -> float:
    """接地率 = 有据错误码 / 答案错误码总数。

    答案未出现任何错误码时定义为 1.0：本判据只对"可证伪的精确符号"负责，无错误码的答案
    不在其管辖范围（那类幻觉风险由 V4 语态锁与人工评审承担，不在此冒充已覆盖）。
    """
    codes = error_codes_in(answer)
    if not codes:
        return 1.0
    return (len(codes) - len(ungrounded_error_codes(answer, allowed))) / len(codes)
