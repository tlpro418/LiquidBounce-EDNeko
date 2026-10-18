# EDneko NG

把 **EDNeko**（Minecraft 1.8.9 的 Forge 客户端）里的战斗 / 移动模块移植到
**LiquidBounce NextGen** 的社区分支，并整体更名为 **EDneko NG**。
目标版本 **Minecraft 1.21.8 + Fabric**。

| | |
|---|---|
| 客户端名称 | **EDneko NG**（窗口标题、聊天前缀、GUI、Mod Menu 均已更名） |
| 上游项目 | [CCBlueX/LiquidBounce](https://github.com/CCBlueX/LiquidBounce) — GPL-3.0 |
| 移植来源 | [RouQingNeko1024/EDNeko](https://github.com/RouQingNeko1024/EDNeko) — GPL-3.0 |
| 本仓库许可证 | [GNU GPL-3.0](LICENSE) |
| 游戏版本 | Minecraft 1.21.8（Fabric） |

> ⚠️ 这不是 LiquidBounce 官方版本，与 CCBlueX 没有任何隶属关系。LiquidBounce 的全部版权归 CCBlueX 所有，
> EDNeko 的全部版权归 RouQingNeko1024 所有。本仓库只是基于它们的 GPL-3.0 衍生作品。
> 更名只涉及用户可见的显示名称，**包名、资源命名空间、版权头和 LICENSE 全部保留原样**，见 `CREDITS.md`。

### 更名说明

- **改变的**：窗口标题、聊天前缀、主菜单 GUI、Mod Menu 条目、Discord 状态、报错窗口、
  配置目录（旧目录会自动迁移）、`/clientinfo` 输出、生成物品的 Lore
- **不变（有意保留）**：Java 包名 `net.ccbluex.liquidbounce`、Minecraft 资源命名空间
  `liquidbounce`（shader / pipeline / item group / cape 都挂在这上面）、GPL 版权头、`LICENSE`、
  `CREDITS.md`、语言文件的翻译键名

---

## 本分支做了什么

### 1. KillAura 转头：EDNEKO 随机化

把 EDNeko 的整套转头随机化搬成了 LiquidBounce 的旋转处理器 `EDNEKO Randomization`，
在 KillAura / 弓预瞄的 `Rotations` 里都能选：

- 10 种图案：`None` / `Zig-Zag` / `LazyFlick` / `Noise` / `TrueRandom` / `Perlin` / `Advanced` /
  `HybridNoise` / `AugustusXIntave` / `Chaotic`
- `AugustusXIntave` 三种子模式（`Basic` / `OnlyRotation` / `Doubled`）+ `RandomStrength`
- Chaos 六套系统、5 种滤波器、Perlin 256 表、Advanced 轨迹库、MiniDisturbance
- 另外补了 `Noise`（Fractal / Drift / Human / Hybrid / Entropy，四档强度）和 `Bezier` 平滑

> 顺带说一句：EDNeko 那个 3750 项的 `NoisePreset` 下拉菜单实测只有 96 项有真实实现，剩下的都是空壳，
> 所以这里只搬了真正有用的部分。

### 2. Speed 模式

只移植了反作弊相关的那批，`Speed` 模块里可选：

- Grim：`Grim` / `GrimCollide` / `NewGrim`
- Polar / Karhu：`Polar` / `Karhu` / `Karhu2`
- Intave：`Intave12.5.5` / `Intave13.0.9` / `Intave14.0.x-A` / `Intave14.0.x-B` / `Intave14.1.x` /
  `Intave14.8.4` / `Intave2` / `Intave3` / `IntaveHop` / `IntaveHop14` / `IntaveNew` / `IntaveTimer` /
  `IntaveTimerHop`
- 另外还有 `AACHop4` / `VerusHop` / `VanillaHop` / `LowHop`

### 3. AntiKnockBack（完整移植）

EDNeko 把击退拆在 `Velocity` / `Velocity2` / `AntiKnockBack` 三个模块里，这里全部合并进一个
`AntiKnockBack` 模块，所有开关和阈值逐条对齐。

### 4. Velocity

只保留 `IntaveStrong`（来自 EDNeko 的 `Velocity2`）。EDNeko 的 `Polar` 模式实测是空壳
（只往一个从没被读过的字段写值），没有移植价值，已确认剔除。

### 5. 弓预瞄（BowAimbot）— 本分支原创

LiquidBounce 原来的 AutoBow 用的是抛物线近似公式，远距离误差很大。这里换成了**逐 tick 数值积分**的
弹道解算器：

- 把整条飞行轨迹按游戏真实的物理跑一遍（先移动 → `×0.99` → `y -= 0.05`），
  再对俯仰角做「1° 粗扫 → 24 次二分」，精度到浮点级
- 同一水平角下「平射弧」和「抛射弧」都能打中时，自动选**飞行时间最短的平射弧**
- **拉满弓才开始瞄**（`FullChargeOnly`，20 tick），不会自动拉弓
- **实时预判移动目标**：用玩家物理模拟预测命中时刻的位置，反复迭代收敛，
  提前量随距离自适应；并解掉了默认 30 tick 的预测上限（满弓能打 110+ 格 ≈ 70+ tick）
- 转头直接复用 KillAura 那套（含 ShortStop / Fail / Noise / 随机化等全部处理器）
- 已用独立数值测试台验证：105 组「距离 3~112 格 × 高差 ±20 格」，99 组精确命中（偏离 0.0000 格）

### 6. 不挖玻璃（NoGlassBreak）— 本分支原创

有的服务器反作弊有 bug，挖玻璃会被误判封号。这个模块开启后**完全挖不动玻璃**，连包都不会发出去：

- 按方块注册名是否包含 `glass` 识别，覆盖普通玻璃、玻璃板、16 种染色玻璃及其玻璃板、遮光玻璃
- 同时掐掉初始点击（`BlockAttackEvent`）和每 tick 的挖掘进度（`BlockBreakingProgressEvent`），
  所以不会产生任何 `START_DESTROY_BLOCK` / `STOP_DESTROY_BLOCK` 包，本地也不会累积破坏进度
- Nuker / PacketMine 这类自己拼包的模式同样会被拦住
- **出站包兜底**：任何指向玻璃的 `PlayerActionC2SPacket`（START / ABORT / STOP）在发送前被直接丢弃，
  优先级为 `FINAL_DECISION`，能否决前面所有处理器
- 可选 `Panes` / `Stained` 开关，默认全保护

---

## 下载

去 [Releases](../../releases) 下载 `liquidbounce-<版本>.jar`，丢进 `.minecraft/mods/` 即可。

需要：Fabric Loader、[Fabric API](https://modrinth.com/mod/fabric-api)、
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)。

## 自己构建

需要 **JDK 21**（Gradle 9.1 会自己下载）。

```bash
./gradlew build
```

产物在 `build/libs/`。

> 仓库里的 `settings.gradle.kts` / `build.gradle.kts` 带有几个**网络环境补丁**（Maven 走 aliyun 镜像、
> ModMenu 改从 TerraformersMC 拉取、屏蔽 sodium/lithium 的 mixin 兼容层），都有注释说明。
> 如果你的网络能直连 Maven Central 和 Modrinth，按注释把补丁删掉即可恢复原样。

## 已知偏差

- **没有移植 sodium / lithium 的 mixin 兼容层**：这两个 mod 只在 Modrinth 分发，构建环境取不到。
  源码仍留在树里，`build.gradle.kts` 中有详细的恢复说明。
- 部分菜单可见性（`doNotIncludeWhen`）没有复刻，只影响设置菜单的显示，不影响功能。
- EDNeko 的 `jumpMovementFactor`（1.8.9 的 `field_70747_aH`）在 1.21.8 没有对应物。
- 弓预瞄不做地形遮挡检测，箭会被墙挡住的情况不会预判。

## 许可证

本仓库以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。
归属与修改声明见 [CREDITS.md](CREDITS.md)。

简单说：

- ✅ 可以使用、修改、分发
- ❗ **必须开源**：分发本项目的任何衍生作品时，必须一并提供完整源代码，且同样以 GPL 授权
- ❗ **不能闭源**，不能放进闭源或混淆的程序里
- ❗ 必须保留版权声明和许可证文本

本项目是 LiquidBounce 的衍生作品，**不能**改成 MIT / Apache 等其它许可证。

## 致谢

- [CCBlueX](https://github.com/CCBlueX) — LiquidBounce，整个框架与绝大部分功能
- [RouQingNeko1024](https://github.com/RouQingNeko1024) — EDNeko，被移植模块的原始实现
- [FabricMC](https://fabricmc.net/) — Fabric、Fabric API、Yarn 映射
