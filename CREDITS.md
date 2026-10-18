# 归属与修改声明 / Credits & Modification Notice

本文件记录本仓库的第三方代码来源、许可证，以及相对上游所做的修改。
This file records third-party code origins, licenses and the modifications made to the upstream work.

---

## 1. 上游作品 / Upstream works

### LiquidBounce

- 来源：<https://github.com/CCBlueX/LiquidBounce>
- 版权：Copyright (c) 2015 - 2025 CCBlueX
- 许可证：**GNU General Public License v3.0**
- 本仓库的基础。除下方列出的文件外，全部源码来自上游（提交 `449ff34`，tag `v0.35.2`）。

### EDNeko

- 来源：<https://github.com/RouQingNeko1024/EDNeko>
- 版权：Copyright (c) RouQingNeko1024
- 许可证：**GNU General Public License v3.0**
- 本仓库中被移植模块（KillAura 转头随机化、Speed 模式、AntiKnockBack、Velocity）的原始实现来源。
  移植方式为**改写**：EDNeko 是 Minecraft 1.8.9 / Forge / MCP 工程，本仓库是 1.21.8 / Fabric / Yarn，
  两边 API 完全不同，因此是按其逻辑重新实现，而不是复制源码。
  每个被移植的文件头部都写明了对应的 EDNeko 原始类。

### 明确未包含的内容 / Explicitly NOT included

- 没有任何 Mojang 的代码或资源。Mixin 在运行时注入，不打包 Minecraft。
- 没有任何闭源、混淆或商业保护客户端的代码。
- 不含 EDNeko 的二进制 jar，只含改写后的 Kotlin 源码。

---

## 2. 修改声明 / Modification notice

依据 GPL-3.0 第 5(a) 条 ——“修改过的作品必须带有显著的声明，说明你修改过它，并给出相关日期”。

**修改者**：LiquidBounce-EDNeko 仓库维护者
**修改日期**：2026-10-15

相对上游 `CCBlueX/LiquidBounce` tag `v0.35.2`（提交 `449ff34`）的全部改动如下。

### 新增文件

| 文件 | 说明 | 来源 |
|---|---|---|
| `utils/aiming/projectiles/PredictiveProjectileAngleCalculator.kt` | 逐 tick 弹道解算器 | 原创 |
| `utils/aiming/features/processors/EdnekoRandomizationProcessor.kt` | 转头随机化（10 种图案 + Chaos + 滤波器） | 移植自 EDNeko |
| `utils/aiming/features/processors/NoiseRotationProcessor.kt` | 噪声转头（Fractal / Drift / Human / Hybrid / Entropy） | 移植自 EDNeko |
| `utils/aiming/features/processors/anglesmooth/impl/BezierAngleSmooth.kt` | 贝塞尔平滑 | 移植自 EDNeko |
| `features/module/modules/combat/ModuleAntiKnockBack.kt` | 完整击退控制模块 | 移植自 EDNeko |
| `features/module/modules/combat/velocity/mode/VelocityIntaveStrong.kt` | Velocity `IntaveStrong` 模式 | 移植自 EDNeko |
| `features/module/modules/movement/speed/modes/EdnekoSpeedMode.kt` | Speed 模式基类 | 移植自 EDNeko |
| `features/module/modules/movement/speed/modes/EdnekoSpeedModes.kt` | `AACHop4` / `VerusHop` / `VanillaHop` / `LowHop` | 移植自 EDNeko |
| `features/module/modules/movement/speed/modes/EdnekoGrimKarhuPolarModes.kt` | Grim / Polar / Karhu 系列 | 移植自 EDNeko |
| `features/module/modules/movement/speed/modes/EdnekoIntaveModes.kt` | Intave 系列 13 个模式 | 移植自 EDNeko |

> 路径前缀均为 `src/main/kotlin/net/ccbluex/liquidbounce/`。

### 修改文件

| 文件 | 改动 |
|---|---|
| `features/module/modules/combat/aimbot/autobow/AutoBowAimbotFeature.kt` | 重写瞄准逻辑：改用逐 tick 解算器、加满弓门槛、去掉 30 tick 预判上限、转头启用 combat 专用处理器 |
| `utils/aiming/RotationsConfigurable.kt` | 注册 `ednekoRandomization` 处理器 |
| `features/module/modules/combat/killaura/features/KillAuraFailSwing.kt` | 默认值对齐 EDNeko；每次点击都挥空 |
| `features/module/modules/combat/velocity/ModuleVelocity.kt` | 加入 `IntaveStrong` 模式 |
| `features/module/modules/movement/speed/ModuleSpeed.kt` | 注册移植来的模式，移除 3 个上游模式 |
| `features/module/ModuleManager.kt` | 注册 `ModuleAntiKnockBack` |
| `src/main/resources/liquidbounce.mixins.json` | 移除 sodium / lithium 的 3 条 mixin 条目（见 README「已知偏差」） |
| `.github/workflows/generate-definitions.yml` | 移除 `release` 触发器：该工作流要用 CCBlueX 的 NPM_TOKEN 发布 npm 包，fork 没有该密钥 |
| `.github/workflows/generate-dokka-html.yml` | 移除 `release` 触发器：该工作流会部署到 CCBlueX 的 GitHub Pages，fork 未配置 |
| `build.gradle.kts` | 网络环境补丁：Maven 镜像、ModMenu 换源、屏蔽 sodium/lithium mixin 源码 |
| `settings.gradle.kts` | 网络环境补丁：插件仓库换 aliyun 镜像 |
| `gradle.properties` | 无功能改动 |

---

## 3. 第三方依赖 / Third-party dependencies

这些是构建期依赖，不属于本仓库的源码，各自保留原许可证。

| 依赖 | 许可证 |
|---|---|
| [Fabric Loader](https://github.com/FabricMC/fabric-loader) | Apache-2.0 |
| [Fabric API](https://github.com/FabricMC/fabric-api) | Apache-2.0 |
| [Yarn mappings](https://github.com/FabricMC/yarn) | CC0-1.0 |
| [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin) | Apache-2.0 |
| [Kotlin](https://kotlinlang.org/) 标准库 | Apache-2.0 |
| [ModMenu](https://github.com/TerraformersMC/ModMenu) | MIT |
| [Gradle](https://gradle.org/) / Loom | Apache-2.0 |
| Minecraft | Mojang，**不随本仓库分发** |

---

## 3.1 美术素材 / Artwork

客户端图标取自 [EDNeko](https://github.com/RouQingNeko1024/EDNeko)（GPL-3.0），即那个蓝色字母 `N`：

| 文件 | 来源 |
|---|---|
| `resources/liquidbounce/icon_16x16.png`、`icon_32x32.png`、`icon_64x64.png` | EDNeko `assets/minecraft/edneko/icon_*.png`，原样使用 |
| `resources/liquidbounce/logo_banner.png` | 由上述图标居中生成 |
| `src-theme/public/img/lb-logo.svg`、`img/menu/icon-liquidbounce.svg`、`img/menu/icon-liquidbounce-hover.svg` | 同一图标，以 base64 内嵌为 SVG |

原 LiquidBounce 的图标与横幅（[CCBlueX/LiquidBounce](https://github.com/CCBlueX/LiquidBounce)，GPL-3.0）已被替换，不再随本仓库分发。

---

## 4. 许可证 / License

本仓库整体以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

本仓库是 LiquidBounce 的衍生作品，因此**必须**继续以 GPL-3.0 发布，不得改为其它许可证。
任何分发本仓库或其衍生作品的行为，都必须一并提供完整源代码。
