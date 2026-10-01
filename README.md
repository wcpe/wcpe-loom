# WCPE Loom

[![CI 发布状态](https://github.com/wcpe/wcpe-loom/actions/workflows/publish.yml/badge.svg?branch=dev/1.17-wcpe)](https://github.com/wcpe/wcpe-loom/actions/workflows/publish.yml)
[![最新正式版](https://img.shields.io/github/v/release/wcpe/wcpe-loom?filter=wcpe-v*&label=正式版)](https://github.com/wcpe/wcpe-loom/releases/latest)
[![Maven 仓库](https://img.shields.io/badge/Maven-maven.wcpe.top-orange)](https://maven.wcpe.top/repository/maven-releases/dev/architectury/architectury-loom/)
[![License](https://img.shields.io/badge/License-MIT-green)](./LICENSE)

**WCPE Loom** 是 [Architectury Loom](https://github.com/architectury/architectury-loom)（`architectury` 分支，Loom 1.17 代系）的定制版本：在保留其 **Fabric + Forge/NeoForge** 能力的基础上，重新接回 **老版本 Forge（1.8–1.16）支持**，并叠加 WCPE 的并发缓存、配置缓存兼容等补丁，发布到自建 Maven 仓库供下游使用。

## 快速开始

| 前置条件 | 要求 |
|---|---|
| Gradle | 9.x（本项目 CI 与端到端测试使用 9.5.0） |
| JDK | 17 及以上（CI 构建与测试使用 21） |
| 仓库凭据 | **无需**——`maven.wcpe.top` 的正式版产物匿名可读 |

接入三步：**① 声明仓库与插件版本 → ② 在需要 Minecraft 的模块应用插件 → ③ 照常声明 `minecraft` / `mappings` 依赖**。

### ① 声明仓库与插件

<details>
<summary><b>Kotlin DSL</b>（<code>settings.gradle.kts</code>，推荐）</summary>

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.wcpe.top/repository/maven-releases/")   // WCPE Loom 本体
        maven("https://maven.fabricmc.net/")                          // Loom 自身的运行时依赖：stitch、tiny-remapper 等
        maven("https://maven.minecraftforge.net/")                    // Forge 系依赖：de.oceanlabs.mcp、net.minecraftforge 等
        maven("https://maven.architectury.dev/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.wcpe.top/repository/maven-releases/")
        mavenCentral()
    }
}
```

> **为什么要四个仓库**：`maven.wcpe.top` 只发布 WCPE Loom 本体；插件自身的运行时依赖分散在 Fabric Maven（`stitch`、`tiny-remapper`、`class-tweaker`、`lorenz-tiny`）与 Forge Maven（`de.oceanlabs.mcp:mcinjector`、`net.minecraftforge:DiffPatch`），漏掉任何一个都会以 `Could not find …` 失败（这几条已按本清单实测通过）。
>
> **项目依赖用的仓库无需手写**：`minecraft`、`mappings` 等所需 Mojang / Fabric / Forge 仓库由 Loom 自动注入（**NeoForge 不在其列**，见下方 NeoForge 示例）。

</details>

<details>
<summary><b>Groovy DSL</b>（<code>settings.gradle</code>）</summary>

```groovy
pluginManagement {
    repositories {
        maven { url = 'https://maven.wcpe.top/repository/maven-releases/' }
        maven { url = 'https://maven.fabricmc.net/' }
        maven { url = 'https://maven.minecraftforge.net/' }
        maven { url = 'https://maven.architectury.dev/' }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = 'https://maven.wcpe.top/repository/maven-releases/' }
        mavenCentral()
    }
}
```

> 各仓库的作用与 Kotlin DSL 版本相同：`maven.wcpe.top` 提供 WCPE Loom 本体，Fabric / Forge 仓库提供插件自身的运行时依赖。

</details>

### ② 应用插件

<details>
<summary><b>单模块项目</b></summary>

```kotlin
// build.gradle.kts
plugins {
    id("top.wcpe.loom") version "1.17-latest"
}
```

</details>

<details>
<summary><b>多模块项目</b>（只在需要 Minecraft 的模块应用）</summary>

```kotlin
// settings.gradle.kts：版本只需声明一次
pluginManagement {
    plugins {
        id("top.wcpe.loom") version "1.17-latest"
    }
}
```

```kotlin
// versions/fabric-1.20.1/build.gradle.kts 等需要 Minecraft 的模块
plugins {
    id("top.wcpe.loom")
}
```

</details>

### ③ 声明 Minecraft 与模组依赖

<details>
<summary><b>Fabric</b></summary>

```groovy
dependencies {
    minecraft "com.mojang:minecraft:1.20.1"
    mappings "net.fabricmc:yarn:1.20.1+build.10:v2"
    modImplementation "net.fabricmc:fabric-loader:0.16.10"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.92.5+1.20.1"
}
```

</details>

<details>
<summary><b>Forge（含 1.8–1.16 legacy 链路）</b></summary>

```groovy
dependencies {
    minecraft "com.mojang:minecraft:1.20.1"
    mappings "net.fabricmc:yarn:1.20.1+build.10:v2"
    forge "net.minecraftforge:forge:1.20.1-47.2.0"        // 现代 Forge（1.17+）

    // legacy 链路（示例：1.12.2；14.23.5.2851 起改用 userdev3 分类器，Loom 会自动归一）
    // minecraft "com.mojang:minecraft:1.12.2"
    // forge "net.minecraftforge:forge:1.12.2-14.23.5.2860"
}
```

</details>

<details>
<summary><b>NeoForge</b></summary>

```groovy
// NeoForge 的仓库 Loom 不会自动注入，需自行声明
repositories {
    maven { url = 'https://maven.neoforged.net/releases/' }
}

dependencies {
    minecraft "com.mojang:minecraft:1.21.1"
    mappings loom.officialMojangMappings()
    neoForge "net.neoforged:neoforge:<版本>"   // 具体版本见 https://maven.neoforged.net/releases/net/neoforged/neoforge/
}
```

</details>

> `loom { … }` 配置块、run configs、`fabricApi`、IDE 运行配置生成等与上游一致，详见文末「上游 README（Architectury Loom 原文）」。

### ④ 版本选择

| 写法 | 解析到 | 适用场景 |
|---|---|---|
| `1.17-latest` | 最新正式版（当前 `1.17.7`） | 自动跟随更新，推荐 |
| `1.17.7` | 固定正式版 | 需要可复现的构建 |
| `1.17-dev-latest` | 开发线最新构建（`1.17-dev.<运行号>`） | 抢先验证未发布的改动 |
| `1.17-dev.<运行号>` | 固定开发版 | 复现某个开发构建 |

两个 `*-latest` 是仓库中的**指针产物**：其插件标记 POM 指向当时的具体版本号，随每次发布自动前移，因此不会解析失败。

## 当前使用哪个上游

| 角色 | 来源 | 说明 |
|---|---|---|
| **直接上游** | [architectury/architectury-loom](https://github.com/architectury/architectury-loom) | `architectury` 分支，基底提交 `026ce830` |
| 旁系来源 | [SparkUniverse/architectury-loom](https://github.com/SparkUniverse/architectury-loom) | Essential Loom `dev/1.15`；本项目 1.15 线的基底，其功能按需移植，**不并入历史** |
| 更上游 | [FabricMC/fabric-loom](https://github.com/FabricMC/fabric-loom) | 最原始上游 |

## 仓库分支与版本线

| 分支 | 定位 | 状态 |
|---|---|---|
| **`dev/1.17-wcpe`**（默认分支） | **开发主线**，基于 architectury 1.17 上游 | 活跃开发 |
| `1.15-wcpe` | 1.15 代系（Essential Loom `dev/1.15` + WCPE 补丁），已归档 | 冻结保留 |

- 1.17 线不走 quilt 式补丁队列，而是把原 1.15 线上的 WCPE 补丁**重放到** architectury 1.17 基底之上（上游相隔约 5 个月，两线同一项目的不同历史点）。
- 正式版以 tag 标记：`wcpe-v1.17.1`、`wcpe-v1.17.2`…（不用 `v1.17.*`，该前缀已被上游 release tag 占用）。

## 插件 id

本 fork 使用自有域名的插件 id，并保留上一层分叉的 id 作为**兼容别名**，既有项目无需改动即可继续使用：

| 用途 | 插件 id | 兼容别名 |
|---|---|---|
| 主插件 | `top.wcpe.loom` | `dev.architectury.loom` |
| 无重映射变体 | `top.wcpe.loom-no-remap` | `dev.architectury.loom-no-remap` |
| 重映射变体 | `top.wcpe.loom-remap` | `dev.architectury.loom-remap` |
| companion | `top.wcpe.loom-companion` | `dev.architectury.loom-companion`、`net.fabricmc.fabric-loom-companion` |
| 仓库插件 | `top.wcpe.loom-repositories` | `dev.architectury.loom-repositories` |

> 应用方式见上方「快速开始 · ② 应用插件」。把 id 换成兼容别名即可，行为完全相同。

## 版本与发布

| 目的 | 版本号 | 说明 |
|---|---|---|
| 正式版 | `1.17.<补丁号>`（取自 tag，如 `wcpe-v1.17.7` → `1.17.7`） | 打 tag 推送后自动构建发布，并创建 GitHub Release |
| 日常推送 | `1.17.<运行号>`（自动递增、不可覆盖） | 推送到 `dev/1.17-wcpe` 即触发，属于开发线命名空间 |

产物坐标：`dev.architectury:architectury-loom:<版本>`（Gradle 插件标记为 `top.wcpe.loom:top.wcpe.loom.gradle.plugin`），仓库 `maven.wcpe.top/repository/maven-releases/`。

<details>
<summary><b>维护者：发布一个版本</b></summary>

正式版（自动更新 `1.17-latest` 指针 + 创建 GitHub Release，变更清单按 `.github/release.yml` 分组生成，基点取上一个 `wcpe-v*` tag）：

```bash
git tag -a wcpe-v1.17.8 -m "WCPE Loom 1.17.8"
git push origin wcpe-v1.17.8
```

开发版（不创建 Release，只更新 `1.17-dev-latest` 指针）：

```bash
git push origin dev/1.17-wcpe
```

</details>

支持的 Minecraft 版本：Fabric 全线；Forge/NeoForge 含 1.8–1.16 的 legacy 链路（1.12.2 端到端测试覆盖）。

## 常见问题

<details>
<summary><b>配置缓存偶发失效 / 日志出现 <code>.lock' has been removed</code></b></summary>

`1.17.6` 起已修复。早期版本在配置阶段用存在性判断探测锁文件，而锁文件是本次构建创建、末尾删除的瞬时文件——一旦上一次构建被杀留下残留锁，"文件存在"就会被写进配置缓存指纹，下一次构建读到"不存在"便丢弃缓存，白算约 30 秒。升级到 `1.17.6` 及以上即可。

</details>

<details>
<summary><b>Forge 系依赖反复被探测，或报 <code>status code 500</code></b></summary>

`cpw.mods`、`net.minecraftforge`、`de.oceanlabs`、`net.jodah`、`org.mcmodlauncher` 这几个 group 由 Loom 注入的官方仓库 `https://maven.minecraftforge.net/` 提供。若项目自己的镜像/代理排在其前面且返回 5xx，Gradle 无法判定该仓库是否拥有该模块、该结论不会被缓存，于是每次构建都重新探测，并在探测失败时中断构建（返回 404 则会被缓存 24 小时，不会重复探测）。修法：重试；或用 `exclusiveContent` 把这些 group 钉到官方源。

</details>

<details>
<summary><b>反复重新配置 / 构建慢</b></summary>

开启 Gradle 配置缓存（`org.gradle.configuration-cache=true`）可让重复构建整段跳过配置阶段——本 fork 的缓存与锁改动正围绕这一点。另外请确认 CI 环境的 Gradle 缓存可复用：缓存为空时每一轮都会重新解析依赖。

</details>

---

## 上游 README（Architectury Loom 原文）

> 以下为上游 [architectury/architectury-loom](https://github.com/architectury/architectury-loom) 的 README 原文，保留以便对照上游能力与用法。

<details>
<summary><b>展开：Architectury Loom 原文（含上游用法说明）</b></summary>

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

</details>
