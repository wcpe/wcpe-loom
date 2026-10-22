#!/usr/bin/env bash
# 按提交生成发布说明（不依赖 PR）
#
# 用法：
#   scripts/gen-release-notes.sh <上一版tag> <本版tag>     # 输出到 stdout
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

# 对比链接里优先显示 tag 名（人读得懂），只有传进来的不是 tag 时才退回提交 SHA。
# WCPE_LABEL_PREV / WCPE_LABEL_CURR 可显式覆盖显示名——修历史 release 时会用到：
# 那时区间端点必须是主线上的 commit（孤立 tag 会让区间失真），但链接仍应显示 tag 名。
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
RAW="$(git log --no-merges --reverse --pretty=format:'%s%x1f%h' "${PREV_COMMIT}..${CURR_COMMIT}")"

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