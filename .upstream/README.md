# 补丁队列使用指南

本文件是 `dev/1.17-wcpe` 这条线的维护手册：说明 Debian quilt 真源、生成的应用视图、补丁溯源、
校验和正式发版边界。这里的 Debian 目录只保存源补丁编排；本项目仍是 Maven 发布的 Gradle 插件项目，
不假说仓库在维护 Debian 正式发行包。

## 配套文件

| 文件 | 作用 |
|---|---|
| `PATCH-QUEUE.md` | 队列规范（结构、补丁判定、DEP-3 溯源和维护纪律） |
| `migration-1.17.md` | 历史谱系清单（基底、补丁来源和遗留说明） |
| `README.md` | 本文件：当前操作手册 |
| `debian/1.17` | 补丁真源分支，标准 3.0 (quilt) 未应用态 |
| `.upstream/quilt-1.17.lock` | 发版时固化本次源树的锁定文件 |
| `../scripts/sync-upstream.sh` | 只读校验入口：`verify` / `status` |
| `../scripts/gen-release-notes.sh` | 发布清单生成与校验入口 |

## 一、真源、临时工作分支和应用视图

当前 1.17 线采用以下关系：

```
debian/1.17                         唯一真源：标准 3.0 (quilt) 未应用态
  ↓  完整 series 共 151 条：132 条历史 + 15 条本次新增 + 4 条旧源码/测试补录

gbp pq import / export               临时补丁工作分支的导入与导出
  ↓  按 series 逐条自动重放，并保留每条提交的 trailer

dev/1.17-wcpe                       生成的应用视图，供构建、测试和 Maven 发布
```

必须牢记：

- `debian/1.17` 是唯一补丁真源。标准 3.0 (quilt) 的未应用态由补丁文件和 `series` 顺序共同定义。
- 迁移时的 `series` 共 151 条：132 条历史、15 条本次新增、4 条旧源码/测试补录。旧主线已有 132+4 条；
  这次只把 15 条按 `series` 顺序新应用到旧主线，保留 `Origin`、`Forwarded` 和 `Patch-Name`。
- `dev/1.17-wcpe` 是应用视图，不是第二个权威队列。不要在应用视图上另行维护补丁顺序或复制一套来源。
- `gbp pq import` 和 `gbp pq export` 只用于临时补丁工作分支。完成编辑后，应把结果导回 `debian/1.17` 的
  未应用态，再由 `series` 重新生成应用视图。
- 原 `patch-queue/1.17` tag 已冻结为历史锚点。它只用于阅读旧历史，不再作为当前队列末端移动。
- CI 与 TestKit 镜像属于仓库元数据，不是 Debian 补丁真源，也不产生另一套补丁队列。

## 二、补丁工作流

在 `debian/1.17` 上开始工作，进入临时补丁分支：

```bash
gbp pq import
```

在临时分支中按逻辑单元修改并提交。一个补丁应能独立说明目的、独立审查，撤销时不应牵连不相关的补丁。
完成后导出回标准 quilt 未应用态：

```bash
gbp pq export
```

导出后审阅 `series` 和补丁差异，并提交打包分支；不要覆盖未修改的历史补丁。上游 `.gitignore` 的 `/*`
会忽略 `debian/` 下的新增文件。新增补丁要逐一 `git add -f -- debian/patches/<文件>`，不要全量暂存。

同步新增补丁时，从远端 `dev/1.17-wcpe` 建隔离的候选视图，把尚未应用的条目按 `series` 顺序逐条
`git am --no-3way`；它们的内容和 trailer 来自已提交的打包分支。应用完成后刷新 Maven 视图的锁文件，再做
源码和 CI 校验。修改既有补丁时应单独验收派生视图的前向更新，不能把全系列重复应用或重写已发布历史。

### 提交信息溯源

新补丁继续保留提交信息末尾的 DEP-3 溯源块和本仓库扩展字段：

```
fix(forge): 修掉 xxx

正文说明为什么改、改动要点。

Origin: vendor, wcpe-loom
Forwarded: no
Patch-Name: <slug>
```

字段约定如下：

| 字段 | 取值 |
|---|---|
| `Origin` | `vendor, wcpe-loom`、`backport, <上游版本>` 或 `upstream, <提交 URL>` |
| `Forwarded` | `no`、`not-needed` 或已反馈的 URL |
| `Applied-Upstream` | 可选；上游已收录时记录对应版本或提交 |
| `Patch-Name` | 小写字母、数字和连字符组成，匹配 `^[a-z0-9]+(-[a-z0-9]+)*$`，长度不超过 48，序列内唯一 |

`Patch-Name` 是补丁的稳定身份，发布说明按它做集合差；补丁重放或应用视图重建时不得随意改名。历史补丁不
回填缺失的 trailer，元数据提交也不带 `Patch-Name`。历史上已经补录的四条旧补丁只在描述中保留，不计入本版
新增补丁。

## 三、校验与源锁定

Maven 分支中的 `.upstream/quilt-1.17.lock` 固化正式发版所依据的源树。执行校验前，先从打包分支取得本地
canonical ref；缺省 canonical ref 是 `refs/remotes/origin/debian/1.17`：

```bash
git fetch origin refs/heads/debian/1.17:refs/remotes/origin/debian/1.17
bash scripts/sync-upstream.sh verify
```

`verify` 是只读校验，目标是确认锁定源、`series` 和生成的应用视图彼此一致；它不改写工作区、分支或 tag。
需要查看当前判定摘要时可使用：

```bash
bash scripts/sync-upstream.sh status
```

命令入口也接受只读校验参数：

```bash
bash scripts/sync-upstream.sh {verify|status} [--view 提交] [--package-ref 打包引用]
```

`verify` 和 `status` 都只读，不会修改工作区或引用。不要把 `dev/1.17-wcpe` 的提交数量、旧 tag 或本地临时分支当作第二个真源。真源始终是 `debian/1.17` 的标准
quilt 未应用态及其 `series`。

## 四、正式发版

正式发版保持三步边界，不把打包真源和 Maven 发布视图混成一套历史：

1. **独立验证**：在 `debian/1.17`、`.upstream/quilt-1.17.lock` 和生成视图之间完成只读校验，确认应用视图可由
   `series` 重现。
2. **主线验证**：只让 `dev/1.17-wcpe` 快进到已验证的同一 SHA，由 CI 在这个 SHA 上运行测试和 TestKit 镜像验证。
   不制造 merge 提交。
3. **发布标签**：只发布 `wcpe-v*` tag。上一标签的使用方式和既有上传策略保持不变。

流程中严禁 merge、强推或临时修改分支保护。历史上留下的 `patch-queue/1.17` 前缀和旧提交前缀不改写。

发布清单仍然在 tag 前生成并提交，CI 只校验归档清单，不替代真源校验：

```bash
bash scripts/gen-release-notes.sh --tag wcpe-v1.17.N
# 提交发布清单并创建 tag 后，由 CI 执行严格校验
bash scripts/gen-release-notes.sh --check wcpe-v1.17.N
```

## 五、旧命令与历史说明

以下命令属于迁移前的队尾标签模型，不是当前操作：

- `add-patch`
- `add-patch finish`
- `rebase`
- `pull-queue`
- `setup-mirror`

新模式下，`scripts/sync-upstream.sh` 会提示这些命令不再适用，并指向 `debian/1.17` 与 `gbp pq import/export`。
不要据此推导额外的 Debian 规则，也不要把历史命令重新写成当前推荐流程。历史提交、历史发布说明和旧命令
前缀保持原样，只有当前手册改用 quilt 真源描述。

## 六、日常边界速查

| 事项 | 当前做法 |
|---|---|
| 补丁真源 | `debian/1.17` 的标准 3.0 (quilt) 未应用态 |
| 补丁顺序 | `debian/patches/series` |
| 临时编辑 | `gbp pq import` 进入、`gbp pq export` 导出 |
| 构建与测试视图 | 从 `series` 派生的 `dev/1.17-wcpe` 应用视图 |
| 历史锚点 | 冻结的 `patch-queue/1.17` tag |
| 发版锁定 | `.upstream/quilt-1.17.lock` |
| 默认打包引用 | `refs/remotes/origin/debian/1.17` |
| 校验 | `git fetch` 打包分支后运行 `bash scripts/sync-upstream.sh verify` |
| 版本发布 | 只发 `wcpe-v*` tag，沿用既有上传策略 |
