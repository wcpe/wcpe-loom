# CLAUDE.md

本仓库的工作指南见 **[AGENTS.md](AGENTS.md)**——架构、补丁队列约定、构建与提交规范都在那里。

以下是最容易出错、必须先知道的几条（完整说明见 AGENTS.md）：

1. **本仓库不是普通 Git 流程**。默认分支 `dev/1.17-wcpe` 的历史分「补丁段 / 元数据段」两段，
   中间由可移动的 `patch-queue/1.17` tag 分隔。
   - 改**代码/构建配置/测试** → 走 `scripts/sync-upstream.sh add-patch <名字>`，
     分支起点是 `patch-queue/1.17`，**不是**默认分支 HEAD。
   - 改 **CI/文档/脚本** → 直接在默认分支提交推送，无需强推。
2. **发版纪律**：一旦打了 `wcpe-v*` tag，那个提交之前的历史就不能再改写。
   插补丁要重写队列，必须放在**发版之前**做。已发生则把主线等价提交补进
   `.upstream/release-anchors.txt`。
3. **不要一次跑完整 `./gradlew build` 或整套测试**，集成测试耗时数小时。
   用 `./gradlew compileJava` / `test --tests "…"` 做针对性验证。
4. **注释与提交信息用简体中文**；提交信息格式 `<type>(<scope>): <中文描述>`，
   不加 AI 签名或作者尾注。
5. **临时文件放 `.tmp/`**（已 gitignore）。

详细规范：`.upstream/README.md`（操作手册）、`.upstream/PATCH-QUEUE.md`（队列规范）、
`.upstream/migration-1.17.md`（谱系清单）。
