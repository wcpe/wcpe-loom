# WCPE Loom 1.17 补丁队列规范

本规范定义 1.17 线的 Debian quilt 真源、生成的应用视图、补丁溯源和发版边界。当前补丁真源是
`debian/1.17` 的标准 3.0 (quilt) 未应用态；本项目仍是 Maven 发布的 Gradle 插件项目，Debian 目录不代表
仓库在维护 Debian 正式发行包。

## 1. 队列结构

```
debian/1.17                         唯一真源：标准 3.0 (quilt) 未应用态
  ↓  完整 series 共 151 条：132 条历史 + 15 条本次新增 + 4 条旧源码/测试补录

gbp pq import / export               临时补丁工作分支
  ↓  由 series 逐条自动重放，并保留提交 trailer

dev/1.17-wcpe                       生成的应用视图
```

规范要点：

- `debian/1.17` 是唯一补丁真源。补丁文件和 `debian/patches/series` 共同定义标准 quilt 未应用态。
- 迁移时的 `series` 共 151 条：132 条历史、15 条本次新增、4 条旧源码/测试补录。旧主线已有 132+4 条；
  这次只把 15 条按 `series` 顺序应用到旧主线。`dev/1.17-wcpe` 是生成的应用视图，不是第二个补丁真源。
- 应用视图重放保留每条补丁提交的 `Origin`、`Forwarded`、`Patch-Name` 等 trailer。
- `gbp pq import` 和 `gbp pq export` 只负责补丁工作分支与未应用态之间的转换。
- `gbp pq export` 后审阅补丁与 `series` 再提交。上游 `.gitignore` 的 `/*` 会忽略新增的打包文件；
  新补丁须逐一 `git add -f -- debian/patches/<文件>`，不要全量暂存。
- 原 `patch-queue/1.17` tag 冻结为历史锚点，不再作为当前队列末端移动。
- CI 与 TestKit 镜像属于仓库元数据，不改变补丁真源，不产生第二套队列。

补丁与元数据的判定仍是：*换上游后这行还需要吗？* 需要 → 补丁；不需要 → CI、文档、脚本等仓库元数据。

## 2. 补丁的判定标准

一个补丁是一个逻辑变更单元，须同时满足：

1. 能用一句话说明其目的；
2. 能独立审查，不必先读其他补丁；
3. 撤销它不牵连不相关补丁的功能。

| 情形 | 处置 |
|---|---|
| 一个变更恰好是一个逻辑单元 | 1:1 保留 |
| 多个提交共同构成一个逻辑变更 | 压缩为一个补丁 |
| 一个提交混合多个不相关变更 | 拆分为多个补丁 |

队列描述最终状态，不描述临时开发过程。补丁的顺序以 `series` 为准，不以应用视图上的手工提交顺序为准。

## 3. 溯源标注

每个新补丁的提交信息末尾必须保留 DEP-3 溯源块，并带本仓库扩展字段 `Patch-Name`：

```
<type>(<scope>): <中文描述>

<正文：说明为什么改、改动要点>

Origin: <取值>
Forwarded: <取值>
Patch-Name: <slug>
```

字段约定：

| 字段 | 取值 | 含义 |
|---|---|---|
| `Origin` | `upstream, <提交 URL>` | 取自上游 |
| `Origin` | `backport, <上游版本>` | 从其他代系移植 |
| `Origin` | `vendor, wcpe-loom` | 本项目自研 |
| `Forwarded` | `no` | 应当反馈上游但尚未反馈 |
| `Forwarded` | `not-needed` | 本项目专属，无需反馈 |
| `Forwarded` | `<URL>` | 已反馈，附链接 |
| `Applied-Upstream` | `<版本或提交>` | 可选；上游已收录时记录 |
| `Patch-Name` | `^[a-z0-9]+(-[a-z0-9]+)*$`，≤ 48 字符 | 补丁稳定标识，序列内唯一 |

`Patch-Name` 只认 trailer，不认 SHA、标题或 patch-id。应用视图重建或源树重新导出时，稳定标识必须保持不变。
历史补丁缺失该字段时不回填，元数据提交不带该字段。历史上已经补录的四条旧补丁只在描述中保留，不计入
本版新增补丁。

## 4. 校验与源锁定

Maven 分支中的 `.upstream/quilt-1.17.lock` 固化正式发版所依据的源树。校验时先取得打包分支，并使用默认本地
canonical ref `refs/remotes/origin/debian/1.17`：

```bash
git fetch origin refs/heads/debian/1.17:refs/remotes/origin/debian/1.17
bash scripts/sync-upstream.sh verify
```

`verify` 必须是只读操作，至少用于确认锁定源、`series` 和 `dev/1.17-wcpe` 应用视图能够对应；不得通过校验
生成第二个补丁来源，不得改写工作区、分支或 tag。需要摘要时使用：

```bash
bash scripts/sync-upstream.sh status
```

命令入口接受只读校验参数：

```bash
bash scripts/sync-upstream.sh {verify|status} [--view 提交] [--package-ref 打包引用]
```

`verify` 和 `status` 都不得修改工作区或引用。

## 5. 维护和发版纪律

1. 补丁顺序以 `debian/patches/series` 为唯一顺序来源；应用视图只读生成，不在其上建立并行队列。
2. 补丁工作使用 `gbp pq import` / `gbp pq export` 临时分支流程，不恢复旧队尾标签操作。
3. 发版前先独立验证真源、锁定文件和应用视图；验证通过后，主线只快进到同一 SHA，由 CI 在该 SHA 上运行。
4. 正式版本只发布 `wcpe-v*` tag；上一标签和既有上传策略保持不变。
5. 队列和主线流程禁止 merge、强推以及临时修改分支保护；验证失败不能靠改写引用绕过。
6. CI、TestKit、发布清单等仓库元数据可以按普通元数据维护，但不得冒充补丁真源。
7. 历史 `patch-queue/1.17` tag、历史提交前缀和历史发布说明保持不变；旧流程只作为历史记录。

发布清单在 tag 前生成并提交，提交清单并创建 tag 后由 CI 严格校验：

```bash
bash scripts/gen-release-notes.sh --tag wcpe-v1.17.N
# 提交发布清单并创建 tag 后，由 CI 执行严格校验
bash scripts/gen-release-notes.sh --check wcpe-v1.17.N
```

旧的 `add-patch`、`finish`、`rebase`、`pull-queue`、`setup-mirror` 命令属于迁移前模型。新模式下脚本会提示
它们不再适用；当前规范不为这些命令补充替代参数，也不假设尚未实现的 Debian 操作。
