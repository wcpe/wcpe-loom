# 历史补丁面命名登记表（`wcpe-v1.17.19` 及更早）

`Patch-Name` 是 2026-10 引入的字段（见 [`PATCH-QUEUE.md`](PATCH-QUEUE.md) §3）。**1.17.19 及更早的
109 个补丁没有它，也不回填**——回填等于重写已发布历史（19 个 `wcpe-v*` tag 会因此全部脱离主线），
而收益只有「计数好看」：这些补丁的说明早已在各自的 Release 里，新机制做集合差时它们两边都不出现，
本来就不会重复。

本表的存在只为解决一个真实缺口：**将来要改某个老补丁时，它需要有个名字**。
没有名字，那次改动就进不了「本版新增补丁」（差集只认 trailer）——这一点已实测：

> 拿一个老补丁改一行、不加 trailer → 发布说明报「本版新增 0 个」（静默漏列）；
> 同样改动顺手加 `Patch-Name` → 正常列出 1 条。

## 规矩

1. 本表**不被任何脚本读取**，不参与发布说明计算，纯档案。
2. 只有当你要**改动**某个老补丁时，才把它当成一次正常的补丁改写：在提交信息末尾加
   `Patch-Name: <本表给的名字>`（slug 一旦用上就不要再改，否则等于换了个补丁）。
3. 表里的 slug 是我按标题语义起的**建议值**；改动某个补丁时如觉得名字不贴切可以换，
   换过之后请顺手把本表对应行更新掉，避免同一补丁在不同时期有两个名字。
4. 顺序 = 补丁段顺序（基底 → `patch-queue/1.17` 末端），与 `git log` 一致。
5. 标题列省略了尾部的 `(#N)` PR 编号（那是仓库早期的合并编号，不属于标题本身）。

## 全表（109 个）

| # | 提交 | 标题 | 建议 slug |
|---|---|---|---|
| 1 | `9cdada20` | feat(cache): 跨 daemon per-key 缓存锁与原子发布 | `cache-daemon-per-key-lock` |
| 2 | `9c5972e8` | fix(forge): 完善无混淆 Forge 运行链 | `forge-unobfuscated-run-chain` |
| 3 | `e028de5a` | fix(cache): 扩展跨 daemon 加锁与原子发布到全部缓存产物 | `cache-daemon-lock-all-artifacts` |
| 4 | `ecf04c83` | fix(build): 复合构建保留全局依赖替换规则并共享元数据缓存 | `composite-global-dep-substitution` |
| 5 | `3bf8fa5d` | fix(cache): AsyncCache 改用平台线程池避免虚拟线程 pinning | `async-cache-platform-threads` |
| 6 | `77e2d159` | fix(mods): 版本校验兼容 WCPE 版本后缀 | `mod-version-wcpe-suffix` |
| 7 | `d9f848e3` | fix(download): 内容寻址下载跳过无谓的强制重下 | `download-content-addressed-skip` |
| 8 | `3ff26b1c` | test(integration): 增加多 Loom 子项目与 Windows 路径回归测试 | `test-multi-loom-windows-path` |
| 9 | `d3580b00` | fix(cache): 缓存服务按 classloader 隔离 | `cache-service-classloader-isolation` |
| 10 | `b4ba51dd` | fix(loom): 重映射任务支持构建缓存 | `remap-task-build-cache` |
| 11 | `5b32fc37` | fix(service): 展开服务实例化异常的根因 | `service-instantiation-cause-unwrap` |
| 12 | `8e9673b3` | fix(migration): 移除 1.17 已重构掉的 RunConfig | `migration-drop-runconfig` |
| 13 | `80df4184` | fix(cache): 补回重放时丢失的补丁内容 | `cache-restore-lost-replay-content` |
| 14 | `eaeb5cb4` | feat(forge): 恢复 legacy Forge（1.8-1.16）支持链 | `legacy-forge-support-chain` |
| 15 | `0434d003` | fix(forge): 修复迁移中丢失的 legacy 支持与 setup 时序 | `legacy-forge-setup-order` |
| 16 | `f5c19d89` | fix(config-cache): 补回 IDE 驱动守卫与执行期 stdin 转发 | `ide-driver-guard-stdin` |
| 17 | `9e1933f0` | feat(ide): 移植 DownloadSourcesHook 与 IDE 源码下载挂接 | `ide-download-sources-hook` |
| 18 | `6b71f4de` | fix(ide): 运行目录相对路径统一使用正斜杠 | `ide-run-dir-forward-slash` |
| 19 | `ca566e37` | test(forge): 移植 McmodInfoTest | `test-mcmodinfo-port` |
| 20 | `9f28af85` | fix(build): 补回 checkstyle 抑制过滤器并修正 javadoc 格式 | `build-checkstyle-suppression-javadoc` |
| 21 | `50f8f97f` | fix(forge): server-only 下不再生成与注册 client-extra | `forge-server-only-client-extra` |
| 22 | `fcb35782` | feat(plugin): 新增 top.wcpe.loom 插件 id 并保留 architectury 别名 | `plugin-wcpe-id-alias` |
| 23 | `8916b5c5` | test(ci): 适配本 CI 环境的两个上游测试 | `test-ci-upstream-two-cases` |
| 24 | `dd14726d` | feat(forge): 支持 1.12.2 的 userdev3 形态 userdev | `forge-userdev3-1-12-2` |
| 25 | `1961fbd7` | fix(forge): mojmap.tsrg2 改为锁保护下的原子写入 | `forge-tsrg2-atomic-write` |
| 26 | `f47a5bd0` | perf(zipfs): 只读路径改用独立文件系统绕开全局锁 | `zipfs-readonly-separate-fs` |
| 27 | `232199b8` | ci: 测试工作流限定分支并排除 tag 推送 | `ci-workflow-branch-tag-filter` |
| 28 | `fd45c885` | fix(forge): forge 系共享缓存的生产与删除补齐跨进程锁 | `forge-shared-cache-cross-process-lock` |
| 29 | `cf9cb8a8` | chore(repo): WCPE 发布体系、队列规范与文档元数据 | `repo-release-system-queue-docs` |
| 30 | `94308751` | docs: 补 add-patch 流程并写全补丁队列操作手册 | `docs-add-patch-manual` |
| 31 | `66dabf1b` | fix(scratch): 验证 add-patch | `scratch-add-patch-verify` |
| 32 | `70c307da` | fix(scripts): add-patch finish 改为可从默认分支执行 | `scripts-finish-from-default-branch` |
| 33 | `960b0d30` | fix(scripts): 修复 add-patch finish 的语法错误 | `scripts-finish-syntax-fix` |
| 34 | `a2fe2e5b` | chore: 移除误加入的测试用临时文件 | `chore-remove-stray-temp-file` |
| 35 | `80f72636` | ci: 为 forge 系列测试作业准备 JDK 21 | `ci-forge-tests-jdk21` |
| 36 | `784c4cac` | chore(repo): 发布体系分离开发/正式命名空间并实现 latest 指针 | `repo-dev-release-namespace-latest` |
| 37 | `fd225dea` | perf(cache): 补齐供给链无锁快路径 & 发布体系改 PR 模式 | `cache-supply-chain-lockfree-pr-mode` |
| 38 | `d7a3a79c` | fix(ci): release.yml 移除空 labels 分组避免吞掉无标签 PR 的变更条目 | `ci-release-empty-labels-group` |
| 39 | `49f1c9a0` | fix(ci): 稳定 TestKit 依赖解析并使 Release 重跑幂等 | `ci-testkit-resolve-release-idempotent` |
| 40 | `44ab9df0` | fix(cache): 锁文件状态污染配置缓存导致偶发全量重算 | `cache-lockfile-config-cache-pollution` |
| 41 | `0fca8b3c` | ci: 同一提交不再重复构建，正式版构建优先 | `ci-dedupe-build-release-priority` |
| 42 | `1b70d6bb` | ci: 纯文档推送不再触发测试工作流；docs: 修正 README 产物坐标 | `ci-skip-docs-push-fix-readme` |
| 43 | `a6cec2ad` | perf(cache): 复用跨项目 mappings 与 mod 元数据解析结果 | `cache-cross-project-mappings-reuse` |
| 44 | `39b4ad57` | ci: Release 说明显式指定基点并补上直推提交 | `ci-release-notes-base-and-direct-push` |
| 45 | `7fd466b4` | fix(build): 修正 Javadoc 首句句号以通过 checkstyle | `build-javadoc-first-sentence-period` |
| 46 | `f91ecbdc` | ci: 正式版构建取消同一提交的开发版构建，不再排队等待 | `ci-release-cancel-dev-build` |
| 47 | `f730ff22` | ci: 发布标题统一为「WCPE Loom <版本号>」 | `ci-release-title-unify` |
| 48 | `0aa6a05a` | docs(readme): 补齐使用方式并把所有代码块改为折叠块 | `docs-readme-usage-collapsible` |
| 49 | `6ffb5a21` | perf(forge): 钉死 Forge 库的无效动态版本，消除全仓库版本列举 | `forge-pin-dynamic-lib-versions` |
| 50 | `507aef83` | feat(cache): 共享缓存默认并发安全，消除多工作树互相删除产物 | `cache-shared-concurrency-safe` |
| 51 | `9bc84c1a` | feat(isolation): 支持 Isolated Projects 并补齐 Forge 兼容链与声明式归档解压 | `isolation-projects-support` |
| 52 | `6f98bdbc` | fix(isolation): 修完跨 classloader 扩展注册这一类问题 | `isolation-cross-classloader-extensions` |
| 53 | `e6eadf81` | fix(isolation): 补齐跨项目数据链路的失效判据与静默降级 | `isolation-cross-project-invalidation` |
| 54 | `8d667079` | fix(cache): 收紧共享产物就绪判据，补上「残留锁只诊断」依赖的安全前提 | `cache-shared-ready-criteria` |
| 55 | `07e3e9ae` | chore(cleanup): 删除已无调用方的 StaticPathWatcher | `cleanup-remove-static-path-watcher` |
| 56 | `d632c73f` | fix(aw,at): 让条目只持有纯值，避免 Gson 结构进入序列化路径 | `aw-at-plain-value-entries` |
| 57 | `d557aadb` | fix(forge): 输入 jar 缺失时不再产出空 sources jar，并让反编译缓存键覆盖注入输入 | `forge-no-empty-sources-jar` |
| 58 | `460975a6` | fix(sources): SourceRemapper 不再静默丢弃 MC classpath，重映射器构造脱离 Project | `sources-keep-mc-classpath` |
| 59 | `ae06d840` | feat(core,spec): 引入执行期 jar 操作的抽象与派生数据缓存 | `exec-jar-ops-abstraction` |
| 60 | `bfcd1c08` | feat(pipeline): 为执行期重建补上处理器契约，并引入 jar 生产任务 | `pipeline-processor-contract-jar-task` |
| 61 | `ec4e70dd` | feat(mc): MC jar 生产由配置期迁至任务 | `mc-jar-produce-task` |
| 62 | `dcfd2172` | feat(mod): mod jar 重映射由配置期迁至任务，并删除 ModProcessor | `mod-jar-remap-task` |
| 63 | `3f85235b` | test: 补齐单元测试 | `test-unit-coverage` |
| 64 | `523fcc3b` | test: 补齐集成测试与夹具，并纳入 CI 矩阵 | `test-integration-fixtures-matrix` |
| 65 | `a3bcdcd5` | fix(mc,at): 修复 Forge 上处理器执行期重建的两处缺陷 | `mc-at-forge-processor-exec-defects` |
| 66 | `0d79ca2a` | test(forge): 冷 gradle home 拷贝时跳过锁文件，修掉批量跑时的偶发失败 | `test-forge-cold-home-lockfile` |
| 67 | `eecc218e` | docs(upstream): 补记本批补丁的溯源归类 | `docs-upstream-provenance` |
| 68 | `9412285e` | fix(mc): 补回 mod 重映射任务的 Minecraft classpath | `mc-restore-remap-classpath` |
| 69 | `15a92e91` | test(pipeline): 补 mod 重映射的覆写传播等价性测试 | `test-remap-overwrite-propagation` |
| 70 | `79e8318a` | feat(mc): vanilla jar 的下载/抽取/合并/拆分迁到执行期任务 | `mc-vanilla-jar-tasks` |
| 71 | `018eb5d1` | fix(mc): 分层映射按坐标认定为 tiny v2，否则投影对真实工程全失效 | `mc-layered-mapping-tinyv2` |
| 72 | `f44857b3` | feat(mc): Forge 的 srg→named 映射生成迁到执行期任务 | `mc-forge-srg-named-task` |
| 73 | `f56b65d0` | perf(mc): innerClassNames 改由重映射任务执行期从输入 jar 现算 | `mc-inner-class-names-on-demand` |
| 74 | `f51df2a6` | feat(forge): patched jar 的最终生产迁到执行期任务 | `forge-patched-jar-task` |
| 75 | `143f93fd` | fix(forge): Forge 反编译的 runtimeJar 接上产出方，不再按裸路径声明输入 | `forge-decompile-runtime-jar-provider` |
| 76 | `59b52a27` | refactor(forge): 外部 Forge 工具调用服务化，脱离配置期求值 | `forge-external-tool-service` |
| 77 | `be3f876a` | refactor(forge): AT / MCP rename / merge-mapping 三处外部调用脱离配置期求值 | `forge-at-mcp-merge-service` |
| 78 | `d6c3c565` | feat(forge): provide() 的补丁链三级产物迁到执行期任务 | `forge-provide-patch-chain-tasks` |
| 79 | `53559e0d` | feat(forge): mappings 迁移迁到执行期任务 | `forge-mappings-task` |
| 80 | `5961d38f` | feat(forge): 解除 Forge 的 vanilla jar 整批回退门，modern Forge 走任务化 | `forge-lift-vanilla-fallback-gate` |
| 81 | `325e6ece` | fix(forge): 迁移器缓存加内容键，堵住跨配置复用导致的陈旧产物 | `forge-migrator-cache-content-key` |
| 82 | `82793d0b` | fix(forge): 修两处本轮引入的回归——配置缓存永久失效与缓存格式不向后兼容 | `forge-fix-migration-regressions` |
| 83 | `8120ca2c` | fix(zipfs): 根治 zipfs 关闭竞态，自愈判据改行为判别并覆盖多形态 | `zipfs-close-race-selfheal` |
| 84 | `a52db050` | test(pipeline): 等价性探针改到执行期发起，修冷缓存下配置期对照失败 | `test-pipeline-equiv-probe-exec` |
| 85 | `85c1356a` | test(benchmark): 修掉 ConfigurationBaselineTest 里传错参数的 contains 调用 | `test-config-baseline-contains-fix` |
| 86 | `a4741ed5` | test: 给两个重量级集成用例的测试工程显式设堆，修 CI 上的 daemon OOM | `test-heavy-cases-heap-size` |
| 87 | `fea3ac16` | fix(sources): genSources 补上 intermediary 产物的依赖，修 DecompileTest 硬失败 | `sources-gensources-intermediary-dep` |
| 88 | `7126c4ac` | fix(annotations): 注解数据的成员名改走映射树，修注解从不生效 | `annotations-member-name-via-mappings` |
| 89 | `48a2a399` | fix(at): AT 规则按目标 jar 的命名空间正确翻译 | `at-translate-by-target-namespace` |
| 90 | `e367d7c2` | feat(forge): 补充改写 MC jar 中对 Forge 成员的引用 | `forge-rewrite-mc-forge-member-refs` |
| 91 | `27073688` | feat(forge): 把 legacy Forge 的 universal jar 重映射到 named | `legacy-forge-universal-remap` |
| 92 | `14090631` | feat(forge): 补全 legacy Forge 缺失的继承方法映射 | `legacy-forge-inherited-method-mappings` |
| 93 | `106861c6` | fix(configuration-cache): 配置期取好重映射源配置，避免执行期访问 Project | `config-cache-remap-source-config` |
| 94 | `7b43c680` | build(spotless): 把 greclipse 的 Eclipse P2 源映射到可达镜像 | `build-spotless-greclipse-mirror` |
| 95 | `6f58ad25` | fix(at): 只在规则与作用对象命名空间不一致时翻译 AT 规则 | `at-conditional-translate` |
| 96 | `59d2104c` | fix(isolation): 让 jar processor 哈希不再随配置顺序漂移 | `isolation-jar-processor-hash-stable` |
| 97 | `8c4c02f6` | fix(cache): JarManifestService 的服务键改用项目路径而非名称 | `cache-manifest-service-key-path` |
| 98 | `6e97971c` | fix(cache): exportLoomProjectData 不再在执行期访问 Project | `cache-export-data-no-project-access` |
| 99 | `687cd182` | fix(forge): 解开 patched jar 生产与 WITH_SRG 登记之间的环 | `forge-break-with-srg-cycle` |
| 100 | `68189ad0` | fix(isolation): 隔离项目下能读到子项目自己的 gradle.properties | `isolation-subproject-gradle-properties` |
| 101 | `a7de7888` | fix(repo): 用独立回退仓库替代已弃用的 artifactUrls | `repo-fallback-repository-artifacturls` |
| 102 | `49e9c532` | fix(cache): 日志透传开关不再参与 forge 产线的缓存键 | `cache-verbose-flag-not-cache-input` |
| 103 | `aaa01d87` | fix(configuration): 去掉并行配置下会死锁的跨项目全局锁 | `configuration-remove-cross-project-lock` |
| 104 | `e50af5d6` | fix(forge): 补回 49e9c532 的 checkstyle 违规 | `forge-restore-checkstyle-fix` |
| 105 | `7f807392` | fix(configuration): 跨项目数据改用 Gradle 官方通道并对接任务图，告警归零 | `configuration-official-channel-taskgraph` |
| 106 | `187f6526` | fix(mods): 版本校验认得出开发线版本号（1.17-SNAPSHOT 不再崩） | `mods-version-dev-snapshot` |
| 107 | `70d49585` | test(integration): 补上跨项目 mixin 映射的端到端用例 | `test-cross-project-mixin-mapping` |
| 108 | `d4665c1a` | feat(pipeline): legacy Forge 补丁链与映射改写迁到执行期任务 | `pipeline-legacy-forge-tasks` |
| 109 | `55f95304` | fix(configuration): 去掉配置期整段串行的跨项目缓存锁 | `configuration-remove-serial-cache-lock` |
