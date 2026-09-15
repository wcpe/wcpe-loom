# `.upstream/` 目录说明

本目录保存**队列与谱系元数据**，属于仓库元数据而非补丁（不参与补丁队列）。

| 文件 | 作用 |
|---|---|
| `PATCH-QUEUE.md` | **队列规范**：结构、补丁判定标准、DEP-3 溯源标注、校验与维护纪律 |
| `migration-1.17.md` | **1.17 主线谱系清单**：基底、25 个补丁与来源的对照、例外说明、同步上游的方法、审计方法与已知遗留 |
| `essential-base` | （继承自 1.15 线）essential 基底快照的说明 |

对应的维护脚本：`scripts/sync-upstream.sh`（`status` / `verify` / `rebase`，默认只读）。

## 与 1.15 线的关系

1.15 线的队列元数据在其分支 `1.15-wcpe` 上：`.upstream/wcpe-patches.txt`（12 个补丁的清单）、
`.upstream/PATCH-QUEUE.md`（同一套规范）、`scripts/sync-upstream.sh`（quilt 式逐补丁采集）。
本线沿用同一套规范与纪律，但**队列模型不同**：

- 1.15 线：基底 + 12 个可逐个增删的补丁，同步上游时逐补丁 rebase
- **1.17 线（本线）**：基底 + 一条由 1.15 线改动整体重放后合并而成的**固定序列**（25 个补丁），
  同步上游时对**整条序列** `rebase --onto`

具体差异与理由见 `PATCH-QUEUE.md` 开头的对照表。

## 快速上手

```bash
scripts/sync-upstream.sh status                 # 看当前基底/队列状态
scripts/sync-upstream.sh verify                 # 校验队列是否符合规范
git fetch architectury
scripts/sync-upstream.sh rebase <新基底sha> base/architectury-1.17-<新sha>
```

第三条会在**临时分支**上重放整条队列，不会改动默认分支，也不会推送；冲突解决与切换分支都需人工完成。
