#!/usr/bin/env bash
# 按提交生成发布说明（不依赖 PR）
#
# 用法：
#   scripts/gen-release-notes.sh <上一版tag> <本版tag>     # 输出到 stdout
#
# 环境变量：
#   WCPE_CHERRY_PICK=1   跨分叉时排除「内容相同但 SHA 不同」的重复提交（1.17.4 这类分叉版本用）
#   WCPE_SKIP_PATTERN    额外过滤掉匹配该正则的提交标题（如开发过程提交），可重复用 `|` 组合
#   WCPE_MAIN_REF        主线分支名，默认 dev/1.17-wcpe
#
# 背景：GitHub 的 `gh release create --generate-notes` 只统计**经 PR 合入**的提交，
# 而本仓库以直推为主，于是出现两种坏结果：正文全空（一条 PR 都没有时），
# 或只剩零星几条 PR、同版本其它提交全丢。改用提交本身生成，直推与 PR 合入一视同仁。
#
# 分类依据是 Conventional Commits 的 type（feat/fix/perf/...），与提交信息规范一致，
# 无需在 GitHub 上给 PR 打 label，也没有额外维护成本。
#
# 设计约束：
#   - 只读 git，不联网、不改任何 ref
#   - 输出稳定（同一区间多次运行结果一致），便于重跑与校对
#   - 区间内若无提交，明确说「无变更」而不是输出空正文

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

PREV="${1:-}"
CURR="${2:-}"

if [ -z "${PREV}" ] || [ -z "${CURR}" ]; then
	echo "用法: scripts/gen-release-notes.sh <上一版tag|提交> <本版tag|提交>" >&2
	echo "      两端都可以是 tag 或任意 commit-ish，内部统一解析为 commit。" >&2
	exit 2
fi

# 解析为 commit：annotated tag 的 rev-parse 给的是 tag 对象 SHA，必须 ^{commit} 才能拿到提交。
# 这不是细节——用 tag 对象 SHA 做区间会算错（实测把 3 个提交算成 54 个）。
PREV_COMMIT="$(git rev-parse -q --verify "${PREV}^{commit}")" \
	|| { echo "无法解析为提交: ${PREV}" >&2; exit 1; }
CURR_COMMIT="$(git rev-parse -q --verify "${CURR}^{commit}")" \
	|| { echo "无法解析为提交: ${CURR}" >&2; exit 1; }

# 主线（默认分支）。环境变量可覆盖，CI 里通常就是当前分支。
WCPE_MAIN_REF="${WCPE_MAIN_REF:-dev/1.17-wcpe}"

# 发布点 → 主线提交的对照表。理由与格式见该文件头部说明。
ANCHORS_FILE=".upstream/release-anchors.txt"

# 查表：把 tag/ref 换成主线上对应的提交。
#
# 顺序很重要：先判断该提交是否已在主线历史内——在的话无需换算（它就是自己的锚点），
# 否则才查表。反过来做会把本来正确的端点也换掉（曾把 1.17.13 自身换算成旧提交）。
#
# 输出格式：`<self标志>\t<commit>`。self 标志为 1 表示「刻意就用它自己」——这类发布点
# 不在主线、但与相邻发布点之间可直接比较（例如早期几条各自独立的支线），不该告警。
# 用输出字段而不是全局变量传递：调用方写在 `$(...)` 里，全局变量改在子 shell 里传不回来。
anchor_of() {
	local ref="$1" commit

	commit="$(git rev-parse "${ref}^{commit}")"

	if git merge-base --is-ancestor "${commit}" "${WCPE_MAIN_REF}" 2>/dev/null; then
		printf '0\t%s' "${commit}"
		return 0
	fi

	local mapped=""

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

		# 表里写的是短 SHA，解析成完整提交
		if git rev-parse -q --verify "${mapped}^{commit}" >/dev/null 2>&1; then
			printf '0\t%s' "$(git rev-parse "${mapped}^{commit}")"
			return 0
		fi

		echo "警告：${ANCHORS_FILE} 里 ${ref} 的锚点 ${mapped} 无法解析，按原值使用。" >&2
	fi

	printf '0\t%s' "${commit}"
}

# 区间端点：优先用对照表里的主线提交，避免跨分叉重复计算。
#
# `anchor_of` 跑在命令替换的子 shell 里，函数内设的 SELF_ANCHOR 传不回来。
# 用「把标记写进输出」的办法：约定返回 "<self标志>\t<commit>"，调用方再拆开。
PREV_RAW="$(anchor_of "${PREV}")"
CURR_RAW="$(anchor_of "${CURR}")"

PREV_SELF="${PREV_RAW%%	*}"
PREV_ANCHOR="${PREV_RAW##*	}"
CURR_SELF="${CURR_RAW%%	*}"
CURR_ANCHOR="${CURR_RAW##*	}"

for pair in "起点:${PREV}:${PREV_ANCHOR}:${PREV_SELF}" "终点:${CURR}:${CURR_ANCHOR}:${CURR_SELF}"; do
	role="${pair%%:*}"
	rest="${pair#*:}"
	name="${rest%%:*}"
	rest="${rest#*:}"
	anchor="${rest%%:*}"
	self="${rest##*:}"

	if [ "${anchor}" != "$(git rev-parse "${name}^{commit}")" ]; then
		echo "注：${name} 已按对照表换算为主线提交 $(git rev-parse --short "${anchor}")（${role}）。" >&2
	elif [ "${self}" = "1" ]; then
		echo "注：${name} 按对照表声明为 self，直接用它自身（${role}）。" >&2
	elif ! git merge-base --is-ancestor "${anchor}" "${WCPE_MAIN_REF}" 2>/dev/null; then
		echo "警告：${name} 不在 ${WCPE_MAIN_REF} 历史内，且 ${ANCHORS_FILE} 未收录它；" >&2
		echo "      区间可能把上一版已发布的提交重复算入。请在该表补一行：${name} <主线提交>" >&2
		echo "      若它与相邻发布点可直接比较、无需换算，写 ${name} self 即可。" >&2
	fi
done

# 对比链接里优先显示 tag 名（人读得懂），只有传进来的不是 tag 时才退回提交 SHA。
# WCPE_LABEL_PREV / WCPE_LABEL_CURR 可显式覆盖显示名。
label_of() {
	if git rev-parse -q --verify "refs/tags/$1" >/dev/null; then
		printf '%s' "$1"
	else
		git rev-parse --short "${1}^{commit}"
	fi
}
PREV_LABEL="${WCPE_LABEL_PREV:-$(label_of "${PREV}")}"
CURR_LABEL="${WCPE_LABEL_CURR:-$(label_of "${CURR}")}"

# 取区间内的非 merge 提交，格式：<type>(<scope>): <描述>\x1f<短hash>
# 用 0x1F 作分隔符：提交信息里不会出现这个控制字符
#
# WCPE_CHERRY_PICK=1：跨分叉时排除「内容相同但 SHA 不同」的重复提交。
# 背景：两条发布支线各自 cherry-pick 过同一批改动时，A..B 会把 B 侧那批重复算入。
# 实测 1.17.3 → 1.17.4 只差 3 条实变更，不加此选项会算成 36 条。
# --cherry-pick --right-only 必须配三点号：配两点号会静默给出错误结果，故这里按开关切换运算符。
# 注意它依赖 patch-id，而重写会改变 patch-id，因此只能作**辅助**：
# 需要精确结果时仍应拿 `git diff --name-only 上一版 本版` 的文件清单交叉验证。
if [ "${WCPE_CHERRY_PICK:-0}" = "1" ]; then
	RANGE="${PREV_ANCHOR}...${CURR_ANCHOR}"
	CHERRY_ARGS=(--cherry-pick --right-only)
else
	RANGE="${PREV_ANCHOR}..${CURR_ANCHOR}"
	CHERRY_ARGS=()
fi

RAW="$(git log --no-merges "${CHERRY_ARGS[@]}" --reverse --pretty=format:'%s%x1f%h' "${RANGE}")"

if [ -z "${RAW}" ]; then
	echo "本版无提交变更。"
	exit 0
fi

# 按 Conventional Commits 的 type 归类。键为 type，输出为「中文标题|正则」。
# 顺序即输出顺序：功能 → 修复 → 性能 → Forge → 重构 → 测试 → 构建/CI → 文档 → 其它
categorize() {
	local subject="$1"
	# 取 type：`type:` 或 `type(scope):` 或带 `!` 的破坏性标记
	local type="${subject%%:*}"
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

ORDER=("新增功能" "缺陷修复" "性能与并发" "回滚" "重构" "测试" "构建与依赖" "CI 与发布" "文档" "其他变更")

# 先把每条提交归到对应桶里。用临时文件而不是关联数组，兼容 bash 3.2（macOS 自带）
TMPDIR_NOTES="$(mktemp -d)"
trap 'rm -rf "${TMPDIR_NOTES}"' EXIT

while IFS=$'\x1f' read -r subject hash; do
	[ -n "${subject}" ] || continue
	# 过程性提交（验证工具的 scratch、误提交的临时文件等）对使用者无意义，按需过滤。
	# 用正则而不是写死清单：不同版本的噪声提交标题不一样。
	if [ -n "${WCPE_SKIP_PATTERN:-}" ] \
		&& printf '%s' "${subject}" | grep -qE "${WCPE_SKIP_PATTERN}"; then
		continue
	fi
	cat_name="$(categorize "${subject}")"
	# 去掉类型前缀，标题更易读；scope 提取出来加粗前置
	clean="$(printf '%s' "${subject}" | sed -E 's/^[a-zA-Z]+(\([^)]*\))?!?:[[:space:]]*//')"
	# 去掉尾部的 PR 编号 `(#12)`：本清单按提交生成，已附短 hash，再留 PR 号是重复噪声。
	# 只匹配结尾，正文里出现的括号不受影响。
	clean="$(printf '%s' "${clean}" | sed -E 's/[[:space:]]*\(#[0-9]+\)[[:space:]]*$//')"
	scope="$(printf '%s' "${subject}" | sed -nE 's/^[a-zA-Z]+\(([^)]*)\)!?:.*/\1/p')"
	if [ -n "${scope}" ]; then
		clean="**${scope}**: ${clean}"
	fi
	printf -- '- %s (%s)\n' "${clean}" "${hash}" >> "${TMPDIR_NOTES}/${cat_name}"
done <<< "${RAW}"

for name in "${ORDER[@]}"; do
	[ -f "${TMPDIR_NOTES}/${name}" ] || continue
	echo "### ${name}"
	echo
	cat "${TMPDIR_NOTES}/${name}"
	echo
done

echo "**完整对比**：https://github.com/${GITHUB_REPOSITORY:-wcpe/wcpe-loom}/compare/${PREV_LABEL}...${CURR_LABEL}"