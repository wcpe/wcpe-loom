#!/usr/bin/env bash
# WCPE Loom 1.17 补丁队列维护脚本
#
# 用法：
#   scripts/sync-upstream.sh status                     # 查看基底/队列末端/提交数（只读）
#   scripts/sync-upstream.sh verify                     # 校验队列是否符合规范（只读，默认）
#   scripts/sync-upstream.sh add-patch <补丁名>          # 开一个补丁工作分支（起点=队列末端）
#   scripts/sync-upstream.sh add-patch finish           # 校验溯源块与补丁面 + 把元数据重放到新队列之后
#   scripts/sync-upstream.sh rebase <新基底sha> [tag名]  # 把整条队列重放到新基底（临时分支上）
#
# 补丁名（slug）规则：^[a-z0-9]+(-[a-z0-9]+)*$，长度 ≤ 48。它会写进提交 trailer
# `Patch-Name`，成为发布说明做差集用的「补丁面」标识（与 SHA 拓扑无关、重写免疫）。
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
		git log -1 --format=%B "${sha}" | grep -q '^Origin:' || git log -1 --format='%h %s' "${sha}"
	done
}

# 取一个提交的补丁面名称（trailer `Patch-Name`），没有则输出空行。
#
# 为什么用 git 原生的 %(trailers:key=...) 而不是 grep 正文：trailer 只从提交信息
# **末尾的 trailer 块**里取，正文里偶然写到的 "Patch-Name: xxx"（比如贴的示例）
# 不会被误认；另外它按 key 大小写不敏感匹配，与 git 自己的 trailer 语义一致。
# 值可能带换行（续行、或同一个 key 写了两次），这里只取第一行并清掉 \r 与两端空白：
# 不做这一步，一个完全合法的值会因为在正文里折行而被判成「不一致」。
#
# 注意 git 的 trailer 规则比看起来严：trailer 必须是提交信息的**最后一段**，且那段里
# 不能再有不像 "key: value" 的行（后面空一行再写正文也算失效）。这两种情形下
# %(trailers) 会安静地返回空——所以报「缺失」时先看提交信息末尾是不是干净。
patch_name_of() {
	local raw
	raw="$(git log -1 --format='%(trailers:key=Patch-Name,valueonly)' "$1" 2>/dev/null || true)"
	printf '%s\n' "${raw}" | tr -d '\r' | sed -n '1{s/^[[:space:]]*//; s/[[:space:]]*$//; p;}'
}

# 补丁面 slug 规则：^[a-z0-9]+(-[a-z0-9]+)*$ 且长度 ≤ SLUG_MAX。
# 这个 slug 会写进提交 trailer，成为发布说明做差集用的「补丁面」标识，所以要严。
# 非法时把原因写进全局 SLUG_ERR 并返回 1——用全局而不是 stdout，是因为调用方
# 常常处在 $(...) 或 if ! 里，stdout 会被吃掉、原因就丢了。
SLUG_MAX=48
SLUG_ERR=""
slug_error() {
	local s="$1"
	SLUG_ERR=""
	if [ -z "${s}" ]; then
		SLUG_ERR="不能为空"
		return 1
	fi
	if [ "${#s}" -gt "${SLUG_MAX}" ]; then
		SLUG_ERR="长度 ${#s} 超过 ${SLUG_MAX}"
		return 1
	fi
	if [[ ! "${s}" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
		SLUG_ERR="只允许小写字母 a-z 与数字 0-9，词间用单个连字符连接"
		return 1
	fi
	return 0
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
		echo "✗ 缺少溯源块（Origin:）的补丁:"; printf '%s\n' "${missing}" | sed 's/^/    /'; rc=1
	else
		echo "✓ 所有补丁均含溯源块"
	fi

	# —— 补丁面（Patch-Name）——
	# 发布说明引擎只认这个 trailer 做差集（不认标题、不认 SHA、不认 patch-id），
	# 这是队列被重写（换基底 rebase、插补丁）后仍然认得出「同一个补丁」的唯一来源。
	# 所以：slug 重复 / 格式非法 = 差集会算错（两个改动算成一个面，或整版漏算），记 error；
	# 缺 trailer 的只有历史补丁（1.17.19 及更早，契约规定不回填），只提醒不拦。
	local pn_sha pn_slug pn_total=0 pn_noslug=0 pn_ndup=0
	local pn_bad="" pn_dup=""
	local -A pn_first=()
	while read -r pn_sha; do
		[ -n "${pn_sha}" ] || continue
		pn_total=$((pn_total + 1))
		pn_slug="$(patch_name_of "${pn_sha}")"
		if [ -z "${pn_slug}" ]; then
			pn_noslug=$((pn_noslug + 1))
			continue
		fi
		if ! slug_error "${pn_slug}"; then
			pn_bad="${pn_bad}
$(git log -1 --format='%h %s' "${pn_sha}")    [Patch-Name: ${pn_slug}]  ${SLUG_ERR}"
			continue
		fi
		if [ -n "${pn_first[${pn_slug}]:-}" ]; then
			pn_ndup=$((pn_ndup + 1))
			pn_dup="${pn_dup}
${pn_slug}  <- $(git log -1 --format='%h %s' "${pn_first[${pn_slug}]}")  ×  $(git log -1 --format='%h %s' "${pn_sha}")"
		else
			pn_first[${pn_slug}]="${pn_sha}"
		fi
	done < <(git rev-list --reverse "${base}..${queue}")

	if [ "${pn_noslug}" != "0" ]; then
		echo "· 警告：${pn_noslug}/${pn_total} 个补丁缺 Patch-Name（历史豁免：1.17.19 及更早的补丁没有该 trailer，契约规定不回填，它们的说明已在历史 Release 里；新补丁必须带）"
	else
		echo "✓ 所有 ${pn_total} 个补丁都带 Patch-Name（补丁面标识）"
	fi
	if [ -n "${pn_bad}" ]; then
		echo "✗ Patch-Name 格式非法（应为 ^[a-z0-9]+(-[a-z0-9]+)*\$ 且长度 ≤ ${SLUG_MAX}）:"
		printf '%s\n' "${pn_bad}" | sed -e '/^$/d' -e 's/^/    /'
		rc=1
	fi
	if [ "${pn_ndup}" != "0" ]; then
		echo "✗ Patch-Name 重复的补丁（一个补丁一个面名称；重名会让两个改动在发布说明里合为一条）:"
		printf '%s\n' "${pn_dup}" | sed -e '/^$/d' -e 's/^/    /'
		rc=1
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
	[ -n "${arg}" ] || die "用法: scripts/sync-upstream.sh add-patch <补丁名> | add-patch finish [分支名]"
	if [ "${arg}" = "finish" ]; then cmd_add_patch_finish "${2:-}"; return; fi

	# 补丁名（slug）就是发布说明里的「补丁面」标识：它会进提交 trailer，之后每个版本的
	# 差集都按它算。格式错了当下看不出后果，但会污染整份发布说明，所以在建分支前就拦住。
	if ! slug_error "${arg}"; then
		{
			echo "✗ 补丁名不合法：'${arg}' —— ${SLUG_ERR}"
			echo
			echo "  补丁名是发布说明里的「补丁面」标识（会写进提交 trailer Patch-Name），"
			echo "  必须全小写、单词间用单个连字符： ^[a-z0-9]+(-[a-z0-9]+)*\$，长度 ≤ ${SLUG_MAX}。"
			echo "  示例：legacy-forge-at-translate · cross-project-lock · exec-phase-migration"
		} >&2
		return 1
	fi

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
  2) 提交（一个逻辑变更 = 一个提交），提交信息末尾必须带溯源块 + 补丁面，例如：

       fix(forge): 修掉 xxx

       正文说明为什么改。

       Origin: vendor, wcpe-loom
       Forwarded: no
       Patch-Name: ${arg}

     Origin 取值： vendor, wcpe-loom | backport, <上游版本> | upstream, <URL>
     Forwarded 取值： no | not-needed | <URL>
     Patch-Name：本补丁的「补丁面」名称，跟 Origin/Forwarded 同处末尾 trailer 块，
       固定为本分支的 slug（${arg}）。发布说明按它做差集——只认这个 trailer，不认
       标题/SHA/patch-id，所以队列被重写（换基底 rebase、插补丁）之后仍然认得出是
       同一个补丁（重写免疫）。它在补丁段内必须全局唯一：要改已有补丁就改它本身，
       不要新开同名 slug。

  3) 提交后切回默认分支，执行： scripts/sync-upstream.sh add-patch finish ${branch}
EOF
}

cmd_add_patch_finish() {
	local branch="${1:-}" queue base oldtip cand cands expect_slug
	# finish 必须从默认分支执行：add-patch 分支切自队列末端，其上没有 scripts/ 目录
	if [ -z "${branch}" ]; then
		cands="$(git for-each-ref --format='%(refname:short)' 'refs/heads/add-patch/*')"
		[ -n "${cands}" ] || die "没有 add-patch/* 分支；用法: add-patch finish <分支名>"
		[ "$(printf '%s\n' "${cands}" | wc -l)" = "1" ] || die "存在多个 add-patch/* 分支，请显式指定：add-patch finish <分支名>"
		branch="${cands}"
	fi
	git rev-parse -q --verify "refs/heads/${branch}" >/dev/null || die "分支 ${branch} 不存在"
	[ -z "$(git status --porcelain)" ] || die "工作区不干净，请先提交或清理"
	git checkout -q "${branch}"
	queue="$(queue_commit)"
	[ -n "${queue}" ] || die "找不到队列末端 tag ${QUEUE_TAG}"
	base="$(base_commit)"

	local n
	n="$(git rev-list --count "${queue}..HEAD")"
	[ "${n}" != "0" ] || die "本分支尚无新提交，先改代码并提交"

	local missing
	missing="$(missing_origin "${queue}" HEAD)"
	if [ -n "${missing}" ]; then
		echo "✗ 以下新提交缺少溯源块（Origin:），请先补齐："
		printf '%s\n' "${missing}" | sed 's/^/    /'
		echo "  补法： git commit --amend（本分支未推送，可安全改写）"
		return 1
	fi
	echo "✓ 新增补丁 ${n} 个，溯源块齐备（${queue:0:8} → $(git rev-parse --short HEAD)）"

	# —— 补丁面 trailer ——
	# 分支的 slug 就是本次补丁的面名称：新提交必须全部标成它。标成别的名字，这个改动会
	# 被发布说明算到另一个补丁面上；漏标则永远进不了「新增补丁」（差集只认 trailer）。
	# 多个新提交共用同一个 slug 是允许的（= 同一补丁的开发过程），但要建议压成一个。
	expect_slug="${branch#add-patch/}"
	if ! slug_error "${expect_slug}"; then
		echo "✗ 无法从分支名推出合法的补丁面名称：'${expect_slug}'（${SLUG_ERR}）"
		echo "  分支名应为 add-patch/<slug>，slug 规则 ^[a-z0-9]+(-[a-z0-9]+)*\$ 且长度 ≤ ${SLUG_MAX}。"
		echo "  补法： git branch -m add-patch/<合法slug>（然后切回 ${DEFAULT_BRANCH} 重新执行 finish）"
		return 1
	fi

	local tsha tslug tnbad=0 tbad="" tdup_n=0 tdup=""
	while read -r tsha; do
		[ -n "${tsha}" ] || continue
		tslug="$(patch_name_of "${tsha}")"
		if [ "${tslug}" != "${expect_slug}" ]; then
			tnbad=$((tnbad + 1))
			tbad="${tbad}
$(git log -1 --format='%h %s' "${tsha}")    [Patch-Name: ${tslug:-（缺失）}]"
		fi
	done < <(git rev-list --reverse "${queue}..HEAD")
	if [ "${tnbad}" != "0" ]; then
		echo "✗ ${tnbad}/${n} 个新提交的 Patch-Name 缺失或不等于本分支 slug（${expect_slug}）："
		printf '%s\n' "${tbad}" | sed -e '/^$/d' -e 's/^/    /'
		echo
		echo "  补法（本分支未推送，可安全改写）："
		echo "    git commit --amend        # 末一个提交：在提交信息末尾的 trailer 块里加/改一行"
		echo "                              #   Patch-Name: ${expect_slug}"
		echo "    git rebase -i ${queue:0:8}   # 中间提交用 reword 逐条补"
		echo "  注意：trailer 必须是提交信息的最后一段，其后不要再写正文；末段里夹一行"
		echo "        不像 trailer 的正文，git 也会当作整块无效（只从末段取 trailer）。"
		echo "  同一补丁分多次提交写成的，按 .upstream/PATCH-QUEUE.md §2 的压缩判据压成一个提交后再 finish。"
		return 1
	fi
	echo "✓ ${n} 个新提交的补丁面一致：Patch-Name = ${expect_slug}"

	# 与补丁段既有 slug 重名：一个补丁一个面名称。重名会让两个改动在差集里合为同一面
	# （新增里只出现一条；删掉其中一个也不报变化），等于发布说明静默出错，所以硬拦。
	while read -r tsha; do
		[ -n "${tsha}" ] || continue
		if [ "$(patch_name_of "${tsha}")" = "${expect_slug}" ]; then
			tdup_n=$((tdup_n + 1))
			tdup="${tdup}
$(git log -1 --format='%h %s' "${tsha}")"
		fi
	done < <(git rev-list --reverse "${base}..${queue}")
	if [ "${tdup_n}" != "0" ]; then
		echo "✗ 补丁面 slug '${expect_slug}' 与补丁段既有 ${tdup_n} 个补丁重名："
		printf '%s\n' "${tdup}" | sed -e '/^$/d' -e 's/^/    /'
		echo
		echo "  一个补丁一个面名称：要改既有补丁就改它本身（改写它的提交），而不是新开一个同名 slug。"
		echo "  若确实是一个全新的逻辑变更，给本分支换个不重名的名字： git branch -m add-patch/<新slug>"
		return 1
	fi
	echo "✓ 补丁面 slug '${expect_slug}' 未与补丁段既有补丁重名"

	# 多个新提交共用一个 slug = 同一补丁的开发过程，不拦但提醒：队列描述的是最终状态，
	# 不是开发年表（.upstream/PATCH-QUEUE.md §2），并入队列前应当压成一个提交。
	if [ "${n}" -gt 1 ]; then
		echo "· 本分支有 ${n} 个新提交且共用补丁面 ${expect_slug}：建议按 §2 压缩判据合成一个提交（git rebase -i ${queue:0:8}）"
	fi

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
