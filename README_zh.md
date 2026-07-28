# 机械动力：魔法工业

[English](./README.md)

一款 **机械动力（Create）** 的 NeoForge 附属模组，在动能（旋转力）与魔法模组之间架起桥梁：将应力转化为魔力，将流体雾化为体积雾场，自动化施法物品生产等——全部通过机械动力的工业体系实现。

## 特性

### 核心机制

- **动力注魔台**（Kinetic Mana Generator）——消耗机械动力旋转应力产生魔力，为置于法术组构台或充能阵列中的 Trickster 晶结充能。每 RPM 应力消耗可通过面板上的滑动框实时调节。
- **动力雾化器**（Kinetic Atomizer）——将泵入的流体雾化为体积雾场，半径随转速变化。雾场浓度随距离衰减；重叠雾场取最大浓度。雾化配方的前置条件。
- **冷凝管**（Condenser）——当水流经时从雾场中冷凝回收流体，注入下方的动力排泄口。效率随水压和雾场浓度变化。
- **雾场系统**（Mist field）——服务端按维度维护的 `ConcurrentHashMap` 空间数据存储。浓度基于欧几里得距离实时计算，重叠雾场采用最强源解析。定时排放支持配方副产物。

### 流体系统

- **液态魔力**（Liquid Mana）——全亮度发光流体，存储魔力；兼容机械动力管道与储罐网络。由 `manaPerBucket` 配置。
- **液态媒质**（Liquid Media）——浅紫色发光流体，存储咒法学媒质（1 桶 = `mediaPerBucket` 媒质单位，默认 400,000）。机械动力与咒法学能量系统之间的桥接流体。
- **双向转换**——加热搅拌：液态媒质 ↔ 液态魔力（1:1）。在工作盆中进行雾化搅拌：液态魔力 → 液态媒质蒸气。冷凝管：雾 → 液态媒质。完整循环回路。

### 配方类型

- **加热辊压**（Heated Compacting）——为动力辊压机新增的加热盆配方。将紫水晶粉/碎片/充能紫水晶转化为液态媒质（输出量根据配置文件动态重算）。
- **雾化辊压**（Mist Compacting）——需要或产生雾副产物的辊压配方。
- **雾化搅拌**（Mist Mixing）——带雾条件与排放的搅拌配方（时长、半径、量按配方可配）。

### Trickster 集成

- **Trickster 戏法：** `temporary_kinetic_stress`（动力应力戏法）——消耗魔力，在指定时长内向任意动能方块实体施加临时应力与转速，大小与时长可配置。
- **序列装配**——通过机械动力流水线制作 Trickster 晶结：未完成晶结 → 注液器注入液态魔力 → 动力辊压机定型。支持绿宝石、虹彩、钻石、回响、星辰晶结。
- **Display Link 目标**——将文本写入法术组构台与模块化法术组构台的参数中。支持独立参数或核心+参数对。
- **机械臂支持**——机械臂可向 Trickster 充能阵列、法术组构台与模块化法术组构台插入/提取晶结物品。
- **动力法术核心**（Kinetics Spell Core）——附着到模块化法术组构台上，将其与齿轮链网络（Bits 'n' Bobs）链接，将动能状态暴露为 Trickster 数据供法术逻辑调用。

### 咒法学集成

- **施法物品自动化流水线**——全自动化生产杂件（Cypher）、缀品（Trinket）与造物（Artifact）：
  1. 机械手 + 含 Iota 物品 → 将图案写入崭新施法物品
  2. 注液器 + 液态媒质 → 填充媒质至配置上限
  3. 动力辊压机 → 定型为完整咒法学物品，所有图案、媒质与颜料完整转移
- **媒质之瓶序列装配**——机械手 + 玻璃瓶 + 远古卷轴（制作试剂瓶图案）→ 注液器注入液态媒质 → 动力辊压机定型为媒质之瓶。最终容量由实际填充的媒质量决定。
- **自定义咒法学图案**——`从方块读取 Iota`（Read Iota from Block，图案：`wqwqwqwqwqwaw`，0 媒质消耗）。从置于机械动力置物台或置物板上的含 Iota 物品中读取 Iota。
- **石板图案切石**——为所有非卓越法术的咒法学图案动态注册切石配方。将空白石板放入切石机即可直接印制任意图案。
- **媒质之瓶流体能力**——定型后的媒质之瓶可通过机械动力流体管道填充/抽取液态媒质，在自动化系统中作为媒质缓冲器。
- **Patchouli 手册条目**——在咒法学的"Hexbook"中新增 6 个条目，覆盖全部集成功能，提供完整的中英文翻译。

### 视觉与网络

- **体积雾场渲染**——雾场通过 Veil 着色器后处理渲染为半透明彩色体积（Veil 可选但推荐安装）。
- **网络同步**——雾场状态通过自定义数据包同步到客户端，实现实时渲染更新。

## 依赖

### 必需
- [NeoForge](https://neoforged.net)（1.21.1，21.1+）
- [机械动力（Create）](https://createmod.net)（6.0.10+）

### 可选
- [Trickster](https://modrinth.com/mod/trickster)（2.0.0-beta.48+）——晶结自动化、动力法术核心、Display Link 目标、应力戏法
- [咒法学（Hexcasting）](https://modrinth.com/mod/hexcasting)（0.12.0-devel-pre-35+）——施法物品流水线、媒质之瓶、自定义图案、石板切石
- [Create: Bits 'n' Bobs](https://modrinth.com/mod/create-bits-n-bobs)（2.1.9-beta+）——齿轮链集成
- [Veil](https://modrinth.com/mod/veil)（4.1.4+）——体积雾场着色器渲染

## 配置

所有配置项均位于通用配置文件（`createmanaindustry-common.toml`）：

| 键 | 默认值 | 说明 |
|---|---|---|
| `manaPerStress` | 0.001 | 每 tick 每应力单位产生的魔力量 |
| `manaPerBucket` | 2048 | 一桶液态魔力所含的魔力值 |
| `mediaPerBucket` | 400000 | 一桶液态媒质所含的媒质值 |
| `kineticStressTrickManaMultiplier` | 2.0 | 临时应力戏法的魔力消耗倍率 |
| `mistMaxRadius` | 16 | 雾化器最大雾场半径（格） |
| `mistFluidPerTick` | 8 | 256 RPM 时每 tick 的基础流体消耗 |
| `mistBaseConcentration` | 1.0 | 距离 0 处的雾场浓度 |
| `condenseEfficiency` | 5.0 | 每浓度单位每 tick 冷凝的基准流体量（mB） |
| `cypherMaxMedia` | 6400000 | 未完成杂件的最大媒质容量 |
| `trinketMaxMedia` | 64000000 | 未完成缀品的最大媒质容量 |
| `artifactMaxMedia` | 640000000 | 未完成造物的最大媒质容量 |
| `batteryMaxMedia` | 640000000 | 未完成媒质之瓶的最大媒质容量 |

## 构建

```bash
./gradlew build
```

## 许可

MIT License
