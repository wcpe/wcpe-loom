#!/usr/bin/env bash
# WCPE Loom 1.17：核对 Maven 应用视图和 Debian quilt 队列，不修改工作区或引用。
set -euo pipefail

root=$(git rev-parse --show-toplevel)
case "${1:-verify}" in
	verify|status)
		if (( $# > 0 )); then shift; fi
		exec python3 -I -B "$root/scripts/verify-quilt-view.py" "$@"
		;;
	add-patch|rebase|pull-queue|setup-mirror)
		printf '%s\n' '旧命令只适用于迁移前的队尾标签。现在请在 debian/1.17 使用 gbp pq import / export；见 .upstream/README.md。' >&2
		exit 2
		;;
	*)
		printf '%s\n' '用法: bash scripts/sync-upstream.sh {verify|status} [--view 提交] [--package-ref 打包引用]' >&2
		exit 2
		;;
esac
