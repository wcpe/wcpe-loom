# 补丁队列使用指南

本文件是 `dev/1.17-wcpe` 这条线的**维护手册**：说明队列怎么组织、什么算补丁什么算元数据、
日常怎么加补丁、怎么同步上游、以及哪些操作会把改动**静默丢掉**。

配套文件：

| 文件 | 作用 |
|---|---|
| `PATCH-QUEUE.md` | 队列**规范**（结构、补丁判定标准、DEP-3 溯源标注、维护纪律） |
| `migration-1.17.md` | **谱系清单**（基底、22 个补丁与来源对照、例外说明、审计方法、已知遗留） |
| `README.md` | 本文件：**操作手册** |
| `../scripts/sync-upstream.sh` | 维护脚本：`status` / `verify` / `add-patch` / `rebase` |

## 一、两段结构（最重要的概念）

```
base/architectury-1.17-026ce830        ← 基底（不可变 tag）
  ↓  22 个代码补丁，每个带 Origin: / Forwarded: 溯源块
patch-queue/1.17                       ← 队列末端标记（tag）
  ↓  1 个元数据提交
HEAD = dev/1.17-wcpe（默认分支）
```

- **补丁段**：会被**重放到新上游基底**的内容 —— 代码、构建配置、测试。
- **元数据段**：描述**本仓库自身**、重放时没有意义的内容 —— CI、README、队列规范、脚本、CHANGELOG。

**判定规则一句话**：*换上游后这行还需要吗？* 需要 → 补丁；不需要 → 元数据。

> 这与 Debian 的划分同构：`debian/patches/*.patch` + `series` 是补丁段；
> `debian/control`、`changelog`、`rules` 是元数据段（可直接提交）。

## 二、与 Debian / 1.15 线的对应

| Debian（gbp / quilt） | 本线 | 1.15 线 |
|---|---|---|
| `debian/patches/` + `series` | `patch-queue/1.17` 之前的 22 个补丁 | 12 个补丁提交 |
| 打包元数据 | 队列末端之后的元数据提交 | 1 个元数据提交 |
| `gbp pq rebase` | `scripts/sync-upstream.sh rebase` | `sync-upstream.sh essential-rebase` |
| `gbp pq start` / `export` | `sync-upstream.sh add-patch` / `add-patch finish` | `sync-upstream.sh pick` |
| 改动未进系列 → 打包失败 | 改动丢在队列末端之后 → **下次同步上游时静默丢失** | 同 |

## 三、日常操作

### 1. 加一个新补丁

```bash
scripts/sync-upstream.sh add-patch my-fix     # 1) 建工作分支（起点=队列末端）
#     …改代码…
git commit                                    # 2) 一个逻辑变更一个提交，带溯源块
scripts/sync-upstream.sh add-patch finish     # 3) 校验溯源块 + 元数据重放到新队列之后
```

`finish` 会：校验新提交是否都带 `Origin:`（缺了直接拒绝）→ 生成候选分支并把元数据提交重放到
新队列末端之后 → 打印校对命令与后续人工步骤。它**不会**代你前移 tag、切换分支或推送。

**溯源块写法**（提交信息末尾）：

```
fix(forge): 修掉 xxx

正文说明为什么改、改动要点。

Origin: vendor, wcpe-loom
Forwarded: no
```

| 字段 | 取值 |
|---|---|
| `Origin` | `vendor, wcpe-loom`（本项目自研）· `backport, <上游版本>`（从别的代系移植）· `upstream, <提交URL>`（取自上游） |
| `Forwarded` | `no`（该反馈上游但还没）· `not-needed`（本仓库专属）· `<URL>`（已反馈） |
| `Applied-Upstream` | 可选；上游已收录 → 换基底时**直接删掉该补丁** |

### 2. 改文档 / CI / 脚本（元数据）

直接在默认分支上提交、正常 `git push` 即可。**不用强推**，分支保护不受影响，也不需要跑 `verify`。

碎提交完全合规；嫌乱时可在**发版前**压缩一次（见第五节）。

### 3. 校验

```bash
scripts/sync-upstream.sh verify    # 只读：线性无 merge、补丁数、溯源块齐备、分支基于队列末端
scripts/sync-upstream.sh status    # 只读：基底/队列/元数据/远端状态一览
```

### 4. 同步上游

```bash
git fetch architectury
scripts/sync-upstream.sh rebase <新基底sha> base/architectury-1.17-<新sha>
```

在**临时分支**上重放整条队列；冲突逐补丁解决（每个补丁单一逻辑，归因容易）。完成后还要把
**元数据**重放到新队列之后（脚本会打印命令），再更新 `migration-1.17.md` 的基底与溯源归类。

## 四、绝不要做的事（会把改动静默丢掉）

| 错误做法 | 后果 |
|---|---|
| 把代码改动当普通提交**丢在队列末端之后** | 下次 `rebase` 只重放 `base..patch-queue/1.17` → **你的补丁不会跟上新上游** |
| **amend 队列内的补丁**（`patch-queue/1.17` 之前的提交） | 队列被改写 → 必须前移 tag 并重新证明内容等价；若队列已被 rebase 或他人引用，牵连一片 |
| 已发布版本之后**改写该发布点之前的历史** | 已发布的 `wcpe-v*` tag 会被留在分支历史之外（GitHub 显示"不在默认分支上"）；产物本身仍有效，但溯源混乱 |
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
scripts/sync-upstream.sh status                    # 状态一览（只读）
scripts/sync-upstream.sh verify                    # 规范自检（只读）
scripts/sync-upstream.sh add-patch <名>            # 开补丁工作分支
scripts/sync-upstream.sh add-patch finish          # 校验 + 元数据重放 + 打印后续步骤
scripts/sync-upstream.sh rebase <新基底sha> [tag]  # 整条队列重放到新基底（临时分支）
```

环境变量可覆盖默认边界（换基底/测试用）：`WCPE_BASE_TAG`、`WCPE_QUEUE_TAG`、
`WCPE_DEFAULT_BRANCH`、`WCPE_UPSTREAM_REMOTE`。
