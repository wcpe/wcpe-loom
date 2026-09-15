#!/usr/bin/env bash
# WCPE Loom 1.17 补丁队列维护脚本
#
# 用法：
#   scripts/sync-upstream.sh status          # 查看基底/队列末端/提交数（只读）
#   scripts/sync-upstream.sh verify          # 校验队列是否符合 .upstream/PATCH-QUEUE.md 规范（只读，默认）
#   scripts/sync-upstream.sh rebase <新基底sha> [新基底tag名]
#                                            # 把整条队列重放到新基底（在临时分支上执行，不碰默认分支）
#
# 原则（与 1.15 线一致）：
#   - 默认只读；任何改写都在临时分支上进行，**不自动改默认分支、不自动打发布 tag**
#   - 改写后必须人工确认内容等价，再自行切换分支
#
# 与 1.15 线的差异：本线是「整条序列 rebase」模型，故无 pick/candidates 之类的逐补丁采集命令；
# 同步上游用 rebase。

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

BASE_TAG="${WCPE_BASE_TAG:-base/architectury-1.17-026ce830}"
QUEUE_TAG="${WCPE_QUEUE_TAG:-patch-queue/1.17}"
UPSTREAM_REMOTE="${WCPE_UPSTREAM_REMOTE:-architectury}"

die() { echo "错误: $*" >&2; exit 1; }

base_commit() {
	git rev-parse -q --verify "refs/tags/${BASE_TAG}^{commit}" 2>/dev/null \
		|| die "找不到基底 tag ${BASE_TAG}"
}

queue_commit() {
	git rev-parse -q --verify "refs/tags/${QUEUE_TAG}^{commit}" 2>/dev/null || echo ""
}

cmd_status() {
	local base queue
	base="$(base_commit)"
	queue="$(queue_commit)"
	echo "基底 tag    : ${BASE_TAG} -> ${base:0:8}"
	echo "队列末端 tag: ${QUEUE_TAG} -> ${queue:0:8}"
	echo "当前 HEAD   : $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD))"
	if [ -n "${queue}" ]; then
		echo "队列提交数  : $(git rev-list --count "${base}..${queue}")"
		echo "队列是否含 merge: $(git rev-list --merges --count "${base}..${queue}") 个"
	fi
	echo "上游远端    : ${UPSTREAM_REMOTE} -> $(git rev-parse --short "${UPSTREAM_REMOTE}" 2>/dev/null || echo '(未 fetch)')"
}

cmd_verify() {
	local base queue rc=0
	base="$(base_commit)"
	queue="$(queue_commit)"
	[ -n "${queue}" ] || die "找不到队列末端 tag ${QUEUE_TAG}"

	local merges
	merges="$(git rev-list --merges --count "${base}..${queue}")"
	if [ "${merges}" != "0" ]; then
		echo "✗ 队列中存在 ${merges} 个 merge 提交（规范禁止）"; rc=1
	else
		echo "✓ 队列线性，无 merge 提交"
	fi

	echo "· 队列提交数: $(git rev-list --count "${base}..${queue}")"

	# 溯源块：序列中的提交均应含 Origin: 字段
	local missing
	missing="$(git log --format='%H %s' "${base}..${queue}" | while read -r sha _; do
		git log -1 --format=%B "${sha}" | grep -q '^Origin:' || echo "${sha:0:8}"
	done)"
	if [ -n "${missing}" ]; then
		echo "✗ 缺少溯源块（Origin:）的提交:"; echo "${missing}" | sed 's/^/    /'; rc=1
	else
		echo "✓ 所有补丁均含溯源块"
	fi

	# 工作树当前分支是否就是队列末端
	if [ "$(git rev-parse HEAD)" = "${queue}" ] || git merge-base --is-ancestor "${queue}" HEAD 2>/dev/null; then
		echo "✓ 当前分支基于队列末端"
	else
		echo "· 当前分支与队列末端无祖先关系（改写进行中？）"
	fi

	[ "${rc}" = "0" ] && echo "校验通过" || echo "校验未通过"
	return "${rc}"
}

cmd_rebase() {
	local new_base="${1:-}" new_tag="${2:-}"
	[ -n "${new_base}" ] || die "用法: scripts/sync-upstream.sh rebase <新基底sha> [新基底tag名]"

	local base branch
	base="$(base_commit)"
	git rev-parse -q --verify "${new_base}^{commit}" >/dev/null || die "新基底 ${new_base} 不存在（先 fetch ${UPSTREAM_REMOTE}）"

	if [ -n "${new_tag}" ]; then
		git tag -a "${new_tag}" "${new_base}" -m "architectury 1.17 基底（${new_base:0:8}）"
		echo "已锚定新基底 tag: ${new_tag}"
	fi

	branch="rebase/$(date +%Y%m%d-%H%M%S)"
	echo "在临时分支 ${branch} 上重放队列（不触碰默认分支）..."
	git branch "${branch}" "${QUEUE_TAG}^{commit}"
	git checkout -q "${branch}"
	if git rebase --onto "${new_base}" "${base}"; then
		echo
		echo "重放完成。请人工完成以下步骤："
		echo "  1) 复核冲突解决是否符合预期： git log --oneline ${new_base}..HEAD"
		echo "  2) 对比内容：                      git diff <改写前备份> HEAD"
		echo "  3) 确认无误后再自行切换默认分支，并前移 tag ${QUEUE_TAG}"
		echo "  4) 更新 .upstream/migration-1.17.md 的基底与溯源归类"
	else
		echo "重放出现冲突，解决后执行 git rebase --continue（当前在 ${branch}）"
		return 1
	fi
}

case "${1:-verify}" in
	status) cmd_status ;;
	verify) cmd_verify ;;
	rebase) shift; cmd_rebase "$@" ;;
	*) echo "用法: scripts/sync-upstream.sh {status|verify|rebase <新基底sha> [新基底tag名]}"; exit 2 ;;
esac
