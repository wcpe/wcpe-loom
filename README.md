# WCPE Loom

[![CI 发布状态](https://github.com/wcpe/wcpe-loom/actions/workflows/publish.yml/badge.svg?branch=dev/1.17-wcpe)](https://github.com/wcpe/wcpe-loom/actions/workflows/publish.yml)
[![最新正式版](https://img.shields.io/github/v/release/wcpe/wcpe-loom?filter=wcpe-v*&label=正式版)](https://github.com/wcpe/wcpe-loom/releases/latest)
[![Maven 仓库](https://img.shields.io/badge/Maven-maven.wcpe.top-orange)](https://maven.wcpe.top/repository/maven-releases/net/fabricmc/architectury-loom/)
[![License](https://img.shields.io/badge/License-MIT-green)](./LICENSE)

**WCPE Loom** 是 [Architectury Loom](https://github.com/architectury/architectury-loom)（`architectury` 分支，Loom 1.17 代系）的定制版本：在保留其 **Fabric + Forge/NeoForge** 能力的基础上，重新接回 **老版本 Forge（1.8–1.16）支持**，并叠加 WCPE 的并发缓存、配置缓存兼容等补丁，发布到自建 Maven 仓库供下游使用。

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

```groovy
plugins {
	id 'top.wcpe.loom'
}
```

## 版本与发布

| 目的 | 操作 | 版本号 |
|---|---|---|
| 正式版 | 打 tag 并推送（`git tag wcpe-v1.17.2 && git push origin wcpe-v1.17.2`） | `1.17.2`（取自 tag），并自动创建 GitHub Release |
| 日常推送 | 推送到 `dev/1.17-wcpe` | `1.17.<运行号>`（自动递增、不可覆盖） |

产物坐标：`net.fabricmc:architectury-loom:<版本>`，仓库 `maven.wcpe.top/repository/maven-releases/`。

支持的 Minecraft 版本：Fabric 全线；Forge/NeoForge 含 1.8–1.16 的 legacy 链路（1.12.2 端到端测试覆盖）。

---

## 上游 README（Architectury Loom 原文）

> 以下为上游 [architectury/architectury-loom](https://github.com/architectury/architectury-loom) 的 README 原文，保留以便对照上游能力与用法。

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
