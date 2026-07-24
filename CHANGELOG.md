# Changelog

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
