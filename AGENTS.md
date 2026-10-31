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

## 3. 补丁队列（本仓库最重要、也最反直觉的约定）

本仓库**不是**普通 Git 流程。默认分支 `dev/1.17-wcpe` 的历史分三段：

```
base/architectury-1.17-026ce830   基底（不可变 tag，上游快照）
  ↓  补丁段：线性提交，每个 = 一个逻辑变更，带 DEP-3 溯源块
patch-queue/1.17                  队列末端（可移动 tag，重写后需前移）
  ↓  元数据段：CI / 文档 / 脚本 / 队列规范
dev/1.17-wcpe                     默认分支 = HEAD
```

**判据**：*换上游这行还需要吗？* 需要 → 补丁段；不需要 → 元数据段。

### 改代码（补丁段）

```bash
bash scripts/sync-upstream.sh add-patch <名字>   # 起点是 patch-queue/1.17，不是默认分支 HEAD
# …改代码、提交（末尾带溯源块 + Patch-Name）…
git checkout dev/1.17-wcpe
bash scripts/sync-upstream.sh add-patch finish <名字>
```

提交信息末尾必须有溯源块：

```
fix(forge): 修掉 xxx

正文说明为什么改、改动要点。

Origin: vendor, wcpe-loom
Forwarded: no
Patch-Name: <slug>
```

- `Origin`：`vendor, wcpe-loom` / `backport, <上游版本>` / `upstream, <URL>`
- `Forwarded`：`no`（该反馈上游）/ `not-needed`（本仓库专属）/ `<URL>`（已反馈）
- `Patch-Name`：`<slug>`（`^[a-z0-9]+(-[a-z0-9]+)*$`，≤ 48 字符，补丁段内唯一）。发布说明按它做集合差，
  重写队列换了 SHA 也能认出「本版新增」——所以打上去就别再改；历史补丁不回填，元数据提交不带它。

### 改 CI / 文档 / 脚本（元数据段）

直接在 `dev/1.17-wcpe` 提交并 `git push`，无需强推、无需动分支保护。

### 发版纪律（最容易踩）

**一旦打了 `wcpe-v*` tag，那个提交之前的历史就不能再改写。**

插补丁必然要重写队列并强推，但如果重写在已发布 tag 之前，那个 tag 会永久脱离主线历史，
导致 compare 失真、发版区间跳版、同版本号对应两份历史。

- 插补丁、压缩元数据都放在**发版之前**
- 打完 tag 之后只用普通提交，直到下次发版前再整理
- 万一已发生：发布说明不受影响（它按 `Patch-Name` 补丁面算，与 SHA/拓扑无关）；
  锚点表 `.upstream/release-anchors.txt` 已废弃，不必再补

**发布清单随 tag 一起提交**：打 tag 前先 `bash scripts/gen-release-notes.sh --tag wcpe-v1.17.N` 生成
`.upstream/releases/wcpe-v1.17.N.md`，人工审阅后用普通提交（元数据段）提交，再打 tag。CI 用
`--check <tag>` 严格校验，清单缺失或不一致会让 Release 作业失败——它不现生成，也不回退旧区间引擎。
锚点表只对 `--legacy` 重算 1.17.19 及更早版本有用，已冻结。

### 校验与同步上游

```bash
bash scripts/sync-upstream.sh verify     # 只读自检：线性、无 merge、溯源与 Patch-Name 齐备、基于队列末端
bash scripts/sync-upstream.sh status     # 只读：基底/队列/元数据/远端一览
bash scripts/sync-upstream.sh rebase <新基底sha> [tag]   # 整条队列重放到新基底
```

**首次同步上游前必须先配置远端**——仓库只带 `origin`，脚本默认的上游名 `architectury`
需要自己添加，否则 `fetch` 会失败、`status` 会显示「上游远端: architectury -> (未 fetch)」：

```bash
git remote add architectury https://github.com/architectury/architectury-loom.git
git fetch architectury dev/1.17          # 本线跟随的分支是 dev/1.17，不是 architectury
```

> 基底 tag 的注释里记着当时的来源与日期，例如
> `base/architectury-1.17-026ce830` 对应上游 `dev/1.17` 的 `026ce830`。
> 换基底时用 `git tag -l --format='%(contents)' <基底tag>` 回溯来源。

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
