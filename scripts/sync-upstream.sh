#!/usr/bin/env bash
# WCPE Loom 上游同步采集脚本（Debian quilt 维护流程）
#
# 用法：
#   scripts/sync-upstream.sh verify              # 校验补丁队列是否符合规范（默认，只读）
#   scripts/sync-upstream.sh status              # 查看当前基底/队列状态
#   scripts/sync-upstream.sh candidates          # 列出上游候选提交（只读）
#   scripts/sync-upstream.sh pick <sha>...       # 审核后采集提交（cherry-pick -x）
#   scripts/sync-upstream.sh essential-rebase    # Essential 换基底 rebase（候选分支上执行）
#
# 原则：本脚本默认只产候选、不自动改队列分支、不自动打发布 tag。
# cherry-pick 的 --right-only --cherry-pick 只排除 patch-id 等价提交，
# 改写/拆分/合并后的等价功能仍需人工审查。
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

BASE_FILE=".upstream/essential-base"
UPSTREAM_REMOTE="upstream"           # FabricMC/fabric-loom
ARCH_REMOTE="architectury"           # architectury/architectury-loom
ESS_REMOTE="essential-loom"          # SparkUniverse/architectury-loom
UPSTREAM_BRANCH="dev/1.17"
ESS_BRANCH="dev/1.15"
ARCH_BRANCH="dev/1.17"

# 补丁队列边界（见 .upstream/PATCH-QUEUE.md）
BASE_TAG="base/essential-1.15-713489a9"
QUEUE_TAG="patch-queue/1.15"
QUEUE_BRANCH="${QUEUE_BRANCH:-main}"

die() { echo "错误: $*" >&2; exit 1; }

require_clean() {
	[ -z "$(git status --porcelain)" ] || die "工作区有未提交修改，先提交或暂存"
}

ensure_base_file() {
	[ -f "$BASE_FILE" ] || die "缺少 $BASE_FILE（基底 SHA 记录），先执行 status 确认后手动创建"
}

fetch_all() {
	echo ">>> 拉取三个上游..."
	git fetch "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH" || die "拉取 $UPSTREAM_REMOTE 失败"
	git fetch "$ARCH_REMOTE" "$ARCH_BRANCH" || die "拉取 $ARCH_REMOTE 失败"
	git fetch "$ESS_REMOTE" "$ESS_BRANCH" || die "拉取 $ESS_REMOTE 失败"
}

cmd_status() {
	echo "=== 基底状态 ==="
	if [ -f "$BASE_FILE" ]; then
		local base; base=$(cat "$BASE_FILE")
		echo "记录的 Essential 基底: $base"
		echo "  $(git log -1 --format='%h %ad %s' --date=short "$base" 2>/dev/null || echo '提交不存在！')"
		local tip; tip=$(git rev-parse "$QUEUE_BRANCH")
		if git merge-base --is-ancestor "$base" "$tip" 2>/dev/null; then
			echo "$QUEUE_BRANCH 包含该基底 ✔"
		else
			echo "⚠ $QUEUE_BRANCH 不包含记录基底（可能已被换基底，请核对）"
		fi
	else
		echo "⚠ 未创建 $BASE_FILE"
	fi
	echo
	echo "=== 边界 tag ==="
	echo "  基底: $(git log -1 --format='%h %ad %s' --date=short "$BASE_TAG" 2>/dev/null || echo '缺失')"
	echo "  末端: $(git log -1 --format='%h %ad %s' --date=short "$QUEUE_TAG" 2>/dev/null || echo '缺失')"
	echo
	echo "=== $QUEUE_BRANCH 队列（基底之上的提交） ==="
	local base; base=$(cat "$BASE_FILE" 2>/dev/null || echo "HEAD~20")
	git log --oneline "${base}..${QUEUE_BRANCH}" 2>/dev/null | head -40
	echo "共 $(git rev-list --count "${base}..${QUEUE_BRANCH}" 2>/dev/null || echo '?') 个提交"
	echo
	echo "=== 队列中的 merge 提交（应为 0） ==="
	local merges; merges=$(git rev-list --merges --count "${base}..${QUEUE_BRANCH}" 2>/dev/null || echo '?')
	echo "$merges 个 $([ "$merges" = "0" ] && echo '✔' || echo '⚠ 队列应保持线性')"
}

cmd_verify() {
	local fail=0

	echo "=== 1. 边界 tag ==="
	if ! git rev-parse --verify --quiet "$BASE_TAG" >/dev/null; then
		echo "✘ 缺少基底 tag: $BASE_TAG"
		fail=1
	else
		local base; base=$(git rev-parse "$BASE_TAG^{commit}")
		echo "✔ 基底: $(git log -1 --format='%h %s' "$base")"
		if git merge-base --is-ancestor "$base" HEAD 2>/dev/null; then
			echo "✔ 基底是 HEAD 的祖先"
		else
			echo "✘ 基底不是 HEAD 的祖先"
			fail=1
		fi
	fi
	if ! git rev-parse --verify --quiet "$QUEUE_TAG" >/dev/null; then
		echo "✘ 缺少队列末端 tag: $QUEUE_TAG"
		return 1
	fi
	echo "✔ 末端: $(git log -1 --format='%h %s' "$QUEUE_TAG")"

	echo
	echo "=== 2. 队列线性（无 merge 提交） ==="
	local merges; merges=$(git rev-list --merges --count "$BASE_TAG..$QUEUE_TAG")
	if [ "$merges" = "0" ]; then
		echo "✔ 无 merge 提交"
	else
		echo "✘ 队列含 $merges 个 merge 提交"
		fail=1
	fi

	echo
	echo "=== 3. 队列末端之后仅含元数据 ==="
	local code; code=$(git diff --name-only "$QUEUE_TAG" HEAD -- src/ gradle/ | wc -l | tr -d ' ')
	if [ "$code" = "0" ]; then
		echo "✔ 末端之后无 src/ 或 gradle/ 改动"
	else
		echo "✘ 末端之后仍有 $code 个代码文件改动："
		git diff --name-only "$QUEUE_TAG" HEAD -- src/ gradle/ | head -10 | sed 's/^/    /'
		fail=1
	fi

	echo
	echo "=== 4. 溯源字段 ==="
	local shas; shas=$(git rev-list --reverse "$BASE_TAG..$QUEUE_TAG")
	local sha body miss=0
	for sha in $shas; do
		body=$(git log -1 --format='%B' "$sha")
		case "$body" in
			*"Origin: "*) ;;
			*) echo "✘ $(git log -1 --format='%h %s' "$sha") — 缺少 Origin"; miss=1 ;;
		esac
		case "$body" in
			*"Forwarded: "*) ;;
			*) echo "✘ $(git log -1 --format='%h %s' "$sha") — 缺少 Forwarded"; miss=1 ;;
		esac
	done
	if [ "$miss" = "0" ]; then
		echo "✔ 全部补丁均带 Origin 与 Forwarded"
	else
		fail=1
	fi

	echo
	echo "=== 5. 提交类型 ==="
	local bad=0 subj
	for sha in $shas; do
		subj=$(git log -1 --format='%s' "$sha")
		case "$subj" in
			feat*|fix*|refactor*|perf*|test*|build*|docs*|chore*|ci*) ;;
			*) echo "✘ $subj — type 不合规"; bad=1 ;;
		esac
	done
	if [ "$bad" = "0" ]; then
		echo "✔ 提交类型均合规"
	else
		fail=1
	fi

	echo
	if [ "$fail" = "0" ]; then
		echo "✔ 补丁队列校验通过"
	else
		echo "✘ 补丁队列校验失败"
		return 1
	fi
}

cmd_candidates() {
	require_clean
	fetch_all
	echo
	echo "=== FabricMC upstream 候选（main 尚未包含、与现有补丁不等价） ==="
	git log --right-only --cherry-pick --no-merges --oneline "main...${UPSTREAM_REMOTE}/${UPSTREAM_BRANCH}" || true
	echo
	echo "=== Architectury 候选（Forge 相关二次筛选提示见下方） ==="
	git log --right-only --cherry-pick --no-merges --oneline "main...${ARCH_REMOTE}/${ARCH_BRANCH}" | head -60 || true
	echo
	echo "提示："
	echo "  1. 路径/关键词仅作二次筛选，Forge 公共 API 也在 net/fabricmc/loom/api："
	echo "     git log --right-only --cherry-pick --no-merges main...${ARCH_REMOTE}/${ARCH_BRANCH} -- src/main/java/dev/architectury src/main/java/net/fabricmc/loom/api"
	echo "  2. 审核后采集: scripts/sync-upstream.sh pick <sha>"
}

cmd_pick() {
	require_clean
	[ $# -ge 1 ] || die "用法: $0 pick <sha>..."
	[ "$(git branch --show-current)" != "main" ] && echo "⚠ 当前不在 main（$(git branch --show-current)），正常流程应在 main 或专用采集分支上"
	for sha in "$@"; do
		echo ">>> cherry-pick -x $sha"
		git cherry-pick -x "$sha" || die "采集 $sha 冲突。解决后 git cherry-pick --continue；放弃用 git cherry-pick --abort。冲突改写时请在提交信息中记录前置依赖与适配点"
	done
	echo ">>> 采集完成，请运行构建验证: ./gradlew build -x test"
}

cmd_essential_rebase() {
	require_clean
	ensure_base_file
	fetch_all

	local old_base new_base old_tip
	old_base=$(cat "$BASE_FILE")
	new_base=$(git rev-parse "${ESS_REMOTE}/${ESS_BRANCH}^{commit}")
	old_tip=$(git rev-parse main)

	# 验证基底关系；不符合时停止，转人工迁移
	git merge-base --is-ancestor "$old_base" "$old_tip" || die "记录基底 $old_base 不在 main 历史中，队列结构已变，转人工迁移"
	git merge-base --is-ancestor "$old_base" "$new_base" || die "记录基底 $old_base 不是新基底 $new_base 的祖先（分叉/回退），转人工迁移"

	if [ "$old_base" = "$new_base" ]; then
		echo "Essential 基底无变化（$new_base），无需 rebase"
		return
	fi

	echo ">>> 基底迁移: $(git log -1 --format='%h %s' "$old_base")"
	echo "            → $(git log -1 --format='%h %s' "$new_base")"

	# 在候选分支工作，不直接改 main
	local work_branch="sync/essential-update"
	if git show-ref --verify --quiet "refs/heads/$work_branch"; then
		die "候选分支 $work_branch 已存在，先处理（完成后删除）"
	fi
	git switch -c "$work_branch" "$old_tip"

	echo
	echo ">>> 预览上游是否已包含等价补丁（'-' = 上游已有等价，可考虑丢弃）："
	git cherry -v "$new_base" HEAD "$old_base" || true

	echo
	echo ">>> 重放到新基底（--empty=stop 会在变空提交处停下供检查）..."
	git -c rebase.updateRefs=false rebase --no-fork-point --no-reapply-cherry-picks --empty=stop "$new_base" \
		|| die "rebase 未完成（可能停在冲突或空提交）。处理后继续 rebase --continue，或放弃：git rebase --abort && git switch main && git branch -D $work_branch"

	echo
	echo ">>> 队列对比审查（旧 vs 新，人工核对差异）："
	git range-diff "$old_base..$old_tip" "$new_base..HEAD" || true

	echo
	echo ">>> 审查通过后："
	echo "    1. 运行构建验证: ./gradlew build -x test"
	echo "    2. 更新基底记录: echo $new_base > $BASE_FILE"
	echo "    3. 受控更新 main: git switch main && git reset --hard $work_branch && 删除 $work_branch"
	echo "    4. 强推时锁定预期旧 SHA: git push --force-with-lease=main:$(git rev-parse main) origin main"
}

case "${1:-verify}" in
	verify)     cmd_verify ;;
	status)     cmd_status ;;
	candidates) cmd_candidates ;;
	pick)       shift; cmd_pick "$@" ;;
	essential-rebase) cmd_essential_rebase ;;
	*)          die "未知命令: $1（可用: verify | status | candidates | pick | essential-rebase）" ;;
esac
