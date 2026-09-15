# 1.17 主线谱系清单

本文件记录 `dev/1.17-wcpe`（仓库默认分支）相对上游的**全部偏离及其来源**，供日后对照上游、
排查补丁回放缺口、或判断某处改动是"有意偏离"还是"漏移植"时使用。

本线**不使用** quilt 式补丁队列（那是 1.15 线的维护方式，见 `1.15-wcpe` 分支的
`.upstream/wcpe-patches.txt`）。本线是**把 1.15 线的改动重放到 architectury 1.17 基底之上**。

## 一、基线

| 项 | 值 |
|---|---|
| 直接上游 | [architectury/architectury-loom](https://github.com/architectury/architectury-loom)，`architectury` 分支 |
| 基底提交 | `026ce830`（Fix dev launch issues in unobfuscated Forge (#352)，2026-09-12） |
| 旁系来源 | SparkUniverse/architectury-loom（Essential Loom `dev/1.15`，提交 `713489a9`）——其功能按需移植，**不并入历史** |
| 更上游 | FabricMC/fabric-loom |
| 相对上游提交数 | 41（截至本文件撰写时） |

两条线是**同一项目相隔约 5 个月的两个历史点**（1.15 线基底 2026-04-15，1.17 线基底 2026-09-12），
因此大量文件存在实现差异属正常演进，**不是**缺口。

## 二、WCPE 补丁在本线的落点

1.15 线共 12 个补丁，在本线逐一重放（重放后 SHA 变化，故下表以提交标题对照）。

| 补丁 | 标题 | 本线承载提交 | 备注 |
|---|---|---|---|
| P1 | feat(cache): 跨 daemon per-key 缓存锁与原子发布 | `82cde5de` | 直接重放 |
| **P2** | **fix(config-cache): 运行与 IDE 任务兼容 Gradle 9 配置缓存** | **无同名提交** | **见下方例外说明** |
| P3 | fix(forge): 完善无混淆 Forge 运行链 | `1eeb5108` | 直接重放；另见 `6463c500` |
| P4 | fix(cache): 扩展跨 daemon 加锁与原子发布到全部缓存产物 | `6a3725c6` | 直接重放 |
| P5 | fix(build): 复合构建保留全局依赖替换规则并共享元数据缓存 | `31d936f0` | 直接重放 |
| P6 | fix(cache): AsyncCache 改用平台线程池避免虚拟线程 pinning | `32c3fb0a` | 直接重放 |
| P7 | fix(mods): 版本校验兼容 WCPE 版本后缀 | `2127b6b0` | 直接重放 |
| P8 | fix(download): 内容寻址下载跳过无谓的强制重下 | `8144c724` | 直接重放 |
| P9 | test(integration): 增加多 Loom 子项目与 Windows 路径回归测试 | `6a35b7d3` | 直接重放 |
| P10 | fix(cache): 缓存服务按 classloader 隔离 | `d6059229` | 直接重放 |
| P11 | fix(loom): 重映射任务支持构建缓存 | `b7f9b922` | 直接重放；另见 `c06b4036` |
| P12 | fix(service): 展开服务实例化异常的根因 | `5bc6b432` | 直接重放 |

## 三、例外说明

### P2 无同名提交（其内容被拆到别处）

P2 的改动在本线**没有独立提交**，其内容分两处承载：

- `7fa9b127` **fix(config-cache): 补回 IDE 驱动守卫与执行期 stdin 转发** ——
  `SourceSetHelper.isIdeDrivenBuild()` 守卫与 `RunGameTask` 执行期设置 stdin。
  这两处在重放时**整块丢失**，致使 `.idea/misc.xml` 沦为配置缓存输入、且配置期持有 `System.in`。
  经审计判据（对比 1.15 基线的"补丁新增行是否仍存在"）发现后补回，并同步补回了测试侧改动
  （`idea.active` 适配与非 IDE 驱动构建的负向用例）。
- `6463c500` **fix(run): 补回无混淆模式下的库排除短路** —— 属无混淆运行链，与 P3 亦有交叉。

### 回放时丢失、后来补回的补丁内容

以下提交**不是**新增功能，而是补回 P1–P12 在重放时被丢掉的部分：

| 提交 | 补回内容 |
|---|---|
| `24158e86` | 跨子项目共享元数据缓存 |
| `b88f5a28` | setupMinecraft 的跨进程缓存锁 |
| `c06b4036` | 重映射任务的 Manifest 版本输入登记 |
| `0617ea5f` | 源码重映射的跨进程锁 |
| `6463c500` | 无混淆模式下的库排除短路 |
| `7fa9b127` | P2 的守卫与 stdin 时序（见上） |

## 四、legacy Forge（1.8–1.16）支持移植

architectury 1.17 上游已移除 legacy Forge 链路，本线按 1.15 线重新接回：

| 提交 | 内容 |
|---|---|
| `0152616c` | legacy 支持文件就位与父类可见性开放 |
| `0935deb4` | 适配 `MinecraftLegacyPatchedProvider` 到 1.17 |
| `4cba8c62` | 恢复 FG2 支持链 |
| `26e1f44c` | 修正 FG2 恢复引入的两处 checkstyle 违规 |
| `05dbde88` | 修复阻塞 build 的测试与格式问题 |
| `fe506ced` | 接上 legacy Forge 的 patched provider 分派 |
| `4145483c` | 修正 legacy access transform 的输入输出路径 |
| `e23c8d36` | 补回 pack200 与 legacy binpatches 支持 |
| `b710e037` | 补回迁移时丢失的 legacy 支持并修正 setup 时序 |
| `383b677f` | 补充 legacy Forge 端到端测试与夹具 |

### 迁移缺口修复（经审计逐项发现，非功能新增）

| 提交 | 修复内容 |
|---|---|
| `b710e037` | `SrgProvider` 的 FG2 `joined.srg` 回退、`McpConfigProvider` 的 legacy 内联 MCP 配置、`ModMetadataFiles` 的 mcmod.info 注册、`FabricModJsonFactory` 的 mcmod.info 侦测、`SingleJarDecompileConfiguration`/`ForgeMigratedMappingConfiguration` 的 legacy 判定、`ForgeLibrariesProvider` 的 legacy 排除、`MappingConfiguration` 的 legacy 映射特例、`LocalMavenHelper` 的 .zip 接受条件、`CompileConfiguration` 的锁位置与 provider 注册顺序；并新增 `GeneratedIntermediateMappingsProvider`（legacy 下现场生成 dummy intermediary，使 legacy 版本无需上游 intermediary 即可解析）与 `LWJGL2UpgradeLibraryProcessor`，补回 `LoggerFilter.withSystemOutAndErrSuppressed` 与 `LoomRepositoryPlugin` 被删的 6 行 |
| `383b677f` | 补充 legacy Forge 端到端测试与夹具（1.12.2 + Forge 14.23.5.2847 全链路） |
| `a2e752c4` | 移植 `DownloadSourcesHook` 与 IDE 源码下载挂接（基底本无此功能，属有意从 essential 侧移植） |
| `1b139d74` | 移植 `McmodInfoTest`（1.15 有、基底与迁移都没有） |

## 五、本线独有的适配与有意偏离

| 项 | 说明 |
|---|---|
| **插件 id** | 改用自有域名 `top.wcpe.loom`、`-no-remap`、`-remap`、`-companion`、`-repositories`（提交 `c5f792d9`）；**保留 `dev.architectury.loom*` 作为兼容别名**，另保留上游的 `net.fabricmc.fabric-loom-companion`。检测逻辑统一走各插件类的 `isApplied()`，避免"插件装了但 loom 认不出" |
| **RunConfig API** | 不回归（提交 `b92725b8`）——1.17 上游已移除该 API，本线改用 `RunConfiguration` |
| **运行目录分隔符** | `RunConfigUtils` 相对路径统一用正斜杠（提交 `f1ec8c7c`）——恢复 1.15 行为，避免 Windows 下产出 `$PROJECT_DIR$/sub\run` 这类混用分隔符 |
| **server-only 的 client-extra** | `MinecraftPatchedProvider.remapJar` 对 `fillClientExtraJar` 与 client-extra 注册加守卫（提交 `6d514d2c`）。该调用链在基底上同样无守卫，属**上游缺陷**，本线先行修复 |
| **checkstyle 抑制过滤器** | 补回 1.15 有的 `SuppressionCommentFilter`（提交 `eaf91288`），否则源码里的 `//CHECKSTYLE:OFF` 全部失效 |
| **发布体系** | 正式版 tag 前缀 `wcpe-v*`（如 `wcpe-v1.17.1` → 版本 `1.17.1`）；日常推送发 `1.17.<运行号>`。**注意**：`v1.17.*` 前缀已被上游 release tag 占用，不可复用（提交 `e3dfc367`、`e2994e42`） |
| **actions 版本** | 工作流统一 actions v5（提交 `9aad5615`、`1cf194f8`） |

## 六、审计方法（排查"漏移植"用）

重放类迁移的典型缺陷是**回退分支/条件被整块或部分丢掉**，且不会编译报错。本线使用的判据：

1. **补丁面**：对每个补丁，逐条检查"它当初新增的代码行是否仍存在于本线"，缺席者逐一判定
   （真缺口 / 正当适配）。此法发现并修掉了 P2 与 2 处配置缓存缺口。
2. **legacy 面**：以 legacy/FG2 特征词筛选 1.15 独有的差异块，逐项判定。
3. **测试面**：对比 1.15 线的测试文件与用例名，1.15 有而本线没有的逐条判定——此法发现
   `McmodInfoTest`（真缺口）与 `SourceSetHelperTest` 丢失的负向用例。
4. **注意判据自身的偏差**：两线基底不同，多数差异是正常演进而非缺口；比对时须以正确的
   编码读取（否则中文注释会被误判为差异），且"文件缺失"未必是缺口（可能是重命名或重复副本）。

## 七、已知遗留

| 项 | 状态 |
|---|---|
| `1.15-wcpe` 归档线的发布管线 | 触发分支已由 `main` 改为 `1.15-wcpe`（提交 `d4635af1`），其代码线已冻结 |
| `dev/1.16-w1` 的"按分支名发布自定义版本"CI 提交 | 仅存于 tag `preserve/1.16-w1-ci`，**尚未并入本线**（本线已有等价的 `RELEASE_VERSION` 入口） |
| 代码层过期 API 告警 | `ForgeExtensionImpl` 等使用 `ForgeExtensionAPI` 中标记待删除的 API，仅告警 |
| 迁移缺口审计的宽清单 | 尚有约 80 项未逐项判定，多为两线正常演进，非阻塞 |
