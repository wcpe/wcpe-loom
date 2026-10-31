#!/usr/bin/env bash
# gen-release-notes.sh 的回归测试
#
# 为什么要有：这套机制的正确性全靠一条语义——「发布说明 = 补丁面集合差」。
# 它最容易在「重写队列」这一步退化回旧行为（重复列出上一版已发布的内容），
# 而这种退化在真实仓库里只能等发版才看得见。这里用一个一次性 git 仓库把
# 「插补丁 → 重写队列 → 打 tag」全过程复现，把那条语义钉住。
#
# 用法： bash scripts/tests/gen-release-notes-test.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/scripts/gen-release-notes.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

PASS=0
FAIL=0
CURRENT=""

case_start() { CURRENT="$1"; printf '· %s\n' "${CURRENT}"; }
ok() { PASS=$((PASS + 1)); }
bad() {
	FAIL=$((FAIL + 1))
	printf '  ✗ %s\n' "$*" >&2
}

assert_eq() { # <期望> <实际> <说明>
	if [ "$1" = "$2" ]; then ok; else bad "$3：期望 [$1]，实际 [$2]"; fi
}

assert_contains() { # <文本> <子串> <说明>
	case "$1" in
	*"$2"*) ok ;;
	*) bad "$3：输出里找不到 [$2]" ;;
	esac
}

assert_not_contains() { # <文本> <子串> <说明>
	case "$1" in
	*"$2"*) bad "$3：输出里不应出现 [$2]" ;;
	*) ok ;;
	esac
}

commit_patch() { # <标题> <slug>
	git commit -q -m "$1

正文说明。

Origin: vendor, wcpe-loom
Forwarded: no
Patch-Name: $2"
}

commit_meta() { # <标题>
	git commit -q -m "$1"
}

# ---------------------------------------------------------------- 搭仓库

git init -q "${WORK}/repo"
cd "${WORK}/repo" || exit 1
git config user.email test@example.com
git config user.name test
git config commit.gpgsign false
mkdir -p src scripts
cp "${SCRIPT}" scripts/gen-release-notes.sh

echo base >src/marker.txt
git add -A
commit_meta "chore: 基底快照"
git tag -a base/architectury-1.17-026ce830 -m "基底"

# 1.17.1：两个补丁 + 一条元数据
echo one >>src/marker.txt
git add -A
commit_patch "feat(core): 功能一" "core-feature-one"
echo two >>src/marker.txt
git add -A
commit_patch "fix(core): 修复一" "core-fix-one"
git tag -a patch-queue/1.17 -m "队列末端 1"
echo meta1 >docs-1.md
git add -A
commit_meta "docs: 第一版文档"
git tag -a wcpe-v1.17.1 -m "1.17.1"
git branch -m dev/1.17-wcpe

case_start "首个版本：全部补丁面都算新增"
out1="$(bash scripts/gen-release-notes.sh --tag wcpe-v1.17.1 --print 2>/dev/null)"
assert_contains "${out1}" "本版新增 2 个" "计数"
assert_contains "${out1}" '`core-feature-one`' "补丁面一"
assert_contains "${out1}" '`core-fix-one`' "补丁面二"
assert_not_contains "${out1}" "第一版文档" "元数据默认不列"

# ---------------------------------------------------------------- 模拟队列重写
# 新补丁插到队列末端，元数据段被 rebase 重放（docs 提交因此换了 SHA——这正是
# 旧机制会把上一版内容重复列出的根源），然后打 1.17.2。

git checkout -q -b add-patch/core-widget patch-queue/1.17
echo three >>src/marker.txt
git add -A
commit_patch "feat(widget): 新增控件" "core-widget"
git checkout -q dev/1.17-wcpe
git checkout -q -b candidate dev/1.17-wcpe
git rebase --onto add-patch/core-widget patch-queue/1.17 candidate >/dev/null 2>&1 \
	|| { echo "测试环境搭建失败：元数据重放冲突" >&2; exit 1; }
git tag -f -a patch-queue/1.17 add-patch/core-widget -m "队列末端 2" >/dev/null
git branch -f dev/1.17-wcpe candidate
git checkout -q dev/1.17-wcpe
git tag -a wcpe-v1.17.2 -m "1.17.2"
echo "--- 重写后的历史（docs 提交已换 SHA）---"
git log --oneline -4

case_start "重写队列后：只列本版新增，不重复上一版"
out2="$(bash scripts/gen-release-notes.sh --tag wcpe-v1.17.2 --print 2>/dev/null)"
assert_contains "${out2}" "本版新增 1 个" "计数只算新补丁"
assert_contains "${out2}" '`core-widget`' "新补丁在"
assert_not_contains "${out2}" "功能一" "旧补丁不重复"
assert_not_contains "${out2}" "修复一" "旧补丁不重复"
assert_not_contains "${out2}" "第一版文档" "旧元数据不重复"

# 再重写一次（只改元数据，不动补丁段），确认依然不重复且新增为零
echo meta2 >docs-2.md
git add -A
commit_meta "docs: 第二版文档"
git tag -a wcpe-v1.17.3 -m "1.17.3"
case_start "只动元数据：新增为零"
out3="$(bash scripts/gen-release-notes.sh --tag wcpe-v1.17.3 --print 2>/dev/null)"
assert_contains "${out3}" "本版新增 0 个" "计数为零"
assert_contains "${out3}" "本版没有新增补丁" "明确说无新增"

# ---------------------------------------------------------------- 确定性与归档

case_start "确定性：同一 tag 两次输出逐字节一致"
a="$(bash scripts/gen-release-notes.sh --tag wcpe-v1.17.2 --print 2>/dev/null)"
b="$(bash scripts/gen-release-notes.sh --tag wcpe-v1.17.2 --print 2>/dev/null)"
assert_eq "${a}" "${b}" "两次输出"

case_start "落盘 + --check"
bash scripts/gen-release-notes.sh --tag wcpe-v1.17.2 >/dev/null 2>&1
[ -f .upstream/releases/wcpe-v1.17.2.md ] && ok || bad "清单文件未生成"
if bash scripts/gen-release-notes.sh --check wcpe-v1.17.2 >/dev/null 2>&1; then ok; else bad "--check 对合法清单应为 0"; fi

case_start "--check 拦住手改"
printf '\n手写的一行\n' >>.upstream/releases/wcpe-v1.17.2.md
if bash scripts/gen-release-notes.sh --check wcpe-v1.17.2 >/dev/null 2>&1; then
	bad "--check 对篡改后的清单应失败"
else
	ok
fi

case_start "--check 拦住缺失"
rm -f .upstream/releases/wcpe-v1.17.2.md
if bash scripts/gen-release-notes.sh --check wcpe-v1.17.2 >/dev/null 2>&1; then
	bad "--check 缺清单时应失败"
else
	ok
fi

case_start "--check 对不存在的 tag 应失败（那是生成口，不是校验口）"
if bash scripts/gen-release-notes.sh --check wcpe-v9.9.9 >/dev/null 2>&1; then
	bad "--check 对不存在的 tag 应失败"
else
	ok
fi

# ---------------------------------------------------------------- JSON

case_start "--json 是合法 JSON 且内容正确"
json="$(bash scripts/gen-release-notes.sh --tag wcpe-v1.17.2 --json 2>/dev/null)"
if command -v python3 >/dev/null 2>&1; then
	if printf '%s' "${json}" | python3 -c '
import json, sys
d = json.load(sys.stdin)
assert d["tag"] == "wcpe-v1.17.2", d
assert [x["slug"] for x in d["added"]] == ["core-widget"], d
assert d["prev"] == "wcpe-v1.17.1", d
' 2>/dev/null; then ok; else bad "JSON 内容不符：${json}"; fi
else
	assert_contains "${json}" '"slug": "core-widget"' "JSON 含新补丁"
fi

# ---------------------------------------------------------------- legacy 引擎

case_start "legacy：缺锚点直接失败（不再只告警）"
git checkout -q -b side-branch wcpe-v1.17.1
echo side >side.txt
git add -A
commit_meta "chore: 支线上的提交"
git tag -a wcpe-v1.17.99 -m "孤立版本"
git checkout -q dev/1.17-wcpe
if bash scripts/gen-release-notes.sh --legacy wcpe-v1.17.99 wcpe-v1.17.3 >/dev/null 2>&1; then
	bad "缺锚点的 legacy 调用应失败"
else
	ok
fi

case_start "legacy：--allow-unanchored 才放行"
if WCPE_MAIN_REF=dev/1.17-wcpe bash scripts/gen-release-notes.sh --legacy wcpe-v1.17.99 wcpe-v1.17.3 --allow-unanchored >/dev/null 2>&1; then
	ok
else
	bad "--allow-unanchored 应放行"
fi

case_start "legacy：位置参数写法仍可用"
if WCPE_MAIN_REF=dev/1.17-wcpe bash scripts/gen-release-notes.sh wcpe-v1.17.99 wcpe-v1.17.3 --allow-unanchored >/dev/null 2>&1; then
	ok
else
	bad "位置参数写法应可用"
fi

# ---------------------------------------------------------------- 真实发版顺序
# 文档规定的流程是「先生成清单 → 提交清单 → 再打 tag」。这条最容易出问题：
# 生成时 tag 还不存在，且清单随后会改变 HEAD——所以清单里不能含提交 SHA。

case_start "真实发版顺序：先生成 → 提交 → 打 tag → --check 通过"
echo six >>src/marker.txt
git add -A
commit_patch "fix(widget): 晚到的修复" "core-late"
bash scripts/gen-release-notes.sh --tag wcpe-v1.17.4 >/dev/null 2>&1
if [ -f .upstream/releases/wcpe-v1.17.4.md ]; then ok; else bad "tag 尚未创建时清单应能生成"; fi
if grep -q '`core-late`' .upstream/releases/wcpe-v1.17.4.md 2>/dev/null; then ok; else bad "清单里没有新补丁"; fi
if grep -q '晚到的修复' .upstream/releases/wcpe-v1.17.4.md 2>/dev/null; then ok; else bad "清单里缺少描述"; fi
git add .upstream/releases/wcpe-v1.17.4.md
commit_meta "docs: 补 1.17.4 的发布清单"
git tag -a wcpe-v1.17.4 -m "1.17.4"
if bash scripts/gen-release-notes.sh --check wcpe-v1.17.4 >/dev/null 2>&1; then
	ok
else
	bad "--check 在 tag 后应通过（清单不含 SHA，与提交无关）"
fi

# ---------------------------------------------------------------- 汇总

printf '\n通过 %s 项，失败 %s 项\n' "${PASS}" "${FAIL}"
[ "${FAIL}" = "0" ] || exit 1
