#!/usr/bin/env bash
# WCPE Loom 1.17 补丁队列维护脚本
#
# 用法：
#   scripts/sync-upstream.sh status                     # 查看基底/队列末端/提交数（只读）
#   scripts/sync-upstream.sh verify                     # 校验队列是否符合规范（只读，默认）
#   scripts/sync-upstream.sh add-patch <补丁名>          # 开一个补丁工作分支（起点=队列末端）
#   scripts/sync-upstream.sh add-patch finish           # 校验溯源块 + 把元数据重放到新队列之后
#   scripts/sync-upstream.sh rebase <新基底sha> [tag名]  # 把整条队列重放到新基底（临时分支上）
#
# 原则（与 1.15 线一致）：
#   - 默认只读；任何改写都在临时/候选分支上进行
#   - **不自动改默认分支、不自动前移 tag、不自动推送**；改写后由人工确认内容等价再切换
#
# 与 1.15 线的差异：本线是「整条序列 rebase」模型，故无逐补丁采集命令；
# 加补丁用 add-patch，同步上游用 rebase。

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

BASE_TAG="${WCPE_BASE_TAG:-base/architectury-1.17-026ce830}"
QUEUE_TAG="${WCPE_QUEUE_TAG:-patch-queue/1.17}"
DEFAULT_BRANCH="${WCPE_DEFAULT_BRANCH:-dev/1.17-wcpe}"
UPSTREAM_REMOTE="${WCPE_UPSTREAM_REMOTE:-architectury}"

die() { echo "错误: $*" >&2; exit 1; }

base_commit() { git rev-parse -q --verify "refs/tags/${BASE_TAG}^{commit}" 2>/dev/null || die "找不到基底 tag ${BASE_TAG}"; }
queue_commit() { git rev-parse -q --verify "refs/tags/${QUEUE_TAG}^{commit}" 2>/dev/null || echo ""; }

# 检查某范围内的提交是否都带溯源块，输出缺失项
missing_origin() {
	local from="$1" to="$2"
	git log --format='%H' "${from}..${to}" | while read -r sha; do
		git log -1 --format=%B "${sha}" | grep -q '^Origin:' || echo "$(git log -1 --format='%h %s' "${sha}")"
	done
}

cmd_status() {
	local base queue
	base="$(base_commit)"; queue="$(queue_commit)"
	echo "基底 tag    : ${BASE_TAG} -> ${base:0:8}"
	echo "队列末端 tag: ${QUEUE_TAG} -> ${queue:0:8}"
	echo "当前 HEAD   : $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD))"
	if [ -n "${queue}" ]; then
		echo "查询队列提交数: $(git rev-list --count "${base}..${queue}")"
		echo "队列是否含 merge: $(git rev-list --merges --count "${base}..${queue}") 个"
		echo "队列末端之后的元数据提交: $(git rev-list --count "${queue}..HEAD") 个"
	fi
	echo "默认分支    : ${DEFAULT_BRANCH} -> $(git rev-parse --short "${DEFAULT_BRANCH}" 2>/dev/null || echo '(本地无此分支)')"
	echo "上游远端    : ${UPSTREAM_REMOTE} -> $(git rev-parse --short "${UPSTREAM_REMOTE}" 2>/dev/null || echo '(未 fetch)')"
}

cmd_verify() {
	local base queue rc=0
	base="$(base_commit)"; queue="$(queue_commit)"
	[ -n "${queue}" ] || die "找不到队列末端 tag ${QUEUE_TAG}"

	local merges
	merges="$(git rev-list --merges --count "${base}..${queue}")"
	if [ "${merges}" != "0" ]; then
		echo "✗ 队列中存在 ${merges} 个 merge 提交（规范禁止）"; rc=1
	else
		echo "✓ 队列线性，无 merge 提交"
	fi

	echo "· 队列提交数: $(git rev-list --count "${base}..${queue}")"

	local missing
	missing="$(missing_origin "${base}" "${queue}")"
	if [ -n "${missing}" ]; then
		echo "✗ 缺少溯源块（Origin:）的补丁:"; echo "${missing}" | sed 's/^/    /'; rc=1
	else
		echo "✓ 所有补丁均含溯源块"
	fi

	if git merge-base --is-ancestor "${queue}" HEAD 2>/dev/null; then
		echo "✓ 当前分支基于队列末端"
	else
		echo "· 当前分支与队列末端无祖先关系（改写进行中？）"
	fi

	[ "${rc}" = "0" ] && echo "校验通过" || echo "校验未通过"
	return "${rc}"
}

cmd_add_patch() {
	local arg="${1:-}"
	[ -n "${arg}" ] || die "用法: scripts/sync-upstream.sh add-patch <补丁名> | add-patch finish"
	if [ "${arg}" = "finish" ]; then cmd_add_patch_finish; return; fi

	local queue branch
	queue="$(queue_commit)"
	[ -n "${queue}" ] || die "找不到队列末端 tag ${QUEUE_TAG}"
	[ -z "$(git status --porcelain)" ] || die "工作区不干净，请先提交或清理"

	branch="add-patch/${arg}"
	git branch -f "${branch}" "${queue}"
	git checkout -q "${branch}"
	cat <<EOF
已切到补丁工作分支 ${branch}（起点 = 队列末端 ${queue:0:8}）。

接下来：
  1) 修改代码
  2) 提交（一个逻辑变更 = 一个提交），提交信息末尾必须带溯源块，例如：

       fix(forge): 修掉 xxx

       正文说明为什么改。

       Origin: vendor, wcpe-loom
       Forwarded: no

     Origin 取值： vendor, wcpe-loom | backport, <上游版本> | upstream, <URL>
     Forwarded 取值： no | not-needed | <URL>

  3) 完成后执行： scripts/sync-upstream.sh add-patch finish
EOF
}

cmd_add_patch_finish() {
	local branch queue oldtip cand
	branch="$(git rev-parse --abbrev-ref HEAD)"
	case "${branch}" in add-patch/*) ;; *) die "当前不在 add-patch/* 分支上（现在：${branch}）";; esac
	queue="$(queue_commit)"
	[ -n "${queue}" ] || die "找不到队列末端 tag ${QUEUE_TAG}"

	local n
	n="$(git rev-list --count "${queue}..HEAD")"
	[ "${n}" != "0" ] || die "本分支尚无新提交，先改代码并提交"

	local missing
	missing="$(missing_origin "${queue}" HEAD)"
	if [ -n "${missing}" ]; then
		echo "✗ 以下新提交缺少溯源块（Origin:），请先补齐："
		echo "${missing}" | sed 's/^/    /'
		echo "  补法： git commit --amend（本分支未推送，可安全改写）"
		return 1
	fi
	echo "✓ 新增补丁 ${n} 个，溯源块齐备（${queue:0:8} → $(git rev-parse --short HEAD)）"

	oldtip="$(git rev-parse "${DEFAULT_BRANCH}")"
	cand="candidate/$(date +%Y%m%d-%H%M%S)"
	git branch -f "${cand}" "${DEFAULT_BRANCH}"
	git checkout -q "${cand}"
	if git rebase --onto "${branch}" "${queue}" "${cand}" >/dev/null 2>&1; then
		echo "✓ 元数据提交已重放到新队列末端之后（候选分支 ${cand}）"
	else
		echo "重放元数据时出现冲突，请解决后执行 git rebase --continue（当前在 ${cand}）"
		return 1
	fi

	echo
	echo "校对：以下差异应当恰好等于你新写的补丁（不应有其它变化）"
	echo "    git diff ${oldtip:0:8} HEAD"
	echo
	echo "确认无误后的人工步骤（脚本不代劳）："
	echo "    1) 前移队列标记： git tag -f -a ${QUEUE_TAG} $(git rev-parse --short "${branch}")"
	echo "    2) 切换默认分支： 需临时放开分支保护后强推，三步见 .upstream/README.md「改写队列」一节"
	echo "    3) 自检：         scripts/sync-upstream.sh verify"
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
		echo "  1) 复核冲突解决： git log --oneline ${new_base}..HEAD"
		echo "  2) 元数据也要重放： git rebase --onto ${branch} ${QUEUE_TAG} ${DEFAULT_BRANCH}"
		echo "  3) 确认无误后再切换默认分支，并前移 tag ${QUEUE_TAG}"
		echo "  4) 更新 .upstream/migration-1.17.md 的基底与溯源归类"
	else
		echo "重放出现冲突，解决后执行 git rebase --continue（当前在 ${branch}）"
		return 1
	fi
}

case "${1:-verify}" in
	status) cmd_status ;;
	verify) cmd_verify ;;
	add-patch) shift; cmd_add_patch "$@" ;;
	rebase) shift; cmd_rebase "$@" ;;
	*) echo "用法: scripts/sync-upstream.sh {status|verify|add-patch <名>|add-patch finish|rebase <新基底sha> [tag名]}"; exit 2 ;;
esac
