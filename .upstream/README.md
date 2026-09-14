# WCPE Loom 维护手册

本目录保存补丁队列的边界、清单与规范。**本文是照着做的操作手册**；
"什么才算一个合规的补丁"见 [PATCH-QUEUE.md](PATCH-QUEUE.md)。

---

## 一页看懂架构

```
┌────────────────────────────────────────────┐
│ 基底  base/essential-1.15-713489a9          │ ← 上游原版，冻住不动
├────────────────────────────────────────────┤
│ P1  P2  P3  ...  P12                        │ ← 我们贴的便利贴
│                                               │   一张只干一件事
├────────────────────────────────────────────┤ ← patch-queue/1.15
│ M1  M2                                        │ ← 封面/目录/说明书
└────────────────────────────────────────────┘   （README/CHANGELOG/CI，不算补丁）
```

三句话：

- **基底** = 从上游拿来的一本原版书，冻住。换基底 = 换一本新书。
- **补丁队列** = 我们在书里贴的便利贴。一张只干一件事，将来换书时好揭好贴。
- **元数据** = 我们的封面、目录、说明书。属于本仓库，不属于补丁。

**为什么要这么做**：上游出新版时，把 12 张便利贴揭下来贴到新书上就行。一张只干一件事，那么"哪张贴不上""哪张上游自己修了可以撕掉"一眼看得出来。这是 Debian 维护几十年的办法。

---

## 目录导航

| 文件 | 作用 | 什么时候动它 |
|---|---|---|
| `essential-base` | 基底提交 SHA | 换基底时 |
| `wcpe-patches.txt` | 补丁清单（顺序 + 溯源 + 重组来源） | 增删补丁后 |
| `PATCH-QUEUE.md` | 补丁判定规范与溯源标注格式 | 规则变化时 |
| `README.md` | 本文：日常操作手册 | 流程变化时 |

---

## 日常操作

### 加一个新补丁

```bash
# 1. 改代码
# 2. 提交，末尾带两行溯源
git add -A
git commit -m "fix(xxx): 做了什么事

Origin: vendor, wcpe-loom
Forwarded: not-needed"
```

- `Origin`：`vendor, wcpe-loom`（自研，永不上游）/ `upstream, <提交URL>` / `backport, <版本>`
- `Forwarded`：`not-needed`（本项目专属）/ `no`（该反馈上游但还没）/ `<URL>`（已反馈）

### 从上游采集一个补丁 ← 想用上游现成的东西时

**场景**：上游已经做了某个功能或修复，你的直接基底（essential）还没跟上。比如要支持 Minecraft 26.2，fabric-loom 早就支持了，essential 没动。

**这跟"换基底"是两回事，别搞混**：

| 你想干什么 | 用什么动作 | 找哪个上游 |
|---|---|---|
| 换整本书（基底升到新版本） | rebase 整个队列 | `essential-loom`（直接上游） |
| 借一个功能（只拿单个提交） | **cherry-pick** | `architectury` / `upstream` |

**为什么不能直接 merge**：merge 会把上游**全部历史**（几千个提交）拉进你的仓库，你那 12 个补丁瞬间被淹没，队列也就废了。cherry-pick 只取**一个提交的改动**，历史保持干净。

#### 四步

```bash
# 1. 看 —— 上游有什么是我没有的
bash scripts/sync-upstream.sh candidates
#    输出分两段：FabricMC 候选、Architectury 候选
#    只列「你没包含、且与现有补丁不等价」的提交

# 2. 挑 —— 把选中的那一个复制过来
bash scripts/sync-upstream.sh pick f6682efb

# 3. 标 —— 写清它从哪来（脚本目前不代劳，要手工加）
git commit --amend
#    在提交信息末尾加：
#    Origin: backport, fabric-loom dev/1.16@f6682efb
#    Forwarded: not-needed

# 4. 查 —— 确认队列没走样
bash scripts/sync-upstream.sh verify
```

**别忘了第 4 步之前的这件事**：新补丁必须落在**队列内部**，所以要把它纳入边界标记：

```bash
git tag -d patch-queue/1.15
git tag -a patch-queue/1.15 -m "1.15 代系补丁队列末端"
```

否则 `verify` 第 3 项会报"末端之后仍有 src/ 改动"。

#### 三个 remote 分别是谁

| 脚本里的名字 | 实际仓库 | 什么时候用它 |
|---|---|---|
| `upstream` | FabricMC/fabric-loom | 找官方实现（新版本支持通常在这） |
| `architectury` | architectury/architectury-loom | 找 Forge / NeoForge 相关实现 |
| `essential-loom` | SparkUniverse/architectury-loom | 换基底时的直接上游 |

#### 怎么从一长串候选里挑

脚本只负责**列出来**，挑哪个是人的判断。三个判断点：

1. **它解决的是不是你的问题** —— 要支持 26.2，就找标题里带 `26.2` 的
2. **它和你的代码冲不冲突** —— 先干跑一次试试：
   ```bash
   git show <sha> --format="" | git apply --check
   ```
3. **它是否依赖别的提交** —— 有些改动要前置补丁，一起拿或按顺序拿

#### Origin 写哪种

| 情况 | 写法 |
|---|---|
| 同代系原样采集 | `Origin: upstream, <提交URL>` |
| 从更新的代系往回搬（backport） | `Origin: backport, fabric-loom dev/1.16@<sha>` |
| 采集后自己又改过 | 用上面的值，并在正文说明改了什么 |

`backport` 意味着"新基底可能已经自带了"——将来换基底时要重点检查这个补丁是否还需要保留。

### 改一个已有的补丁 ← 最常遇到

**规则：如果撤掉原补丁后，你这次的修改就没意义了，那它属于原补丁，改写它，不要新建。**

```bash
# 先找到目标补丁的 sha
git log --oneline base/essential-1.15-713489a9..patch-queue/1.15

# 1. 改代码
# 2. 标记"这是给 P1 的修正"
git add -A
git commit --fixup=<P1 的 sha>

# 3. 揉进去（GIT_SEQUENCE_EDITOR=: 让它不弹编辑器）
GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash base/essential-1.15-713489a9
```

揉完之后，P1 还是那一张便利贴，历史里不会多出"修复我自己"的垃圾提交。

**省事做法**：平时不管，正常提交；发版前集中整理一次。流程不用天天当枷锁。

### 判断表

| 你的修改 | 处理 |
|---|---|
| P1 有 bug，加锁范围写错 | 改写 P1 |
| P1 只覆盖了 A 路径，要扩到 B 路径 | 改写 P1（同一件事没做全） |
| P1 的日志文案不好看 | 改写 P1 |
| 给 P1 的功能加一个新的指标上报（新需求） | 新建补丁 |
| P1 没问题，但另一个模块也要加锁 | 新建补丁 |

### 拆分 / 合并补丁

```bash
# 合并相邻的两个补丁
GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash base/essential-1.15-713489a9
# 在待办列表里把后一个的 pick 改成 squash
```

拆分需要把改动按文件或按 hunk 分到不同提交，用 `git reset HEAD~1` 退回来后分批 `git add` 再提交。

---

## 协作：别人的贡献怎么进来

**main 是唯一主线，不接受直接推送。** 其他人在自己的 fork 或分支上开发，提 PR，由维护者审查后并入补丁队列。

### 分支角色

| 分支 | 谁可以写 | 作用 |
|---|---|---|
| `main` | 仅维护者 | 唯一主线，补丁队列在这里 |
| 贡献者的 fork / 分支 | 各自 | 提 PR 用，合完即可删除 |
| `dev/1.15-wcpe` | **已冻结** | 重构前的旧历史，仅作存档，不要往上提交 |

### 分支保护规则

`main` 已启用保护，非维护者：

- 不能直接 push（必须走 PR）
- 不能 force push
- 不能删除 `main`
- PR 需要 1 个 approval

维护者不受这些限制（`enforce_admins: false`）——因为**改写补丁必须 force push**，这条保护对维护者是放行的，只拦外部。

### 合并 PR 的正确方式

GitHub 的三个按钮里只有一个能用：

| 按钮 | 能用吗 | 原因 |
|---|---|---|
| Create a merge commit | ❌ | 会在队列里留下 merge 提交，`verify` 第 2 项直接失败 |
| Squash and merge | ⚠️ | 队列保持线性，但贡献者的提交被压成一条，溯源信息丢失 |
| **Rebase and merge** | ✅ | 保留提交与溯源字段，队列保持线性 |

### 对贡献者的要求

提交信息必须按补丁规范书写，否则没法并入队列：

```
<type>(<scope>): <中文描述>

<为什么改>

Origin: vendor, wcpe-loom
Forwarded: not-needed
```

`Origin` 取值见 [PATCH-QUEUE.md](PATCH-QUEUE.md#3-溯源标注)。不符合规范的 PR，维护者需要先整理成合规补丁再并入，或者直接要求对方改写。

### 两个不用担心的地方

- **从外部分支提 PR 不会触发 force push**：只要贡献者的分支基于最新 `main`，rebase 合并不改写 `main` 的已有历史。
- **PR 合进 main 会触发 CI**：推代码 → 发测试版指针；打 tag → 发正式版。这是预期行为。

---

## 已发布的版本能不能改？

**能改，tag 是锚点。**

```
v1.15-wcpe.5  ──→ 旧的 P1（历史存档，永远取得回来）
                    │
v1.15-wcpe.6  ──→ 改好的 P1 ──→ P2 ──→ ...
```

- 已发布的 tag 不动，谁要 `wcpe.5` 的源码都取得到
- 改写 P1 只影响**下一个版本**的内容
- `maven.wcpe.top` 上已发布的制品不受任何影响

**副作用**：改写 P1 会让它后面所有补丁的 sha 变化（rebase 的连锁反应）。如果已经推到远端，需要 `--force-with-lease` 强推。这是正常的。

---

## 发版

```bash
# 1. 更新 CHANGELOG.md，写清楚这版改了什么
# 2. 校验队列没走样
bash scripts/sync-upstream.sh verify
# 3. 打 tag 并推送（CI 看到 tag 自动构建 + 发布）
git tag -a v1.15-wcpe.6 -m "发布 1.15-wcpe.6"
git push origin v1.15-wcpe.6
```

**版本号连续递增**，不要重置。重构没有改变代码内容，所以 `wcpe.5 → wcpe.6` 是正常演进。

---

## 上游更新了怎么办（换基底）

```bash
bash scripts/sync-upstream.sh essential-rebase
```

它会：揭下所有便利贴 → 贴到新基底上 → 输出两份报告：

1. `git cherry` 的结果：标 `-` 的补丁说明**上游自己已经修了**，可以直接删掉
2. `git range-diff` 的结果：告诉你重放前后哪些补丁内容变了，逐项核对

换完后更新 `essential-base` 和 `wcpe-patches.txt`。

---

## 校验

```bash
bash scripts/sync-upstream.sh verify        # 默认命令，可直接跑
```

五项检查：

1. 基底 tag 存在，且是 HEAD 的祖先
2. 基底到队列末端之间没有 merge 提交
3. 队列末端之后不含 `src/`、`gradle/` 改动（元数据必须隔离在末端之后）
4. 每个补丁都带 `Origin` 与 `Forwarded` 字段
5. 提交类型（`feat`/`fix`/...）合规

---

## 常用速查

```bash
# 看队列长什么样
git log --oneline base/essential-1.15-713489a9..patch-queue/1.15

# 看元数据部分
git log --oneline patch-queue/1.15..HEAD

# 看某个补丁改了什么
git show <sha>

# 看某个补丁碰了哪些文件
git show --stat <sha>

# 上游有什么东西还没采集
bash scripts/sync-upstream.sh candidates

# 采集一个上游提交
bash scripts/sync-upstream.sh pick <sha>

# 试一下某个上游提交能否干净应用（不改工作区）
git show <sha> --format="" | git apply --check

# 队列有没有走样
bash scripts/sync-upstream.sh verify

# 当前基底/队列状态
bash scripts/sync-upstream.sh status
```
