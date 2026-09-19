"""证据快照打包器的回归锁（`scripts/pack_evidence.py`）。

为什么这些用例必须存在：这个脚本的产出会被**发给别人看**，所以它有两类静默失效：

  1. **假覆盖**——MANIFEST 声称覆盖了某项，而该产物在干净检出里根本不存在
     （比如有人把 `logs/` 这种 gitignore 路径当成普通证据登记，CI/他人检出后就是空的）。
  2. **泄漏**——把凭据或本机独有的运行态数据打进归档，然后被原样分发出去。

两者都不会报错，只会让归档看起来完整却不可信（或更糟：可信但泄密）。故用纯函数级用例钉死。

不触网、零凭据：`DASHSCOPE_API_KEY` 用的是显而易见的占位串，不含任何真实凭据。
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent.parent.parent / "scripts"

# 经 sys.path 正常导入（与其它用例对 offline/ 的做法一致），而不是 importlib 按路径各自加载一份：
# 后者每次调用都产生**新的模块对象**，于是"把某条不变量改坏再跑用例"这种变异验证无法传导到用例里，
# 回归锁就等于没被验证过（本文件自己在写完后正是这么踩了一次）。
sys.path.insert(0, str(SCRIPTS))
import pack_evidence as pe  # noqa: E402

FAKE_KEY = "sk-PLACEHOLDER-not-a-real-credential-0000"


def _is_gitignored(rel: str) -> bool:
    r = subprocess.run(["git", "check-ignore", "-q", rel], cwd=pe.REPO, check=False,
                       capture_output=True)
    return r.returncode == 0


def _exists(rel: str) -> bool:
    return (pe.REPO / rel).exists()


def test_registry_paths_are_supplyable_by_a_clean_checkout() -> None:
    """登记表里的非 LOCAL 项必须能被干净检出提供（即：不得是 gitignore 路径）。

    否则 MANIFEST 会声称覆盖，而实际归档是空的——"假覆盖"正是本文件要防的第一类失效。
    """
    offenders = [rel for rel, _, prereq in pe.REGISTRY
                 if prereq != pe.LOCAL and _exists(rel) and _is_gitignored(rel)]
    assert not offenders, f"这些登记项被 gitignore，干净检出下会是空的：{offenders}"


def test_local_evidence_paths_are_actually_ignored() -> None:
    """反过来：标为 LOCAL 的项**必须**确实不入库，否则标注在撒谎。"""
    offenders = [rel for rel, _, prereq in pe.REGISTRY
                 if prereq == pe.LOCAL and _exists(rel) and not _is_gitignored(rel)]
    assert not offenders, f"这些项标为'本机独有'却其实入库了：{offenders}"


def test_credentials_are_never_packed() -> None:
    """凭据类路径绝不出现在登记表里（归档是给人看的，这条是硬约束）。"""
    forbidden = (".env", "data/", "backup/", "redteam_tokens", "demo_tokens")
    hit = [rel for rel, _, _ in pe.REGISTRY if any(f in rel for f in forbidden)]
    assert not hit, f"登记表不得包含凭据类路径：{hit}"


def test_mode_detection_reads_presence_not_value(monkeypatch) -> None:
    """模式判定只看 key 存在与否；值不进任何产物。"""
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    assert pe.detect_mode() == "mock"
    monkeypatch.setenv("DASHSCOPE_API_KEY", FAKE_KEY)
    assert pe.detect_mode() == "live"


def test_manifest_never_contains_the_key_value(monkeypatch) -> None:
    """把 key 设进环境后渲染 MANIFEST，全文不得出现它的值。"""
    monkeypatch.setenv("DASHSCOPE_API_KEY", FAKE_KEY)
    meta = {"generated_at": "2026-01-01T00:00:00+08:00",
            "git": {"sha": "0" * 40, "dirty": False, "dirty_paths": []},
            "mode": pe.detect_mode(), "python": "3.11.9", "host_os": "linux",
            "includes_local_evidence": False}
    out = pe.render_manifest(meta, [], [], None)
    assert FAKE_KEY not in out, "MANIFEST 泄漏了凭据值"
    assert "set" in out, "应只以 set/absent 形式出现"


def test_manifest_must_warn_about_local_evidence(monkeypatch) -> None:
    """含本机运行态证据时必须显著告警——否则归档会被原样公开分发。"""
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    items = [{"path": "logs/audit.jsonl", "bytes": 1, "sha256": "a" * 64,
              "produce": "运行期落盘", "prereq": pe.LOCAL}]
    meta = {"generated_at": "t", "git": {"sha": "0" * 40, "dirty": False, "dirty_paths": []},
            "mode": "mock", "python": "3.11", "host_os": "linux", "includes_local_evidence": True}
    out = pe.render_manifest(meta, items, [], None)
    assert "不要原样公开分发" in out


def test_mock_archive_declares_live_numbers_unverifiable(monkeypatch) -> None:
    """mock 档 + live 报告：必须明说"这里的数字复核不出来"。

    这是本项目双模约束的直接后果（README 明写 mock 不承诺指标表数字）。
    归档若不说这句，读者会拿 mock 快照去核对 live 数字，然后以为数字是错的。
    """
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    prov = {"report_mode": "live", "report_backend": {"embedding": "dashscope:text-embedding-v3"},
            "corpus_docs": {"sha256": "a" * 64, "files": 64},
            "chunks": {"sha256": "b" * 64, "lines": 423},
            "golden": {"sha256": "c" * 64, "samples": 59}}
    meta = {"generated_at": "t", "git": {"sha": "0" * 40, "dirty": False, "dirty_paths": []},
            "mode": "mock", "python": "3.11", "host_os": "linux", "includes_local_evidence": False}
    out = pe.render_manifest(meta, [], [], prov)
    assert "复核不出来" in out
    assert "mock" in out


def test_local_evidence_is_excluded_unless_opted_in(tmp_path, monkeypatch) -> None:
    """默认排除 LOCAL 证据；显式开启才纳入。"""
    for rel, _, _ in pe.REGISTRY:
        p = tmp_path / rel
        if rel.endswith("/"):
            p.mkdir(parents=True, exist_ok=True)
            (p / "x.txt").write_bytes(b"x")
        else:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b"x")
    monkeypatch.setattr(pe, "REPO", tmp_path)

    default_items, skipped = pe.collect(False)
    assert not [i for i in default_items if i["prereq"] == pe.LOCAL]
    assert any("include-local-evidence" in s for s in skipped), "跳过原因要告诉人怎么打开"

    with_local, _ = pe.collect(True)
    assert [i for i in with_local if i["prereq"] == pe.LOCAL]


def test_collect_dedupes_files_listed_twice(tmp_path, monkeypatch) -> None:
    """同一文件被"单文件项"和"目录项"同时覆盖时，不得重复计入。

    重复会让 CHECKSUMS.sha256 出现同一路径两行，`sha256sum -c` 的输出随之产生无意义重复项。
    """
    for rel, _, _ in pe.REGISTRY:
        p = tmp_path / rel
        if rel.endswith("/"):
            p.mkdir(parents=True, exist_ok=True)
            (p / "x.txt").write_bytes(b"x")
        else:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b"x")
    monkeypatch.setattr(pe, "REPO", tmp_path)
    items, _ = pe.collect(True)
    paths = [i["path"] for i in items]
    assert len(paths) == len(set(paths)), "collect() 必须按路径去重"


def test_checksums_file_is_lf_so_sha256sum_c_works(tmp_path, monkeypatch) -> None:
    """CHECKSUMS 必须以 LF 落盘。

    实测踩到过：Windows 上 write_text 写出 CRLF，`sha256sum -c` 把行尾的 \\r 当成文件名的一部分，
    于是**全部**项报 "No such file or directory"——归档自带的完整性校验命令直接失效。
    """
    src = pe.REPO / "offline" / "eval" / "reports" / "PROVENANCE.json"
    assert src.is_file(), "本用例依赖仓库内真实存在的一个产物"
    monkeypatch.setattr(pe, "REPO", tmp_path)
    monkeypatch.setattr(pe, "REGISTRY", [("offline/eval/reports/PROVENANCE.json", "x", pe.CLEAN)])
    (tmp_path / "offline" / "eval" / "reports").mkdir(parents=True)
    (tmp_path / "offline" / "eval" / "reports" / "PROVENANCE.json").write_bytes(src.read_bytes())
    assert pe.main(["--out", str(tmp_path / "out")]) == 0

    archives = list((tmp_path / "out").iterdir())
    assert len(archives) == 1
    sums = (archives[0] / "CHECKSUMS.sha256").read_bytes()
    assert b"\r" not in sums, "CHECKSUMS 含 CR，sha256sum -c 会全项失败"
    assert sums.endswith(b"\n")

    if os.name != "nt":  # noqa: SIM108  # Windows 无 sha256sum，跨平台校验只在 POSIX 上跑
        r = subprocess.run(["sha256sum", "-c", "CHECKSUMS.sha256"], cwd=archives[0], check=False,
                           capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr
