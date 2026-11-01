- **发布点**：`wcpe-v1.17.20`
- **补丁**：本版新增 17 个
- **完整对比**：https://github.com/wcpe/wcpe-loom/compare/wcpe-v1.17.19...wcpe-v1.17.20

## 新增补丁

### 缺陷修复

- **mods**: 支持一个 mod 声明多个 class tweaker（`class-tweakers`）
- **forge**: genForgePatchedSources 支持构建缓存与配置缓存（`forge-patched-sources-cache`）
- **cache**: 缓存条目锁支持同线程重入（`lock-reentrant`）
- **download**: 下载锁改为「判活 + 接管」，不再误伤并发与崩溃残留（`assets-download-lock`）
- **assets**: 给共享 assets 目录的写入加跨进程锁（`assets-lock`）
- **test**: 修掉两个测试文件的 codenarc 违规（`codenarc-fix`）
- **forge**: 字段与方法分开建表，避免字段引用被改成方法名（`forge-field-method-tables`）
- **forge**: 解析继承字段，字段引用不再停在官方名（`forge-inherited-field-mapping`）
- **repo**: 回退仓库改回启用 Gradle Module Metadata（`repo-fallback-gradle-metadata`）

### 性能与并发

- **cache**: 按产物批次分片 Minecraft 共享缓存锁（`lock-shard`）
- **configuration**: extractNatives 的 natives 配置改为惰性解析（`eager-resolution`）
- **processor**: SpecContext 的 modDependencies 改为惰性求值（`config-remap-spec`）
- **cache**: prune 改用一次性排序，去掉 O(n²) 的逐条插入（`decompile-cache-opt`）
- **cache**: 全命中时不再复制整包 classes 进临时 jar（`decompile-cache-opt-part2`）
- **pipeline**: 合并/拆分 MC jar 前先判共享产物能否复用（`reuse-merge-split`）
- **forge**: MCP 链开工前先判 patched jar 能否复用（`reuse-forgeprepatch`）

### 测试

- **mods**: 补多 class tweaker 的端到端用例（`class-tweakers-e2e`）
