#!/usr/bin/env python3
"""verify-quilt-view.py 的隔离回归测试。"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Callable


SCRIPT = Path(__file__).resolve().parents[1] / "verify-quilt-view.py"
LOCK_PATH = ".upstream/quilt-1.17.lock"
PACKAGE_REF = "refs/heads/debian/1.17"
REMOTE_REF = "refs/remotes/origin/debian/1.17"
FIXTURE_ENV = {
	"PATH": os.environ.get("PATH", os.defpath),
	"LC_ALL": "C",
	"GIT_CONFIG_NOSYSTEM": "1",
	"GIT_CONFIG_GLOBAL": os.devnull,
	"GIT_OPTIONAL_LOCKS": "0",
	"GIT_AUTHOR_NAME": "quilt fixture",
	"GIT_AUTHOR_EMAIL": "quilt@example.invalid",
	"GIT_COMMITTER_NAME": "quilt fixture",
	"GIT_COMMITTER_EMAIL": "quilt@example.invalid",
}


class Fixture:
	"""在一次性 Git 仓库中搭建上游、打包、quilt 视图和元数据段。"""

	def __init__(
		self,
		root: Path,
		*,
		series: list[str] | None = None,
		lock_transform: Callable[[dict[str, Any]], None] | None = None,
		package_transform: Callable[[Fixture], None] | None = None,
		metadata_transform: Callable[[Fixture], None] | None = None,
		historical: bool = False,
		view_drift: bool = False,
	):
		self.repo = root / "repo with spaces"
		self.repo.mkdir()
		self.git("init", "-q", "--template=", "--object-format=sha1", "-b", "quilt-source")
		self.write("src/value.txt", "base\n")
		self.commit("chore: 建立上游基底")
		self.upstream = self.rev("HEAD")

		patch_names = ["patch-one.patch", "nested/patch-two.patch"]
		patch_slugs = ["patch-one", "patch-two"]
		commits: list[str] = []
		generated_patches: list[bytes] = []
		for index, value in enumerate(("one", "two")):
			self.write("src/value.txt", f"{value}\n")
			if index == 1:
				self.write("src/space name.txt", "space path\nPatch-Name: patch-two\n")
			self.commit(
				f"fix(test): 应用第 {index + 1} 个补丁\n\n"
				f"Origin: vendor, test\nForwarded: no\nPatch-Name: {patch_slugs[index]}"
			)
			commits.append(self.rev("HEAD"))
			generated_patches.append(self.git("format-patch", "--stdout", "-1", "HEAD"))
		quilt_tree = self.tree("HEAD")

		self.git("checkout", "-q", "-b", "debian/1.17", self.upstream)
		series_lines = series if series is not None else ["  # 补丁队列", "", " patch-one.patch\t", patch_names[1]]
		self.write("debian/source/format", "3.0 (quilt)\n")
		self.write("debian/patches/series", "\n".join(series_lines) + "\n")
		for path, content in zip(patch_names, generated_patches):
			self.write_bytes(f"debian/patches/{path}", content)
		if package_transform is not None:
			package_transform(self)
		self.commit("chore: 建立 Debian 打包提交")
		self.packaging = self.rev("HEAD")
		self.git("update-ref", REMOTE_REF, self.packaging)

		view_parent = self.upstream
		view_items = patch_names
		if historical or view_drift:
			# 模拟保留的发布历史：上一版已含第一条补丁和单独的运维覆盖。
			self.git("checkout", "-q", "-b", "maven", commits[0])
			self.write(".gitignore", "build/\n")
			self.commit("chore: 保留上一版运维配置")
			view_parent = self.rev("HEAD")
			view_items = patch_names[1:]
			if view_drift:
				self.write("src/value.txt", "意外漂移\n")
				self.write("src/space name.txt", "space path\nPatch-Name: patch-two\n")
				self.commit(self.git("show", "-s", "--format=%B", commits[1]).decode("utf-8"))
			else:
				self.git("cherry-pick", commits[1])
		else:
			self.git("checkout", "-q", "quilt-source")
		view_end = self.rev("HEAD")
		self.write(".github/workflows/test-push.yml", "name: test\n")
		self.write("README.md", "测试元数据\n")
		if metadata_transform is not None:
			metadata_transform(self)
		self.commit("docs: 建立 Maven 元数据段")
		self.metadata_end = self.rev("HEAD")

		lock = {
			"format": 1,
			"upstream": self.upstream,
			"packaging": self.packaging,
			"quilt_tree": quilt_tree,
			"view_parent": view_parent,
			"view_end": view_end,
			"metadata_end": self.metadata_end,
			"view_items": view_items,
			"overlays": self.overlays(quilt_tree, self.tree(self.metadata_end)),
		}
		if lock_transform is not None:
			lock_transform(lock)
		self.write(LOCK_PATH, json.dumps(lock, ensure_ascii=False, indent=2) + "\n")
		self.commit("chore: 固化 quilt 视图锁")
		self.view = self.rev("HEAD")

	def git(self, *args: str, input_bytes: bytes | None = None) -> bytes:
		proc = subprocess.run(
			["git", *args],
			cwd=self.repo,
			input=input_bytes,
			stdout=subprocess.PIPE,
			stderr=subprocess.PIPE,
			env=FIXTURE_ENV,
			check=False,
			shell=False,
		)
		if proc.returncode != 0:
			raise AssertionError(
				f"fixture Git 失败：git {' '.join(args)}\n"
				f"stdout={proc.stdout.decode(errors='replace')}\n"
				f"stderr={proc.stderr.decode(errors='replace')}"
			)
		return proc.stdout

	def rev(self, revision: str) -> str:
		return self.git("rev-parse", revision).decode("ascii").strip()

	def tree(self, revision: str) -> str:
		return self.rev(f"{revision}^{{tree}}")

	def write(self, path: str, text: str) -> None:
		self.write_bytes(path, text.encode("utf-8"))

	def write_bytes(self, path: str, content: bytes) -> None:
		target = self.repo / path
		target.parent.mkdir(parents=True, exist_ok=True)
		target.write_bytes(content)

	def commit(self, message: str) -> None:
		self.git("add", "-A")
		self.git("commit", "-q", "-m", message)

	def add_patch_header(self, patch: bytes, slug: str) -> bytes:
		separator = patch.find(b"\n\n")
		if separator < 0:
			raise AssertionError("format-patch 输出缺少邮件头分隔行")
		return patch[:separator] + f"\nPatch-Name: {slug}".encode("ascii") + patch[separator:]

	def entries(self, tree: str) -> dict[str, tuple[str, str]]:
		raw = self.git("ls-tree", "-rz", "--full-tree", tree)
		result: dict[str, tuple[str, str]] = {}
		for record in raw.split(b"\0"):
			if not record:
				continue
			meta, raw_path = record.split(b"\t", 1)
			mode, _kind, object_id = meta.split(b" ", 2)
			result[raw_path.decode("utf-8", "surrogateescape")] = (
				mode.decode("ascii"),
				object_id.decode("ascii"),
			)
		return result

	def overlays(self, quilt_tree: str, metadata_tree: str) -> dict[str, list[list[str] | None]]:
		left = self.entries(quilt_tree)
		right = self.entries(metadata_tree)
		result: dict[str, list[list[str] | None]] = {}
		for path in sorted(set(left) | set(right)):
			left_entry = list(left[path]) if path in left else None
			right_entry = list(right[path]) if path in right else None
			if left_entry != right_entry:
				result[path] = [left_entry, right_entry]
		return result


class VerifyQuiltViewTest(unittest.TestCase):
	"""覆盖锁文件和队列视图的关键失败门禁。"""

	def run_verify(self, fixture: Fixture, *args: str) -> subprocess.CompletedProcess[str]:
		return subprocess.run(
			[sys.executable, "-I", "-B", str(SCRIPT), *args],
			cwd=fixture.repo,
			env={**FIXTURE_ENV, "TMPDIR": str(fixture.repo.parent)},
			capture_output=True,
			encoding="utf-8",
			check=False,
			shell=False,
		)

	def assert_failed(self, fixture: Fixture, text: str) -> None:
		result = self.run_verify(fixture)
		self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
		self.assertIn(text, result.stderr, result.stderr)

	def test_valid_view(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-valid-") as directory:
			fixture = Fixture(Path(directory))
			result = self.run_verify(fixture)
			self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
			self.assertIn("patches=2 replayed=2 overlays=2", result.stdout)

	def test_historical_view_and_dirty_worktree(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-historical-") as directory:
			fixture = Fixture(Path(directory), historical=True)
			fixture.write(LOCK_PATH, "未提交的草稿\n")
			fixture.commit("docs: 写入未完成的新锁")
			fixture.write(LOCK_PATH, "本地工作树的草稿\n")
			result = self.run_verify(fixture, "--view", fixture.view, "--package-ref", PACKAGE_REF)
			self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
			self.assertIn("patches=2 replayed=1 overlays=3", result.stdout)
			self.assertEqual((fixture.repo / LOCK_PATH).read_text(encoding="utf-8"), "本地工作树的草稿\n")

	def test_wrong_order(self) -> None:
		def reverse_items(lock: dict[str, Any]) -> None:
			lock["view_items"] = list(reversed(lock["view_items"]))

		with tempfile.TemporaryDirectory(prefix="verify-quilt-order-") as directory:
			self.assert_failed(Fixture(Path(directory), lock_transform=reverse_items), "view_items")

	def test_duplicate_series(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-duplicate-") as directory:
			fixture = Fixture(Path(directory), series=["patch-one.patch", "patch-one.patch"])
			self.assert_failed(fixture, "重复列出")

	def test_missing_series_target(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-missing-") as directory:
			fixture = Fixture(Path(directory), series=["missing.patch"])
			self.assert_failed(fixture, "缺少 'debian/patches/missing.patch'")

	def test_out_of_bounds_series_path(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-boundary-") as directory:
			fixture = Fixture(Path(directory), series=["../patch-one.patch"])
			self.assert_failed(fixture, ".. 段")

	def test_omitted_patch_file(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-omitted-") as directory:
			fixture = Fixture(Path(directory), series=["patch-one.patch"])
			self.assert_failed(fixture, "未列入 series")

	def test_patch_source_drift(self) -> None:
		def drift(fixture: Fixture) -> None:
			path = fixture.repo / "debian/patches/nested/patch-two.patch"
			path.write_bytes(path.read_bytes().replace(b"+two\n", b"+drift\n"))

		with tempfile.TemporaryDirectory(prefix="verify-quilt-drift-") as directory:
			fixture = Fixture(Path(directory), package_transform=drift)
			self.assert_failed(fixture, "完整 series 重放后的 tree")

	def test_view_commit_drift(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-view-drift-") as directory:
			fixture = Fixture(Path(directory), view_drift=True)
			self.assert_failed(fixture, "不等于视图提交")

	def test_packaging_source_change(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-package-change-") as directory:
			fixture = Fixture(Path(directory), package_transform=lambda repo: repo.write("src/value.txt", "wrong\n"))
			self.assert_failed(fixture, "除 debian/ 外必须完全等于 upstream")

	def test_invalid_overlay(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-overlay-") as directory:
			fixture = Fixture(Path(directory), metadata_transform=lambda repo: repo.write("src/value.txt", "wrong\n"))
			self.assert_failed(fixture, "未获准路径 'src/value.txt'")

	def test_overlay_exact_mode(self) -> None:
		def override(lock: dict[str, Any]) -> None:
			lock["overlays"]["README.md"][1][0] = "100755"

		with tempfile.TemporaryDirectory(prefix="verify-quilt-overlay-mode-") as directory:
			fixture = Fixture(Path(directory), lock_transform=override)
			self.assert_failed(fixture, "overlays 的 mode/blob 与实际不一致")

	def test_final_metadata_strict_allowlist(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-final-") as directory:
			fixture = Fixture(Path(directory))
			fixture.write(".upstream/releases/wcpe-v1.17.24.md", "正式清单\n")
			fixture.commit("docs: 增加正式清单")
			result = self.run_verify(fixture)
			self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
			fixture.write(".upstream/releases/wcpe-v1.17.024.md", "非规范的文件名\n")
			fixture.commit("docs: 增加非规范文件名")
			self.assert_failed(fixture, "未获准路径 '.upstream/releases/wcpe-v1.17.024.md'")

	def test_patch_name_only_in_diff_is_rejected(self) -> None:
		def omit_name(fixture: Fixture) -> None:
			path = fixture.repo / "debian/patches/nested/patch-two.patch"
			path.write_bytes(path.read_bytes().replace(b"\nPatch-Name: patch-two", b"", 1))

		with tempfile.TemporaryDirectory(prefix="verify-quilt-header-") as directory:
			fixture = Fixture(Path(directory), package_transform=omit_name)
			self.assert_failed(fixture, "唯一非空的合法 Patch-Name")

	def test_patch_name_in_mail_header(self) -> None:
		def move_name(fixture: Fixture) -> None:
			path = fixture.repo / "debian/patches/nested/patch-two.patch"
			patch = path.read_bytes().replace(b"\nPatch-Name: patch-two", b"", 1)
			path.write_bytes(fixture.add_patch_header(patch, "patch-two"))

		with tempfile.TemporaryDirectory(prefix="verify-quilt-rfc-header-") as directory:
			fixture = Fixture(Path(directory), package_transform=move_name)
			result = self.run_verify(fixture)
			self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

	def test_duplicate_patch_name_in_mail(self) -> None:
		def duplicate_name(fixture: Fixture) -> None:
			path = fixture.repo / "debian/patches/nested/patch-two.patch"
			path.write_bytes(fixture.add_patch_header(path.read_bytes(), "patch-two"))

		with tempfile.TemporaryDirectory(prefix="verify-quilt-duplicate-name-") as directory:
			fixture = Fixture(Path(directory), package_transform=duplicate_name)
			self.assert_failed(fixture, "唯一非空的合法 Patch-Name")

	def test_missing_origin_in_patch(self) -> None:
		def omit_origin(fixture: Fixture) -> None:
			patch = fixture.repo / "debian/patches/patch-one.patch"
			patch.write_bytes(patch.read_bytes().replace(b"Origin: vendor, test\n", b"", 1))

		with tempfile.TemporaryDirectory() as directory:
			fixture = Fixture(Path(directory), package_transform=omit_origin)
			self.assert_failed(fixture, "一致且非空的 Origin")

	def test_short_sha(self) -> None:
		def shorten(lock: dict[str, Any]) -> None:
			lock["upstream"] = lock["upstream"][:8]

		with tempfile.TemporaryDirectory(prefix="verify-quilt-short-") as directory:
			self.assert_failed(Fixture(Path(directory), lock_transform=shorten), "40 位")

	def test_patch_symlink(self) -> None:
		def link(fixture: Fixture) -> None:
			path = fixture.repo / "debian/patches/nested/patch-two.patch"
			path.unlink()
			path.symlink_to("../patch-one.patch")

		with tempfile.TemporaryDirectory(prefix="verify-quilt-symlink-") as directory:
			fixture = Fixture(Path(directory), package_transform=link)
			self.assert_failed(fixture, "不接受链接或 gitlink")

	def test_packaging_code_is_not_executed_and_repository_is_unchanged(self) -> None:
		with tempfile.TemporaryDirectory(prefix="verify-quilt-untrusted-") as directory:
			root = Path(directory)
			marker = root / "build-invoked"

			def write_rules(fixture: Fixture) -> None:
				fixture.write("debian/rules", f"#!{sys.executable}\nfrom pathlib import Path\nPath({str(marker)!r}).touch()\n")
				(fixture.repo / "debian/rules").chmod(0o755)

			fixture = Fixture(root, package_transform=write_rules)
			fixture.git("checkout", "-q", "debian/1.17")
			git_dir = fixture.repo / ".git"
			index_before = (git_dir / "index").read_bytes()
			objects_before = set((git_dir / "objects").rglob("*"))
			refs_before = fixture.git("show-ref")
			result = self.run_verify(fixture, "--view", fixture.view)
			self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
			self.assertFalse(marker.exists(), "校验不能执行 debian/rules")
			self.assertEqual((git_dir / "index").read_bytes(), index_before)
			self.assertEqual(set((git_dir / "objects").rglob("*")), objects_before)
			self.assertEqual(fixture.git("show-ref"), refs_before)
			self.assertEqual(fixture.rev("HEAD"), fixture.packaging)
			self.assertEqual(fixture.git("status", "--porcelain"), b"")


if __name__ == "__main__":
	unittest.main(verbosity=2)
