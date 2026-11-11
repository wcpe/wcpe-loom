- **发布点**：`wcpe-v1.17.23`
- **补丁**：本版新增 4 个
- **完整对比**：https://github.com/wcpe/wcpe-loom/compare/wcpe-v1.17.22...wcpe-v1.17.23

## 新增补丁

### 缺陷修复

- **metadata**: 版本清单派生缓存改按内容 SHA-256 建键（`metadata-spec-content-key`）
- **bundle**: 服务端 bundle 元数据缓存校验结构，区分损坏与 legacy（`bundle-metadata-cache-validate`）
- **cache**: mapped provider 就绪判定改用任务投影，不预读尚未生产的产物（`mapped-provider-readiness-projection`）
- **plugin**: 版本提示改用 BuildService 去重，不再写 JVM 系统属性（`version-log-build-service`）
