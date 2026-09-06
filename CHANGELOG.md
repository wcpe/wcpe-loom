# Changelog

## 1.15-wcpe.2

### 修复

- **按 classloader 隔离 LoomCacheService 与元数据缓存**：服务注册名包含 Loom classloader 身份，不再跨 loader 共享强类型服务；同一 loader 的多个子项目仍复用同一 build service 与 `AsyncCache`
- **isolated-projects 下不再访问根项目模型**：共享元数据缓存从 `rootProject.extra` 收进 build service，修复多 Minecraft 版本构建的配置缓存跨项目访问错误
- **Gradle 9.5 配置缓存下延迟解析 RunConfig**：避免 Forge run task 在配置阶段解析 detached configuration，修复 unsafe resolution 与配置缓存序列化边界
- **增强多 Loom 子项目回归测试**：覆盖同一 Gradle root 下两个直接应用 Loom 的子项目、外部 classloader 服务碰撞，以及 isolated-projects 配置缓存的存储与复用
- **Forge 26.x 无混淆运行链兼容**：不再注入会触发错误 SRG 路由的 naming/remapper service，并在无映射配置时跳过 SRG→named 参数
- **Forge 开发运行时模块图兼容**：避免注入与 terminalconsoleappender 分裂包冲突的 fabric-log4j-util，无混淆环境由 RunConfig 与 IDE 统一保留完整客户端运行库，并将 AccessTransformer 升级到 8.2.17
- **Forge 26.x 预补丁链兼容**：复用 Forge `mcp_config` 的 merge 步骤生成预补丁 JAR，避免缺失 `preProcessJar` 导致输出未生成，并兼容 binarypatcher 产物没有 `MANIFEST.MF` 的情况

## 1.15-wcpe.1

基于 Essential Loom dev/1.15（713489a9）的 WCPE 定制版本。

Essential Loom 完整继承 fabric-loom 与 Forge/NeoForge（含老版本 Forge 1.8-1.16）支持能力；本版本在其之上叠加 WCPE 并发与兼容补丁，发布到 maven.wcpe.top。插件 ID 保持 `gg.essential.loom` 系列不变，通过 `-wcpe` 版本号后缀与仓库限定区分。

### 增强

- **跨 daemon 共享缓存锁**：NIO FileLock 锁定共享缓存写入，支持多进程/多 daemon 并发构建
- **共享缓存改用 per-key 锁与原子发布**：锁下沉到真正写共享缓存的位置，原子替换发布新产物，同一 checkout 的并发构建互不阻塞
- **LoomFilesBaseImpl 支持自定义缓存目录**：`fabric.loom.cache.dir` 系统属性覆盖默认缓存目录，实现多项目隔离

### 修复

- **AbstractRunTask 兼容 Gradle 配置缓存**：配置期立即物化 RunConfig 提取纯数据快照，断开对 Project 的延迟引用；`excludedLibraryPaths` 延迟到执行期解析
- **RunGameTask 配置缓存兼容**：setStandardInput(System.in) 从构造函数移到 exec() 执行期
- **XVFBExistsValueSource**：exists 方法改为接受 ProviderFactory 替代 Project
- **AsyncCache 虚拟线程 pinning 导致 daemon 卡死**：改为平台线程池（有界队列 + CallerRuns 背压）
- **ModConfigurationRemapper 跨子项目重复解析元数据**：改为挂在根项目上的共享 `AsyncCache`
- **复合构建兼容性**：延后 Loom 依赖处理与 Minecraft 处理器解析、在任务图就绪后处理依赖、恢复可变项目模型访问/复制配置的复合构建替换/Minecraft 模型初始化时序/评估期依赖装配
- **原子协调与覆盖 Minecraft 映射产物**：检查与生成放入同一缓存事务，保留旧完整产物并以原子替换发布
- **按游戏版本隔离分层映射缓存**：避免跨版本缓存污染
- **兼容 WCPE Loom 版本后缀**：ArtifactMetadata 识别 WCPE 版本后缀

### CI/CD

- 发布仓库改为 maven.wcpe.top（maven-releases）
- tag 发布正式版（1.15-wcpe.N）+ CHANGELOG 驱动 GitHub Release；push main 发布开发版
- 正式版/开发版分别发布 latest 指针版本
- 新增本地发布版本号覆盖入口（-PloomPublishVersion）
- test-push.yml 改为仅 workflow_dispatch 手动触发

## 1.16-wcpe-2

### 修复

- **AsyncCache 虚拟线程 pinning 导致 daemon 卡死**：tiny-remapper 的 `FileSystemReference.open()/close()` 使用 `synchronized(openFsMap)` 全局锁，虚拟线程进入 synchronized 块时会 pin 住 carrier 线程。并行配置下多个子项目同时解析 mod jar，大量虚拟线程争抢锁导致 carrier 线程池耗尽，配置线程在 `CompletableFuture.join()` 上永久阻塞。改为平台线程池（有界队列 + CallerRuns 背压），平台线程进入 synchronized 不会 pin carrier。
- **ModConfigurationRemapper 跨子项目重复解析元数据**：多个子项目依赖同一个 mod jar 时，`ArtifactMetadata` 会被每个子项目各解析一次，主线程在 `AsyncCache.join` 上重复阻塞。改为挂在根项目上的共享 `AsyncCache`，缓存键改为 `jar 路径 + mixin remap 类型`（`ArtifactRef` 在不同子项目是不同实例，无法跨项目命中）。

### 增强

- **LoomFilesBaseImpl 支持自定义缓存目录**：通过 `fabric.loom.cache.dir` 系统属性覆盖默认 `~/.gradle/caches/fabric-loom`，实现多项目隔离，避免多项目同时构建时的锁竞争。

## 1.16-wcpe-1

基于上游 fabric-loom dev/1.16（v1.16.3）的 WCPE 定制版本。

### 修复

- **AbstractRunTask 兼容 Gradle 配置缓存**：配置期立即物化 RunConfig，提取纯数据快照断开对 Project 的延迟引用；新增 `@Inject` ProviderFactory 和 gradleUserHomeDir Property；canUseArgFile/canPathBeASCIIEncoded 改读 Property 而非执行期 getProject()
- **RunGameTask 配置缓存兼容**：setStandardInput(System.in) 从构造函数移到 exec() 执行期，避免配置期持有不可序列化的 System.in
- **XVFBExistsValueSource**：exists 方法改为接受 ProviderFactory 替代 Project
- **修复上游遗留的 codenarc/spotless CI 失败**：测试代码 closure 移到括号外；排除 GetterMethodCouldBeProperty 误报；spotless groovy 排除测试目录

### CI/CD

- 改造发布流程：push 到 main 发布开发预发布版（1.16-wcpe-dev-latest），打 tag 发布正式版
- 发布仓库改为 maven.wcpe.top（maven-releases）
- 正式版同时发布 latest 指针版本（1.16-wcpe-latest）
- 新增本地发布版本号覆盖入口（-PloomPublishVersion）
