# Allay Dimension（allay_dimension）— ±3000 万 Y 轴维度分析与开发文档

> 模组：CreateManaIndustry（机械动力：魔法工业）
> 版本基线：1.21.1 / NeoForge 21.1.227 / Java 21 / Mixin（已有 `createmanaindustry.mixins.json` + `CMIMixinPlugin`）
> 参考实现：`.refs/CubicChunks3`（NeoForge 21.6.4-beta / MC 1.21.6，**作者声明"Not yet usable"，未完成重写**）、`.refs/voxy`（Fabric 0.2.19-beta / MC 26.2 dev，**Fabric 独占**）、`.refs/neoforge-21.1.227`（原版反编译源）
> 状态：**分析完成，未开始实现**（2026-09-02）
> 目标：注册自定义维度 `createmanaindustry:allay_dimension`；Y 轴支持 −30,000,000 ~ +30,000,000；XZ 与默认世界边界一致（±29,999,984）；兼容 voxy 地图

---

## 1. 任务定义与结论速览

| 目标 | 可行性 | 路线 |
|---|---|---|
| 注册维度 `allay_dimension` | ✅ 立即可做 | 原版数据包 JSON（`dimension_type` + `dimension`），阶段 0 |
| Y 轴 ±3000 万 | ⚠️ 原版**完全不可能**，必须自建加载层 | 借鉴 CubicChunks3 的 Cube（立方区块）架构，阶段 1–3 |
| XZ ±29,999,984 | ✅ 原生支持 | `BlockPos` X/Z 各 26 bit（±33,554,432），CC3 `CubePos` 21 bit/轴（±33,554,431），均覆盖 |
| 兼容 voxy | ⚠️ 有硬限制，需窗口化策略 | voxy 内部 Y 仅 8 bit（**±4096 格**），超界**静默数据混叠**；见 §7 三条路线，推荐路线 A（可视窗口折叠） |

**核心结论（三道数学硬墙）**：

1. **原版墙**：1.21.1 `BlockPos` 打包 X/Z 各 26 bit、Y 仅 12 bit → `DimensionType` 硬约束 `min_y ∈ [−2032, 2031]`、`height ≤ 4064`（见 §2.1）。维度 JSON 写再大也解析报错。±3000 万 Y 必须像 CubicChunks 一样**绕开整个"列区块 + 统一高度"模型**。
2. **CC3 能力墙**：CC3 的 `CubePos` 每轴 21 bit（方块 ±33,554,431）**恰好覆盖 ±3000 万**，其 Cube/CloPos/DASM/SurfaceTracker 架构是现成蓝图；但 CC3 面向 1.21.6 且自身未完成（光照、存档 IO、地形生成均为 TODO），**只能参考架构，不能依赖或直接移植**。
3. **voxy 墙**：voxy 的 64 bit section key 中 Y 仅 8 bit 有符号（section 粒度 32 格 → **Y ≈ ±4096 格**），超出部分被 `y & 0xFF` **静默截断混叠**（无报错、数据库被污染）；且其 ingest 触发链 100% 依赖"原版列区块 + 原版光照 + Sodium 渲染管线"，Cube 世界中会**静默失效**。此外 voxy **没有 NeoForge 版本**（§7.4）。

---

## 2. 三道数学硬墙的源码证据

### 2.1 原版 1.21.1：Y 只有 12 bit

`.refs/neoforge-21.1.227/net/minecraft/core/BlockPos.java`（打包位宽在**类加载期固化**，Mixin 无法按维度差异化）：

```java
private static final int PACKED_X_LENGTH = 1 + Mth.log2(Mth.smallestEncompassingPowerOfTwo(30000000)); // = 26
private static final int PACKED_Z_LENGTH = PACKED_X_LENGTH;                                            // = 26
public static final int PACKED_Y_LENGTH = 64 - PACKED_X_LENGTH - PACKED_Z_LENGTH;                      // = 12
```

`.refs/neoforge-21.1.227/net/minecraft/world/level/dimension/DimensionType.java`：

```java
public static final int BITS_FOR_Y = BlockPos.PACKED_Y_LENGTH;      // 12
public static final int Y_SIZE = (1 << BITS_FOR_Y) - 32;            // 4064
public static final int MAX_Y = (Y_SIZE >> 1) - 1;                  // 2031
public static final int MIN_Y = MAX_Y - Y_SIZE + 1;                 // -2032
// codec: Codec.intRange(MIN_Y, MAX_Y).fieldOf("min_y")
//        Codec.intRange(16, Y_SIZE).fieldOf("height")
// 构造校验: height % 16 == 0; minY % 16 == 0; minY + height <= MAX_Y + 1
```

即**任何** 1.21.1 维度（含 mod 维度）的 `min_y/height` 被锁死在 `min_y ≥ −2032`、`min_y + height ≤ 2032`、总量 ≤ 4064。连带后果：`LevelChunkSection[] sections` 数组按 height 分配、网络包中 section 索引用窄类型、`Heightmap` 每列一张、光照引擎按列组织——整条管线都假设"世界是一个有限高度的柱体"。

### 2.2 CubicChunks3：21 bit/轴的 CubePos —— 够用

`CubicChunksCore/src/main/java/io/github/opencubicchunks/cc_core/api/CubePos.java`：

- 每轴 21 bit 打包进一个 long；`MAX_COORDINATE_VALUE = Coords.blockToCube(33554431)` → 方块坐标 **±33,554,431**，覆盖 ±30,000,000 需求（余量 11%）。
- 用 long **最高两位的奇偶性**区分 cube long 与 chunk long（`bit62 XOR bit63 != 0` 为 cube），cube 的最高两位取反以避开 `Long.MAX_VALUE` 作非法哨兵。
- 默认 Cube 是 **32×32×32**（`DIAMETER_IN_SECTIONS = 2`，可配 1/2/4/8 → 16/32/64/128 边长；`CubicConstants.SECTION_COUNT = 8` 个 `LevelChunkSection`/cube）。注意**不是**直觉上的 16³。
- API 理论界（`CubicChunksBase`）：`MAX_SUPPORTED_HEIGHT = Integer.MAX_VALUE/2`，实际受打包位宽限制为 ±33.5M。
- XZ 无需额外处理：原版世界边界 ±29,999,984 本就来自 26 bit X/Z 打包（3000 万取整），Cube 的 X/Z 21 bit（±33.5M 方块）与之一致。

### 2.3 voxy：section key 中 Y 仅 8 bit

`.refs/voxy/src/main/java/me/cortex/voxy/common/world/WorldEngine.java`（第 91–108 行）：

```java
public static long getWorldSectionId(int lvl, int x, int y, int z) {
    return ((long)lvl<<60)|((long)(y&0xFF)<<52)|((long)(z&((1<<24)-1))<<28)|((long)(x&((1<<24)-1))<<4);
}
public static int getY(long id) { return (int)((id<<4)>>56); } // 符号扩展 → 有符号 8 bit
```

- key 布局：`lvl(4) | y(8) | z(24) | x(24) | spare(4)`。section 粒度 32 格（lvl0 section = 2×2×2 个 16³ chunk section）。
- **Y 表达范围 = ±127 section × 32 格 ≈ ±4096 格**。XZ 24 bit/轴（±8,388,608 section = ±2.68 亿格，充裕）。
- 超界不报错：`y & 0xFF` 截断后 Y=+4096 与 Y=0 共用同一 key，**数据互相覆盖**（渲染错误 LoD + 数据库永久污染）。
- 该布局**贯穿全链路**：磁盘格式（`SaveLoadSystem3.serialize` 把 key 原样写盘）、GPU（`NodeStore.writeNode` 写 SSBO）、GLSL（`assets/voxy/shaders/lod/pos_util.glsl` 的 `getLoDPosition` 同样 8 bit Y 解包）、邻接 remesh（`AsyncNodeManager` 按 `section.y±1`）。

---

## 3. CubicChunks3 技术体系综述（我们借鉴什么）

CC3 = `io.github.opencubicchunks/cubicchunks`（根项目，NeoForge mod）+ `CubicChunksCore`（loader 无关库：CubePos/Coords/CubicConstants/SurfaceTracker）。构建期依赖 `regionlib`（自研 3D region 存档库）与 **DASM**（字节码变换，见 §4.4）。**当前状态：地形为占位正弦波、光照 `getRawBrightness` 恒满亮、存档 `cc_read` 返回 empty——全部标注 TODO**。`src_old/` 是上一代（≈1.21.1 时期）实现，保留了 regionlib IO、旧 mixin，参考价值高。

### 3.1 核心思想：不做"更高的柱"，做"垂直堆叠的 Cube"

CC3 **不**篡改 `LevelHeightAccessor.getMinY()/getHeight()`（`MixinLevelHeightAccessor` 仅是方法改名兼容），而是：

- 世界 = XZ 上的**列 chunk**（保留，承载结构引用、POI 等 2D 数据）+ 垂直方向**独立加载的 Cube**（承载方块/方块实体/流体）。
- `MixinLevel` 把 `Level` 的方块访问（`getBlockState/setBlockState/getBlockEntity/getFluidState/...`）用 `@WrapOperation` 重定向到 `cc_getCubeAt(blockPos)` —— **方块读写根本不经过列 chunk 的 sections 数组**。
- 是否 cubic 由 `CubicLevelHeightAccessor.WorldStyle` 决定：`CUBIC`（3D 生成+无限高）/`HYBRID`（原版生成+无限高）/`CHUNK`（纯原版）。**这正好是 allay_dimension 需要的形态：仅本维度 CUBIC，其它维度不受影响。**
- `BlockPos` 的 int Y 天然容纳 ±3000 万——限制只在 chunk 的 section 数组与打包位置里，Cube 用自己的 21 bit 打包绕开。

### 3.2 CloPos：一套管线管两种对象

`CubicChunksCore/.../cc_core/world/level/CloPos.java`（"**Cl**unk-or-cube p**os**ition"）：统一 Chunk（列）与 Cube 的位置类型，使 `ChunkHolder/DistanceManager/TicketStorage/PlayerChunkSender` **原版类不替换**、一套代码同时管理列与 cube。列模式 y 为哨兵 `Integer.MAX_VALUE`；cube 的邻居 = 26 个相邻 cube + 其覆盖的所有列 chunk（单向边）。

### 3.3 加载管线改造点（Mixin 清单，按 1.21.1 适配时逐一核对）

| 环节 | CC3 Mixin（`src/main/java/.../cubicchunks/mixin/`） | 作用 |
|---|---|---|
| Level 门控 | `core/common/world/level/MixinLevel`、`MixinLevelHeightAccessor`、`MixinLevelReader`、`CanBeCubic/MarkableAsCubic` 接口 | `cc_isCubic` 标志 + 方块访问重定向到 Cube |
| 区块源 | `world/level/chunk/MixinChunkSource`（实现 `CubeSource`）、`server/level/MixinServerChunkCache`（实现 `ServerCubeCache`，DASM 生成 cube 版 getChunk/tick/broadcast + 4096 槽 cube 环形缓存） | cube 的查询/加载入口 |
| 持有器 | `server/level/MixinChunkHolder`、`MixinGenerationChunkHolder`（注入 `cc_cubePos` 字段，非空即 cube holder）、`MixinChunkMap`（`GeneratingCubeMap+CubicChunkMap`） | 一套 holder 双态 |
| 生成步骤 | `chunk/status/MixinChunkStatusTasks`（cubic 世界列生成全 passThrough）、`cube/status/MixinCubeStatusTasks`+`CubeStatusTasks`（cube 生成；**现为正弦波占位**）、`CubeStep/CubePyramid`（DASM 从 `ChunkStep/ChunkPyramid` 整类变换） | 3D 生成金字塔 |
| 距离/票据 | `MixinDistanceManager`、`MixinTicketStorage`、`MixinPlayerTicketTracker`、`MixinFixedPlayerDistanceChunkTracker`、`MixinChunkTracker` | 垂直视距（`verticalViewDistance` 默认 8） |
| 网络 | `network/CCNetworkHandler`：`CCClientboundLevelCubeWithLightPacket`、`CCClientboundForgetLevelCloPacket`、`CCClientboundSetCubeCacheCenterPacket`；`server/network/MixinPlayerChunkSender` | 21 bit/轴 long 直写，规避原版 Y/section 窄类型 |
| 客户端缓存 | `client/multiplayer/ClientCubeCache.Storage`（**3D** 环形缓存 `viewRange³`）、`MixinClientChunkCache`、`MixinClientLevel` | cube 版 ClientChunkCache |
| 客户端渲染 | `MixinViewArea`（cubic 时 `sectionGridSizeY = renderDistance*2+1`，Y 与 XZ 对等；摄像机相对定位）、`MixinSectionRenderDispatcher$RenderSection`、`MixinSectionOcclusionGraph`、`MixinRenderRegionCache`、`MixinSectionCopy` | 3D 渲染网格 |
| 实体/出生点 | `world/entity/MixinEntity`（`cc_cubePosition` 字段随 `setPosRaw` 更新）、`MixinMinecraftServer`（`SpawnPlaceFinder` 二分找地表、出生区块半径换算） | 垂直实体跟踪 |
| 高度图 | Core 的 `SurfaceTrackerNode/Branch/Leaf`（16 叉树，`MAX_SCALE=6` 覆盖 ±2^28；格雷码增量编码，`InterleavedHeightmapStorage` 按位交织存 `.str` 文件） | **Core 中已完成的部分**，无限高度 heightmap |
| 存档 | `src_old/.../world/storage/RegionCubeIO.java`：`region2d/`（列 NBT）+ `region3d/`（cube NBT），512B 扇区 + zlib | 新代码 TODO，旧实现可参考 |

注意：**没有** `BlockPos/ChunkPos/Heightmap/GameRules` 的 mixin——坐标本身不动，动的是"谁持有方块数据"。

### 3.4 DASM：为什么不用纯 Mixin

Cube 管线需要的是"原版 Chunk 管线方法的完整复制 + 类型批量替换"（`ChunkPos→CubePos`、`ChunkAccess→CubeAccess`、`StaticCache2D→StaticCache3D`……），`@Inject/@Redirect` 表达不了"复制整个方法并换 20 个类型"。CC3 用 `io.github.notstirred:dasm:3.2.0` 构建期完成（`mixin/dasmsets/ChunkToCubeSet`、`ChunkToCloSet` 等 6 个重定向集），并在 `ASMConfigPlugin` 里后处理 `@Public/@FactoryFromConstructor`。**对本项目的启示**：allay_dimension 的 cube 管线规模若小于 CC3，可先用 Mixin `@Inject + 手写复制`（维护性差但无构建魔法），规模上来再引 DASM；两条路在文档 §8 的阶段 1 定夺。

### 3.5 CC3 给 mod 生成器的适配守则（`ModOverworldChunkGeneratorsandCC.md` 要点）

地形生成器不得硬编码 Y 界；用 `chunk.getMinBuildHeight()/getMaxBuildHeight()`；`SectionPos.of(chunkPos, chunk.getMinSection())` 收集结构 feature；JigsawJunction 过滤加 Y 边界；`LevelChunkSection + LocalY(yCoord & 15)` 放方块；检测到 CC 时不放基岩。**allay_dimension 的自定义 NoiseGeneratorSettings 必须遵守同样守则**（§4.2）。

---

## 4. 阶段 0：allay_dimension 维度本体（数据驱动，立即可做）

即使最终 cube 化，也需要先有一个"正常注册的维度"作为容器——CC3 的 HYBRID/CUBIC 也是挂在普通维度上切换 WorldStyle。**本阶段产出的 JSON 在阶段 1 后语义变化：`min_y/height` 从"真实边界"退化为"原版管线可见的窗口"（§4.4）。**

### 4.1 注册名与资源布局

维度 key：`createmanaindustry:allay_dimension`（注册名即目录名）。全部数据驱动，无需 Java 注册代码：

```
src/main/resources/data/createmanaindustry/
├── dimension_type/allay.json                 # 维度类型（id: createmanaindustry:allay）
├── dimension/allay_dimension.json            # 维度本体（id: createmanaindustry:allay_dimension）
├── worldgen/noise_settings/allay.json        # 噪声设置（可选：先用原版引用，后自定义）
└── worldgen/biome/allay_*.json               # 自定义生物群系（可选）
```

### 4.2 dimension_type JSON（1.21.1 字段集）

```json
{
  "ultrawarm": false,
  "natural": false,
  "coordinate_scale": 1.0,
  "has_skylight": true,
  "has_ceiling": false,
  "min_y": -2032,
  "height": 4064,
  "logical_height": 4064,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "effects": "minecraft:overworld",
  "ambient_light": 0.0,
  "monster_spawn_light_level": { "type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7 },
  "monster_spawn_block_light_limit": 0
}
```

要点：
- `min_y=-2032, height=4064` 是 1.21.1 合法**最大窗口**（§2.1）；这就是阶段 1 之前玩家可用的实际范围。
- `natural=false`：避免指南针乱指、床爆炸语义差异按需调整；`effects` 先用 overworld（天空渲染），后续可注册自定义 `DimensionSpecialEffects`（本项目已有 Iris/Veil 兼容层，见 `client/render/`）。
- 若希望窗口先聚焦地表带，可改 `min_y=0, height=2032` 等；窗口位置在阶段 1 变为可动态配置项。

### 4.3 dimension JSON

```json
{
  "type": "createmanaindustry:allay",
  "generator": {
    "type": "minecraft:noise",
    "settings": "createmanaindustry:allay",
    "biome_source": {
      "type": "minecraft:fixed",
      "biome": "createmanaindustry:allay_plains"
    }
  }
}
```

阶段 1 后 `generator` 在 cubic 模式下走自定义路径（列生成 passThrough + cube 生成，见 §5.2），但 JSON 仍需合法——generator 只负责窗口内地形，垂直扩展由 cube 层接手。

### 4.4 世界边界与传送入口

- XZ 边界：世界边界是**每个维度独立**的 `WorldBorder`，默认继承 overworld（±29,999,984）。无需额外代码即可满足"与 XZ 默认世界边界一致"；若要显式钉死，在 `ServerLevel` 创建后（`LevelEvent.Load`）`level.getWorldBorder().setSize(5.9999968E7)`。
- Y ±3000 万的"软件边界"：cube 化后由 `AllayDimensionLimits`（§5.5）在方块放置/实体移动/出生点查找三处 clamp，值取 `30_000_000`（余量内于 CubePos 的 ±33,554,431）。
- 传送入口（建议后续独立文档）：`/cmi dimension allay` 命令 + Allay Burner 相关传送方块；出生 Y 用 `SpawnPlaceFinder` 二分策略（§3.3）。

---

## 5. 阶段 1–2：Cube 加载层（allay 版"CubicChunks-lite"）

> 设计原则：**只对本维度生效**（WorldStyle 门控），不污染主世界/下界/末地；不引入 DASM 前先评估手写量；CC3 已验证的编码与数据结构直接复用（抄 Core 的纯逻辑类，MIT 协议保留署名）。

### 5.1 位置与常量（从 CubicChunksCore 移植的纯逻辑类）

| 类（CC3 源路径 `CubicChunksCore/src/main/java/io/github/opencubicchunks/cc_core/`） | 移植为 | 说明 |
|---|---|---|
| `api/CubePos.java` | `com.iridium126.createmanaindustry.dimension.allay.cube.AllayCubePos` | 21 bit/轴打包；cube/chunk long 用高位奇偶区分（本模没有 chunk long 冲突可简化，但保留以便对齐 CC 生态） |
| `utils/Coords.java` | `...cube.AllayCoords` | block↔cube↔section 换算；**固定 diameter=2（32 格 cube）**，不做 CC 的 1/2/4/8 可配（砍掉 EarlyConfig） |
| `api/CubeAccess.java` 骨架 | `...cube.AllayCube`（接口） | 镜像 `ChunkAccess`：`LevelChunkSection[8]`、结构引用、方块实体；heightmap 方法走 SurfaceTracker |
| `world/level/CloPos.java` | `...cube.AllayCloPos`（可选，阶段 2） | 若决定让 holder 双态复用原版类则需要；若自建独立 `CubeMap` 则可砍 |
| `world/heightmap/surfacetrackertree/*` | `...heightmap/*`（**直接移植，Core 已完成**） | 无限高度高度图：16 叉树 + 格雷码 + `.str` 交织存储，覆盖 ±2^28 |

常量（`AllayDimensionLimits`）：

```java
public static final int Y_BOUND = 30_000_000;            // 软件边界，±
public static final int XZ_BOUND = 29_999_984;           // 与默认世界边界一致
// 编码能力校验（CubePos 21 bit → ±33,554,431）：
static { assert Y_BOUND <= Coords.blockToCube(33_554_431) * 32; }
```

### 5.2 服务端加载管线（1.21.1 适配的 mixin 目标）

1.21.1 与 CC3 目标 1.21.6 的管线类名有差异（1.21.1 是 `ChunkProgressListener/ChunkMap/ChunkHolder/ServerChunkCache` 一族，无 1.21.6 的 `GenerationChunkHolder` 拆分），以下按 1.21.1 实际类列出本模需要的 mixin（全部挂 `CMIMixinPlugin`，按 `allay_dimension` 存在与 WorldStyle 判定启用，主世界路径零开销）：

| 优先级 | Mixin 目标（1.21.1） | 职责 |
|---|---|---|
| P0 | `Level`（`getBlockState/setBlockState/getBlockEntity/getFluidState` 等） | allay 维度内重定向到 `AllayCubeMap`；等价 CC3 `MixinLevel` |
| P0 | `ServerLevel` + `ServerChunkCache` | `AllayCubeCache`（环形缓存）+ `AllayCubeMap`（cube 的 ChunkMap 等价物，ticket 驱动） |
| P0 | `ChunkMap`（1.21.1：`chunkMap` 内部 holder 管理） | cube holder 生命周期；列 chunk 在 allay 维度退化为"2D 元数据列"（结构引用/POI），生成步骤 passThrough |
| P1 | `DistanceManager`/ticket 系统 | 垂直视距：每玩家 `(xzViewDist, yViewDist)` 两个圆雉（复用 CC3 `MixinPlayerTicketTracker` 思路，常量默认 yViewDist=8） |
| P1 | `PlayerChunkSender` + 新增 `ClientboundAllayCubePacket` | cube 下发；网络编码用 `AllayCubePos.asLong()` 直写 long（21 bit/轴，规避原版 section 窄类型）——**必须自带协议版本**，与 §7 voxy 无关但同理由 |
| P1 | `MinecraftServer`（出生点）+ `PlayerRespawnLogic` | `AllaySpawnPlaceFinder`：给定 (x,z) 在 [−Y_BOUND, Y_BOUND] 二分/步进找地表（surface 查询走 SurfaceTracker） |
| P2 | `Entity`（`cc_cubePosition` 字段） | 实体按 cube 分区跟踪、`cubeTickets` |
| P2 | 光照：`LevelLightEngine`/`LightEngine` | **最大坑**。CC3 未完成（恒满亮占位）。务实方案：cube 内 16³ section 用原版 `LightEngine` 逐 cube 实例化（列光照的组织假设被打散）；天空光按 cube Y 递推近似。见 §6.3 |

### 5.3 数据持久化

阶段 2 采纳 CC3 旧实现（`src_old/.../RegionCubeIO.java`）的布局，或其依赖库 `io.github.opencubicchunks:regionlib`（Maven 可得）：

```
saves/<world>/dimensions/createmanaindustry/allay_dimension/
├── region/          # 列 chunk（2D 元数据：结构引用、POI）——原版 anvil 格式，兼容工具链
├── region3d/        # cube NBT（自定义 3D region，8×8×8 cube/文件）
└── heightmaps/      # SurfaceTracker .str 文件（格雷码交织位图）
```

cube NBT 结构对齐原版 chunk section NBT（`block_states/skylight/blocklight/biomes`）以最大化 §7 voxy 导入器的可复用性。

### 5.4 与 mod 生态的兼容边界（本项目相关）

- **Create 传动网络**：`RotationPropagator` 跨 cube 的轴连接需在 `AllayCubeMap` 上做跨 cube 查询——本模内容优先，阶段 2 末验证。
- **Iris/Veil 光影（本模已有 `irisveil` mixin 组）**：自定义维度的 `effects` 若注册自定义天空，需走 `DimensionSpecialEffects` 扩展点而非 mixin。
- **存档兼容承诺**：allay_dimension 是新维度，无历史包袱；`region3d` 格式 v1 定版前加 `DataVersion` 头。

### 5.5 防越界（软件边界的三处强制）

1. 方块放置：`ServerLevel.setBlock` 的 allay 分支前置 `AllayDimensionLimits.isInBounds(pos)`；
2. 实体移动：实体 tick 中 `y` clamp（弹回而非 teleport，避免卡虚空的补偿抖动）；
3. 出生/传送：目标 Y 必须经 `AllaySpawnPlaceFinder` 解析。

---

## 6. 阶段 3：客户端（缓存/渲染/网络）

1. **`ClientAllayCubeCache`**：对齐 CC3 `ClientCubeCache.Storage` 的 3D 环形缓存（`AtomicReferenceArray<LevelCube>`，容量 = `(2·xzViewDist+1)² · (2·yViewDist+1)`），`ClientboundSetCubeCacheCenterPacket` 语义照搬（玩家跨 cube 移动时整体重定位）。
2. **渲染网格**：对齐 CC3 `MixinViewArea` 思路 —— allay 维度内 `sectionGridSizeY` 改为 `2·yRenderDist+1`、以摄像机 cube 为原点相对定位；本模已有 Flywheel/Embeddium 生态（`.refs/sodium`、`Flywheel` 在 refs 中），**Embeddium 的 `RenderSectionManager` 恰是 voxy 的 ingest 触发点（§7.2），渲染改造与 voxy 兼容必须同盘棋**。
3. **光照**：分两步——阶段 3a 客户端仅消费服务端随 cube 包下发的光照 DataLayer（对齐 CC3 包名 `LevelCubeWithLightPacket` 的承诺）；阶段 3b 客户端本地增量重算（对齐原版 `LightEngine` 接口逐 section 粒度）。
4. **网络**：`ClientboundAllayCubePacketData` 镜像原版 `ClientboundLevelChunkPacketData`（含 heightmap 字段则填 SurfaceTracker 序列化或置空+随包补发）。

---

## 7. voxy 兼容性专项分析

### 7.1 voxy 工作方式（决定兼容面的四个事实）

1. **纯客户端数据源**：实时 LoD 100% 来自客户端内存 `LevelChunk`——通过 mixin Sodium 的 `RenderSectionManager.onChunkAdded/onChunkRemoved` + `ClientLevel.setBlocksDirty` + `ClientChunkCache.drop` 触发 `VoxelIngestService.enqueueIngest`（从 `chunk.getMinSectionY()-1` 遍历 `chunk.getSections()`，从客户端 `LightEngine` 拷贝 block/sky DataLayer；**光照状态非 `LIGHT_AND_DATA` 的 chunk 直接跳过**）。**不读 region 文件做实时渲染**。
2. **维度识别全自动**：mixin `Level.<init>` 捕获维度 key 构造 `WorldIdentifier`（`SHA-256(biomeSeed + dimensionKey)`），每维度独立 `WorldEngine` 与存储目录 `{saves/<world>/voxy}/<hash>/storage/`（RocksDB+ZSTD 默认）。**allay_dimension 无需任何 voxy 侧适配即被识别**。
3. **Y 硬限制如 §2.3**：±4096 格、静默混叠、布局贯穿 Java/GLSL/磁盘。voxy **没有 Y 切片 UI**，也没有任何高度 clamp 代码。
4. **无服务端协议、无公开 API**：无 C→S/S→C 同步；唯一可用注入点是 public static 的 `VoxelIngestService.rawIngest(...)` 与 `WorldUpdater.insertUpdate(...)`；`/voxy import` 有插件式 `IDataImporter` 接口（内置 anvil/DH/Bobby 导入器）。

### 7.2 Cube 世界中 voxy 的失效模式（不做适配时的实际表现）

| voxy 依赖 | allay cube 维度中的表现 | 后果 |
|---|---|---|
| Sodium `RenderSectionManager.onChunkAdded` 收到**列 chunk** | 列 chunk 在 allay 维度是 2D 元数据壳，sections 空/窗口错位 | ingest 到空数据或错位窗口数据 |
| `chunk.getSections()` + `getMinSectionY()`（维度高度数组） | 只覆盖 §4.2 的 4064 窗口，窗口外 cube 不经过列 chunk | 窗口外区域 voxy **永远为空**（静默） |
| `LightEngine` `LIGHT_AND_DATA` 检查 | 若光照走 cube 包自定义下发，列光照状态不满足 | **整个维度 LoD 为空**（静默不 ingest） |
| 8 bit section Y key | 窗口内地形若超出 ±4096 格（窗口中心若在 Y>0 高空） | 数据混叠污染数据库（不可逆，需删库重来） |
| `RenderDistanceTracker.add`：每 XZ 列为 `minSec..maxSec` 每层建 lvl4 节点 | 若向 voxy 呈现超大 `getMaxSectionY()` | 每列百万级空节点，内存/GPU 爆炸 |

结论：**不做适配 = allay_dimension 在 voxy 上"看起来正常但地图全空"（最轻）或数据库被污染（最重，若窗口外数据进入 ingest）**。

### 7.3 三条兼容路线

**路线 A（推荐）：可视 Y 窗口 + 需要时窗口重定位（零 voxy 改动）**

把"voxy/原版管线可见的世界"限制为一个 ≤±4096 格（实操取 ±2048 更稳，见下）的**动态窗口**：

- allay_dimension 逻辑上 ±3000 万，但 `LevelHeightAccessor`（即维度 JSON 的 `min_y/height` + `ClientLevel` 呈现）始终是一个 4064 高的窗口；窗口随"主流柱"（玩家聚集团/风暴事件）的活跃 Y 缓慢重定位（下边界取 4096 的倍数，避免 key 混叠边界）。
- 玩家在窗口外的垂直探索照常进行（cube 层不感知窗口），但**窗口外不承诺 voxy 可见**——它本来就是 LoD 地图而非全息存档。
- 这正是 CC3 对原版管线"窗口化"策略与 voxy 自带 `IS_MINE_IN_ABYSS` hack（`WorldUpdater`/`IBoundStore.transformBeforeStore`/`VoxyRenderSystem` 三处成对坐标重映射，把超大世界折叠进 8 bit Y 窗口）的同构思路；MiB hack 是编译期常量不可用，但证明该模式在 voxy 数据模型内成立。
- 代价：窗口重定位时旧窗口 LoD 与新窗口不连续（key 坐标重叠）——需要服务端/本模在重定位时提示玩家 `/voxy` 数据按窗口分库（或接受少量脏数据）。**建议窗口步长与 voxy key 对齐（4096 格整数倍）以避免混叠**。

**路线 B：fork voxy 扩展 key（中成本）**

key 改分段式：`yWindow(8bit) + windowId`（利用 4 个 spare bit + 借 lvl 高位可表达 ~4096 个窗口 → Y ≈ ±16.7M；再加 XZ 各让 2 bit 可到 ±30M）。需同步改 `getWorldSectionId/getX/getY/getZ`、`SaveLoadSystem3`（磁盘迁移）、`NodeStore.writeNode`、`pos_util.glsl/node.glsl/quad_util.glsl`、`AsyncNodeManager` 邻居计算、`RenderDistanceTracker`（改为相机 Y 窗口驱动 + 稀疏 section 驱动，解决每列节点爆炸）。适合确定要"全高度 LoD"后再投入。

**路线 C：绕开 voxy ingest 链，直灌数据（配合 A 或 B）**

对窗口外/自定义光照路径，直接调用 voxy 唯一稳定注入点 `VoxelIngestService.rawIngest(engine, section, x, y, z, blockLight, skyLight)`（或底层 `WorldUpdater.insertUpdate`），从 allay 的 cube 数据自行生成 voxy section——绕开 Sodium/原版光照检查依赖。风险：voxy 无 API 承诺，需 `ModList.isLoaded("voxy")` 反射防护 + 版本探测；且直灌仍受 8 bit key 限制，只解决"触发链失效"不解决"范围"。

### 7.4 前置现实：voxy 在 NeoForge 1.21.1 的可用性

voxy 全历史无 NeoForge 构建（Fabric + 强依赖 Fabric 版 Sodium + access widener；`IS_MINE_IN_ABYSS` 所在 dev 分支已到 MC 26.2）。玩家侧现状是 **Sinytra Connector 转 Fabric**（若可用）。因此本模的"voxy 兼容"工程含义是：

1. **不主动破坏**：allay_dimension 呈现给原版管线的窗口保持标准 `LevelChunk`/光照语义（路线 A 的窗口即为此服务）——Connector 场景下 voxy 对窗口内地形**开箱即用**；
2. **文档声明**：窗口外高度不承诺任何第三方地图可见性（voxy/Xaero/JourneyMap 同理，它们同样依赖列 chunk 数组）；
3. 若后续官方出 NeoForge 版或确定 fork（路线 B/C），以 §7.3 的注入点清单为施工图。

### 7.5 voxy 兼容验收清单

- [ ] 窗口内地形（≤±2048 格相对窗口原点）在 voxy（Connector 环境）正常渲染 LoD；
- [ ] 玩家垂直穿越窗口边界（cube 层继续工作，voxy 地图在该处截止，无混叠花屏）；
- [ ] `/voxy import current` 对 allay_dimension 的 `region/`（列 chunk anvil）可导入，`region3d/` 明确报"不支持的格式"而非静默空导入（必要时提供本模 `IDataImporter`，见 §8 阶段 4）;
- [ ] 维度切换（overworld ↔ allay_dimension）voxy WorldEngine 正确切换（依赖其 `WorldIdentifier`，预期免适配，需实测）。

---

## 8. 分阶段实施路线图

| 阶段 | 内容 | 交付物 | 依赖 |
|---|---|---|---|
| **0. 维度本体**（可独立发布） | §4 JSON 三件套 + 世界边界钉死 + `/cmi dimension allay` 传送命令 + 自定义 `effects`/噪声设置初版 | `allay_dimension` 可进入、可玩（4064 窗口） | 无 |
| **1. Cube 内核** | §5.1 纯逻辑类移植（CubePos/Coords/SurfaceTracker）+ `AllayCubeMap/AllayCubeCache` + P0 mixin（Level 重定向、列生成 passThrough）+ 软件边界三处强制 | 窗口外可放置/破坏方块（仅服务端 + 内部状态，客户端暂不可见） | 阶段 0；决定 mixin 手写 vs 引 DASM（评估后预计手写 ~6 个 P0 类可控） |
| **2. 持久化 + 票据** | `region3d` cube IO + `region/` 列元数据 + 垂直视距票据 + 出生点查找 | 重启存续、多人各自垂直视距 | 阶段 1；regionlib 依赖引入评估 |
| **3. 客户端** | §6：cube 包 + 3D 客户端缓存 + ViewArea 改造 + 光照下发 | 窗口外**可视**（Embeddium/Flywell 兼容实测） | 阶段 2 |
| **4. voxy 兼容收口** | §7.3 路线 A 窗口重定位机制 + 验收清单实测 + （可选）`IDataImporter` 从 `region3d` 导入历史 | §7.5 全勾 | 阶段 3；Connector 环境 |
| **5.（远期）生态** | Create 跨 cube 传动验证、光照本地重算、B/C 路线评估 | — | 按需求 |

里程碑口径：阶段 0 独立小版本；1–3 为一个大版本（Cube 内核不拆发，拆发会出现"服务端有方块客户端看不见"的中间态）；4 随后热修。

---

## 9. 风险清单

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | CC3 未完成（光照/IO/地形 TODO），参考代码本身有坑 | 高 | 只移植 Core 中"已完成且纯逻辑"的部分（CubePos/Coords/SurfaceTracker）；管线类只抄**思路**按 1.21.1 重写 |
| R2 | 1.21.1 与 CC3 目标 1.21.6 管线 API 差异（`GenerationChunkHolder` 拆分等） | 高 | §5.2 已按 1.21.1 类名重列；每条 mixin 动工前对照 `.refs/neoforge-21.1.227` 源码核对签名 |
| R3 | 光照引擎是最大未验证领域（CC3 直接躺平） | 高 | 阶段 3a 服务端权威光照随包下发（正确性优先）；本地重算放远期 |
| R4 | voxy 8 bit Y 混叠污染玩家数据库（不可逆） | 高 | 路线 A 窗口对齐 4096 倍数；窗口外数据绝不进入原版列 chunk sections；验收清单 §7.5 |
| R5 | mixin 面积大，与 Create/Embeddium/Iris（本模 refs 全有）冲突 | 中 | 全部 mixin 挂 `CMIMixinPlugin` 按 WorldStyle 短路；本模已有 `irisveil`/`bnb` 等 mixin 组的共存经验 |
| R6 | `region3d` 自定义格式无第三方工具支持 | 中 | 列 chunk 保持标准 anvil；cube NBT 字段名对齐原版 section NBT（§5.3） |
| R7 | Y ±3000 万下实体/寻路/掉落物等原版逻辑的数值假设（重力累积、视锥剔除精度） | 中 | 软件边界内实测；必要时实体 tick 分段（长距离传送拆步） |
| R8 | 网络包体积/频率（cube 级 32³ 下发） | 低 | 镜像原版 chunk 包的增量机制（仅 dirty section 重发） |

---

## 10. 附录：参考代码索引

**CubicChunks3**（`.refs/CubicChunks3/`，MIT，目标 1.21.6 未完成）：
- 位置编码：`CubicChunksCore/src/main/java/io/github/opencubicchunks/cc_core/api/CubePos.java`、`utils/Coords.java`、`api/CubicConstants.java`
- 双工位置：`cc_core/world/level/CloPos.java`；WorldStyle：`cc_core/world/CubicLevelHeightAccessor.java`
- 无限高度图（已完成，直接移植）：`cc_core/world/heightmap/surfacetrackertree/{SurfaceTrackerNode,Branch,Leaf}.java`、`storage/InterleavedHeightmapStorage.java`
- 列 cube 映射：`cc_core/world/ColumnCubeMap.java`
- 管线 mixin：`src/main/java/io/github/opencubicchunks/cubicchunks/mixin/`（清单见 §3.3；DASM 重定向集 `mixin/dasmsets/`）
- 旧实现（≈1.21.1，含 regionlib IO）：`src_old/main/java/`
- 生成器适配守则：`ModOverworldChunkGeneratorsandCC.md`
- 常量/配置：`cc_core/config/EarlyConfig.java`、`config/CommonConfig.java`（verticalViewDistance=8）

**voxy**（`.refs/voxy/`，Fabric 0.2.19-beta）：
- section key 与 Y 限制：`src/main/java/me/cortex/voxy/common/world/WorldEngine.java`（getWorldSectionId/getY）
- ingest 管线：`common/world/service/VoxelIngestService.java`（enqueueIngest/rawIngest）、`common/voxelization/WorldConversionFactory.java`、`common/world/WorldUpdater.java`（insertUpdate、MiB 折叠 hack）
- ingest 触发 mixin：`client/mixin/sodium/MixinRenderSectionManager.java`、`client/mixin/minecraft/{MixinClientChunkCache,MixinClientLevel}.java`
- 顶层节点爆炸点：`client/core/rendering/RenderDistanceTracker.java`；渲染窗口：`client/core/VoxyRenderSystem.java`
- 维度识别：`commonImpl/mixin/minecraft/MixinWorld.java`、`commonImpl/WorldIdentifier.java`、`commonImpl/VoxyInstance.java`
- 磁盘格式：`common/world/SaveLoadSystem3.java`；GLSL Y 解包：`src/main/resources/assets/voxy/shaders/lod/pos_util.glsl`
- 导入框架（可插 `IDataImporter`）：`commonImpl/ImportManager.java`、`commonImpl/importers/{IDataImporter,WorldImporter,DHImporter}.java`
- MiB 折叠先例：`commonImpl/VoxyCommon.java`（`IS_MINE_IN_ABYSS=false` 编译期常量）

**原版 1.21.1**（`.refs/neoforge-21.1.227/`）：
- 高度常量链：`net/minecraft/core/BlockPos.java`（PACKED_*_LENGTH）→ `net/minecraft/world/level/dimension/DimensionType.java`（BITS_FOR_Y/Y_SIZE/MAX_Y/MIN_Y + codec 校验）
- 管线类（mixin 目标核对用）：`net/minecraft/server/level/{ChunkMap,ChunkHolder,ServerChunkCache,DistanceManager,PlayerChunkSender}.java`、`net/minecraft/world/level/chunk/{LevelChunk,ChunkAccess,ChunkStatus}.java`、`net/minecraft/client/multiplayer/ClientChunkCache.java`、`net/minecraft/client/renderer/ViewArea.java`

---

*本文档由 2026-09-02 的源码分析生成：CubicChunks3（含 src_old 与 CubicChunksCore）、voxy（dev@02dfb1b7）全库扫描 + NeoForge 21.1.227 反编译源核对。阶段 0 动工时无需再调研；阶段 1 动工前按 §5.2 表逐条对照 1.21.1 源签名。*
