# WCPE Loom

[![CI 发布状态](https://github.com/wcpe/fabric-loom/actions/workflows/publish.yml/badge.svg?branch=dev/1.15-wcpe)](https://github.com/wcpe/fabric-loom/actions/workflows/publish.yml)
[![最新正式版](https://img.shields.io/badge/正式版-1.15--wcpe.2-blue)](https://github.com/wcpe/fabric-loom/releases/tag/v1.15-wcpe.2)
[![Maven 仓库](https://img.shields.io/badge/Maven-maven.wcpe.top-orange)](https://maven.wcpe.top/repository/maven-releases/gg/essential/architectury-loom/)
[![License](https://img.shields.io/badge/License-MIT-green)](./LICENSE)

**WCPE Loom** 是 [Essential Loom](https://github.com/SparkUniverse/architectury-loom)（dev/1.15 分支）的定制版本，在保留其完整 **Fabric + Forge/NeoForge（含老版本 Forge 1.8-1.16）** 能力的基础上，叠加了 WCPE 的并发缓存、配置缓存兼容与复合构建修复补丁，发布到自建 Maven 仓库供下游使用。

## 仓库关系

本项目采用 **Debian quilt 式补丁队列**维护：

```
wcpe/fabric-loom (dev/1.15-wcpe，默认分支)
├── WCPE 定制补丁（13 个提交）
│     缓存锁与原子发布 / 配置缓存兼容 / 复合构建修复 / 映射缓存隔离 …
└── 基底：Essential Loom dev/1.15（713489a9）
      └── 上游继承链：SparkUniverse/architectury-loom
                       ← architectury/architectury-loom
                       ← FabricMC/fabric-loom（原始上游）
```

- **直接基底**：[SparkUniverse/architectury-loom](https://github.com/SparkUniverse/architectury-loom)（Essential Loom，dev/1.15，提交 `713489a9`）
- **上游继承**：[architectury/architectury-loom](https://github.com/architectury/architectury-loom) ← [FabricMC/fabric-loom](https://github.com/FabricMC/fabric-loom)
- 同步策略：基底更新用 rebase 重放补丁队列；FabricMC/Architectury 的更新按需 cherry-pick 采集（见 `scripts/sync-upstream.sh`）

## 使用方式

在 `settings.gradle` 中添加 WCPE Maven 仓库：

```groovy
pluginManagement {
    repositories {
        maven {
            name = 'WCPE'
            url = 'https://maven.wcpe.top/repository/maven-releases/'
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
```

在 `build.gradle` 中应用插件（插件 ID 沿用 `gg.essential.loom` 系列）：

```groovy
plugins {
    id 'gg.essential.loom' version '1.15-wcpe.1'
}
```

可选的 5 个插件入口：

| 插件 ID | 用途 |
|---|---|
| `gg.essential.loom` | 标准插件（Fabric + Forge/NeoForge 全支持） |
| `gg.essential.loom-no-remap` | 不做重映射的轻量变体（未混淆 Minecraft） |
| `gg.essential.loom-remap` | 重映射变体 |
| `gg.essential.loom-companion` | 复合构建伴侣插件 |
| `gg.essential.loom-repositories` | 仓库配置插件 |

## 版本号说明

| 版本形式 | 含义 | 稳定性 |
|---|---|---|
| `1.15-wcpe.1`、`1.15-wcpe.2` … | 正式版（tag `v1.15-wcpe.N` 触发发布） | 不可变，推荐使用 |
| `1.15-wcpe-latest` | 指向最新正式版的指针版本 | 随发布更新 |
| `1.15-wcpe-dev-latest` | 指向最新开发版（推送代码触发 CI 自动发布，用于测试发布通道） | 滚动更新，勿锁定 |

- 版本号中的 `1.15` 对应基底 Essential Loom 的 `dev/1.15` 分支代系；`-wcpe` 后缀为定制版标识，与官方版本空间完全隔离
- 上游 Essential Loom 的 `1.15.50` 中的 `50` 是 CI 运行编号（非语义化补丁位）；本项目改用 tag 驱动的语义化版本号
- 本地调试发布：`./gradlew publishToMavenLocal -PloomPublishVersion=<版本号>`

## 构建与开发

```bash
git clone https://github.com/wcpe/fabric-loom.git
cd fabric-loom
./gradlew build -x test     # 完整构建（跳过测试）
./gradlew test --tests "net.fabricmc.loom.test.unit.cache.*"   # 缓存并发测试
```

上游同步：

```bash
./scripts/sync-upstream.sh status        # 查看基底与补丁队列状态
./scripts/sync-upstream.sh candidates    # 列出上游候选提交
./scripts/sync-upstream.sh essential-rebase   # Essential 换基底重放补丁队列
```

---

以下为原 README（上游 Essential Loom / Architectury Loom），保留作参考。

# 原始 README（上游项目）

---

# Essential Loom

A fork of [Architectury Loom](https://github.com/architectury/architectury-loom/), primarily to support legacy forge versions
but with some other changes for our purposes. Used by [Essential Gradle Toolkit](https://github.com/EssentialGG/essential-gradle-toolkit/).

Talk to us on [Discord](https://discord.gg/essential). Use the `#programmer-chat` channel for Essential Loom discussions.
Issues can be reported on Discord or using GitHub issues.

# Original Readme

---

# Architectury Loom

Talk to us on [Discord](https://discord.gg/C2RdJDpRBP)!

---

A fork of [Juuxel's Loom fork]("https://github.com/Juuxel/fabric-loom") that is a fork of [Fabric Loom](https://github.com/FabricMC/fabric-loom) that supports the Forge modding toolchain.

A [Gradle](https://gradle.org/) plugin to setup a deobfuscated development environment for Minecraft mods. Primarily used in the Fabric toolchain.

* Has built in support for tiny mappings (Used by [Yarn](https://github.com/FabricMC/yarn))
* Utilises the Fernflower and CFR decompilers to generate source code with comments.
* Designed to support modern versions of Minecraft (Tested with 1.14.4 and upwards)
* ~~Built in support for IntelliJ IDEA, Eclipse and Visual Studio Code to generate run configurations for Minecraft.~~
  - Currently, only IntelliJ IDEA and Visual Studio Code work with Forge Loom.
* Loom targets the latest version of Gradle 7 or newer 
* Supports Java 17 upwards

## Usage

View the [documentation](https://docs.architectury.dev/loom/introduction) for usages.
