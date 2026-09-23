# -*- coding: utf-8 -*-
r"""接地一致性判据的回归锁（`offline/grounding.py`）。

为什么这些用例必须存在：V9 是"防幻觉事后环"的**唯一**度量，而它有两条静默失效路径，
两条都不报错、只会让人以为这里被保护着：

  1. **判据挂在空气上**——词法正则被换成永不匹配的形状（或有人另写了一份副本又改错），
     于是 `ungrounded_error_codes` 恒返回空集，"幻觉率 0%"变成恒真式。这正是本项目对
     空转门闩的前科类型（CI 用 mtime 判同代，checkout 刷平时间戳后恒真）。
  2. **方向偏错**——把"求差集"写成"求交集"或"恒空"，正常答案照样绿，而幻觉答案也绿。

故本文件既锁"改坏必红"（变异验证，见 test_detection_is_bound_to_the_lexicon），
也锁"反向正常流程不得红"（接地答案必须判为空集 / 接地率 1.0），并用 pattern 相等 +
"源码不得就地 compile" 两条结构锁防未来重构引入第三份错误码词法。

**变异验证实测记录（2026-09-24，本机 pytest 9.1.1；全套 12 用例）**——逐条真跑，不是推论：
  ① `ungrounded_error_codes` 改成恒返回 `set()` → **2 failed / 10 passed**
     （test_hallucinated_code_is_caught、test_detection_is_bound_to_the_lexicon 变红）。
  ② 在 grounding.py 内就地 `re.compile` 一份**同串**副本 → **1 failed / 11 passed**
     （test_lexicon_is_imported_not_compiled_here 变红）。
  ③ 就地编译一份**漂移**副本（`\d{5}`）→ **7 failed / 5 passed**，含
     test_lexicon_matches_the_chunker_source。
  ④ 词法换成永不匹配的空正则 → 幻觉答案漏检（test_detection_is_bound_to_the_lexicon 内联覆盖）。

**一次"假绿"的现场记录（② 的前身）**：结构锁第一版写的是 `ERROR_CODE_RE is errorcode.ERROR_CODE_RE`，
用**源码文本**匹配 `compile(`。实测两条都不成立——`is` 对同串副本恒真（`re.compile` 按
(pattern, flags) 有内部缓存），而文本匹配会被本模块 docstring 里自我描述的 `compile(` 打成假红。
改成"AST 里不得有 compile 调用 + pattern/flags 相等"之后才真正红了。**留这段是因为它本身就是
本文件要防的东西**：一个看起来在保护、实际什么都没挡的门闩。
"""
from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

import pytest

OFFLINE = Path(__file__).resolve().parent.parent

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把某条不变量改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
sys.path.insert(0, str(OFFLINE))
sys.path.insert(0, str(OFFLINE / "chunkers"))
import grounding as g  # noqa: E402
import errorcode  # noqa: E402

ALLOWED = {"50012_DB_TIMEOUT", "42901_RATE_LIMIT_EXCEEDED"}


# ---------------------------------------------------------------- 词法提取

def test_extracts_and_dedupes_error_codes():
    ans = "先看 50012_DB_TIMEOUT，同类还有 50012_DB_TIMEOUT；另见 42901_RATE_LIMIT_EXCEEDED。"
    assert g.error_codes_in(ans) == {"50012_DB_TIMEOUT", "42901_RATE_LIMIT_EXCEEDED"}


def test_empty_and_none_are_safe():
    assert g.error_codes_in("") == set()
    assert g.error_codes_in(None) == set()
    assert g.ungrounded_error_codes(None, ALLOWED) == set()
    assert g.grounding_rate(None, ALLOWED) == 1.0


def test_plain_numbers_are_not_error_codes():
    """5 位数字但不带 _大写段（端口/时长/行号）不得被当成错误码——否则误报率会失真。"""
    assert g.error_codes_in("超时 5000ms，行号 12345，端口 8081") == set()


# ---------------------------------------------------------------- 参考集合解析

def test_ref_error_codes_accepts_both_key_shapes():
    """done 帧用 chunkId（camelCase，AnswerPayload.Ref），离线语料用 chunk_id（snake）——两种都要认。"""
    table = {"c1": {"50012_DB_TIMEOUT"}, "c2": {"42901_RATE_LIMIT_EXCEEDED"}}
    refs = [{"chunkId": "c1"}, {"chunk_id": "c2"}]
    assert g.ref_error_codes(refs, table) == {"50012_DB_TIMEOUT", "42901_RATE_LIMIT_EXCEEDED"}


def test_unknown_chunk_contributes_nothing():
    """引用查不到元数据 → 不贡献授权码（方向保守：该 chunk 的码全判无据，宁可误报不放过）。"""
    assert g.ref_error_codes([{"chunkId": "ghost"}], {"c1": {"50012_DB_TIMEOUT"}}) == set()


def test_ref_error_codes_tolerates_missing_metadata():
    assert g.ref_error_codes([{"chunkId": "c1"}], {"c1": None}) == set()
    assert g.ref_error_codes([], {}) == set()
    assert g.ref_error_codes(None, {}) == set()


# ---------------------------------------------------------------- 判据正反两面

def test_grounded_answer_is_clean():
    """反向正常流程不得红：全部错误码有据 → 空集、接地率 1.0。"""
    ans = "根因是 50012_DB_TIMEOUT，扩容连接池即可。"
    assert g.ungrounded_error_codes(ans, ALLOWED) == set()
    assert g.grounding_rate(ans, ALLOWED) == 1.0


def test_hallucinated_code_is_caught():
    """正向改坏必红：答案出现参考中不存在的码 → 必须被点名（若实现退化为恒空集，此处红）。"""
    ans = "根因是 50012_DB_TIMEOUT，也可能是 50099_GHOST_FAIL。"
    assert g.ungrounded_error_codes(ans, ALLOWED) == {"50099_GHOST_FAIL"}
    assert g.grounding_rate(ans, ALLOWED) == 0.5


def test_no_codes_means_out_of_jurisdiction():
    """无错误码的答案不在本判据管辖内——定义为 1.0 而非 0.0，避免把"没管到"说成"管住了"。"""
    assert g.grounding_rate("建议先扩容连接池并观察。", ALLOWED) == 1.0


# ---------------------------------------------------------------- 单一词法源 + 变异验证

def test_lexicon_matches_the_chunker_source():
    """词法必须与 chunker 单一事实源逐字符相等（pattern + flags）。

    这里**不能用 `is` 判身份**：`re.compile` 按 (pattern, flags) 有内部缓存，同串副本会拿到
    同一个对象，`is` 对副本恒真——那是假绿（本用例第一版正是这样，实测变异不红，见文件头）。
    真正要防的是**漂移**：chunker 改词法（并与 Java 同步）而此处副本不变，于是 V9 检出的
    "幻觉"与入库元数据对不上，两个都自称正确。
    """
    assert g.ERROR_CODE_RE.pattern == errorcode.ERROR_CODE_RE.pattern
    assert g.ERROR_CODE_RE.flags == errorcode.ERROR_CODE_RE.flags


def test_lexicon_is_imported_not_compiled_here():
    """结构锁：grounding.py 里不得存在任何 `compile(...)` **调用**——词法只能"导入"，不能"就地编译"。

    这一条才是真正防"引入副本"的那道：副本在**今天与正本同串**时行为无害，但它是未来的漂移
    载体，而上一条 pattern 相等断言只能在已经漂移之后才报警。本模块单一职责，就地编译没有正当
    理由；若将来确有第二种词法需求，应改这条锁并写明理由，而不是绕过它。

    判据走 **AST 而非源码文本**：文本匹配会被本模块 docstring 里"不得出现 compile("这类
    自我描述句子打成假红（第一版正是如此，实测红）。只认真正的 Call 节点，散文自然免疫。
    """
    tree = ast.parse((OFFLINE / "grounding.py").read_text(encoding="utf-8"))
    offenders = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        f = node.func
        if (isinstance(f, ast.Attribute) and f.attr == "compile") or \
           (isinstance(f, ast.Name) and f.id == "compile"):
            offenders.append(getattr(node, "lineno", "?"))
    assert not offenders, \
        f"grounding.py 第 {offenders} 行就地编译了正则——错误码词法必须从 chunkers/errorcode.py 导入"


def test_detection_is_bound_to_the_lexicon(monkeypatch):
    """变异验证（改坏必红）：把词法换成"永不匹配"后，同一条幻觉答案不再被检出。

    证明 V9 的判据真的挂在错误码词法上，而不是某种恒真的旁路——若此处变异后仍检出，
    说明判据来自别处，那条"幻觉率 0%"就不可信。
    """
    ans = "疑似 50099_GHOST_FAIL 导致连接耗尽。"
    assert g.ungrounded_error_codes(ans, ALLOWED) == {"50099_GHOST_FAIL"}   # 正常：检出
    monkeypatch.setattr(g, "ERROR_CODE_RE", re.compile(r"(?!x)x"))          # 变异：空正则
    assert g.ungrounded_error_codes(ans, ALLOWED) == set()                  # 变异后漏检
    assert g.grounding_rate(ans, ALLOWED) == 1.0


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
