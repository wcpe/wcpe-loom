#!/usr/bin/env bash
# 按「补丁面」生成发布说明
#
# 用法：
#   scripts/gen-release-notes.sh --tag <tag>              # 生成并落盘 .upstream/releases/<tag>.md
#   scripts/gen-release-notes.sh --tag <tag> --print      # 只打印，不落盘
#   scripts/gen-release-notes.sh --tag <tag> --json       # 结构化输出（机器可读）
#   scripts/gen-release-notes.sh --check <tag>            # 严格校验归档清单：缺失/不一致 → 退出码 1
#   scripts/gen-release-notes.sh --legacy <prev> <curr>   # 旧区间引擎（只用于重算历史版本）
#   scripts/gen-release-notes.sh <prev> <curr>            # --legacy 的简写
#
# 为什么不再按提交区间生成（2026-10 裁定）：
#   本仓库为保持补丁队列整齐，每次插补丁都会重写历史。重写让上一版 tag 脱离主线的祖先链，
#   于是 `PREV..CURR` 会退到「共同祖先 → 当前」，把上一版已发布的提交重复算进来；而元数据段
#   在重写时被整段重放，同一条改动换了 SHA 又一次出现。实测 1.17.16~1.17.19 每版 78%~93%
#   的条目都是这种「同标题、不同 SHA」的重写副本，发布说明因此失去信息价值。
#
#   新机制只认**补丁面**：每个补丁提交带 trailer `Patch-Name: <slug>`，说明 =
#   「本版补丁面 − 上一版补丁面」。补丁面的身份是名字而不是 SHA，重写不会改变它，
#   于是不再需要锚点表、不再依赖 tag 拓扑、也不会重复。元数据提交（文档/CI/脚本）对外
#   无意义，默认不列（--include-meta 可附加，但只用于本地预览）。
#
# 设计约束：
#   - 只读 git；只有 --tag 落盘模式会写 .upstream/releases/<tag>.md
#   - 输出确定性：同一 tag 多次重算逐字节一致（--check 依赖这一点），故不写时间戳
#   - 每次调用最多扫两遍历史（BASE..PREV、BASE..CURR），不做全历史 patch-id 计算
#
# 退出码：0 成功；1 语义错误（清单缺失/不一致、legacy 缺锚点）；2 用法错误
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

BASE_REF="${WCPE_BASE_TAG:-base/architectury-1.17-026ce830}"
RELEASE_DIR="${WCPE_RELEASE_DIR:-.upstream/releases}"
REPO_SLUG="${WCPE_REPO_SLUG:-wcpe/wcpe-loom}"
MAIN_REF="${WCPE_MAIN_REF:-dev/1.17-wcpe}"
ANCHORS_FILE="${WCPE_ANCHORS_FILE:-.upstream/release-anchors.txt}"
ALLOW_UNANCHORED="${WCPE_ALLOW_UNANCHORED:-0}"

usage() {
	cat <<'EOF'
用法：
  scripts/gen-release-notes.sh --tag <tag>              生成 .upstream/releases/<tag>.md 并打印
  scripts/gen-release-notes.sh --tag <tag> --print      只打印，不落盘
  scripts/gen-release-notes.sh --tag <tag> --json       输出 JSON（stdout）
  scripts/gen-release-notes.sh --check <tag>            严格校验归档清单（缺失/不一致 → 1）
  scripts/gen-release-notes.sh --legacy <prev> <curr>   旧区间引擎，只用于重算历史版本
  scripts/gen-release-notes.sh <prev> <curr>            同 --legacy

选项：
  --base <ref>        覆盖基底（默认 $WCPE_BASE_TAG）
  --json              结构化输出，供工具消费
  --print             不落盘
  --include-meta      附加「仓库元数据变更」一节（默认不列；只允许与 --print/--json 同用）
  --allow-unanchored  仅 --legacy：容忍缺失锚点（本地排查用，CI 禁用）

退出码：0 成功 / 1 语义错误 / 2 用法错误
EOF
}

die() { printf '错误: %s\n' "$*" >&2; exit 1; }
warn() { printf '警告: %s\n' "$*" >&2; }
note() { printf '%s\n' "$*" >&2; }

# ---------------------------------------------------------------- 参数解析

MODE=""
TAG=""
PRINT_ONLY=0
JSON_OUT=0
INCLUDE_META=0
LEGACY_PREV=""
LEGACY_CURR=""

# 向后兼容：两个位置参数（都不以 - 开头）视为 legacy 调用。
if [ $# -ge 2 ] && [ "${1#-}" = "$1" ] && [ "${2#-}" = "$2" ]; then
	MODE=legacy
	LEGACY_PREV="$1"
	LEGACY_CURR="$2"
	shift 2
fi

while [ $# -gt 0 ]; do
	case "$1" in
	--tag)
		MODE=tag
		[ $# -ge 2 ] || { usage >&2; exit 2; }
		TAG="$2"
		shift 2
		;;
	--check)
		MODE=check
		[ $# -ge 2 ] || { usage >&2; exit 2; }
		TAG="$2"
		shift 2
		;;
	--legacy)
		MODE=legacy
		[ $# -ge 3 ] || { usage >&2; exit 2; }
		LEGACY_PREV="$2"
		LEGACY_CURR="$3"
		shift 3
		;;
	--base)
		[ $# -ge 2 ] || { usage >&2; exit 2; }
		BASE_REF="$2"
		shift 2
		;;
	--print) PRINT_ONLY=1; shift ;;
	--json) JSON_OUT=1; shift ;;
	--include-meta) INCLUDE_META=1; shift ;;
	--allow-unanchored) ALLOW_UNANCHORED=1; shift ;;
	-h | --help)
		usage
		exit 0
		;;
	-*)
		printf '未知选项: %s\n' "$1" >&2
		usage >&2
		exit 2
		;;
	*)
		printf '多余的参数: %s\n' "$1" >&2
		usage >&2
		exit 2
		;;
	esac
done

[ -n "${MODE}" ] || { usage >&2; exit 2; }

# --include-meta 只影响本地预览：归档清单必须用默认口径生成，否则 --check 永远对不上。
if [ "${INCLUDE_META}" = "1" ] && [ "${MODE}" = "tag" ] && [ "${PRINT_ONLY}" = "0" ] && [ "${JSON_OUT}" = "0" ]; then
	die "--include-meta 只能配合 --print/--json 使用：归档清单固定不含元数据段，否则 --check 会不一致"
fi

# ---------------------------------------------------------------- 通用工具

resolve() {
	git rev-parse -q --verify "${1}^{commit}" 2>/dev/null || die "无法解析为提交: $1"
}

short_of() { git rev-parse --short "$1"; }

# 对比链接优先显示 tag 名（人读得懂），只有不是 tag 时才退回短 SHA。
label_of() {
	if git rev-parse -q --verify "refs/tags/$1" >/dev/null 2>&1; then
		printf '%s' "$1"
	else
		short_of "$(resolve "$1")"
	fi
}

# 上一版按**版本号顺序**取，不用 git describe：后者按祖先关系找，重写后的孤立 tag 会被跳过。
#
# 待发布的 tag 通常**还没创建**（清单要在打 tag 之前提交），所以比较序列里要把它自己
# 也放进去，否则 "$TAG" 找不到就直接退化成「没有上一版」，变成全量新增。
prev_tag_of() {
	local cur="$1"
	{
		git tag -l 'wcpe-v*'
		printf '%s\n' "${cur}"
	} | grep -v '^$' | sort -V -u | awk -v cur="${cur}" '
		$0 == cur { print prev; exit }
		{ prev = $0 }'
}

# Conventional Commits 的 type → 中文分组。顺序即输出顺序。
categorize() {
	local subject="$1" type
	type="${subject%%:*}"
	type="${type%%(*}"
	type="${type%!}"
	case "${type}" in
	feat) echo "新增功能" ;;
	fix) echo "缺陷修复" ;;
	perf) echo "性能与并发" ;;
	revert) echo "回滚" ;;
	refactor) echo "重构" ;;
	test) echo "测试" ;;
	build) echo "构建与依赖" ;;
	ci) echo "CI 与发布" ;;
	docs) echo "文档" ;;
	*) echo "其他变更" ;;
	esac
}

CATEGORY_ORDER=("新增功能" "缺陷修复" "性能与并发" "回滚" "重构" "测试" "构建与依赖" "CI 与发布" "文档" "其他变更")

# 去掉类型前缀、尾部的 PR 编号，留下人读的描述部分。
clean_subject() {
	local subject="$1"
	subject="$(printf '%s' "${subject}" | sed -E 's/^[a-zA-Z]+(\([^)]*\))?!?:[[:space:]]*//')"
	printf '%s' "${subject}" | sed -E 's/[[:space:]]*\(#[0-9]+\)[[:space:]]*$//'
}

scope_of() {
	printf '%s' "$1" | sed -nE 's/^[a-zA-Z]+\(([^)]*)\)!?:.*/\1/p'
}

# ---------------------------------------------------------------- 补丁面扫描

# scan_ref <ref> <faces_out> <meta_out>
#
#   faces_out：<slug>\x1f<sha>\x1f<subject>，按补丁段正序，slug 去重（保留最早一次）
#   meta_out ：<sha>\x1f<subject>，无 Patch-Name 的提交（元数据段），同样正序
#
# 为什么用 `-z`：提交信息里可能有换行或多值 trailer，`-z` 让每条记录以 NUL 分隔，
# 解析不会被正文干扰。为什么用 `%(trailers:...)`：trailer 解析交给 git，
# 不必自己写正则去认「末尾段落里的 Key: value」，也就不会把正文里的同名行误当 trailer。
scan_ref() {
	local ref="$1" faces_out="$2" meta_out="$3"
	local range raw rec sha subject trailer rest slug
	local -A seen=()

	if git merge-base --is-ancestor "${BASE_REF}" "${ref}" 2>/dev/null; then
		range="${BASE_REF}..${ref}"
	else
		warn "基底 ${BASE_REF} 不是 ${ref} 的祖先，退回全历史扫描（只在换基底途中才会出现）"
		range="${ref}"
	fi

	: >"${faces_out}"
	: >"${meta_out}"

	raw="$(mktemp)"
	if ! git log --reverse --no-merges --no-show-signature -z \
		--format='%H%x1f%s%x1f%(trailers:key=Patch-Name,valueonly)' "${range}" >"${raw}"; then
		rm -f "${raw}"
		die "读取补丁面失败：git log ${range}"
	fi

	while IFS= read -r -d '' rec; do
		sha="${rec%%$'\x1f'*}"
		rest="${rec#*$'\x1f'}"
		subject="${rest%%$'\x1f'*}"
		trailer="${rest#*$'\x1f'}"
		# 多值 trailer 以换行分隔；一个补丁只应有一个面名称，取第一个并去空白。
		slug="${trailer%%$'\n'*}"
		slug="$(printf '%s' "${slug}" | tr -d '[:space:]')"
		if [ -z "${slug}" ]; then
			printf '%s\x1f%s\n' "${sha}" "${subject}" >>"${meta_out}"
			continue
		fi
		if [ -n "${seen[${slug}]:-}" ]; then
			warn "补丁面 ${slug} 在 ${ref} 的历史里出现多次，只取最早一次（忽略 $(short_of "${sha}")）"
			continue
		fi
		seen[${slug}]=1
		printf '%s\x1f%s\x1f%s\n' "${slug}" "${sha}" "${subject}" >>"${faces_out}"
	done <"${raw}"

	rm -f "${raw}"
}

# diff_faces <prev_faces> <curr_faces> <out>
# 按补丁面做集合差：只认 slug，不看 SHA、不看拓扑 —— 这是重写免疫的唯一来源。
diff_faces() {
	local prev="$1" curr="$2" out="$3" slug sha subject
	local -A prevset=()

	while IFS=$'\x1f' read -r slug sha subject; do
		[ -n "${slug}" ] && prevset[${slug}]=1
	done <"${prev}"

	: >"${out}"
	while IFS=$'\x1f' read -r slug sha subject; do
		[ -n "${slug}" ] || continue
		[ -n "${prevset[${slug}]:-}" ] && continue
		printf '%s\x1f%s\x1f%s\n' "${slug}" "${sha}" "${subject}" >>"${out}"
	done <"${curr}"
}

# diff_meta <prev_meta> <curr_meta> <out>
# 元数据没有面名称，只能按标题去重；仅 --include-meta 时使用，不进入归档清单。
diff_meta() {
	local prev="$1" curr="$2" out="$3" sha subject
	local -A prevtitles=()

	while IFS=$'\x1f' read -r sha subject; do
		[ -n "${subject}" ] && prevtitles[${subject}]=1
	done <"${prev}"

	: >"${out}"
	while IFS=$'\x1f' read -r sha subject; do
		[ -n "${subject}" ] || continue
		[ -n "${prevtitles[${subject}]:-}" ] && continue
		printf '%s\x1f%s\n' "${sha}" "${subject}" >>"${out}"
	done <"${curr}"
}

# ---------------------------------------------------------------- 渲染

# render_markdown <tag> <prev_label> <added_faces> <meta_entries> <include_meta>
#
# 清单里**不写提交 SHA**：发版流程是「先生成清单 → 提交清单 → 再打 tag」，写进 SHA 会让
# 同一版本重算出的内容随提交而变，--check 就永远对不上。tag 本身已经锚定了提交。
render_markdown() {
	local tag="$1" prev_label="$2" added="$3" meta="$4" with_meta="$5"
	local slug sha subject scope desc cat_name added_count printed

	printf -- '- **发布点**：`%s`\n' "${tag}"
	added_count="$(wc -l <"${added}" | tr -d ' ')"
	printf -- '- **补丁**：本版新增 %s 个\n' "${added_count}"
	if [ -n "${prev_label}" ]; then
		printf -- '- **完整对比**：https://github.com/%s/compare/%s...%s\n' \
			"${REPO_SLUG}" "${prev_label}" "${tag}"
	fi
	printf '\n'

	# 分组小桶用临时文件而不是关联数组：这样天然保持「组内顺序 = 补丁段顺序」。
	local tmpdir
	tmpdir="$(mktemp -d)"

	while IFS=$'\x1f' read -r slug sha subject; do
		[ -n "${slug}" ] || continue
		cat_name="$(categorize "${subject}")"
		desc="$(clean_subject "${subject}")"
		scope="$(scope_of "${subject}")"
		if [ -n "${scope}" ]; then
			desc="**${scope}**: ${desc}"
		fi
		printf -- '- %s（`%s`）\n' "${desc}" "${slug}" >>"${tmpdir}/${cat_name}"
	done <"${added}"

	printf '## 新增补丁\n\n'
	if [ "${added_count}" = "0" ]; then
		printf '本版没有新增补丁——仅仓库元数据（文档 / CI / 脚本）变更。\n'
	else
		printed=0
		for cat_name in "${CATEGORY_ORDER[@]}"; do
			[ -s "${tmpdir}/${cat_name}" ] || continue
			[ "${printed}" = "0" ] || printf '\n'
			printf '### %s\n\n' "${cat_name}"
			cat "${tmpdir}/${cat_name}"
			printed=1
		done
	fi

	if [ "${with_meta}" = "1" ]; then
		printf '\n## 仓库元数据变更\n\n'
		if [ ! -s "${meta}" ]; then
			printf '无（相对上一版没有新增元数据提交）。\n'
		else
			while IFS=$'\x1f' read -r sha subject; do
				[ -n "${subject}" ] || continue
				printf -- '- %s（%s）\n' "${subject}" "$(short_of "${sha}")"
			done <"${meta}"
		fi
	fi

	rm -rf "${tmpdir}"
}

# render_json <tag> <curr_sha> <prev_label> <added_faces> <meta_entries> <include_meta>
render_json() {
	local tag="$1" curr_sha="$2" prev_label="$3" added="$4" meta="$5" with_meta="$6"
	local slug sha subject scope cat_name desc first

	printf '{\n'
	printf '  "tag": "%s",\n' "${tag}"
	printf '  "commit": "%s",\n' "${curr_sha}"
	printf '  "prev": "%s",\n' "${prev_label}"
	printf '  "added": ['
	first=1
	while IFS=$'\x1f' read -r slug sha subject; do
		[ -n "${slug}" ] || continue
		cat_name="$(categorize "${subject}")"
		scope="$(scope_of "${subject}")"
		desc="$(clean_subject "${subject}")"
		[ "${first}" = "1" ] || printf ','
		first=0
		printf '\n    {"slug": "%s", "sha": "%s", "scope": "%s", "category": "%s", "description": "%s"}' \
			"${slug}" "$(short_of "${sha}")" "${scope}" "${cat_name}" "${desc}"
	done <"${added}"
	[ "${first}" = "1" ] || printf '\n  '
	printf '],\n'

	printf '  "meta": ['
	first=1
	if [ "${with_meta}" = "1" ]; then
		while IFS=$'\x1f' read -r sha subject; do
			[ -n "${subject}" ] || continue
			[ "${first}" = "1" ] || printf ','
			first=0
			printf '\n    {"sha": "%s", "subject": "%s"}' "$(short_of "${sha}")" "${subject}"
		done <"${meta}"
		[ "${first}" = "1" ] || printf '\n  '
	fi
	printf ']\n}\n'
}

# ---------------------------------------------------------------- v2 引擎

build_v2() {
	local tag="$1" curr prev curr_short prev_label prev_commit tmpdir

	# 清单必须在打 tag **之前**生成（它要跟 tag 一起进历史），所以这里不能要求 tag 已存在：
	# tag 已有则用 tag，没有则按 HEAD 算——两者的补丁面相同，输出也相同（清单不写 SHA）。
	if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null 2>&1; then
		curr="$(resolve "refs/tags/${tag}")"
	else
		curr="$(resolve HEAD)"
		note "注：tag ${tag} 尚未创建，按当前 HEAD（$(short_of "${curr}")）算；打 tag 后用 --check 复核"
	fi
	curr_short="$(short_of "${curr}")"
	prev="$(prev_tag_of "${tag}")"

	tmpdir="$(mktemp -d)"
	trap "rm -rf '${tmpdir}'" EXIT

	scan_ref "${curr}" "${tmpdir}/curr.faces" "${tmpdir}/curr.meta"

	if [ -n "${prev}" ]; then
		prev_commit="$(resolve "refs/tags/${prev}")"
		prev_label="${prev}"
		scan_ref "${prev_commit}" "${tmpdir}/prev.faces" "${tmpdir}/prev.meta"
	else
		: >"${tmpdir}/prev.faces"
		: >"${tmpdir}/prev.meta"
		prev_label=""
		note "注：${tag} 之前没有 wcpe-v* tag，全部补丁面都算本版新增"
	fi

	diff_faces "${tmpdir}/prev.faces" "${tmpdir}/curr.faces" "${tmpdir}/added"
	diff_meta "${tmpdir}/prev.meta" "${tmpdir}/curr.meta" "${tmpdir}/meta"

	note "· 补丁面：上一版 $(wc -l <"${tmpdir}/prev.faces" | tr -d ' ') 个 → 本版 $(wc -l <"${tmpdir}/curr.faces" | tr -d ' ') 个；本版新增 $(wc -l <"${tmpdir}/added" | tr -d ' ') 个"

	if [ "${JSON_OUT}" = "1" ]; then
		render_json "${tag}" "${curr_short}" "${prev_label}" "${tmpdir}/added" "${tmpdir}/meta" "${INCLUDE_META}"
		return 0
	fi

	local target="${RELEASE_DIR}/${tag}.md"
	if [ "${PRINT_ONLY}" = "1" ]; then
		render_markdown "${tag}" "${prev_label}" "${tmpdir}/added" "${tmpdir}/meta" "${INCLUDE_META}"
		return 0
	fi

	mkdir -p "${RELEASE_DIR}"
	render_markdown "${tag}" "${prev_label}" "${tmpdir}/added" "${tmpdir}/meta" 0 >"${tmpdir}/rendered"

	if [ -f "${target}" ] && cmp -s "${tmpdir}/rendered" "${target}"; then
		note "· ${target} 已是最新（内容一致，未改动）"
		cat "${target}"
		return 0
	fi

	cp "${tmpdir}/rendered" "${target}"
	note "已写入 ${target}"
	cat "${target}"
}

# ---------------------------------------------------------------- 校验

check_v2() {
	local tag="$1" target tmpdir

	# 校验面向「已经发出去的 tag」：tag 不存在就没有可核对的归档，直接报用法错。
	git rev-parse -q --verify "refs/tags/${tag}" >/dev/null 2>&1 \
		|| die "--check 需要一个已存在的 tag，${tag} 不存在（生成清单用 --tag）"

	target="${RELEASE_DIR}/${tag}.md"
	[ -f "${target}" ] || die "缺少发布清单 ${target}
发版前请先执行： bash scripts/gen-release-notes.sh --tag ${tag}
然后把该文件作为元数据提交提交，再打 tag。"

	tmpdir="$(mktemp -d)"
	trap "rm -rf '${tmpdir}'" EXIT

	# 用与归档完全相同口径重算，逐字节比较：确定性是这套机制能当契约的前提。
	(
		MODE=tag
		PRINT_ONLY=1
		INCLUDE_META=0
		JSON_OUT=0
		build_v2 "${tag}"
	) >"${tmpdir}/expected" 2>/dev/null || die "重算 ${tag} 的发布清单失败"

	if ! cmp -s "${tmpdir}/expected" "${target}"; then
		printf '错误: %s 与重算结果不一致（清单被手改过，或生成它的补丁面已变）。\n' "${target}" >&2
		printf '差异（- 重算结果 / + 归档内容）：\n' >&2
		diff -u "${tmpdir}/expected" "${target}" >&2 || true
		die "请重新生成并提交： bash scripts/gen-release-notes.sh --tag ${tag}"
	fi

	note "✓ ${target} 与重算结果一致"
}

# ---------------------------------------------------------------- legacy 区间引擎
#
# 只用于重算历史版本（那些版本还没有 Patch-Name）。它必须靠 release-anchors.txt 把脱离
# 主线的 tag 换算回主线提交，否则区间会退到共同祖先、把上一版的内容重复算入。缺锚点时
# 这里**直接失败**而不是降级：产出一份大半重复的说明比失败更糟。

anchor_of() {
	local ref="$1" commit mapped
	commit="$(resolve "${ref}")"

	if git merge-base --is-ancestor "${commit}" "${MAIN_REF}" 2>/dev/null; then
		printf '0\t%s' "${commit}"
		return 0
	fi

	mapped=""
	if [ -f "${ANCHORS_FILE}" ]; then
		mapped="$(awk -v r="${ref}" '
			/^[[:space:]]*#/ { next }
			NF >= 2 && $1 == r { print $2; exit }
		' "${ANCHORS_FILE}")"
	fi

	if [ -n "${mapped}" ]; then
		if [ "${mapped}" = "self" ]; then
			printf '1\t%s' "${commit}"
			return 0
		fi
		if git rev-parse -q --verify "${mapped}^{commit}" >/dev/null 2>&1; then
			printf '0\t%s' "$(resolve "${mapped}")"
			return 0
		fi
		warn "${ANCHORS_FILE} 里 ${ref} 的锚点 ${mapped} 无法解析"
	fi

	printf '0\t%s' "${commit}"
}

build_legacy() {
	local prev="$1" curr="$2"
	local prev_commit curr_commit prev_anchor curr_anchor prev_self curr_self
	local pair role rest name anchor self missing="" err range raw
	local prev_label curr_label tmpdir cat_name desc scope subject hash printed

	prev_commit="$(resolve "${prev}")"
	curr_commit="$(resolve "${curr}")"

	IFS=$'\t' read -r prev_self prev_anchor <<<"$(anchor_of "${prev}")"
	IFS=$'\t' read -r curr_self curr_anchor <<<"$(anchor_of "${curr}")"

	for pair in "起点:${prev}:${prev_anchor}:${prev_self}" "终点:${curr}:${curr_anchor}:${curr_self}"; do
		role="${pair%%:*}"
		rest="${pair#*:}"
		name="${rest%%:*}"
		rest="${rest#*:}"
		anchor="${rest%%:*}"
		self="${rest##*:}"

		if [ "${anchor}" != "$(resolve "${name}")" ]; then
			note "注：${name} 已按对照表换算为主线提交 $(short_of "${anchor}")（${role}）"
		elif [ "${self}" = "1" ]; then
			note "注：${name} 按对照表声明为 self，直接用它自身（${role}）"
		elif ! git merge-base --is-ancestor "${anchor}" "${MAIN_REF}" 2>/dev/null; then
			missing="${missing}${missing:+$(printf '\n')}    ${role}  ${name}"
		fi
	done

	if [ -n "${missing}" ]; then
		err="以下发布点不在 ${MAIN_REF} 的历史内，且 ${ANCHORS_FILE} 未收录它们：
${missing}

按旧区间引擎，这会让区间退回共同祖先、把上一版已发布的提交重复算入——所以这里直接失败，
而不是产出一份看似正常、实则大半是重复内容的说明。

处理方式（二选一）：
  1) 在 ${ANCHORS_FILE} 补一行：<tag> <主线等价提交>
     若它与相邻发布点可直接比较、无需换算，写 <tag> self
  2) 若这一版已有 Patch-Name 补丁面，改用新引擎： --tag <tag> --print
     （新引擎按补丁面做差集，不需要锚点，见本脚本头部说明）"
		if [ "${ALLOW_UNANCHORED}" = "1" ]; then
			warn "已按 --allow-unanchored 继续（仅本地排查；CI 不得使用）"
			warn "${err}"
		else
			die "${err}"
		fi
	fi

	range="${prev_anchor}..${curr_anchor}"
	raw="$(git log --no-merges --reverse --pretty=format:'%s%x1f%h' "${range}")"

	prev_label="$(label_of "${prev}")"
	curr_label="$(label_of "${curr}")"

	printf -- '- **发布点**：`%s`（%s）\n' "${curr_label}" "$(short_of "${curr_commit}")"
	printf -- '- **补丁**：本版新增 %s 个\n' "$(printf '%s' "${raw}" | grep -c . || true)"
	printf -- '- **完整对比**：https://github.com/%s/compare/%s...%s\n\n' "${REPO_SLUG}" "${prev_label}" "${curr_label}"
	printf '## 变更提交\n\n'

	if [ -z "${raw}" ]; then
		printf '本区间无提交变更。\n'
		return 0
	fi

	tmpdir="$(mktemp -d)"
	while IFS=$'\x1f' read -r subject hash; do
		[ -n "${subject}" ] || continue
		cat_name="$(categorize "${subject}")"
		desc="$(clean_subject "${subject}")"
		scope="$(scope_of "${subject}")"
		[ -n "${scope}" ] && desc="**${scope}**: ${desc}"
		printf -- '- %s（%s）\n' "${desc}" "${hash}" >>"${tmpdir}/${cat_name}"
	done <<<"${raw}"

	printed=0
	for cat_name in "${CATEGORY_ORDER[@]}"; do
		[ -s "${tmpdir}/${cat_name}" ] || continue
		[ "${printed}" = "0" ] || printf '\n'
		printf '### %s\n\n' "${cat_name}"
		cat "${tmpdir}/${cat_name}"
		printed=1
	done

	rm -rf "${tmpdir}"
}

# ---------------------------------------------------------------- 分派

case "${MODE}" in
tag) build_v2 "${TAG}" ;;
check) check_v2 "${TAG}" ;;
legacy) build_legacy "${LEGACY_PREV}" "${LEGACY_CURR}" ;;
*)
	usage >&2
	exit 2
	;;
esac
