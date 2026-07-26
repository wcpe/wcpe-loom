# Changelog

## 1.16-wcpe-2

### 修复

- **AsyncCache 虚拟线程 pinning 导致 daemon 卡死**：tiny-remapper 的 `FileSystemReference.open()/close()` 使用 `synchronized(openFsMap)` 全局锁，虚拟线程进入 synchronized 块时会 pin 住 carrier 线程。并行配置下多个子项目同时解析 mod jar，大量虚拟线程争抢锁导致 carrier 线程池耗尽，配置线程在 `CompletableFuture.join()` 上永久阻塞。改为平台线程池（有界队列 + CallerRuns 背压），平台线程进入 synchronized 不会 pin carrier。
- **ModConfigurationRemapper 跨子项目重复解析元数据**：多个子项目依赖同一个 mod jar 时，`ArtifactMetadata` 会被每个子项目各解析一次，主线程在 `AsyncCache.join` 上重复阻塞。改为挂在根项目上的共享 `AsyncCache`，缓存键改为 `jar 路径 + mixin remap 类型`（`ArtifactRef` 在不同子项目是不同实例，无法跨项目命中）。

### 增强

- **LoomFilesBaseImpl 支持自定义缓存目录**：通过 `fabric.loom.cache.dir` 系统属性覆盖默认 `~/.gradle/caches/fabric-loom`，实现多项目隔离，避免多项目同时构建时的锁竞争。

### 修复（CI）

- **Checkstyle 违规**：修复上述改动引入的 7 处 Checkstyle 违规（RegexpMultiline 空行、WhitespaceAround record 空体、JavadocStyle/JavadocParagraph 中文 javadoc 改英文以匹配规则）。

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
