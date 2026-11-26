# WCPE Loom — 开发者与 AI 代理指南

本文件面向在本仓库工作的开发者与 AI 代理：先讲清仓库结构（含补丁队列这一非标准约定），
再给构建与提交规范。**动手前请先读完第一节**——本仓库的历史是被显式管理的，不是普通 Git 流程。

## 1. 这是什么

`wcpe-loom` 是 [fabric-loom](https://github.com/FabricMC/fabric-loom) 的 fork，
经由 [architectury-loom](https://github.com/architectury/architectury-loom) 分叉而来，
面向 WCPE 的 Minecraft 模组开发场景。它是一个 Gradle 插件，提供 Minecraft 的下载、映射、
重映射、反编译、运行配置与 mod 依赖处理。

对外发布的插件 id：

| 插件 id | 实现类 | 用途 |
|---|---|---|
| `top.wcpe.loom` | `net.fabricmc.loom.LoomGradlePlugin` | 主插件（混淆环境） |
| `top.wcpe.loom-no-remap` | `net.fabricmc.loom.LoomNoRemapGradlePlugin` | 非混淆环境 |
| `top.wcpe.loom-remap` | `net.fabricmc.loom.LoomRemapGradlePlugin` | remap 变体 |
| `top.wcpe.loom-companion` | `net.fabricmc.loom.LoomCompanionGradlePlugin` | 复合构建配套 |

**构建要求**：JDK 21+（`build.gradle` 启动即校验）。

## 2. 仓库结构

```
src/main/java/
├── net/fabricmc/loom/          上游 fabric-loom 的主体
│   ├── api/                    对外 API：扩展接口、MappingOption、processor 契约
│   ├── configuration/          配置期：Minecraft/mappings/mod 依赖的解析与产出
│   ├── extension/              LoomGradleExtension 实现
│   ├── pipeline/               执行期任务（RemapMinecraft、GenerateSrgNamed、AT 等）
│   ├── task/                   面向用户的 Gradle 任务与服务
│   ├── spec/                   分层规格与派生数据缓存
│   ├── internal/               内部工具（LoomProjectData 等）
│   └── util/                   通用工具
└── dev/architectury/loom/      architectury 扩展
    ├── forge/                  Forge / legacy Forge 支持（含 mcpconfig）
    ├── neoforge/               NeoForge 支持
    ├── accesstransformer/      AT 规则解析与翻译
    └── mappings/               MappingOption 等映射平台抽象

src/test/groovy/…/test/
├── unit/                       单元测试（快，优先跑）
├── integration/                集成测试（起真 Gradle 构建，慢）
└── benchmark/                  基准

src/test/resources/projects/    集成测试用的消费方夹具工程
scripts/                        队列维护与发布脚本（见第 3 节）
.upstream/                      补丁队列的规范、手册、谱系清单（见第 3 节）
.github/workflows/              CI：test-push.yml（测试）、publish.yml（发布）
```

**架构要点**：`configuration/` 是配置期（Gradle 配置阶段，急切产出产物），
`pipeline/` 是执行期（任务化，可缓存）。近年大量工作是把产物生产从配置期迁到执行期任务，
以支持 configuration cache 与 build cache。改动这两处的边界时要留意缓存键。

## 3. 补丁队列与应用视图（当前约定）

本线的补丁真源已经迁移到 Debian 标准 3.0 (quilt) 的未应用态。这里的 Debian 结构只负责保存和编排源补丁，
本项目仍是 Maven 发布线上的 Gradle 插件项目，不表示仓库在维护 Debian 正式发行包。

```
debian/1.17                         真源分支：标准 3.0 (quilt) 未应用态
  ↓  完整 series 共 151 条：132 条历史 + 15 条本次新增 + 4 条旧源码/测试补录

gbp pq import / export               临时补丁工作分支的导入与导出
  ↓  按 series 逐条自动重放，并保留每条提交的 trailer

dev/1.17-wcpe                       生成的应用视图，不是第二个权威队列
```

- `debian/1.17` 是唯一补丁真源；完整标准 `series` 共 151 条：132 条历史、15 条本次同步新增、4 条旧源码/测试补录。
  旧主线已有 132+4 条；本次只把 15 条按 `series` 顺序应用到旧主线。
- `gbp pq import` 与 `gbp pq export` 只用于临时补丁工作分支；完成修改后把结果导回真源，不要把应用视图当成独立队列维护。
- `dev/1.17-wcpe` 是供构建、测试和 Maven 发布使用的生成视图。它与真源对应同一批补丁，但不是第二个权威来源。
- 原 `patch-queue/1.17` tag 冻结为历史锚点，仅用于阅读历史；不再作为当前队列末端移动。
- CI 与 TestKit 镜像属于仓库元数据，不改变 Debian 补丁真源，也不产生另一套队列。

**判据**：*换上游后这行还需要吗？* 需要 → 补丁；不需要 → CI、文档、脚本等仓库元数据。

### 补丁工作与溯源

在 `debian/1.17` 上用 `gbp pq import` 进入临时工作分支，按逻辑单元修改并提交；完成后用
`gbp pq export` 导回未应用态，审阅补丁和 `series` 后再提交。上游 `.gitignore` 的 `/*`
第 2 行 `/*` 会忽略 `debian/`，打包分支不修改该忽略规则；导出新增补丁后逐个使用
`git add -f -- debian/patches/<文件>`，不要全量 add。新补丁继续保留提交信息末尾的 DEP-3 溯源块和 `Patch-Name`：

```
fix(forge): 修掉 xxx

正文说明为什么改、改动要点。

Origin: vendor, wcpe-loom
Forwarded: no
Patch-Name: <slug>
```

- `Origin`：`vendor, wcpe-loom` / `backport, <上游版本>` / `upstream, <URL>`。
- `Forwarded`：`no`（该反馈上游）/ `not-needed`（本仓库专属）/ `<URL>`（已反馈）。
- `Patch-Name`：`<slug>`（`^[a-z0-9]+(-[a-z0-9]+)*$`，≤ 48 字符，补丁序列内唯一）。发布说明按它做集合差，
  重建应用视图时也能认出同一逻辑补丁；历史补丁不回填，元数据提交不带它。

### 校验、锁定与发版

Maven 分支中的 `.upstream/quilt-1.17.lock` 固化本次源树。校验前先取得打包分支的本地 canonical ref；缺省值是
`refs/remotes/origin/debian/1.17`：

```bash
git fetch origin refs/heads/debian/1.17:refs/remotes/origin/debian/1.17
bash scripts/sync-upstream.sh verify
```

`verify` 是只读校验，不生成第二个队列，也不改写工作区或引用。`verify` 和 `status` 都接受只读参数
`[--view 提交] [--package-ref 打包引用]`。正式流程保持以下顺序：

1. 在补丁真源和锁定源上独立验证，确认应用视图可由 `series` 重现。
2. 让主线只对已验证的同一 SHA 快进，交给 CI 在该 SHA 上验证；不制造 merge 提交。
3. 只发布 `wcpe-v*` tag；上一标签的使用方式和上传策略保持不变。

不得通过强推、merge 或临时修改分支保护来绕过上述流程。旧的 `add-patch`、`add-patch finish`、`rebase`、`pull-queue`、
`setup-mirror` 只属于迁移前的队尾标签流程；新模式下脚本会提示这些命令不再适用。历史提交前缀和历史说明不改写。

发布清单仍在打 tag 前生成并提交：

```bash
bash scripts/gen-release-notes.sh --tag wcpe-v1.17.N
# 提交发布清单并创建 tag 后，由 CI 执行严格校验
bash scripts/gen-release-notes.sh --check wcpe-v1.17.N
```

四条历史旧补录只在历史描述中保留，不计入本版新增补丁；不要把它们重新列为本版新增。

详细规范见 `.upstream/README.md`（操作手册）、`.upstream/PATCH-QUEUE.md`（规范）、
`.upstream/migration-1.17.md`（谱系清单）。

## 4. 构建与测试

**不要一次跑完整 `./gradlew build` 或整套测试**——本仓库集成测试要起真实 Gradle 构建并下载
Minecraft 产物，全量跑耗时数小时。

```bash
# 局部验证：只编解码改动区域
./gradlew compileJava
./gradlew checkstyleMain codenarcMain

# 单元测试（快）
./gradlew test --tests "net.fabricmc.loom.test.unit.*"

# 针对性的集成测试（慢，按需挑）
./gradlew test --tests "net.fabricmc.loom.test.integration.forge.AccessTransformerTest"

# 最终验证
./gradlew build -x test
```

### 测试注意事项

- **集成测试的内层 daemon 会 OOM**：处理完整 Minecraft 产物的用例（MigrateMappings、DataGeneration、
  RemapTaskCache、SplitModDependencyProducer 等）需要在夹具工程的 `gradle.properties` 里显式写
  `org.gradle.jvmargs=-Xmx2G`，否则 CI 上会以 `DaemonStoppedException` 失败而非断言失败。
- **配置缓存相关**：执行期任务里**不能访问 `Project`**。如果在 provider 的 lambda 里回调到
  `getProject()`，`--rerun-tasks` 这类跳过快照的用法会抛 `InvalidUserCodeException`。
  配置期就要把需要的东西取好（例如 `NamedDomainObjectProvider` 是可序列化的惰性句柄）。
- **`git cherry` 在本仓库不可靠**：队列重写会生成新 patch-id，内容已合入的提交仍会被报成未合入。
  判断某修复是否已落地，看实质标志（函数、字段、文件是否存在）。

### 推送前必须本地跑完整静态检查（硬性要求）

**每次 `git push` 之前，必须在本地跑完与 CI 同款的全量静态检查，通过后才能推：**

```bash
./gradlew build check -x test
```

这一条不是建议。教训（run 40638565347）：一个只含测试夹具的新用例有两行断言超了
Groovy 行宽，`build` 作业在 `:spotlessGroovyCheck` 处挂掉，后续整个测试矩阵与发布全部跳过，
白白占掉一轮 CI（本仓库矩阵要 30–40 分钟）。更糟的是发布被静默阻断：
`test-push.yml` 的 publish 步骤要求测试通过，于是那次推送什么都没发出去。

它同时能拦住另一类只有本地才便宜的问题（同一轮之后又补修过一次）：

- **强制项。** 判据与风险见上一段：只改 `scripts/**` 的推送，CI 只跑 `scripts` 作业
  （发布说明引擎回归），不编译、不跑矩阵、不发快照。也就是说**这类改动的静态检查
  完全依赖你在本地跑完这一步**——CI 不会替你兜底。
- `:spotlessGroovyCheck` / `:spotlessJavaCheck`：`./gradlew spotlessApply` 可自动修。
- `:checkstyleMain` / `:checkstyleTest`：**Javadoc 首句必须以 `.` 结尾，不是 `。`**
  （中文注释也适用）；把注解从 `@Input` 改成 `@Internal` 之后容易留下 unused import。
- `:codenarcTest`、`:checkPackageInfos`、`:javadoc`。

只跑 `compileJava` / `compileTestGroovy` 是不够的：它们覆盖不到上面任何一项。
推送前也可以直接对照 `.github/workflows/test-push.yml` 里 `build` 作业的命令行。

**代价对比**：本地一次 `build check -x test` 约 40 秒到 1.5 分钟（增量），而 CI 失败一轮
是 30 分钟起步且会阻断发布。

## 5. 代码规范

- **注释一律用中文**；提交信息用简体中文（标题与正文都是）。
- 缩进用 tab，`checkstyle.xml` 校验：import 分组顺序、声明后紧跟 `if`/`try` 需空一行、
  禁止连续空行、**Javadoc 首句必须以 `.` 结尾**（是英文句点，不是中文句号「。」）等。
- 提交信息格式：`<type>(<scope>): <中文描述>`，type 用英文小写
  （`feat` `fix` `refactor` `docs` `chore` `test` `build` `ci`）。
- 禁止在提交信息里加 AI 签名或作者尾注。

## 6. 已知环境注意事项

- **spotless 的 greclipse 需要从 Eclipse P2 仓库下载格式化器**。`download.eclipse.org` 会周期性
  返回「Temporary shutdown」页面，导致 `build` 失败。`build.gradle` 已把源映射到可达镜像，
  可用 `-Ploom.eclipseP2Mirror=<URL>` 覆盖，传空值回退官方源。
- **不能指望 `--generate-notes`**：它只统计经 PR 合入的提交，而本仓库以直推为主。
  发布说明由 `scripts/gen-release-notes.sh --tag` 按 `Patch-Name` 补丁面生成，清单在发版前提交到
  `.upstream/releases/<tag>.md`，CI 只校验并发布它（见第 3 节「发版纪律」）。

## 7. 临时文件

过程性文件（脚本、笔记、验证工程）一律放进项目根目录的 `.tmp/`（已 gitignore），
不要散落在仓库根目录，也不要提交。
