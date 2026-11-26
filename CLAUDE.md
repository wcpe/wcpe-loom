# CLAUDE.md

本仓库的工作指南见 **[AGENTS.md](AGENTS.md)**——架构、补丁队列约定、构建与提交规范都在那里。

以下是最容易出错、必须先知道的几条（完整说明见 AGENTS.md）：

1. **补丁真源是 `debian/1.17`**，采用标准 3.0 (quilt) 未应用态；完整标准 `series` 共 151 条（132 条历史、15 条本次新增、
   4 条旧源码/测试补录）。默认主线旧历史已有 132+4 条，本次同步只将 15 项展开逐条追加。用 `gbp pq import` /
   `gbp pq export` 在临时补丁工作分支上编辑；导出后逐个明确 `git add -f -- debian/patches/<文件>`，`dev/1.17-wcpe`
   只是从 `series` 派生的应用视图，不是第二个权威队列。
2. **校验与发版**：Maven 分支中的 `.upstream/quilt-1.17.lock` 固化发版源。先把打包分支 fetch 到默认
   `refs/remotes/origin/debian/1.17`，再运行 `bash scripts/sync-upstream.sh verify`；正式流程是独立验证 → 主线只快进同 SHA CI →
   只发 `wcpe-v*` tag。禁止 merge、强推或临时修改分支保护，上一标签和上传策略不变。
3. 原 `patch-queue/1.17` tag 仅冻结为历史锚点。旧的 `add-patch`、`add-patch finish`、`rebase`、`pull-queue`、`setup-mirror`
   命令在新模式下会提示不再适用，历史前缀不改写。
4. **不要一次跑完整 `./gradlew build` 或整套测试**，集成测试耗时数小时。
   用 `./gradlew compileJava` / `test --tests "…"` 做针对性验证。
5. **注释与提交信息用简体中文**；提交信息格式 `<type>(<scope>): <中文描述>`，
   不加 AI 签名或作者尾注。
6. **临时文件放 `.tmp/`**（已 gitignore）。

详细规范：`.upstream/README.md`（操作手册）、`.upstream/PATCH-QUEUE.md`（队列规范）、
`.upstream/migration-1.17.md`（谱系清单）。
