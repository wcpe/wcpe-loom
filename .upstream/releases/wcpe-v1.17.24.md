- **发布点**：`wcpe-v1.17.24`
- **补丁**：本版新增 15 个
- **完整对比**：https://github.com/wcpe/wcpe-loom/compare/wcpe-v1.17.23...wcpe-v1.17.24

## 新增补丁

### 新增功能

- **forge**: 接入指定合包的官方运行产物与源码（`forge-mavenizer-complete-input`）

### 缺陷修复

- **forge**: 用内容指纹校验预补丁产物复用（`forge-prepatch-content-identity`）
- **forge**: 恢复旧版开发包的两项运行库（`legacy-forge-userdev3-runtime-libs`）
- **forge**: 同时保留旧启动器与自定义混入映射（`legacy-forge-dli-mixin-mappings`）
- **forge**: 隔离自定义运行环境的注入键（`forge-dli-run-environment-keys`）
- **forge**: 按归一化描述符补译旧版成员引用（`legacy-forge-member-descriptor-remap`）
- **mcp**: 复用通过官方摘要校验的离线映射（`mcp-offline-input-cache`）
- **forge**: 允许保留源码生成的诊断目录（`forge-mcp-workdir-diagnostics`）
- **forge**: 从安装包补齐二进制链缺失的内部类（`forge-runtime-binpatch-inner-classes`）
- **forge**: 对齐二进制补丁中的匿名类创建点（`forge-inner-class-newsite-realignment`）
- **forge**: 在旧版重映射后桥接改名方法（`forge-renamed-method-bridge`）
- **cache**: 校验共享产物与输入的内容摘要（`artifact-reuse-verify-content`）
- **forge**: 恢复非目标平台的反编译短路（`forge-sources-platform-short-circuit`）

### 测试

- **forge**: 使用同批输入验证访问转换产物（`forge-at-probe-input-lineage`）

### 其他变更

- **forge**: 修正补丁栈的静态检查格式（`forge-patch-format-validation`）
