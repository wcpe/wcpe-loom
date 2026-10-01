# 1.17 主线谱系清单

本文件记录 `dev/1.17-wcpe`（仓库默认分支）相对上游的**全部偏离及其来源**，供日后对照上游、
排查补丁回放缺口、或在同步上游时归因冲突时使用。

本线**不使用** quilt 式补丁队列（那是 1.15 线的维护方式，见 `1.15-wcpe` 分支的
`.upstream/wcpe-patches.txt`），而是**把 1.15 线的改动重放到 architectury 1.17 基底之上**，
并把重放结果整理为一条**可整体 rebase 的补丁序列**。

## 一、基线

| 项 | 值 |
|---|---|
| 直接上游 | [architectury/architectury-loom](https://github.com/architectury/architectury-loom)，`architectury` 分支 |
| 基底提交 | `026ce830`（Fix dev launch issues in unobfuscated Forge (#352)，2026-09-12） |
| 基底锚点 | tag `base/architectury-1.17-026ce830` |
| 队列末端 | tag `patch-queue/1.17` |
| 旁系来源 | SparkUniverse/architectury-loom（Essential Loom `dev/1.15`，提交 `713489a9`）——功能按需移植，**不并入历史** |
| 更上游 | FabricMC/fabric-loom |
| 代码补丁数 | 22（另有 4 个元数据提交，位于队列末端之后） |

两条线是**同一项目相隔约 5 个月的两个历史点**（1.15 线基底 2026-04-15，1.17 线基底 2026-09-12），
因此大量文件存在实现差异属正常演进，**不是**缺口。

## 二、同步上游的方法

1. 锚定新的上游提交（如 tag `base/architectury-1.17-<新sha>`）
2. `git rebase --onto <新基底> 026ce830 patch-queue/1.17`
3. 逐补丁解决冲突：每个补丁只承载**单一逻辑**，冲突归因容易
4. 内容校验：`git diff <新队列> <旧队列>` 应仅体现上游自身变更
5. 前移 tag `patch-queue/1.17`，并更新本文件第一、三节

## 三、补丁序列（22 个代码补丁 + 队列之外的元数据，按基底之后从上到下）

| # | 提交标题 | 来源 |
|---|---|---|
| 1 | feat(cache): 跨 daemon per-key 缓存锁与原子发布 | 补丁 P1 |
| 2 | fix(forge): 完善无混淆 Forge 运行链 | 补丁 P3 |
| 3 | fix(cache): 扩展跨 daemon 加锁与原子发布到全部缓存产物 | 补丁 P4 |
| 4 | fix(build): 复合构建保留全局依赖替换规则并共享元数据缓存 | 补丁 P5 |
| 5 | fix(cache): AsyncCache 改用平台线程池避免虚拟线程 pinning | 补丁 P6 |
| 6 | fix(mods): 版本校验兼容 WCPE 版本后缀 | 补丁 P7 |
| 7 | fix(download): 内容寻址下载跳过无谓的强制重下 | 补丁 P8 |
| 8 | test(integration): 增加多 Loom 子项目与 Windows 路径回归测试 | 补丁 P9 |
| 9 | fix(cache): 缓存服务按 classloader 隔离 | 补丁 P10 |
| 10 | fix(loom): 重映射任务支持构建缓存 | 补丁 P11 |
| 11 | fix(service): 展开服务实例化异常的根因 | 补丁 P12 |
| 12 | fix(migration): 移除 1.17 已重构掉的 RunConfig | 本线有意偏离 |
| 13 | fix(cache): 补回重放时丢失的补丁内容 | 见第四节 |
| 14 | feat(forge): 恢复 legacy Forge（1.8-1.16）支持链 | legacy 移植（9 个过程提交合并） |
| 15 | fix(forge): 修复迁移中丢失的 legacy 支持与 setup 时序 | 迁移缺口修复 |
| 16 | fix(config-cache): 补回 IDE 驱动守卫与执行期 stdin 转发 | 补丁 P2 的内容 |
| 17 | feat(ide): 移植 DownloadSourcesHook 与 IDE 源码下载挂接 | 从 essential 侧移植 |
| 18 | fix(ide): 运行目录相对路径统一使用正斜杠 | 恢复 1.15 行为 |
| 19 | test(forge): 移植 McmodInfoTest | 测试移植 |
| 20 | fix(build): 补回 checkstyle 抑制过滤器并修正 javadoc 格式 | 配置层缺口 |
| 21 | fix(forge): server-only 下不再生成与注册 client-extra | 上游缺陷先行修复 |
| 22 | feat(plugin): 新增 top.wcpe.loom 插件 id 并保留 architectury 别名 | 本线工程化（含 2 个夹具/补丁 id 修正） |
| — | ci(publish): 建立 1.17 线发布体系并升级 actions v5 | **元数据**（队列之外：CI 配置） |
| — | docs: 重写 README 并补充 1.17 主线谱系清单 | **元数据**（队列之外：文档） |
| — | docs: 按重建后的结构更新谱系清单 | **元数据**（队列之外：文档） |
| — | docs: 补齐 1.17 线的队列规范、同步脚本与元数据说明 | **元数据**（队列之外：本次修正计数） |

## 四、例外说明

### P2 无同名提交

P2（`fix(config-cache): 运行与 IDE 任务兼容 Gradle 9 配置缓存`）在本线**没有独立提交**，
其内容分两处承载：第 16 项（`SourceSetHelper.isIdeDrivenBuild()` 守卫与 `RunGameTask`
执行期设置 stdin）与第 2 项（无混淆模式下的库排除短路）。这两处在重放时**整块丢失**，
经审计发现后补回，并同步补回了测试侧改动（`idea.active` 适配与非 IDE 驱动构建的负向用例）。

### 第 13 项：补回重放时丢失的补丁内容

该项合并了 5 个修补提交，分别补回：跨子项目共享元数据缓存、`setupMinecraft` 的跨进程缓存锁、
重映射任务的 Manifest 版本输入登记、源码重映射的跨进程锁、无混淆模式下的库排除短路。
它们**不是新功能**，而是 P1/P4/P11 等在重放时被丢掉的部分。

### 提交合并的说明

重建前该线有 42 个提交（含大量"补回/格式修正/过程调整"等中间提交）。为使队列可整体 rebase、
并让冲突归因落在单一逻辑上，按主题合并为 24 个补丁提交。重建采用**同序重放**，因此不产生冲突；
重建后与重建前的**内容差异为空**（逐字节一致）—— 这是客观验证，不是主观判断。

## 五、本线独有的适配与有意偏离

| 项 | 说明 |
|---|---|
| **插件 id** | 自有域名 `top.wcpe.loom`、`-no-remap`、`-remap`、`-companion`、`-repositories`；**保留 `dev.architectury.loom*` 作为兼容别名**，另保留上游的 `net.fabricmc.fabric-loom-companion`。检测逻辑统一走各插件类的 `isApplied()` |
| **RunConfig API** | 不回归 —— 1.17 上游已移除该 API，本线改用 `RunConfiguration` |
| **运行目录分隔符** | `RunConfigUtils` 相对路径统一用正斜杠，避免 Windows 下产出 `$PROJECT_DIR$/sub\run` 这类混用分隔符 |
| **server-only 的 client-extra** | `MinecraftPatchedProvider.remapJar` 对 `fillClientExtraJar` 与 client-extra 注册加守卫。该调用链在基底上同样无守卫，属**上游缺陷**，本线先行修复 |
| **checkstyle 抑制过滤器** | 补回 1.15 有的 `SuppressionCommentFilter`，否则源码里的 `//CHECKSTYLE:OFF` 全部失效 |
| **发布体系** | 正式版 tag 前缀 `wcpe-v*`（如 `wcpe-v1.17.1` → 版本 `1.17.1`）；日常推送发 `1.17.<运行号>`。**`v1.17.*` 前缀已被上游 release tag 占用，不可复用** |
| **actions 版本** | 工作流统一 actions v5 |

## 六、审计方法（排查"漏移植"用）

重放类迁移的典型缺陷是**回退分支/条件被整块或部分丢掉**，且不会编译报错。本线使用的判据：

1. **补丁面**：对每个补丁，逐条检查"它当初新增的代码行是否仍存在于本线"，缺席者逐一判定
   （真缺口 / 正当适配）。此法发现并修掉了 P2 与 2 处配置缓存缺口。
2. **legacy 面**：以 legacy/FG2 特征词筛选 1.15 独有的差异块，逐项判定。
3. **测试面**：对比 1.15 线的测试文件与用例名，1.15 有而本线没有的逐条判定——此法发现
   `McmodInfoTest`（真缺口）与 `SourceSetHelperTest` 丢失的负向用例。
4. **判据自身的偏差**（务必注意）：两线基底不同，多数差异是正常演进而非缺口；比对时须以正确的
   编码读取（否则中文注释会被误判为差异）；"文件缺失"未必是缺口（可能是重命名或重复副本）。

## 七、已知遗留

| 项 | 状态 |
|---|---|
| `1.15-wcpe` 归档线的发布管线 | 触发分支已由 `main` 改为 `1.15-wcpe`，其代码线已冻结 |
| `dev/1.16-w1` 的"按分支名发布自定义版本"CI 提交 | 仅存于 tag `preserve/1.16-w1-ci`，**尚未并入本线**（本线已有等价的 `RELEASE_VERSION` 入口） |
| 代码层过期 API 告警 | `ForgeExtensionImpl` 等使用 `ForgeExtensionAPI` 中标记待删除的 API，仅告警 |
| 重建前的完整历史 | 保留于 tag `backup/pre-queue-1.17` 与分支 `backup/1.17-pre-queue` |
