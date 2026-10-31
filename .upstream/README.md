# 补丁队列使用指南

本文件是 `dev/1.17-wcpe` 这条线的**维护手册**：说明队列怎么组织、什么算补丁什么算元数据、
日常怎么加补丁、怎么同步上游、以及哪些操作会把改动**静默丢掉**。

配套文件：

| 文件 | 作用 |
|---|---|
| `PATCH-QUEUE.md` | 队列**规范**（结构、补丁判定标准、DEP-3 溯源标注、维护纪律） |
| `migration-1.17.md` | **谱系清单**（基底、补丁与来源对照、例外说明、审计方法、已知遗留） |
| `README.md` | 本文件：**操作手册** |
| `releases/` | 每个正式版的**发布清单**（`<tag>.md`，随 tag 提交、CI 校验；见该目录的 README） |
| `../scripts/sync-upstream.sh` | 维护脚本：`status` / `verify` / `add-patch` / `rebase` |

> `scripts/sync-upstream.sh` 没有执行位，示例统一写成 `bash scripts/...`；
> `scripts/gen-release-notes.sh` 有执行位，但同样写 `bash`，免得读者记两套。

## 一、两段结构（最重要的概念）

```
base/architectury-1.17-026ce830        ← 基底（不可变 tag）
  ↓  代码补丁，每个带 Origin: / Forwarded: 溯源块
patch-queue/1.17                       ← 队列末端标记（tag）
  ↓  元数据提交（CI / 文档 / 脚本 / 队列规范）
HEAD = dev/1.17-wcpe（默认分支）
```

> 具体条数以 `scripts/sync-upstream.sh status` 为准：它直接数 `基底..队列末端` 的提交，
> 而本文与 `PATCH-QUEUE.md` 里的数字是写作时的快照，加补丁后不会自动更新。

- **补丁段**：会被**重放到新上游基底**的内容 —— 代码、构建配置、测试。
- **元数据段**：描述**本仓库自身**、重放时没有意义的内容 —— CI、README、队列规范、脚本、CHANGELOG。

**判定规则一句话**：*换上游后这行还需要吗？* 需要 → 补丁；不需要 → 元数据。

> 这与 Debian 的划分同构：`debian/patches/*.patch` + `series` 是补丁段；
> `debian/control`、`changelog`、`rules` 是元数据段（可直接提交）。

## 一之二、发版纪律（最容易踩、后果最持久的一条）

> **一旦打了 `wcpe-v*` tag，那个提交之前的历史就不能再改写。**

补丁队列要求历史线性、补丁可整体重放，所以插补丁必然要重写队列并强推。这没问题——
问题是**重写发生在已发布 tag 之前**时，那个 tag 会永久脱离主线历史（GitHub 上显示
"不在默认分支上"），并连带产生三个后果：

| 后果 | 表现 |
|---|---|
| compare 失真 | `compare/旧版...新版` 退到共同祖先重算，把上一版内容重复算进新版；GitHub 报 `status: diverged` |
| 发版区间错 | `git describe` 按祖先关系找上一版，孤立 tag 会被跳过、直接退到更早的版本，多算一整版内容 |
| 溯源混乱 | 同一个版本号对应两份不同的历史，事后无法确定哪份才是真正发布的代码 |

> 前两条在新机制下只影响 GitHub 网页端的 compare 与 `git describe` 这类**按拓扑**的工具；
> **发布说明已不再受它们影响**（它按补丁面算，不按提交区间算，见流程 C）。
> 第三条（同版本号两份历史）依然成立，所以铁律本身不变。

**三条纪律**：

1. **插补丁、压缩元数据都放在发版之前**。想发的版本攒齐了再整理队列，然后打 tag。
2. **打完 tag 之后只能用普通提交**（元数据直推不受影响），直到下次发版前再一并整理。
3. **万一已经发生**（发版后又重写了队列）——分两层看：
   - **发布说明不受影响**。新机制按提交 trailer `Patch-Name` 的**补丁面**做集合差（见流程 C），
     只认 trailer、不认 SHA 与拓扑，跨分叉也能算准「本版新增」，因此不需要为它补任何锚点。
   - **锚点只对 `--legacy` 重算历史版本有用，且已废弃**。
     [`release-anchors.txt`](release-anchors.txt) 的条目冻结保留，供
     `bash scripts/gen-release-notes.sh --legacy <上一版> <本版>` 重算 1.17.19 及更早版本的说明；
     **不要再为新版本补锚点**——新补丁都带 `Patch-Name`，锚点对它们没有意义。

> 已发生过的实例：`1.17.13` 发版后又为合入 jar processor 哈希修复重写了一次队列，
> 它因此变成孤立 tag；当时（旧机制）若不补锚点，`1.17.14` 的变更清单会退到 `1.17.11` 开始算
> （多算一整版）。锚点补好后，发版区间恢复正确——但 GitHub 网页端的 compare 对这两个版本仍会失真，
> 这是历史改写的既成后果。新机制下同类重写不必再补表：说明由补丁面算出，与端点落在哪条分叉无关。

## 一之三、全景图与四种流程

### 仓库结构

```
┌─────────────────────────────────────────────────────────────────────┐
│  上游  architectury/architectury-loom  1.17                          │
└────────────────────────────┬────────────────────────────────────────┘
                             │ 快照（不可变，换基底 = 换 tag）
                             ▼
        ┌────────────────────────────────────────────┐
        │ base/architectury-1.17-026ce830            │  ← 基底 tag
        └────────────────────┬───────────────────────┘
                             │
        ╔════════════════════▼═══════════════════════╗
        ║   补丁段：N 个提交（每个 = 一个逻辑变更）    ║  会被整体重放到新基底
        ║   线性，禁止 merge                          ║  每个带 DEP-3 溯源块
        ╚════════════════════╤═══════════════════════╝
                             │
        ┌────────────────────▼───────────────────────┐
        │ patch-queue/1.17                           │  ← 队列末端 tag
        └────────────────────┬───────────────────────┘     （可移动，重写后前移）
                             │
        ╔════════════════════▼═══════════════════════╗
        ║   元数据段：CI / 文档 / 脚本 / 队列规范      ║  不参与重放
        ╚════════════════════╤═══════════════════════╝  可直接 push
                             │
        ┌────────────────────▼───────────────────────┐
        │ dev/1.17-wcpe                              │  ← 默认分支 = HEAD
        └────────────────────────────────────────────┘
```

> 各段条数以 `scripts/sync-upstream.sh status` / `verify` 为准。

### 决策：这次改动该走哪条流程

```
这次改的是什么？
├─ 代码 / 构建配置 / 测试           → 流程 A（补丁段，要重写队列）
├─ CI / 文档 / 脚本 / 队列规范       → 流程 B（元数据段，直接 push）
├─ 要给用户发一个正式版             → 流程 C（打 tag）
└─ 上游出了新基底                   → 流程 D（整条队列重放）
```

**一句话判据**：*换上游后这行还需要吗？* 需要 → A；不需要 → B。

### 流程 A：加代码补丁（重写历史）

```bash
bash scripts/sync-upstream.sh add-patch my-feature
#   起点是 patch-queue/1.17，**不是**默认分支 HEAD —— 这点与普通 Git 流程相反
#   …改代码…
git commit          # 一个逻辑变更一个提交，末尾带 Origin: / Forwarded: / Patch-Name:
bash scripts/sync-upstream.sh add-patch finish
#   校验溯源块与 Patch-Name → 把元数据重放到新队列末端之后 → 生成 candidate/ 分支
```

然后**人工三步**（脚本刻意不代劳）：

```bash
git tag -f -a patch-queue/1.17 <新队列末端>
# 临时放开 allow_force_pushes → git push --force → 立即还原保护
bash scripts/sync-upstream.sh verify
```

⚠️ 这一步重写历史，**必须在发版之前做**（见「一之二、发版纪律」）。

### 流程 B：改元数据（不重写）

```bash
# 直接在默认分支改、提交、推送；无需强推、无需动分支保护
git push origin dev/1.17-wcpe
```

### 流程 C：发正式版

```bash
bash scripts/sync-upstream.sh verify          # 先确认队列干净

# 1) 发版前生成发布清单，人工审阅后按普通提交（元数据段）提交
bash scripts/gen-release-notes.sh --tag wcpe-v1.17.N
#   落盘 .upstream/releases/wcpe-v1.17.N.md 并打印一份到终端
git add .upstream/releases/wcpe-v1.17.N.md
git commit -m "docs: 补 1.17.N 的发布说明归档"

# 2) 清单进历史之后再打 tag
git tag -a wcpe-v1.17.N -m "WCPE Loom 1.17.N"
git push origin wcpe-v1.17.N
```

清单**必须在打 tag 之前**提交：CI 不现生成正文，只做 `--check <tag>` 严格校验并发布归档的那份。

推送后 CI 自动完成：取消同提交的开发版构建 → 跑回归门禁（约 40 分钟）→
build + publish 到 Maven → 校验清单并据此建 Release。

**清单缺失或与重算结果不一致 → Release 作业直接失败**（`::error::` + `exit 1`），
不会回退到旧的区间引擎。漏提交的代价是这一版没有 Release（补上清单后重跑作业即可），
而不是发出一份算错的清单。

正文生成的三条要点：

| 要点 | 含义 |
|---|---|
| **补丁面** | 每个补丁提交末尾带 `Patch-Name: <slug>`；说明只认这个 trailer，不认 SHA、不认标题、不认 patch-id |
| **集合差** | 说明 = `patches(本版) - patches(上一版)`；上一版按**版本号顺序**取（`sort -V`），与拓扑无关 |
| **清单随 tag 固化** | 清单在发版前生成、随 tag 一起进历史；`--check` 只做逐字比对，同一 tag 每次重算必须一致 |

一个必须知道的后果：**1.17.19 及更早的补丁没有 `Patch-Name`，也不回填**（回填等于重写已发布历史）。
它们因此永远不出现在「新增补丁」里——那些说明已经在历史 Release 中，符合预期。

### 流程 D：同步上游新基底

```bash
git fetch architectury
bash scripts/sync-upstream.sh rebase <新基底sha> base/architectury-1.17-<新sha>
```

在**临时分支**上把整条队列重放到新基底，冲突逐补丁解决（每个补丁单一逻辑，归因容易）。
完成后还要把元数据重放到新队列之后、更新 `migration-1.17.md` 的基底与溯源归类，
再前移 tag、强推、`verify`。

## 二、与 Debian / 1.15 线的对应

| Debian（gbp / quilt） | 本线 | 1.15 线 |
|---|---|---|
| `debian/patches/` + `series` | `patch-queue/1.17` 之前的补丁段 | 12 个补丁提交 |
| 打包元数据 | 队列末端之后的元数据提交 | 1 个元数据提交 |
| `gbp pq rebase` | `scripts/sync-upstream.sh rebase` | `sync-upstream.sh essential-rebase` |
| `gbp pq start` / `export` | `sync-upstream.sh add-patch` / `add-patch finish` | `sync-upstream.sh pick` |
| 改动未进系列 → 打包失败 | 改动丢在队列末端之后 → **下次同步上游时静默丢失** | 同 |

## 三、日常操作

### 1. 加一个新补丁

```bash
bash scripts/sync-upstream.sh add-patch my-fix    # 1) 建工作分支（起点=队列末端）
#     …改代码…
git commit                                        # 2) 一个逻辑变更一个提交，带溯源块 + Patch-Name
bash scripts/sync-upstream.sh add-patch finish    # 3) 校验溯源块与 Patch-Name + 元数据重放
```

`finish` 会：校验新提交是否都带 `Origin:`（缺了直接拒绝）、`Patch-Name` 是否等于分支名去掉
`add-patch/` 前缀（缺了或与既有 slug 重名同样拒绝）→ 生成候选分支并把元数据提交重放到
新队列末端之后 → 打印校对命令与后续人工步骤。它**不会**代你前移 tag、切换分支或推送。

**溯源块写法**（提交信息末尾）：

```
fix(forge): 修掉 xxx

正文说明为什么改、改动要点。

Origin: vendor, wcpe-loom
Forwarded: no
Patch-Name: legacy-forge-at-translate
```

| 字段 | 取值 |
|---|---|
| `Origin` | `vendor, wcpe-loom`（本项目自研）· `backport, <上游版本>`（从别的代系移植）· `upstream, <提交URL>`（取自上游） |
| `Forwarded` | `no`（该反馈上游但还没）· `not-needed`（本仓库专属）· `<URL>`（已反馈） |
| `Applied-Upstream` | 可选；上游已收录 → 换基底时**直接删掉该补丁** |
| `Patch-Name` | `<slug>`：小写字母/数字/连字符，≤ 48 字符，补丁段内唯一。发布说明按它做集合差，**打完不要再改**；元数据提交不带它 |

### 2. 改文档 / CI / 脚本（元数据）

直接在默认分支上提交、正常 `git push` 即可。**不用强推**，分支保护不受影响，也不需要跑 `verify`。

碎提交完全合规；嫌乱时可在**发版前**压缩一次（见第五节）。

### 3. 校验

```bash
bash scripts/sync-upstream.sh verify    # 只读：线性无 merge、补丁数、溯源块与 Patch-Name、分支基于队列末端
bash scripts/sync-upstream.sh status    # 只读：基底/队列/元数据/远端状态一览
```

### 4. 同步上游

**首次同步前必须先配置上游远端。** 仓库只带 `origin`，而脚本默认的上游名是
`architectury`（可用 `WCPE_UPSTREAM_REMOTE` 覆盖）——不先添加，`git fetch architectury`
会失败，`status` 也会显示「上游远端: architectury -> (未 fetch)」：

```bash
git remote add architectury https://github.com/architectury/architectury-loom.git
# 本线跟随的分支是 dev/1.17（不是名为 architectury 的分支）
git fetch architectury dev/1.17
```

确认是否有新基底：

```bash
# 输出 0 表示上游未更新，无需 rebase
git rev-list --count base/architectury-1.17-026ce830..architectury/dev/1.17
```

换基底：

```bash
bash scripts/sync-upstream.sh rebase <新基底sha> base/architectury-1.17-<新sha>
```

在**临时分支**上重放整条队列；冲突逐补丁解决（每个补丁单一逻辑，归因容易）。完成后还要把
**元数据**重放到新队列之后（脚本会打印命令），再更新 `migration-1.17.md` 的基底与溯源归类。

## 四、绝不要做的事（会把改动静默丢掉）

| 错误做法 | 后果 |
|---|---|
| 把代码改动当普通提交**丢在队列末端之后** | 下次 `rebase` 只重放 `base..patch-queue/1.17` → **你的补丁不会跟上新上游** |
| **amend 队列内的补丁**（`patch-queue/1.17` 之前的提交） | 队列被改写 → 必须前移 tag 并重新证明内容等价；若队列已被 rebase 或他人引用，牵连一片 |
| 已发布版本之后**改写该发布点之前的历史** | 已发布的 `wcpe-v*` tag 会被留在分支历史之外（GitHub 显示"不在默认分支上"）；compare 退到共同祖先重算、同版本号对应两份历史（发布说明已不受影响——它按 `Patch-Name` 补丁面算，与拓扑无关）。**详见「一之二、发版纪律」** |
| 用 `merge` 把上游合进队列 | 规范禁止队列出现 merge 提交（`verify` 会报错），且队列无法线性 rebase |

## 五、改写队列 / 压缩元数据的正确姿势

改写历史（插补丁、压缩元数据、换基底）必然要强推，而默认分支开着
`allow_force_pushes=false`，因此固定三步：

```bash
# 0) 先备份
git tag -a backup/<说明> HEAD -m "改写前备份"

# 1) 临时放开强推（保护配置的其余字段保持不变）
gh api -X PUT "repos/wcpe/wcpe-loom/branches/dev%2F1.17-wcpe/protection" \
  --input .tmp/prot-relax.json      # {"allow_force_pushes":true, ...}

# 2) 强推（务必先捕获退出码再判断，别用管道吃掉它）
git push --force origin dev/1.17-wcpe

# 3) 立即还原保护
gh api -X PUT "repos/wcpe/wcpe-loom/branches/dev%2F1.17-wcpe/protection" \
  --input .tmp/prot-normal.json     # {"allow_force_pushes":false, ...}
```

**硬门槛**：改写后必须 `git diff <改写前> <改写后>` **为空**（或差异仅限你刻意修改的那几个文件）。
空 diff 是客观证据，说明重组无损 —— 达到它可以不重跑测试（内容零变化，行为不可能变）。

**时机建议**：插补丁与压缩元数据都**尽量放在发版前**做；发版之后就尽量只用普通提交，
直到下一次发版前再一并整理。

## 六、命令速查

```bash
bash scripts/sync-upstream.sh status                    # 状态一览（只读）
bash scripts/sync-upstream.sh verify                    # 规范自检（只读）
bash scripts/sync-upstream.sh add-patch <名>            # 开补丁工作分支
bash scripts/sync-upstream.sh add-patch finish          # 校验 + 元数据重放 + 打印后续步骤
bash scripts/sync-upstream.sh rebase <新基底sha> [tag]  # 整条队列重放到新基底（临时分支）

bash scripts/gen-release-notes.sh --tag <tag>           # 生成 .upstream/releases/<tag>.md（发版前）
bash scripts/gen-release-notes.sh --check <tag>         # 严格校验归档清单
bash scripts/gen-release-notes.sh --legacy <prev> <curr>  # 旧区间引擎，仅用于重算历史版本
```

环境变量可覆盖默认边界（换基底/测试用）：`WCPE_BASE_TAG`、`WCPE_QUEUE_TAG`、
`WCPE_DEFAULT_BRANCH`、`WCPE_UPSTREAM_REMOTE`。

发布清单相关（`gen-release-notes.sh`）：`WCPE_BASE_TAG`（基底）、`WCPE_RELEASE_DIR`（默认
`.upstream/releases`）、`WCPE_REPO_SLUG`（生成 compare 链接，默认 `wcpe/wcpe-loom`）。
