#!/usr/bin/env python3
"""校验 Debian quilt 队列与 Maven 应用视图的一致性。"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from email.parser import BytesParser
from email.policy import compat32
from pathlib import Path
from typing import Any


HEX40 = re.compile(r"^[0-9a-f]{40}$")
PATCH_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
MODE6 = re.compile(r"[0-7]{6}")
VERSION_NUMBER = r"(?:0|[1-9][0-9]*)"
RELEASE_NOTE = re.compile(rf"\.upstream/releases/wcpe-v{VERSION_NUMBER}\.{VERSION_NUMBER}\.{VERSION_NUMBER}\.md")
LOCK_PATH = ".upstream/quilt-1.17.lock"
SERIES_PATH = "debian/patches/series"
PATCH_DIR = "debian/patches/"
FILE_MODES = {"100644", "100755"}
TreeEntry = tuple[str, str, str]
TreeEntries = dict[str, TreeEntry]

FINAL_METADATA_FILES = {
	".upstream/quilt-1.17.lock",
	"AGENTS.md",
	"README.md",
	".upstream/README.md",
	".upstream/PATCH-QUEUE.md",
	".upstream/migration-1.17.md",
	"CLAUDE.md",
	".github/workflows/test-push.yml",
	"scripts/sync-upstream.sh",
	"scripts/verify-quilt-view.py",
	"scripts/tests/verify-quilt-view-test.py",
}

OVERLAY_FILES = {
	"AGENTS.md",
	"CLAUDE.md",
	"README.md",
	".gitignore",
	"build.gradle",
	"gradle/ci-testkit-maven-central.init.gradle",
	"src/test/groovy/net/fabricmc/loom/test/util/GradleProjectTestTrait.groovy",
}


class VerificationError(Exception):
	"""表示锁文件、Git 拓扑或补丁内容不符合规范。"""


class GitError(VerificationError):
	"""表示 Git 只读查询或隔离重放失败。"""


class GitReader:
	"""源仓库只查询对象和引用；同样的 Git 调用器也可用于临时裸仓库。"""

	def __init__(self, repo: Path):
		self.repo = repo

	def run(self, *args: str, data: bytes | None = None, allowed_status: tuple[int, ...] = (0,)) -> bytes:
		# 不继承 GIT_DIR、索引、替换引用、配置注入或外部程序设置。禁止协议也可在旧 Git 上阻止按需拉取。
		env = {
			"PATH": os.environ.get("PATH", os.defpath),
			"LC_ALL": "C",
			"GIT_CONFIG_NOSYSTEM": "1",
			"GIT_CONFIG_GLOBAL": os.devnull,
			"GIT_ATTR_NOSYSTEM": "1",
			"GIT_OPTIONAL_LOCKS": "0",
			"GIT_GRAFT_FILE": os.devnull,
			"GIT_NO_LAZY_FETCH": "1",
			"GIT_ALLOW_PROTOCOL": "",
			"GIT_TERMINAL_PROMPT": "0",
		}
		try:
			proc = subprocess.run(
				[
					"git", "--no-pager", "--no-replace-objects",
					"-c", f"core.hooksPath={os.devnull}", "-c", "core.fsmonitor=false",
					"-c", "advice.graftFileDeprecated=false", *args,
				],
				cwd=self.repo,
				input=data,
				stdin=subprocess.DEVNULL if data is None else None,
				capture_output=True,
				env=env,
				shell=False,
				check=False,
			)
		except OSError as exc:
			raise GitError(f"无法启动 git：{exc}") from exc
		if proc.returncode not in allowed_status:
			detail = proc.stderr.decode("utf-8", "replace").strip()
			raise GitError(f"git {args[0]} 失败" + (f"：{detail!r}" if detail else ""))
		return proc.stdout

	def objects_path(self) -> bytes:
		return self.run("rev-parse", "--path-format=absolute", "--git-path", "objects").removesuffix(b"\n")

	def resolve_commit(self, revision: str, label: str) -> str:
		if re.fullmatch(r"[0-9a-fA-F]{1,39}", revision):
			raise VerificationError(f"{label} 不接受短 SHA；请传完整引用或 40 位提交号。")
		try:
			value = self.run("rev-parse", "--verify", "--end-of-options", f"{revision}^{{commit}}")
		except GitError as exc:
			raise VerificationError(f"无法解析 {label} {revision!r}；请确认提交在本地仓库中存在。") from exc
		commit = value.decode("ascii").strip()
		if not HEX40.fullmatch(commit):
			raise VerificationError(f"{label} 未解析为 40 位 SHA-1 提交号：{commit!r}。")
		return commit

	def resolve_package_ref(self, ref: str) -> str:
		if not ref.startswith("refs/"):
			raise VerificationError("--package-ref 必须是完整的 refs/... 引用。")
		try:
			oid = self.run("show-ref", "--verify", "--hash", "--", ref).decode("ascii").strip()
		except GitError as exc:
			raise VerificationError(f"未找到 canonical 打包引用 {ref!r}；请先显式 fetch 打包分支，或用 --package-ref 指定本地引用。") from exc
		return self.require_commit(oid, f"打包引用 {ref}")

	def require_commit(self, oid: str, label: str) -> str:
		if self.resolve_commit(oid, label) != oid:
			raise VerificationError(f"{label} 必须直接指向 commit，而不是 tag 或其他对象。")
		return oid

	def tree_id(self, commit: str) -> str:
		return self.run("rev-parse", "--verify", f"{commit}^{{tree}}").decode("ascii").strip()

	def tree_entries(self, treeish: str, *, recursive: bool = True) -> TreeEntries:
		flags = ("-rt",) if recursive else ()
		raw = self.run("ls-tree", "--full-tree", "-z", *flags, treeish)
		entries: TreeEntries = {}
		for record in raw.split(b"\0")[:-1]:
			meta, raw_path = record.split(b"\t", 1)
			mode, kind, oid = meta.decode("ascii").split(" ")
			path = raw_path.decode("utf-8", "surrogateescape")
			if path in entries:
				raise VerificationError(f"Git tree 中出现重复路径 {path!r}。")
			entries[path] = (mode, kind, oid)
		return entries

	def blob(self, oid: str) -> bytes:
		return self.run("cat-file", "blob", oid)

	def linear_commits(self, ancestor: str, descendant: str, label: str) -> list[str]:
		raw = self.run("rev-list", "--topo-order", "--reverse", "--parents", f"{ancestor}..{descendant}")
		commits: list[str] = []
		previous = ancestor
		for line in raw.decode("ascii").splitlines():
			commit, *parents = line.split()
			if len(parents) > 1:
				raise VerificationError(f"{label} 包含 merge 提交 {commit}；请保持单亲线性历史。")
			if parents != [previous]:
				raise VerificationError(f"{label} 没有从 {ancestor} 连续到 {descendant}；请核对祖先关系并确保不是浅克隆。")
			commits.append(commit)
			previous = commit
		if previous != descendant:
			raise VerificationError(f"{label} 没有从 {ancestor} 连续到 {descendant}；请修正 lock 边界。")
		return commits

	def changed_paths(self, parent: str, commit: str) -> list[str]:
		raw = self.run(
			"diff-tree", "--no-commit-id", "--name-only", "-r", "-z", "--no-renames",
			"--no-ext-diff", "--no-textconv", "--ignore-submodules=none", parent, commit, "--",
		)
		return [path.decode("utf-8", "surrogateescape") for path in raw.split(b"\0")[:-1]]


def relative_path(path: Any, label: str) -> str:
	if not isinstance(path, str) or not path or any(ord(c) < 32 or ord(c) == 127 for c in path):
		raise VerificationError(f"{label} 不是正常的非空相对路径：{path!r}。")
	if path.startswith("/") or "\\" in path or re.match(r"^[A-Za-z]:", path):
		raise VerificationError(f"{label} 只能使用仓库内的正斜杠相对路径：{path!r}。")
	if any(part in {"", ".", ".."} or part.lower() == ".git" for part in path.split("/")):
		raise VerificationError(f"{label} 含空段、.、.. 段或 .git：{path!r}。")
	return path


class IsolatedReplay:
	"""所有索引和新对象写入临时裸仓库；alternates 只读取原对象。"""

	def __init__(self, root: Path, objects: bytes):
		if not os.path.isabs(objects) or b"\n" in objects or b"\r" in objects:
			raise VerificationError("源仓库对象目录必须是可用的绝对路径，且不能包含换行。")
		self.git = GitReader(root)
		self.git.run("init", "--bare", "--quiet", "--template=", "--object-format=sha1")
		# alternates 按行读路径，不按空格或冒号拆分。
		(root / "objects/info/alternates").write_bytes(objects + b"\n")
		self.tree = ""

	def reset(self, tree: str) -> None:
		for path in self.git.tree_entries(tree):
			if path.split("/", 1)[0].lower() == "debian" or ".git" in path.lower().split("/"):
				raise VerificationError(f"重放起点包含受保护路径 {path!r}；请修正 upstream 或 view_parent。")
		self.git.run("read-tree", tree)
		self.tree = tree

	@staticmethod
	def _check_path(raw: bytes, name: str) -> None:
		path = relative_path(raw.decode("utf-8", "surrogateescape"), f"补丁 {name!r} 的 diff 路径")
		if path.split("/", 1)[0].lower() == "debian":
			raise VerificationError(f"补丁 {name!r} 不得修改受保护路径 {path!r}。")

	def apply(self, patch: bytes, name: str) -> str:
		try:
			raw = self.git.run("apply", "--numstat", "-z", data=patch)
		except GitError as exc:
			raise VerificationError(f"补丁 {name!r} 无法解析；请重新导出补丁。{exc}") from exc
		paths = iter(raw.split(b"\0")[:-1])
		for record in paths:
			parts = record.split(b"\t", 2)
			if len(parts) != 3:
				raise VerificationError(f"补丁 {name!r} 的 Git 路径记录异常。")
			if parts[2]:
				self._check_path(parts[2], name)
			else:
				self._check_path(next(paths, b""), name)
				self._check_path(next(paths, b""), name)
		try:
			self.git.run("apply", "--cached", "--whitespace=nowarn", data=patch)
		except GitError as exc:
			raise VerificationError(f"补丁 {name!r} 无法按 series 重放；请检查补丁顺序与内容。{exc}") from exc
		new_tree = self.git.run("write-tree").decode("ascii").strip()
		# 某些 Git 的 numstat 对重命名只报目标；以实际树变化再核对所有端点。
		for path in self.git.changed_paths(self.tree, new_tree):
			self._check_path(path.encode("utf-8", "surrogateescape"), name)
		self.tree = new_tree
		return new_tree

	def trailers(self, message: bytes) -> dict[str, list[str]]:
		raw = self.git.run("interpret-trailers", "--parse", "--no-divider", data=message)
		fields: dict[str, list[str]] = {}
		for line in raw.split(b"\n"):
			key, separator, value = line.partition(b":")
			if separator:
				fields.setdefault(key.decode("utf-8", "replace").lower(), []).append(value.decode("utf-8", "replace").strip())
		return fields


class Verifier:
	"""执行 lock、quilt、view 和 metadata 边界的完整校验。"""

	def __init__(self, repo: Path, view_ref: str, package_ref: str):
		self.git = GitReader(repo)
		self.view_ref = view_ref
		self.package_ref = package_ref

	@staticmethod
	def _strict_json(raw: bytes) -> dict[str, Any]:
		try:
			def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
				result: dict[str, Any] = {}
				for key, value in items:
					if key in result:
						raise VerificationError(f"锁文件包含重复 JSON 字段 {key!r}；请重新生成 lock。")
					result[key] = value
				return result
			value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
		except (UnicodeDecodeError, json.JSONDecodeError) as exc:
			raise VerificationError(f"目标视图中的 .upstream/quilt-1.17.lock 不是合法 UTF-8 JSON：{exc}") from exc
		if not isinstance(value, dict):
			raise VerificationError("锁文件顶层必须是 JSON 对象；请重新生成 lock。")
		return value

	@staticmethod
	def _validate_sha(value: Any, field: str) -> None:
		if not isinstance(value, str) or not HEX40.fullmatch(value):
			raise VerificationError(f"锁文件字段 {field} 必须是完整 40 位小写十六进制 SHA；请重新生成 lock。")

	@staticmethod
	def _entry_value(path: str, entry: TreeEntry | None) -> list[str] | None:
		if entry is None:
			return None
		mode, kind, oid = entry
		if kind != "blob":
			raise VerificationError(f"覆盖项 {path!r} 必须是 blob，不能是 {kind}。")
		return [mode, oid]

	@staticmethod
	def _require_blob(entries: TreeEntries, path: str, label: str) -> str:
		entry = entries.get(path)
		if entry is None:
			raise VerificationError(f"{label} 缺少 {path!r}；请查看对应提交的文件树。")
		mode, kind, oid = entry
		if kind != "blob" or mode not in FILE_MODES:
			raise VerificationError(f"{label} 的 {path!r} 不是普通 blob（{mode}/{kind}）；不接受链接或 gitlink。")
		return oid

	def _load_lock(self, view_commit: str) -> dict[str, Any]:
		oid = self._require_blob(self.git.tree_entries(view_commit), LOCK_PATH, "目标视图")
		lock = self._strict_json(self.git.blob(oid))
		expected = {
			"format",
			"upstream",
			"packaging",
			"quilt_tree",
			"view_parent",
			"view_end",
			"metadata_end",
			"view_items",
			"overlays",
		}
		if set(lock) != expected:
			missing = sorted(expected - set(lock))
			extra = sorted(set(lock) - expected)
			raise VerificationError(f"锁文件字段不精确；缺失={missing!r}，多余={extra!r}。请按 format 1 重新生成 lock。")
		if type(lock["format"]) is not int or lock["format"] != 1:
			raise VerificationError("锁文件 format 必须是整数 1；请重新生成 lock。")
		for field in ("upstream", "packaging", "quilt_tree", "view_parent", "view_end", "metadata_end"):
			self._validate_sha(lock[field], field)
		if not isinstance(lock["view_items"], list):
			raise VerificationError("锁文件 view_items 必须是列表；请重新生成 lock。")
		items = [relative_path(item, f"view_items[{index}]") for index, item in enumerate(lock["view_items"])]
		if len(items) != len(set(items)):
			raise VerificationError("锁文件 view_items 含重复路径；请按 series 顺序重新生成 lock。")
		if not isinstance(lock["overlays"], dict):
			raise VerificationError("锁文件 overlays 必须是对象；请重新生成 lock。")
		for path, value in lock["overlays"].items():
			relative_path(path, f"overlays[{path!r}]")
			if not (isinstance(value, list) and len(value) == 2):
				raise VerificationError(f"overlays[{path!r}] 必须是 [quilt-entry, maven-entry]；请重新生成 lock。")
			for side, entry in zip(("quilt", "maven"), value):
				if entry is None:
					continue
				if not (isinstance(entry, list) and len(entry) == 2):
					raise VerificationError(f"overlays[{path!r}].{side} 必须是 null 或 [mode, blob]。")
				mode, object_id = entry
				if not isinstance(mode, str) or not MODE6.fullmatch(mode):
					raise VerificationError(f"overlays[{path!r}].{side} 的 mode 必须是六位数字字符串。")
				self._validate_sha(object_id, f"overlays[{path!r}].{side}.blob")
		return lock

	def _check_package(self, lock: dict[str, Any], package_ref_commit: str) -> TreeEntries:
		upstream, packaging = lock["upstream"], lock["packaging"]
		self.git.linear_commits(packaging, package_ref_commit, f"packaging 到 canonical 打包引用 {self.package_ref!r}")
		self.git.linear_commits(upstream, packaging, "upstream 到 packaging")
		upstream_root = self.git.tree_entries(upstream, recursive=False)
		package_root = self.git.tree_entries(packaging, recursive=False)
		debian = package_root.pop("debian", None)
		if debian is None or debian[:2] != ("040000", "tree"):
			raise VerificationError("packaging 必须包含普通 debian/ 树；请检查打包提交。")
		if package_root != upstream_root:
			changed = sorted(path for path in package_root.keys() | upstream_root.keys() if package_root.get(path) != upstream_root.get(path))
			raise VerificationError(f"packaging 树除 debian/ 外必须完全等于 upstream；不同的根条目：{changed!r}。")
		entries = self.git.tree_entries(packaging)
		format_oid = self._require_blob(entries, "debian/source/format", "packaging")
		if self.git.blob(format_oid).strip() != b"3.0 (quilt)":
			raise VerificationError("debian/source/format 必须声明 3.0 (quilt)；请修正打包元数据。")
		return entries

	def _parse_series(self, entries: TreeEntries) -> dict[str, str]:
		series_oid = self._require_blob(entries, SERIES_PATH, "packaging")
		try:
			series = self.git.blob(series_oid).decode("utf-8")
		except UnicodeDecodeError as exc:
			raise VerificationError(f"{SERIES_PATH} 必须是 UTF-8 文本。") from exc
		for path, (mode, kind, _) in entries.items():
			if path.startswith(PATCH_DIR) and kind != "tree" and (kind != "blob" or mode not in FILE_MODES):
				raise VerificationError(f"{path!r} 不是普通补丁文件；不接受链接或 gitlink（{mode}/{kind}）。")

		patches: dict[str, str] = {}
		for number, raw_line in enumerate(series.split("\n"), 1):
			line = raw_line.strip()
			if not line or line.startswith("#"):
				continue
			label = f"{SERIES_PATH} 第 {number} 行"
			if len(line.split()) != 1:
				raise VerificationError(f"{label} 只能列出一个补丁文件名，不接受选项或行尾注释。")
			path = relative_path(line, label)
			if path == "series":
				raise VerificationError(f"{label} 不得把 series 自身列为补丁。")
			if path in patches:
				raise VerificationError(f"{label} 重复列出 {path!r}；请只保留一项。")
			patches[path] = self._require_blob(entries, PATCH_DIR + path, "packaging 中 series 列出的补丁")

		for path, (_, kind, _) in entries.items():
			if path.startswith(PATCH_DIR) and path != SERIES_PATH and kind != "tree" and path[len(PATCH_DIR):] not in patches:
				raise VerificationError(f"{path!r} 未列入 series；请补列或移除文件。")
		return patches

	def _check_trailers(self, replay: IsolatedReplay, patch: bytes, path: str, commit: str) -> str:
		mail = BytesParser(policy=compat32).parsebytes(patch, headersonly=True)
		# gbp 导出的 DEP-3 溯源块位于邮件说明末尾；只解析 diff 之前的内容。
		body = mail.get_payload(decode=True) or b""
		description = re.split(rb"(?m)^(?:diff --git |---(?:[ \t][^\r\n]*)?\r?$)", body, maxsplit=1)[0]
		provenance = replay.trailers(b"Patch\n\n" + description.rstrip() + b"\n")
		patch_names = [str(value).strip() for value in mail.get_all("Patch-Name", [])] + provenance.get("patch-name", [])
		if len(patch_names) != 1 or len(patch_names[0]) > 48 or not PATCH_NAME.fullmatch(patch_names[0]):
			raise VerificationError(f"补丁 {path!r} 的邮件头或前置溯源块必须有唯一非空的合法 Patch-Name（小写 slug，最多 48 字符）。")

		message = self.git.run("cat-file", "commit", commit).partition(b"\n\n")[2]
		actual = replay.trailers(message)
		if actual.get("patch-name") != patch_names:
			raise VerificationError(f"视图提交 {commit} 的唯一 Patch-Name trailer 必须匹配 {path!r} 的 {patch_names[0]!r}；请核对顺序与溯源块。")
		for key in ("Origin", "Forwarded"):
			headers = [str(value).strip() for value in mail.get_all(key, [])]
			for expected in (headers, provenance.get(key.lower())):
				if expected and actual.get(key.lower()) != expected:
					raise VerificationError(f"视图提交 {commit} 的 {key} trailer 与补丁 {path!r} 不一致；请保留打包溯源。")
		return patch_names[0]

	@staticmethod
	def _allowed_overlay(path: str) -> bool:
		return path != LOCK_PATH and (path.startswith((".github/", ".upstream/", "scripts/")) or path in OVERLAY_FILES)

	@staticmethod
	def _allowed_final_metadata(path: str) -> bool:
		return path in FINAL_METADATA_FILES or RELEASE_NOTE.fullmatch(path) is not None

	def _check_overlays(self, replay: IsolatedReplay, lock: dict[str, Any]) -> None:
		# 展开树可能只存在于临时对象库。目录由其叶路径对账，不把目录哈希列为覆盖条目。
		quilt_entries = {path: entry for path, entry in replay.git.tree_entries(lock["quilt_tree"]).items() if entry[1] != "tree"}
		metadata_entries = {path: entry for path, entry in self.git.tree_entries(lock["metadata_end"]).items() if entry[1] != "tree"}
		expected = {}
		for path in sorted(quilt_entries.keys() | metadata_entries.keys()):
			left, right = quilt_entries.get(path), metadata_entries.get(path)
			if left == right:
				continue
			if not self._allowed_overlay(path):
				raise VerificationError(f"quilt_tree 到 metadata_end 覆盖了不允许的构件路径 {path!r}；请把改动放回 quilt 队列。")
			expected[path] = [self._entry_value(path, left), self._entry_value(path, right)]
		declared = lock["overlays"]
		if expected != declared:
			mismatches = sorted(path for path in expected.keys() | declared.keys() if expected.get(path) != declared.get(path))
			raise VerificationError(f"overlays 的 mode/blob 与实际不一致：{mismatches[:10]!r}；请重新生成 lock。")

	def _check_metadata(self, ancestor: str, end: str, *, final: bool) -> None:
		label = "最终元数据段" if final else "Maven 元数据段"
		allowed = self._allowed_final_metadata if final else self._allowed_overlay
		parent = ancestor
		for commit in self.git.linear_commits(parent, end, label):
			for path in self.git.changed_paths(parent, commit):
				if not allowed(path):
					raise VerificationError(f"{label}的提交 {commit} 改动了未获准路径 {path!r}；请把构件改动放回 quilt 队列。")
			parent = commit

	def run(self) -> dict[str, Any]:
		view_commit = self.git.resolve_commit(self.view_ref, "--view")
		lock = self._load_lock(view_commit)
		package_ref_commit = self.git.resolve_package_ref(self.package_ref)
		for key in ("upstream", "packaging", "view_parent", "view_end", "metadata_end"):
			self.git.require_commit(lock[key], f"lock.{key}")
		patches = self._parse_series(self._check_package(lock, package_ref_commit))
		positions = {path: index for index, path in enumerate(patches)}
		position = -1
		for path in lock["view_items"]:
			if path not in positions:
				raise VerificationError(f"view_items 补丁 {path!r} 未列入 {SERIES_PATH}；请修正 lock。")
			if positions[path] <= position:
				raise VerificationError(f"view_items 的 {path!r} 不符合 series 顺序；请修正 lock。")
			position = positions[path]
		commits = self.git.linear_commits(lock["view_parent"], lock["view_end"], "quilt 视图段")
		if len(commits) != len(lock["view_items"]):
			raise VerificationError(f"quilt 视图提交数 {len(commits)} 与 view_items 数量 {len(lock['view_items'])} 不一致。")
		self._check_metadata(lock["view_end"], lock["metadata_end"], final=False)
		self._check_metadata(lock["metadata_end"], view_commit, final=True)

		with tempfile.TemporaryDirectory(prefix="verify-quilt-view-") as directory:
			replay = IsolatedReplay(Path(directory), self.git.objects_path())
			# 独立展开全队列：它不依赖 Maven 历史，也不依赖预先存在的 quilt_tree 对象。
			replay.reset(self.git.tree_id(lock["upstream"]))
			for path, oid in patches.items():
				replay.apply(self.git.blob(oid), path)
			if replay.tree != lock["quilt_tree"]:
				raise VerificationError(f"完整 series 重放后的 tree {replay.tree} 不等于 lock.quilt_tree {lock['quilt_tree']}；请核对补丁或重新生成 lock。")

			# Maven 可能继承已经发布的 Patched 历史和运维覆盖；以实际父树逐条核对选中的重放提交。
			replay.reset(self.git.tree_id(lock["view_parent"]))
			slugs: set[str] = set()
			for path, commit in zip(lock["view_items"], commits):
				patch = self.git.blob(patches[path])
				slug = self._check_trailers(replay, patch, path, commit)
				if slug in slugs:
					raise VerificationError(f"重放段的 Patch-Name {slug!r} 重复；请保持补丁标识唯一。")
				slugs.add(slug)
				applied_tree = replay.apply(patch, path)
				expected_tree = self.git.tree_id(commit)
				if applied_tree != expected_tree:
					raise VerificationError(f"补丁 {path!r} 重放后的 tree {applied_tree} 不等于视图提交 {commit} 的 tree {expected_tree}；请重新重放该补丁。")
			self._check_overlays(replay, lock)
		return {"view": view_commit, "package": lock["packaging"], "patches": len(patches), "replayed": len(commits), "overlays": len(lock["overlays"])}


def build_parser() -> argparse.ArgumentParser:
	parser = argparse.ArgumentParser(description="校验 Debian quilt 队列与 Maven 应用视图")
	parser.add_argument("--view", default="HEAD", help="目标 Maven 视图提交，默认 HEAD")
	parser.add_argument(
		"--package-ref",
		default="refs/remotes/origin/debian/1.17",
		help="canonical Debian 打包引用，默认 refs/remotes/origin/debian/1.17",
	)
	return parser


def main(argv: list[str] | None = None) -> int:
	args = build_parser().parse_args(argv)
	try:
		result = Verifier(Path.cwd(), args.view, args.package_ref).run()
	except (VerificationError, OSError) as exc:
		print(f"错误: {exc}", file=sys.stderr)
		return 1
	print(
		f"通过: view={result['view']} package={result['package']} "
		f"patches={result['patches']} replayed={result['replayed']} overlays={result['overlays']}"
	)
	return 0


if __name__ == "__main__":
	sys.exit(main())
