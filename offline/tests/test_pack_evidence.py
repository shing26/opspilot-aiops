"""证据快照打包器的回归锁（`scripts/pack_evidence.py`）。

为什么这些用例必须存在：这个脚本的产出会被**发给别人看**，所以它有两类静默失效：

  1. **假覆盖**——MANIFEST 声称覆盖了某项，而该产物在干净检出里根本不存在
     （比如有人把 `logs/` 这种 gitignore 路径当成普通证据登记，CI/他人检出后就是空的）。
  2. **泄漏**——把凭据或本机独有的运行态数据打进归档，然后被原样分发出去。

两者都不会报错，只会让归档看起来完整却不可信（或更糟：可信但泄密）。故用纯函数级用例钉死。

不触网、零凭据：`DASHSCOPE_API_KEY` 用的是显而易见的占位串，不含任何真实凭据。
"""
from __future__ import annotations

import json
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


# ------------------------------------------------- 出包前的同代自检（设计决定 4）
#
# 本档对外的承诺是"每个数字都能在这里复核"。悄悄打出一份**报告已过期**的快照，
# 是最不该发生的一次出包：读者会把旧数字当现行结论（E1 事故正是这么传播的）。
# 故默认拒绝，放行必须显式 --allow-stale 且在档内留下可审计的痕迹。

def test_same_generation_is_green_on_real_repo() -> None:
    """正常流程：真实仓库上自检必须通过（否则门闩会拦住正常出包）。"""
    gen = pe.same_generation()
    assert gen["ok"] is True, gen
    assert gen["reason"] == "ok"
    assert set(gen["digests"]) == set(pe.pv.GATED_INPUTS)


def test_same_generation_detects_divergence(monkeypatch) -> None:
    """判据绑的是**产物摘要**：把登记里的 chunks 摘要改掉，必须判为分叉。"""
    real = pe.pv.load_provenance()
    assert real is not None, "本用例依赖仓库内真实存在的 PROVENANCE.json"
    tampered = dict(real)
    tampered["chunks"] = dict(real["chunks"], sha256="0" * 64)
    monkeypatch.setattr(pe.pv, "load_provenance", lambda *a, **k: tampered)

    gen = pe.same_generation()
    assert gen["ok"] is False
    assert gen["reason"] == "diverged"
    assert any("chunks" in d for d in gen["diffs"]), gen["diffs"]


def test_same_generation_reports_missing_registry(monkeypatch) -> None:
    """缺出处登记 → 判为不可判定（不是"默认通过"）。"""
    monkeypatch.setattr(pe.pv, "load_provenance", lambda *a, **k: None)
    gen = pe.same_generation()
    assert gen["ok"] is False
    assert gen["reason"] == "missing"
    assert pe.pv.PROVENANCE_REL in gen["detail"]


def _tiny_repo(tmp_path, monkeypatch):
    """把 REPO 指到一个最小仓库，免得拒绝用例去复制整仓。"""
    (tmp_path / "docs").mkdir(parents=True)
    (tmp_path / "docs" / "x.md").write_bytes(b"x")
    monkeypatch.setattr(pe, "REPO", tmp_path)
    monkeypatch.setattr(pe, "REGISTRY", [("docs/x.md", "人工记录", pe.CLEAN)])
    return tmp_path


_STALE = {"ok": False, "reason": "diverged", "detail": "随附报告与语料已分叉",
          "diffs": ["chunks: 摘要 aaaa… → bbbb…（lines=423→424）"],
          "digests": {"chunks": {"sha256": "b" * 64, "counts": {"lines": 424}}}}


def test_pack_refuses_when_generation_diverged(tmp_path, monkeypatch, capsys) -> None:
    """变异 E：报告与语料分叉 → 必须**拒绝出包**，且不留半个归档目录。

    留下目录等于留一份"看起来打好了"的过期快照，而它随时可能被当成成品分发。
    """
    root = _tiny_repo(tmp_path, monkeypatch)
    monkeypatch.setattr(pe, "same_generation", lambda: _STALE)

    assert pe.main(["--out", str(root / "out")]) == 1
    err = capsys.readouterr().err
    assert "拒绝出包" in err
    assert "chunks" in err, "拒绝时要指明哪一项分叉了"
    assert "--allow-stale" in err, "要告诉人怎么在确实需要时放行"
    assert not (root / "out").exists(), "被拒时不得留下归档目录"


def test_pack_allow_stale_marks_archive_as_stale(tmp_path, monkeypatch, capsys) -> None:
    """显式放行可以出包，但**必须留痕**：MANIFEST 正文显著标注 + JSON 记 ok=false。"""
    root = _tiny_repo(tmp_path, monkeypatch)
    monkeypatch.setattr(pe, "same_generation", lambda: _STALE)

    assert pe.main(["--out", str(root / "out"), "--allow-stale"]) == 0
    assert "过期快照" in capsys.readouterr().err

    archive = next((root / "out").iterdir())
    manifest = (archive / "MANIFEST.md").read_text(encoding="utf-8")
    assert "同代自检：未通过" in manifest
    assert "不可作为「当前能力」引用" in manifest
    assert "chunks: 摘要" in manifest, "差异明细要原样写进档内"
    data = json.loads((archive / "MANIFEST.json").read_text(encoding="utf-8"))
    assert data["meta"]["same_generation"]["ok"] is False


def test_pack_records_green_selfcheck_in_archive(tmp_path, monkeypatch) -> None:
    """同代时也如实登记（"通过了"同样是要写下来的结论，否则读者无从判断有没有查过）。"""
    root = _tiny_repo(tmp_path, monkeypatch)
    monkeypatch.setattr(pe, "same_generation", lambda: {"ok": True, "reason": "ok",
                                                       "detail": "随附报告与语料同代",
                                                       "diffs": [], "digests": {}})
    assert pe.main(["--out", str(root / "out")]) == 0
    archive = next((root / "out").iterdir())
    assert "同代自检：通过" in (archive / "MANIFEST.md").read_text(encoding="utf-8")
    data = json.loads((archive / "MANIFEST.json").read_text(encoding="utf-8"))
    assert data["meta"]["same_generation"]["ok"] is True


def test_manifest_flags_missing_selfcheck_rather_than_silence(monkeypatch) -> None:
    """未携带自检结论时要显式说"未记录"——静默缺席会被读成"查过了、没问题"。"""
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    meta = {"generated_at": "t", "git": {"sha": "0" * 40, "dirty": False, "dirty_paths": []},
            "mode": "mock", "python": "3.11", "host_os": "linux", "includes_local_evidence": False}
    assert "同代自检：未记录" in pe.render_manifest(meta, [], [], None)
