# Allay Dimension（allay_dimension）— ±3000 万 Y 轴维度 + 现代体素渲染重写 开发文档

> 模组：CreateManaIndustry（机械动力：魔法工业）
> 版本基线：1.21.1 / NeoForge 21.1.227 / Java 21 / Mixin（已有 `createmanaindustry.mixins.json` + `CMIMixinPlugin`）
> 参考实现：`.refs/CubicChunks3`（NeoForge 21.6.4-beta / MC 1.21.6，作者声明"Not yet usable"，未完成重写）、`.refs/voxy`（Fabric 0.2.19-beta——**仅作为 GPU-Driven 体素渲染架构参考**，兼容性明确搁置）、`.refs/neoforge-21.1.227`（原版反编译源）、`.refs/sodium` / `.refs/Iris`（禁用目标与 mixin 目标核对）
> 状态：**阶段 1–2 已实现（2026-09-03）；阶段 2 冒烟测试发现 2 个客户端 bug（无碰撞坠落 / 破坏方块 AIOOBE），已修复（2026-09-04，§2.5）；阶段 3（ALLVR V0）已实现（2026-09-04，待客户端冒烟测试；同日审查修复 5 处——winding/corner 映射、草方块逐面材质表、mesher 崩溃/worker 保活、3D section 索引、增量同步原子包，见 §13 阶段 3 行）；阶段 4 经 grilling 评审定稿为 4a/4b/4c 切片（2026-09-04，决策见 §13 阶段 4 行），4a（节点树 + GPU 视锥遍历 + cmdgen + MDIC + `allvrGpuPipeline` 开关）已实现（2026-09-04，待冒烟测试）；4b（HiZ 遮挡 + 两阶段时间复用）已实现（2026-09-04，待冒烟测试）**。实现偏差与新增技术事实见 §4.2/§4.3/§2.4/§2.5/§5.2/§6.4 与 §13 阶段 1–4 行。
> 目标：
> 1. 注册自定义维度 `createmanaindustry:allay_dimension`（**阶段 1 前置任务，无独立可玩里程碑**）；Y 轴支持 −30,000,000 ~ +30,000,000；XZ 与默认世界边界一致（±29,999,984）；
> 2. **仅在该维度内**重写区块数据结构与渲染：Cube（32³）体素管线、Mesh Shader、延迟着色 + G-Buffer、GPU-Driven 视锥/遮挡剔除与 LOD 选择、draw call 合并、阴影贴图 + 光照探针**完全取代原版光照引擎**、预烘焙光照/阴影/LOD；
> 3. 维度内禁用 sodium/iris 等不兼容渲染路径（无需兼容其他 mod）；**voxy 兼容明确搁置**（§12）；
> 4. **暂不接入玩法**：游戏性光照、传送内容、自定义群系/天空统一后置阶段 7；进入维度只用原版命令。

---

## 1. 任务定义与结论速览

| 目标 | 可行性 | 路线 |
|---|---|---|
| 注册维度 `allay_dimension` | ✅ 立即可做 | 原版数据包 JSON（§4），阶段 1 前置任务（原"阶段 0 可玩窗口"已取消） |
| Y 轴 ±3000 万 | ⚠️ 原版**完全不可能**，必须自建加载层 | 借鉴 CubicChunks3 的 Cube（立方区块）架构，阶段 1–2 |
| XZ ±29,999,984 | ✅ 原生支持 | `BlockPos` X/Z 各 26 bit（±33,554,432），CC3 `CubePos` 21 bit/轴（±33,554,431），均覆盖 |
| 空岛世界生成 | ✅ 自研密度场 | `AllvrIslandFieldGenerator`（§5.3），阶段 1 |
| Cube 体素数据结构 + GPU 渲染 | ✅ 可行，有 voxy 全套先例 | 页化体素纹理 + 八叉树 LOD + 8B/quad 网格流，阶段 3 |
| GPU-Driven 剔除/LOD/draw call 合并 | ✅ 可行，voxy 已验证 | GPU 遍历 + HiZ 遮挡 + 屏幕面积下钻 + MDIC 零回读，阶段 4 |
| 延迟着色 + G-Buffer + Mesh Shader | ✅ 可行（EXT/NV_mesh_shader 探测 + MDI 回退） | 阶段 5–6 |
| 取代原版光照（CSM + 探针 + 预烘焙） | ✅ 可行且**大幅简化**原计划 | 服务端零光照 BFS：客户端 GPU 烘焙，阶段 5 |
| 游戏性玩法接入 | ⛔ **暂缓** | 阶段 7（游戏性光照/传送内容/自定义群系与天空） |
| 兼容 voxy | ⛔ **明确搁置** | §12：不投入、不主动污染；窗口外数据不进原版列 chunk |

**核心结论**：

1. **原版墙**：1.21.1 `BlockPos` 打包 X/Z 各 26 bit、Y 仅 12 bit → `DimensionType` 硬约束 `min_y ∈ [−2032, 2031]`、`height ≤ 4064`（§2.1）。±3000 万 Y 必须像 CubicChunks 一样**绕开整个"列区块 + 统一高度"模型**。
2. **CC3 能力墙**：CC3 的 `CubePos` 每轴 21 bit（方块 ±33,554,431）**恰好覆盖 ±3000 万**，其 Cube/CloPos/SurfaceTracker 架构是现成蓝图；但 CC3 面向 1.21.6 且自身未完成（光照、存档 IO、地形生成均为 TODO），**只能参考架构，不能依赖或直接移植**——尤其它躺平的光照问题，本计划用"客户端 GPU 光照"彻底绕开（§11）。
3. **渲染路线**：原版/Sodium 的"列 chunk → CPU 网格化 → 逐 section 提交"管线在 ±3000 万 Y + 无限高度下既装不下也跑不动。客户端采用 voxy 已验证的 **GPU-Driven 体素管线**（数据全驻显存、剔除/LOD/命令生成全在 GPU、整场地形 1 次 draw call），并按本项目需求升级为**延迟着色 + Mesh Shader + 预烘焙光照**（voxy 是前向着色 + 无阴影）。
4. **voxy 墙（存档为据，指导搁置决策）**：voxy 的 64 bit section key 中 Y 仅 8 bit（±4096 格），超界静默混叠污染数据库；其 ingest 链 100% 依赖"原版列 chunk + 原版光照 + Sodium 渲染"，在 allay 维度（无列 chunk 方块数据、无原版光照、Sodium 已禁用）中**自然得不到数据**——既无法兼容也不会污染（§12）。

**已定决策（2026-09-03 评审定稿，全文按此执行）**：

1. **原阶段 0 取消**——窗口内地形数据与 cube 存档不兼容、玩法暂不接入，"4064 窗口可玩"没有独立发布价值；JSON 三件套降级为阶段 1 的第一个前置任务（§4）。
2. **世界生成 = 自定义空岛密度场**：单岛 ≈ 2000×2000×200（长宽×厚），3D 格点阵列贯穿全高度；无结构、无 feature、无洞穴（V0），单步生成（§5.3）。
3. **进入维度只用原版命令**（`/execute in ... run tp`），不注册命令、不做出生点安全处理（开发期创造模式测试）。
4. **Java 命名 `Allvr` 前缀**：`Allay` 仅存于注册名/资源 id，避免与既有 18 个 `Allay*` 实体/燃烧器类混淆（§0 命名约定见各节）。
5. **持久化后移至阶段 6**：阶段 1–5 cube 纯内存态，重启按种子确定性重生成空岛。
6. **本次实施终点 = 阶段 3（ALLVR V0）**：Tier B MDI + 前向着色 + CPU 视锥 + 禁用器 + 合成光照；GPU 剔除/延迟着色/CSM/探针/Mesh Shader 属阶段 4–6。

**命名约定（全文生效）**：Java 类一律 `Allvr` 前缀（`AllvrCubePos`、`AllvrBuffers`……新增类同样带前缀）；包结构：服务端 `com.iridium126.createmanaindustry.dimension.{cube,gen,heightmap,net}`，客户端 `com.iridium126.createmanaindustry.client.dimension.{render,light}`（GL 代码不进 common 包）；shader 资源 `assets/createmanaindustry/shaders/allvr/`（平行于 `shaders/particles/`）；子系统代号 **ALLVR**。

---

## 2. 数学硬墙的源码证据

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

### 2.3 voxy：section key 中 Y 仅 8 bit（搁置决策的依据）

`.refs/voxy/src/main/java/me/cortex/voxy/common/world/WorldEngine.java`（第 91–108 行）：

```java
public static long getWorldSectionId(int lvl, int x, int y, int z) {
    return ((long)lvl<<60)|((long)(y&0xFF)<<52)|((long)(z&((1<<24)-1))<<28)|((long)(x&((1<<24)-1))<<4);
}
public static int getY(long id) { return (int)((id<<4)>>56); } // 符号扩展 → 有符号 8 bit
```

- key 布局：`lvl(4) | y(8) | z(24) | x(24) | spare(4)`。section 粒度 32 格（lvl0 section = 2×2×2 个 16³ chunk section）。
- **Y 表达范围 = ±127 section × 32 格 ≈ ±4096 格**。超界不报错：`y & 0xFF` 截断后数据互相覆盖（数据库永久污染）。
- 该布局贯穿全链路（磁盘 SSBO GLSL 邻接 remesh），且 voxy **没有 NeoForge 版本**。详见 §12。

### 2.4 补充硬墙（阶段 1 实现时确认）：SectionPos 的 Y 只有 20 bit

`net/minecraft/core/SectionPos.java`（1.21.1）：`PACKED_X_LENGTH=22, PACKED_Y_LENGTH=20, PACKED_Z_LENGTH=22`——原版把实体/POI 按 **section 坐标 long** 组织（`EntitySectionStorage`/`PoiManager`），Y 20 bit → section ±524,288 → **方块 ±8,388,592（约 ±840 万）**。±30M 范围内的方块数据不受影响（cube 层自管），但 **实体在 |Y| > ~840 万后会 section 混叠**（实体存储/按 AABB 查询可能失灵；玩家本体移动与直接读取不受影响）。64 bit 的 section long 数学上无法同时满足 26 bit XZ + ±30M Y，CC3 同样存在此限制。阶段 1 接受为已知限制；若远期需要全高实体，需自建实体索引（mixin `ServerLevel` 的 entityManager 或 `Entity` 的 section 跟踪改走 cube key）。

**另一个已踩过的坑**：`BlockPos.asLong()` 的 Y 只有 12 bit（§2.1）——**任何**自定义方块级存储都不能拿它当 key（Y>2048 混叠）。cube 内方块实体一律用 15 bit 局部索引（`AllvrCube.localIndex`）。

> **阶段 3 审查修复（2026-09-04，BE 生命周期 + cube 生命周期补全，grilling 评审定稿）**：①**BE 替换语义改为原版 `LevelChunk#setBlockState` 镜像**（双端对称，`AllvrCube.updateBlockEntity(Level, BlockPos, newState)`）——同类型 BE **保留现有实例**（原版语义；此前 chest 每次状态变更（如转朝向）都重建 BE，内容物直接丢失）、异类型替换时对旧实例 `setRemoved()`（原泄漏）；②**cube BE tick**（此前 cube BE 完全不在任何 tick 循环里，Create 机器在维度内不运转）：服务端 `AllvrCubeMap.tickBlockEntities`（每游戏 tick **无预算**全推=原版语义，距任意玩家 ≤ GEN_RADIUS(8) cube 门控=原版模拟距离语义）+ 客户端 `AllvrClientCubeCache.tickBlockEntities`（`ClientTickEvent.Post`，xz≤8/y≤4 门控——Create 旋转/搅拌动画依赖客户端 BE tick）；ticker 由 `BlockState#getTicker(level, beType)` 在创建/重绑定时解析并缓存（镜像 `LevelChunk.TickingTracker` + `RebindableTickingBlockEntityWrapper`），tick 迭代用 key 快照防 CME（镜像原版移除队列语义）；两端的"含 BE cube 登记表"避免每 tick 遍历全量 cube；③**cube 远距卸载**：`unloadFarCubes` 每 40 tick 扫描，超出**所有**玩家 forget 半径（xz>10/y>6）的 cube 从内存移除——**阶段 6 前无存档，其中玩家改动丢失**（R14 扩展，评审接受）；不加 forget 包（`forgetOutOfRange` 同半径，客户端已自清）；`setBlock` 对已卸载 cube 仍按需重生成，语义不变；④**实体冻结**：`AllvrEntityMixin` 对"所在 cube 未加载"的非玩家实体 cancel `Entity#tick`（否则卸载岛上的实体因碰撞读 air 坠入虚空；玩家豁免、客户端不冻结——均与原版一致）。

> **阶段 3 冒烟二轮修复（2026-09-04，纹理采样 + 预测确认链）**：①**逐面 UV 改用原版映射表**：`terrain.vsh` 的 `vUvLocal` 原先直接复用 mesher 的绕向 (u,v) 基 → 侧面纹理旋转 90°。现按 `FaceInfo` 顶点序 + `BlockFaceUV.getU/getV`（uv [0,0,16,16]）推出的原版逐面表计算——EAST `(16−z,16−y)`、WEST `(z,16−y)`、UP `(x,z)`、DOWN `(x,16−z)`、SOUTH `(x,16−y)`、NORTH `(16−x,16−y)`（16 偏移在 fract 下 ≡ 取负号；几何基与绕向不动）；②**半纹素 inset 改"钳制"弃"区间缩放"**：`terrain.fsh` 原先把采样区间压到 `[inset,1−inset]` → 恒显中间 15×15 纹素、边缘减半（冒烟实证）。改为 `clamp(tiled, inset, 1−inset)`——1:1 像素与原版逐像素一致，其它缩放下边缘纹素至多拉伸半纹素；MIP 梯度用未钳制的连续导数；③**权威块更新包改走原版确认链**：`applyBlockUpdate` 原先直写缓存 → 绕过 `ClientLevel#setServerVerifiedBlockState` → `BlockStatePredictionHandler` 条目仍持有破坏前状态 → 预测 ACK 的 `endPredictionsUpTo` → `syncBlockState` 把方块**写回**并把站在洞里的玩家 `absMoveTo` 弹回洞口（实证症状"破坏后进不去、卡边界"）。现与原版 `handleBlockUpdate` 同路径：有待决预测则被处理器吸收，无则落 `Level.setBlock`（mixin 照常接住）；④**新确认已知限制**：`BlockStatePredictionHandler` 以 `BlockPos.asLong()` 作 key——Y 仅 12 bit（§2.1），**|Y|>2048 时预测条目 key 混叠**，`endPredictionsUpTo` 的恢复会 `BlockPos.of` 回解码到混叠后的 Y（随机高度处写块）；窗口内（±2048）无此问题。远期若高 Y 玩法需要，需 mixin 该处理器改用 cube 安全 key（阶段 7 评估）。

### 2.5 阶段 2 冒烟测试新增硬墙（2026-09-04）：碰撞迭代器旁路 + 客户端 setBlock 无越界检查

1. **`BlockCollisions` 不走 `Level.getBlockState`**（1.21.1 `net.minecraft.world.level.BlockCollisions`；`Entity#collide`、窒息判定、`findSupportingBlock` 共用此迭代器）：按 XZ 列调 `collisionGetter.getChunkForCollisions(x>>4, z>>4)` 缓存一个 `BlockGetter` 后**直读 `LevelChunk.getBlockState`**。allay 维度该列 chunk 是空气壳、Y 越窗返回 AIR → **客户端与服务端碰撞全零**（服务端玩家同样穿岛，飞行模式下不暴露；"轮廓正常但坠落"的原因：射线检测走 `Level.clip → traverseBlocks → Level.getBlockState`，已被拦截）。修复 = common `AllvrBlockCollisionsMixin` @WrapOperation 包住 `computeNext` 的唯一 `BlockGetter.getBlockState` 调用点：allay 内客户端经 `AllvrClientBlockHook`（**common 桥类**——common mixin 字节码不得引用 client 类，客户端构造器注入 resolver）→ `AllvrClientCubeCache`，服务端走 duck cube map；未加载 cube = air，等价原版"未加载区块无碰撞"且不触发生成。**流体推送不受影响**（`Entity.updateFluidHeightAndDoFluidPushing` 走 `Level.getFluidState`，已被拦截）。
2. **`LevelChunk.setBlockState` 无 section 索引越界检查**——§5.2 原论断"get/setBlockState 自带越界检查"**有误**：仅 `getBlockState` 有（越界返回 AIR），`setBlockState` 直接 `getSection(getSectionIndex(y))` → AIOOBE。实证：客户端破坏方块预测 `MultiPlayerGameMode.startPrediction → ClientLevel.setBlock → Level.setBlock → LevelChunk.setBlockState → sections[421]`（length 254）崩；放置预测（`BlockItem.place`）同路径必崩。
3. **客户端全部方块写入汇聚于 `Level.setBlock` 4 参**（`ClientLevel.setBlock` 预测/非预测两分支都只 `super.setBlock`）→ 客户端拦截点选它：`AllvrClientLevelMixin` 扩展 → `AllvrClientCubeCache.setBlock`，镜像服务端语义（cache-miss 丢弃 = 原版"未加载区块写入失败"；BE 生命周期、发光体表、flags&1 邻居更新、updateNeighbourShapes 链；渲染通知跳过——cube 无原版 section 可重渲染，阶段 3 由 ALLVR remesh 接管）。
4. **服务端 `flags&2`（sendBlockUpdated 等价物）已兑现（2026-09-04 审查后补，原"显式后置阶段 3"作废）**：`ClientboundAllvrBlockUpdatePacket`（cubePos long 直写 + 15bit cell + `Block.getId` 状态 id）按原版"flags&2 才发"语义原子推送至订阅集包含该 cube 的玩家；客户端经 `AllvrClientCubeCache.setBlock(pos, state, 19, 512)` 应用（emitter 簿记/跨界重 mesh 自动成立）。机制较本条原计划的"dirty cube 重发"改为**原子块更新包**（交互延迟与带宽均优；/fill 级别变更按块发送，与原版一致）。**已知限制**：BE NBT 不随原子包同步（仍靠全量 cube 包/重新订阅）。

---

## 3. CubicChunks3 技术体系综述（服务端 Cube 层借鉴什么）

CC3 = `io.github.opencubicchunks/cubicchunks`（根项目，NeoForge mod）+ `CubicChunksCore`（loader 无关库：CubePos/Coords/CubicConstants/SurfaceTracker）。构建期依赖 `regionlib`（自研 3D region 存档库）与 **DASM**（字节码变换，见 §3.4）。**当前状态：地形为占位正弦波、光照 `getRawBrightness` 恒满亮、存档 `cc_read` 返回 empty——全部标注 TODO**。`src_old/` 是上一代（≈1.21.1 时期）实现，保留了 regionlib IO、旧 mixin，参考价值高。

### 3.1 核心思想：不做"更高的柱"，做"垂直堆叠的 Cube"

CC3 **不**篡改 `LevelHeightAccessor.getMinY()/getHeight()`，而是：

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
| 实体/出生点 | `world/entity/MixinEntity`（`cc_cubePosition` 字段随 `setPosRaw` 更新）、`MixinMinecraftServer`（`SpawnPlaceFinder` 二分找地表、出生区块半径换算） | 垂直实体跟踪（出生点部分本项目已砍，见 §5.2） |
| 高度图 | Core 的 `SurfaceTrackerNode/Branch/Leaf`（16 叉树，`MAX_SCALE=6` 覆盖 ±2^28；格雷码增量编码，`InterleavedHeightmapStorage` 按位交织存 `.str` 文件） | **Core 中已完成的部分**，无限高度 heightmap |
| 存档 | `src_old/.../world/storage/RegionCubeIO.java`：`region2d/`（列 NBT）+ `region3d/`（cube NBT），512B 扇区 + zlib | 新代码 TODO，旧实现阶段 6 参考 |

注意：**没有** `BlockPos/ChunkPos/Heightmap/GameRules` 的 mixin——坐标本身不动，动的是"谁持有方块数据"。**客户端渲染 mixin（CC3 的 `MixinViewArea` 等）与 CC3 的九步 3D 生成金字塔本计划均不采用**——前者被 ALLVR 完全替代（§6 起），后者因空岛生成无结构/无 feature 而退化为单步填充（§5.3），mixin 面积比 CC3 方案小一个数量级。

### 3.4 DASM：为什么不用纯 Mixin

Cube 管线需要的是"原版 Chunk 管线方法的完整复制 + 类型批量替换"（`ChunkPos→CubePos`、`ChunkAccess→CubeAccess`、`StaticCache2D→StaticCache3D`……），`@Inject/@Redirect` 表达不了"复制整个方法并换 20 个类型"。CC3 用 `io.github.notestirred:dasm` 构建期完成。**对本项目的启示**：本项目砍掉生成金字塔、光照、出生点后，剩余 mixin 面（§5.2）以手写 `@Inject + @WrapOperation` 为预计路线；若个别类（如 `ChunkMap` 的 holder 管理）复制量失控，再单独评估 DASM。

### 3.5 CC3 给 mod 生成器的适配守则（`ModOverworldChunkGeneratorsandCC.md` 要点）

地形生成器不得硬编码 Y 界；用 `chunk.getMinBuildHeight()/getMaxBuildHeight()`；`SectionPos.of(chunkPos, chunk.getMinSection())` 收集结构 feature；JigsawJunction 过滤加 Y 边界；`LevelChunkSection + LocalY(yCoord & 15)` 放方块；检测到 CC 时不放基岩。**`AllvrIslandFieldGenerator` 虽为自研（密度场逐 voxel 求值，天然无界），仍遵守其中"不硬编码 Y 界、cube 内用局部坐标放方块"的纪律。**

---

## 4. 维度注册（阶段 1 前置任务，非独立里程碑）

> 2026-09-03 决策：原"阶段 0（4064 窗口可玩）"取消——窗口内地形数据（原版列 chunk 存档）与 cube 存档不兼容、会被完全覆盖，玩法又暂不接入，独立发布没有意义。JSON 三件套是 Cube 层的**永久容器**，作为阶段 1 的第一个任务落地；不带任何可玩性承诺、不写传送命令、不做出生点处理。

### 4.1 注册名与资源布局

维度 key：`createmanaindustry:allay_dimension`（注册名即目录名）。全部数据驱动，无需 Java 注册代码：

```
src/main/resources/data/createmanaindustry/
├── dimension_type/allay.json                 # 维度类型（id: createmanaindustry:allay）
└── dimension/allay_dimension.json            # 维度本体（id: createmanaindustry:allay_dimension）
```

自定义 biome / noise_settings JSON **不引入**（biome_source 引用 `minecraft:plains`，noise settings 引用原版占位——见 §4.3）；自定义群系与天空随玩法后置阶段 7。

### 4.2 dimension_type JSON（1.21.1 字段集）

```json
{
  "ultrawarm": false,
  "natural": false,
  "coordinate_scale": 1.0,
  "has_skylight": true,
  "has_ceiling": false,
  "bed_works": false,
  "respawn_anchor_works": false,
  "piglin_safe": false,
  "has_raids": false,
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
- ⚠️ `bed_works/respawn_anchor_works/piglin_safe/has_raids` 是**必填字段**（`DimensionType` codec 无 orElse），漏写 = 维度解析失败。阶段 1 初版曾漏掉，已修正。
- `min_y=-2032, height=4064` 是 1.21.1 合法**最大窗口**（§2.1）。cube 化后这是纯**形式声明**——只为让原版管线（光照列、`LevelHeightAccessor` 假设等）有合法容差；实际可玩范围由 cube 层的 ±30,000,000 决定。**边界谓词已被 mixin 放宽**（§5.2 实况：`isOutsideBuildHeight` 在 allay 维度改判 ±30M），否则命令层 `BlockPosArgument` 会以"位置超出世界"拒绝 cube-only Y。
- `natural=false`：避免指南针乱指；`bed_works=false`：测试维度不需要床逻辑（与 natural=false 语义一致）；`effects=minecraft:overworld`：先用原版天空渲染，自定义 `DimensionSpecialEffects` 后置阶段 7。
- `has_skylight=true` / 怪物生成字段：仅剩游戏性语义，消费方在阶段 7；渲染光照与本字段无关（§11 客户端自建）。

### 4.3 dimension JSON（阶段 1 实现版：flat 空 layers = 零 mixin 列 passThrough）

```json
{
  "type": "createmanaindustry:allay",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "layers": [],
      "features": false,
      "lakes": false,
      "biome": "minecraft:plains"
    }
  }
}
```

**实现偏差说明（2026-09-03）**：原计划"列生成 passThrough"需要 mixin 原版生成管线（CC3 的 `MixinChunkStatusTasks` 思路）。实现时发现更优解——`FlatLevelGeneratorSettings` 对**空 layers 有原生支持**：`voidGen = layers.stream().allMatch(AIR)`（空列表 → true），`voidGen=true` 时 feature/decoration 全部跳过、flat 生成器无结构（flat 世界本就不带 structure sets）、`buildSurface/fillFromNoise` 对空 layers 天然无操作。这正是原版"虚空"世界类型所用的机制。结果：**列 chunk 从第一天起就是纯空气元数据壳，零 mixin、零地形**，原 P0 mixin 清单中的"列生成 passThrough"项直接删除（§5.2）。唯一方块来源 = cube 层（单一事实源，窗口内外语义一致）。

### 4.4 进入方式与边界

- **进入/离开 = 原版命令**：`/execute in createmanaindustry:allay_dimension run tp @s <x> <y> <z>`（离开同理 `execute in minecraft:overworld`）。**不做任何安全处理**（开发期创造模式测试，掉虚空/卡岛体自行飞出）；不注册自定义命令、不做出生点锚定岛、不做传送方块（均后置阶段 7）。
- XZ 边界：世界边界每维度独立，默认继承 overworld（±29,999,984）——**正确，不写钉死代码**。
- Y ±3000 万的"软件边界"：由 `AllvrDimensionLimits` 在方块放置与实体移动两处 clamp（§5.6），值取 `30_000_000`（余量内于 CubePos 的 ±33,554,431）。

---

## 5. 阶段 1–2：Cube 加载层（服务端）

> 设计原则：**只对本维度生效**（WorldStyle 门控），不污染主世界/下界/末地；CC3 已验证的编码与数据结构直接复用（抄 Core 的纯逻辑类，MIT 协议保留署名）。
> **与本计划渲染目标的关系**：服务端 cube 层只负责"权威方块数据 + 增量下发"；**光照数据完全不生成、不下发**（§11.6）——这是对 CC3 最大坑（光照躺平）的正面回答。**存档 IO 后移阶段 6**（§5.4），本阶段纯内存态。

### 5.1 位置与常量（从 CubicChunksCore 移植的纯逻辑类）

| 类（CC3 源路径 `CubicChunksCore/src/main/java/io/github/opencubicchunks/cc_core/`） | 移植为 | 说明 |
|---|---|---|
| `api/CubePos.java` | `dimension.cube.AllvrCubePos` | 21 bit/轴打包；cube/chunk long 用高位奇偶区分（本模没有 chunk long 冲突可简化，但保留以便对齐 CC 生态） |
| `utils/Coords.java` | `dimension.cube.AllvrCoords` | block↔cube↔section 换算；**固定 diameter=2（32 格 cube）**，不做 CC 的 1/2/4/8 可配（砍掉 EarlyConfig） |
| `api/CubeAccess.java` 骨架 | `dimension.cube.AllvrCube`（接口） | 镜像 `ChunkAccess`：`LevelChunkSection[8]`、结构引用、方块实体；heightmap 方法走 SurfaceTracker |
| `world/level/CloPos.java` | `dimension.cube.AllvrCloPos`（可选，阶段 2） | 若决定让 holder 双态复用原版类则需要；若自建独立 `AllvrCubeMap` 则可砍 |
| `world/heightmap/surfacetrackertree/*` | `dimension.heightmap.*`（**直接移植，Core 已完成**） | 无限高度高度图：16 叉树 + 格雷码 + `.str` 交织存储。⚠️ 消费方（游戏性天光近似）已后移阶段 7，阶段 1–3 无硬需求；因其为已完成纯逻辑、后续必需且移植成本低，随阶段 1 顺带移植（可再后移） |

常量（`dimension.AllvrDimensionLimits`）：

```java
public static final int Y_BOUND = 30_000_000;            // 软件边界，±
public static final int XZ_BOUND = 29_999_984;           // 与默认世界边界一致
// 编码能力校验（CubePos 21 bit → ±33,554,431）：
static { assert Y_BOUND <= Coords.blockToCube(33_554_431) * 32; }
```

### 5.2 服务端加载管线（1.21.1 适配的 mixin 目标）

> **阶段 1 实现实况（2026-09-03，含修复）**：实际落地的 mixin 有 4 个，远小于下表规划——`mixin/allvr/AllvrLevelMixin`（`Level` 的 `getBlockState`/`setBlock`(4 参)/`getFluidState`/`getBlockEntity` 四处 HEAD 注入，维度门控）、`AllvrLevelHeightAccessorMixin`（`isOutsideBuildHeight(I)Z` 放宽为 ±Y_BOUND，`instanceof Level` 门控排除 LevelChunk/WorldGenRegion）、`AllvrServerLevelMixin`（duck `AllvrServerLevelDuck.allvr$getCubeMap()` 挂 per-level cube map）、`AllvrEntityMixin`（`Entity#tick` HEAD 的 ±Y_BOUND clamp）。
>
> **阶段 2 实况（2026-09-04，冒烟测试修复，§2.5）**：新增 2 个 mixin，共 6 个——common 新增 `AllvrBlockCollisionsMixin`（@WrapOperation 包住 `BlockCollisions.computeNext` 的唯一 `BlockGetter.getBlockState` 调用点：原版碰撞迭代器按列 `getChunkForCollisions` 直读 `LevelChunk`、绕过 `Level.getBlockState`，allay 内双侧碰撞全零；客户端经 `AllvrClientBlockHook` common 桥路由，common 字节码不引用 client 类）；client `AllvrClientLevelMixin` 扩展拦截 `Level.setBlock` 4 参（客户端预测写入漏进空气壳列 chunk → `LevelChunk.setBlockState` 无 section 越界检查 AIOOBE 崩溃；现路由到 `AllvrClientCubeCache.setBlock`，miss 丢弃、镜像服务端语义）。**修正上文一处错误论断**：`LevelChunk.getBlockState` 有 section 越界检查（越界返回 AIR），**`setBlockState` 没有**（AIOOBE 实证）。
>
> **AllvrLevelHeightAccessorMixin 的由来（客户端测试抓到的第 3 个坑）**：`dimension_type` 的 4064 窗口除了形式声明外，还被原版**边界谓词**消费——`/setblock` 等命令的 `BlockPosArgument` 先查 `Level.isInWorldBounds`（→ `isOutsideBuildHeight`）再执行，cube-only Y 直接报"该位置已超出此世界"。修复 = 该谓词在 allay 维度改判 ±30,000,000（含端点）；随后所有越窗 touch 列区块 section 数组的调用点均已被路由/边界守卫覆盖（`Level` 读写已拦截、`LevelChunk.get/setBlockState` 自带 section 索引越界检查、heightmap/chunk 坐标与 Y 无关、`WorldGenRegion` 被门控排除保持窗口语义）。**残余的命令层拦截**：`getLoadedBlockPos` 还要求**列 chunk 已加载**（列坐标，Y 无关）——测试远 Y 方块时须先让玩家 tp 到目标附近（原版票据加载该列），或对该列 `/forceload`。**原因**：①列 passThrough 由 §4.3 的 flat 空 layers 方案零 mixin 解决；②cube 加载不走原版 ticket——`AllvrCubeMap` 自驱动（`LevelTickEvent.Post` 逐玩家 shell 加载 + 每帧时间预算 + 传送时 3×3×3 同步环），`ServerChunkCache/ChunkMap/DistanceManager` 的 mixin 在纯内存阶段全部不需要；③方块读对未加载 cube 返回 void air（镜像原版未加载区块语义），写按需生成（镜像原版 `setBlock` 的 `getChunkAt` 创建语义）。下表保留为阶段 2（网络票据）与玩法阶段（7）的规划参照。

1.21.1 与 CC3 目标 1.21.6 的管线类名有差异（1.21.1 是 `ChunkProgressListener/ChunkMap/ChunkHolder/ServerChunkCache` 一族，无 1.21.6 的 `GenerationChunkHolder` 拆分），以下按 1.21.1 实际类列出本模需要的 mixin（全部挂 `CMIMixinPlugin`，按 `allay_dimension` 存在与 WorldStyle 判定启用，主世界路径零开销）：

| 优先级 | Mixin 目标（1.21.1） | 职责 |
|---|---|---|
| P0 | `Level`（`getBlockState/setBlockState/getBlockEntity/getFluidState` 等） | allay 维度内重定向到 `AllvrCubeMap`；等价 CC3 `MixinLevel` |
| P0 | `ServerLevel` + `ServerChunkCache` | `AllvrCubeCache`（环形缓存）+ `AllvrCubeMap`（cube 的 ChunkMap 等价物，ticket 驱动） |
| P0 | `ChunkMap`（1.21.1：`chunkMap` 内部 holder 管理） | cube holder 生命周期；列 chunk 在 allay 维度退化为"2D 元数据列"（结构引用/POI），生成步骤 passThrough |
| P1 | `DistanceManager`/ticket 系统 | 垂直视距：每玩家 `(xzViewDist, yViewDist)` 两个圆雉（复用 CC3 `MixinPlayerTicketTracker` 思路，常量默认 yViewDist=8） |
| P1 | `PlayerChunkSender` + 新增 `ClientboundAllvrCubePacket`（`dimension.net`） | cube 下发；网络编码用 `AllvrCubePos.asLong()` 直写 long（21 bit/轴，规避原版 section 窄类型）；**包内只有方块数据 + 方块实体 + 光源事件表，无任何光照 DataLayer**（§11.6）；自带协议版本号 |
| P2 | `Entity`（`cc_cubePosition` 字段） | 实体按 cube 分区跟踪、`cubeTickets` |

**已砍 mixin（2026-09-03 决策，相对 CC3 清单）**：
- 出生点查找（CC3 `SpawnPlaceFinder`/`MixinMinecraftServer`/`PlayerRespawnLogic` 一族）——不做出生点安全，进入只用原版 `execute in + tp`；
- 服务端游戏性光照近似（`LevelLightEngine`/`getRawBrightness` server 侧）——随玩法后移阶段 7（机制预案见 §11.6）；
- 原版九步生成金字塔（`ChunkStep/ChunkPyramid` 的 cube 变换）——空岛生成无结构/无 feature，单步填充替代（§5.3）。

### 5.3 空岛世界生成（`dimension.gen.AllvrIslandFieldGenerator`，阶段 1）

**形态（决策定稿）**：单岛 ≈ **2000×2000×200**（长宽×厚）的大型空岛，以 **3D 格点阵列**遍布整个世界——XZ 间隔约 2500–3000（岛宽 + 飞行空隙），Y 间隔约 400–600（岛厚 + 层间空隙），**层间 XZ 错位**（半格偏移，缝隙漏光物理正确），从 −Y_BOUND 到 +Y_BOUND 贯穿。垂直飞行永远有下一层岛，垂直探索即维度核心玩法，立方架构物尽其用。

**密度场（纯函数、种子确定性——阶段 6 前无存档，重启重生成依赖此性质）**：

```text
// 每个格点必生成一岛（位置/尺寸抖动），任意坐标 ~3000 格内必有岛面
islandStateAt(p):                                    // p 世界坐标
  cellXZ = floor(p.xz / SPACING_XZ); layer = floor(p.y / SPACING_Y)
  density = 0
  对 (cellXZ ± 1, layer ± 1) 的 27 个候选格点:        // 岛半径 < 间隔，±1 邻域足够
    h = hash(cellXZ, layer, worldSeed)               // 稳定哈希
    center = 格点中心 + 抖动(h)                       // xz ±300, y ±80；奇数层 xz 半格偏移
    size   = (2000±20%, 200±20%)                     // 由 h 决定
    local  = (p - center) / size                     // 归一化坐标
    d = smax(|local.x|,|local.z|,|local.y|) + edgeFBM(p, h)   // 平滑max + 边缘锯齿(±40)
    density = max(density, 1 - d)                   // 岛体并集
  return density                                     // >0 = 实体

// 表层规则：density ∈ (0, 0.05) → 草；(0.05, 0.25) → 土；其余 → 石（底面渐缩由 smax 自然形成）
```

**生成管线（单步）**：票据触发 cube 加载 → 对 32³ 每 voxel 求 `islandStateAt` → 写入 `AllvrCube` 的 `LevelChunkSection[8]`（局部坐标 + `LocalY(y&15)`，§3.5 守则）→ 完成。**无结构起点、无 feature、无九步 status 依赖**；候选格点集（27 个）每 cube 求一次缓存在生成上下文，避免逐 voxel 重算哈希。均匀性：岛内部大片纯石 → `PalettedContainer` 单值调色板天然近零成本（§7.1 客户端简写的服务端侧前提）。

**V0 明确不做**（保护均匀 cube 简写 + 控制范围）：岛屿内部洞穴雕刻、小岛点缀、尺寸/材质变体——全部后置为密度场参数。

### 5.4 数据持久化（后移至阶段 6）

- **阶段 1–5：cube 纯内存态**。世界重启后空岛按种子**确定性重生成**（§5.3 纯函数性质），玩家放置/破坏的方块丢失——已知限制，开发期创造测试可接受。
- **阶段 6 定版存档格式**：届时采纳 CC3 旧实现（`src_old/.../RegionCubeIO.java`）布局或 `io.github.opencubicchunks:regionlib`；目录规划 `region/`（列 2D 元数据，原版 anvil）+ `region3d/`（cube NBT，含届时已存在的光照缓存/mesh 缓存一并序列化）+ `heightmaps/`。**数据结构稳定前不定格式**——阶段 4–5 会给 cube 增加探针/LPV/mesh 缓存内容，提前定版等于自找版本迁移。
- cube NBT 字段名届时对齐原版 chunk section NBT（`block_states/biomes`；不写光照字段）。

### 5.5 与 mod 生态的兼容边界

- **Create 传动网络**：`RotationPropagator` 跨 cube 的轴连接需在 `AllvrCubeMap` 上做跨 cube 查询——远期（阶段 7）验证。
- **渲染 mod（sodium/embeddium/iris/flywheel）**：见 §6.4，维度内禁用其地形/光影路径，实体路径保留。
- **voxy**：§12 搁置。

### 5.6 防越界（软件边界的两处强制）

1. 方块放置：`ServerLevel.setBlock` 的 allay 分支前置 `AllvrDimensionLimits.isInBounds(pos)`；
2. 实体移动：实体 tick 中 `y` clamp（弹回而非 teleport，避免卡虚空的补偿抖动；`tp` 跨维度亦走实体移动路径被覆盖）。

---

## 6. 客户端总体架构：ALLVR 体素渲染器

> 目标只有一句：**在 allay_dimension 内，把"列 chunk + CPU 网格化 + 前向逐 section 提交"整条管线，替换为"GPU 驻留体素 + GPU 剔除/LOD/命令生成 + 延迟着色"**，且渲染线程 CPU 负载与视距/高度近似解耦。

### 6.1 设计目标与性能预算

| 指标 | 目标 | 手段 |
|---|---|---|
| 不透明地形 draw call | **整场 1 次**（+ 阴影 4 级联 × 1） | MDIC（`glMultiDrawElementsIndirectCountARB`），命令由 GPU compute 生成 |
| 渲染线程 CPU 每帧开销 | 稳态 < 1 ms（与视距无关） | 剔除/LOD/排序/命令全 GPU；CPU 只发 ~10 个 dispatch + 少量 upload |
| CPU↔GPU 每帧数据 | 只有"变更集"：新 cube 的体素页 + 脏 cube 列表 | 持久映射环形上传（`glBufferStorage` + `GL_MAP_PERSISTENT_BIT`），预算 ≤ 2 MB/帧 |
| 回读 | **零阻塞回读**（fence + N 帧延迟） | 复用粒子引擎 fence/回读纪律（§6.5） |
| 光照更新 | **零原版光照更新**；变更 cube 触发 GPU 增量重烘焙（预算/帧） | §11 探针 + LPV + CSM |
| 内存 | 体素页 ≤ ~320 MB、quad arena ≤ ~512 MB、G-Buffer ~12 B/px（见 §7.4） | LRU 逐出 + **均匀 cube 零页**（§7.1） |
| 兼容性 | GL 4.6 起步；Mesh Shader 按扩展探测；无扩展自动回退 MDI | §6.2 能力分层 |

约束（优先级从高到低）：
1. 只改 allay 维度——其它维度走原版/Sodium 路径，零影响；
2. 维度内禁用 sodium/iris 的修改（§6.4），不追求与其兼容；
3. 不兼容其它渲染 mod 的地形/光影路径，实体类路径尽量保留（vanilla forward）。

### 6.2 GPU 能力分层

| Tier | 条件 | 路径 |
|---|---|---|
| A | GL 4.6 + `GL_EXT_mesh_shader` 或 `GL_NV_mesh_shader`（NVIDIA Turing+ / AMD RDNA2+ 新驱动） | task+mesh shader 绘制（§9.5）；剔除仍在 compute |
| B | GL 4.5/4.6（有 `ARB_indirect_parameters`，无 mesh shader 扩展） | MDI + 无属性 VAO 顶点拉取（voxy `quads3.vert` 模式，§9.4） |
| C | 低于上述 | 不进维度渲染：聊天栏提示；维度内地形不可见（列 chunk 是空壳，本就没有原版地形可退回），实体/粒子照常 |

探测在管线初始化时一次完成（`AllvrRenderCaps`，缓存 `Capabilities` 引用的模式抄 voxy `gl/Capabilities`）。Tier A/B 共享全部数据结构，仅 draw 路径不同。

### 6.3 帧结构与渲染挂载点

沿用粒子引擎验证过的**帧拆分双阶段**模式（`CreateManaIndustryClient.onRenderLevelStage`）：

```
帧结构（allay 维度内）：
AFTER_SKY（原版地形本该渲染的时段）
  ├─ [compute] 异步烘焙消费：脏 cube 的探针/LPV/mip 更新（预算切片）
  ├─ [compute] 八叉树遍历：视锥 + HiZ 遮挡 + 屏幕面积下钻 → 可见节点队列（§9.1–9.3）
  ├─ [compute] 可见列表致密化 → section/meshlet 列表；半透明距离分桶（§9.4）
  ├─ [compute] cmdgen：生成 MDI 命令缓冲 + dispatch 参数（§9.4）
  ├─ [draw]   G-Buffer 不透明 pass：Tier A 走 task/mesh、Tier B 走 MDIC —— MRT×3 + 借用 MC 主深度
  ├─ [compute] HiZ 金字塔构建（供下一帧遮挡剔除）
  └─ [draw]   延迟光照 pass（全屏）：PBR + CSM + 探针 + LPV + 点光 → RGBA16F HDR → ACES → MC 主颜色
原版实体/方块实体/粒子（vanilla forward，用 §10.4 合成光照）
AFTER_TRANSLUCENT_BLOCKS（原版半透明地形时段）
  └─ [draw]   半透明前向 pass：水面等（GPU 距离桶排序 → 半透明 MDI，§10.3）
AFTER_LEVEL
  └─ 粒子引擎照常（其自绘路径；光影包合并路径在 allay 维度自动旁路，§6.4）
```

挂载方式：mixin `LevelRenderer#renderSectionLayer`（allay 时 HEAD-cancel 原版地形层并触发上述序列）+ `RenderLevelStageEvent`（`AFTER_TRANSLUCENT_BLOCKS` 半透明、`AFTER_LEVEL` 兜底状态恢复）。维度门控 `AllvrRenderController.isActive()` = `Minecraft.getInstance().level.dimension() == ALLAY_KEY && caps ≥ Tier B`，每帧求值——进出维度即热切换（`LevelEvent.Unload/Load` 同步清理，复用粒子引擎的 `onLevelUnload` 模式：必须过滤 `ClientLevel` 且同步执行）。

### 6.4 原版/第三方渲染路径的禁用（维度内）

统一入口 `AllvrIncompatDisabler`（静态注册表，每项 = 检测旗标 + 禁用 mixin），当前三项：

| 目标 | 禁用方式 | 依据/模板 |
|---|---|---|
| **原版地形** | mixin `LevelRenderer#renderSectionLayer` + `SectionRenderDispatcher/ViewArea`（allay 时跳过网格调度与提交） | 粒子引擎 `LevelRendererBlockEntitiesMixin` 的注入点经验（`popPush("blockentities")` 精确窗口） |
| **sodium/embeddium 地形** | 条件 mixin 组 `.sodium.`（`CMIMixinPlugin` 按包名分组 + `FMLLoader.getLoadingModList()` 探测）：`DefaultChunkRenderer.render` HEAD-cancel（保留 begin/end 状态机）+ `RenderSectionManager` 在 allay 跳过 ingest/重建；`remap=false`，类名以 `.refs/sodium`（0.6.x for 1.21.1）核对；embeddium 若类路径不同则加 `.embeddium.` 组分别注册 | voxy `MixinDefaultChunkRenderer` 的 HEAD-cancellable 模式；本项目 `CMIMixinPlugin` 已有 `.iris.`/`.trickster.` 等分组先例；build.gradle 需加 sodium `compileOnly` |
| **iris 光影包** | 条件 mixin 组 `.iris.` 新增 1–2 个：allay 维度内强制 `isShaderPackInUse() == false`（@ModifyReturnValue）+ `ShadowRenderer.renderShadows` 跳过（我们自建 CSM，§11.1）。目标签名以 `.refs/Iris`（1.21.1）核对 | voxy `IrisUtil.disableIrisShaders()/irisShadowActive()` 的语义；本项目已有 5 个 `.iris.` mixin 的成熟分组 |

连带处理：
- **irisveil 粒子合并路径**：`CMIPackEntityMergeHook` 入口加维度判断——allay 内直接返回 false，粒子引擎自动回退自绘路径（现有"hook 成功→跳过自绘"闩锁机制天然支持）。
- **Veil 后处理**：不受影响（作用于最终帧，与地形管线正交）；若与我们的 ACES/bloom 双重 tonemap 冲突，allay 内跳过 Veil 的 bloom 类 pass（`VeilEventPlatform` 钩子已有维度上下文）。
- **flywheel（Create 方块实体 instancing）**：保留——它是 forward + 深度测试路径，与 G-Buffer pass 共用 MC 深度即可正确遮挡；光照来自 §10.4 合成光照。实测异常再入 `AllvrIncompatDisabler`。
- 上述所有 mixin 方法体**第一行**查静态旗标（`SODIUM_ACTIVE`/`IRIS_ACTIVE` + 维度判断），依赖 mod 缺失时目标类永不加载（`IRISVEIL_ACTIVE` 类加载隔离先例）。

**阶段 3 实况（2026-09-04，含对上表的修正）**：

1. **iris 预案不成立，已改为"时段自适应共存"**。源码核实：`isShaderPackInUse()` 是 `IrisApiV0Impl` 给**其它 mod 的查询 API**——iris 自身的帧重构（`MixinLevelRenderer` 在 `renderLevel` HEAD / renderSky / clouds / weather / `renderSectionLayer` 前后约 20 个注入点的 gbuffer 捕获与 composite 链）由 `PipelineManager` 驱动，**不查该 API**。强制它为 false 改变不了 iris 管线，只会误导其它 mod（含 sodium 的 iris 兼容层）；voxy 的先例也是共存而非禁用（`IrisUtil.disableIrisShaders()` 是全局改用户配置，不满足"仅维度内"）。**V0 方案**：无 pack → AFTER_SKY 画（正常路径）；`IRIS_ACTIVE && IrisApi.isShaderPackInUse()` → 改在 **AFTER_LEVEL**（iris final blit 回主帧缓冲之后）画 + 一次性聊天提示——地形以"无 pack 光照"形态可见。`renderShadows` 不砍（保住实体阴影与 irisveil 钩子）；**不新增 `.iris.` mixin**。pack 下的排序/光照瑕疵后置阶段 4–5。
2. **sodium 预案简化**：sodium 自己的 mixin 拦截 `renderSectionLayer`→转调 `DefaultChunkRenderer.render`，**取消原版方法拦不住它**（同一方法两个 HEAD 回调都会跑），所以单独取消 `DefaultChunkRenderer.render`（sodium 0.6 只此一个渲染入口，`.refs` 核实无 GL46 子类）。**`@Mixin(targets="net.caffeinemc...")` 字符串目标 + `remap=false`，build.gradle 不加 sodium 依赖**（上表"需加 sodium compileOnly"作废——离线也安全）；挂 `CMIMixinPlugin` 新增 `.sodium.` 包门控。`RenderSectionManager` 的 ingest-skip **未做**（空气壳 section 空网格成本近零，等 profile 显示有意义再加）。
3. **原版地形禁用**：`LevelRenderer#renderSectionLayer`（1.21.1 签名 `(RenderType,DDDLMatrix4f,Matrix4f)`）HEAD-cancel，allay 门控。空气壳本就画不出东西——禁用的意义是所有权契约（ALLVR 独占地形时段）+ 省每帧空 section 遍历。
4. **Tier C 判据**：per-command 数据经 `gl_BaseInstance/gl_BaseVertex`（GL 4.6 core / ARB_shader_draw_parameters）取——无该能力则地形整体不可见（聊天提示一次），其余路径（实体/粒子）照常。
5. **`AllvrIncompatDisabler` 静态注册表未建**——V0 禁用面只有 2 个 mixin + 渲染器侧的 pack 探测，检查内联在各自文件里；规模到 3+ 项（阶段 5 的 CSM/禁 Veil bloom）时再建。连带处理两条同样后移：irisveil 合并钩子的维度门控**不需要**（V0 不禁 iris，钩子照常工作）；Veil bloom 冲突到阶段 5 才存在。

### 6.5 可复用基础设施（粒子引擎 → ALLVR 映射表）

| 粒子引擎资产（`client/particles/engine/`） | ALLVR 复用为 | 说明 |
|---|---|---|
| `ParticlePrograms`（自托管 GLSL 编译：`#version 450` + PRELUDE `#define` 注入 + `#pragma cmi_include` + F3+T 热重载） | `AllvrShaderCache` | 全部 compute/traversal/cmdgen/mesh/deferred 程序同一套编译骨架；PRELUDE 由 §7 的绑定常量生成；资源根 `shaders/allvr/` |
| `ParticleBuffers`（SSBO 绑定号 Java 单源常量、间接命令 20B 步长、环/双缓冲/原子计数管理） | `AllvrBuffers` | 体素页表/节点树/quad arena/命令缓冲的绑定号与布局单源化 |
| 间接绘制提交（`glDrawArraysIndirect`/`glMultiDrawElementsIndirect` 混排 20B 纪律） | MDIC 提交层 | 升级为 `glMultiDrawElementsIndirectCountARB`（计数在 `GL_PARAMETER_BUFFER`） |
| GPU 视锥剔除（JOML `frustumPlane()` Gribb–Hartmann 6 平面 + `keygen.comp` 球测试） | 遍历 compute 的视锥段 | 平面提取代码直接搬 |
| GPU 排序（radix hist/scan/scatter 三件套） | 半透明距离桶排序 | voxy `buildtranslucents` 的近似分桶 + 本模 radix 细化 |
| fence 零停等回读 + `GL_TIME_ELAPSED` 双计时环 + EMA 节流 | 遍历请求回读/烘焙预算/性能 HUD | `GpuTimerRing`/`ParticleFrameProfiler` 直接复用 |
| GL 状态离场卫生（分组 try/finally 恢复 program/VAO/SSBO 基/纹理单元/depthMask/blend） | 所有自管 pass | 纪律原文照抄（踩坑 #32） |
| `ParticleGLUtil.prepareClientUpload()`（PBO/unpack 状态防护） | 体素页纹理上传 | 3D 纹理 `glTexSubImage3D` 前必调（踩坑 #13） |
| `CollisionBake`（3D 纹理后台烘焙 + LRU） | 体素页管理的 LRU/预算范式 | 模式复用，数据结构换 §7 |
| 无属性 VAO + SSBO vertex pulling（`model.vsh` 的 `gl_VertexID` 拉取） | Tier B 顶点路径 | 与 voxy `quads3.vert` 同构，二选一 |
| 帧拆分双阶段（AFTER_SKY compute / AFTER_LEVEL draw + hook 闩锁仲裁） | §6.3 帧结构 | 已验证与光影包/实体窗口共存 |

shader-dev skill（`.agents/skills/shader-dev/`）取材点：
- `techniques/voxel-rendering.md`：DDA 遍历（初始化/branchless 步进/命中提取）用于 §11.4 体素射线阴影、探针天空可见性烘焙与 §10.4 合成天光的垂直射线；`vertexAo(side, corner)` 邻居 AO（与 MC 平滑光照同构，含防漏光 `side.x*side.y`）用于延迟 pass 逐像素 AO（§10.2）；锥追踪（`lod = log2(diameter)` 的 mip 采样）用于探针烘焙的一弹 GI 评估。
- `techniques/lighting-model.md`：Cook-Torrance 全套（D_GGX/F_Schlick/V_SmithGGX 高度相关、`F0 = mix(0.04, albedo, metallic)` 能量守恒）直接作为 §10.2 延迟光照内核；三光源模型（太阳直射/半球天光/地面反弹）映射为"CSM 太阳 + SH 天光探针 + 探针反弹"。
- `techniques/multipass-buffer.md`：G-Buffer 打包/FBO ping-pong/首帧 initPass 红线；`techniques/post-processing.md`：ACES、MIP 金字塔 bloom、TAA YCoCg 邻域 clamp；`techniques/shadow-techniques.md`：软阴影公式（作 CSM PCF 之外的近场 DDA 软影参考）；`techniques/terrain-rendering.md`：距离自适应误差阈值（`0.0015·t`）原则用于 LOD 屏幕误差判定。
- skill 未覆盖、需自行设计的：CSM（级联矩阵/纹素偏移）、greedy meshing、clipmap 式 LOD 组织——本文档 §8/§9/§11.1 给出。

---

## 7. GPU 数据结构设计

### 7.1 体素存储：页化 3D 纹理 + 材质表 + 均匀 cube 简写

**服务端 → 客户端**：`ClientboundAllvrCubePacket` 携带调色板压缩的 32³ 体素（`Map<stateId, palette>` + 索引位流，格式同原版 section NBT 的 palette 思路）+ 方块实体列表 + 光源事件（新增/移除的 `getLightEmission>0` 方块，§11.3）。**均匀 cube（岛内部纯石等）单值调色板 → 包仅数字节**。

**客户端 worker 线程**：解包为 34³ 的 **R8UI 页**（32³ 数据 + 1 圈 padding，从 26 邻居 cube 填充；邻居变更时标记对应 padding 重填）→ 持久映射环形缓冲上传。材质索引即"渲染态 id"（`AllvrRenderStateMap`：BlockState → 16 bit 渲染态，资源加载期由 BlockColors/ModelBakery 构建，含 sprite UV、不透明/cutout/translucent 分类、发光强度、金属度/粗糙率默认值、非整块模型 id）。

**均匀 cube 简写（空岛世界的必要扩展）**：巨岛内部大片纯石头，不能每个 32³ 都上传 39 KB 体素页——客户端页表加 `uniform:stateId` 条目：**零页存储、零上传**，体素查询/mesher/G-Buffer AO 对"邻居为 uniform"照常应答（直接返回该 stateId）。没有它，2000×2000×200 空岛的页预算直接爆炸（每岛 XZ 62×62 × Y 6~7 ≈ 2.4 万 cube）；有了它，只有岛屿表面壳层（每岛 ~8–9k cube，近场范围内仅其切片）占页。这也是 V0 不开洞穴的原因（洞穴会把内部打回非均匀，简写失效）。

**GPU 侧**：
- `uimage3D voxelAtlas`：tiled 3D 图集，`W_pages × 34 × 34` 布局（每页 34×34×34 texel；W=8192 页 → ≈ 320 MB，与 §6.1 预算一致）；页号由自由表分配，LRU 逐出（§7.4）。`texelFetch` 邻居访问天然越页安全（padding 保证单页内自洽）。uniform 条目不占页号（页表项的高位标记）。
- `samplerBuffer stateTable`：渲染态 id → 材质参数（sprite 原点/尺寸、flags、emissive、roughness/metallic）。
- **原版方块图集直接复用**：fragment 采样 vanilla `SamplerBlockSheet0`（blocks atlas），动画帧（water/岩浆）由原版 atlas 更新机制免费获得；`stateTable` 提供各自 sprite 的当前帧偏移（动画图集的帧位移在 CPU 侧每 tick 查 `TextureAtlasSprite` 更新一个小的 SSBO）。
- mip：体素页**不建硬件 mip**（R8UI 不支持）；LOD 用 §7.2 节点的粗粒度 mesh 表达（与 voxy 一致：LOD=合并网格而非体素 mip）。探针烘焙需要粗可见性时用节点 AABB 层级剔除（§11.2）。

### 7.2 LOD 八叉树节点 SSBO（借鉴 voxy NodeStore，Y 扩展）

世界划分为节点八叉树（4 层）：`L0 = 32³ cube`（全分辨率）、`L1 = 64³`、`L2 = 128³`、`L3 = 256³`、顶层 `L4` 可选列式（512×512×全高，按需）。节点 GPU 表示 **16 B/节点**（`std430 uvec4 nodes[]`）：

```
xy = 打包位置：lvl(4b) | y(有符号 26b) | z(24b) | x(24b)   ← 与 voxy 的 8b y 不同，Y 用 26 bit（±33.5M，对齐 CubePos）
z  = meshPtr(24b，NULL/EMPTY 哨兵) | flags(8b)
w  = childPtr(24b，子节点连续 8 个分配) | flags'(8b)
```

- CPU 侧 `AllvrNodeStore` 用 `HierarchicalBitSet` 连续批量分配（子节点 `childPtr+i`），`writeNode` 压缩为 16 B——**直接照 voxy 实现**，仅 Y 位宽改 26。
- 顶层的加载/卸载由 `RenderDistanceTracker` 式环形增量驱动（每帧限速 N 个），垂直方向按玩家 Y 圆锥裁剪——CPU 只管理顶层节点（数量 O(环面积)），下层细分由 GPU 遍历驱动请求。
- 节点写入用 scatter-write compute（避免多次 `glBufferSubData`，voxy `AsyncNodeManager` 模式）。

### 7.3 Mesh 格式：8 B/quad 流 + meshlet 索引

**quad 流**（持久驻留的 `geometryArena` SSBO，8 B/quad，voxy 格式按 32 格 cube 调整）：

```
uint64 打包：
  axis(2b) | dir(1b) | uSize-1(5b) | vSize-1(5b) | u(5b) | v(5b) | w(5b)
  | stateId(16b) | lightSlot(8b) | reserved(12b)
  （32³ cube 内：pos 0..31 需 5b；size 1..32 → 存 size-1 需 5b；axis+dir+3×pos+2×size = 28b 几何位）
```

位预算精确核算：`28 + 16 + 8 = 52 bit，余 12 bit`（reserved/未来 AO 粗值）。greedy 合并上限 32（一个 cube 的边长），天然满足。

- **全不透明方块** → greedy 合并 quad（§8.1）；**非整块模型**（楼梯/栏杆/含水等）→ 从烘焙的模型几何 SSBO（`modelData[modelId]`，voxy 同构：faceData 面片表）按方块实例发射模型 quad（同模型相邻可做 run 合并，M1 优化项）。
- 每 quad 隐含 4 顶点/2 三角；**全局共享索引缓冲**：16380-quad 的 `0,1,2,2,1,3` short 索引 + 末尾一个 byte 立方体索引（遮挡测试 raster 用）——照抄 voxy `SharedIndexBuffer`，`firstIndex=0 + baseVertex=quadStart*4` 定位。
- **meshlet 表**（Tier A 用）：mesher 顺带按 64 quad（256 顶点上限内）分组，记录 `meshlet{ quadStart, quadCount, aabb }` 到独立 SSBO，task shader 按 meshlet AABB 剔除（§9.5）。
- 半透明 quad 单独分组流（8 组分类：半透明|双面、down|up、north|south、west|east——照 voxy `SectionMeta` 的 8×16bit 计数布局），天然支持 §10.3 分桶。

### 7.4 内存预算与逐出

| 资源 | 默认预算 | 逐出策略 |
|---|---|---|
| 体素页（34³ R8UI × 8192 页；**均匀 cube 零页**） | ≈ 320 MB（空岛世界实际占用 = 表面壳层切片，远低于上限） | 仅 L0 全分辨率节点持有；近场 LRU 逐出（mesh 存活、页回收、重进近场重上传） |
| quad arena | 512 MB（稀疏缓冲 `ARB_sparse_buffer` 按页提交，voxy 兜底模式） | GPU 端可见性 LRU：`sort_visibility.comp` 找最久未见节点 → 回读驱逐（voxy `NodeCleaner` 模式；几何堆可跨维度复用） |
| 节点 SSBO | 2²¹ × 16 B = 32 MB | 随节点卸载回收 |
| G-Buffer（§10.1） | 3 × RGBA8 + 共享深度 ≈ 12 B/px | — |
| 探针/LPV（§11.2/11.3） | 探针体积 ~64 MB + LPV ~16 MB | 脏区域增量更新 |

### 7.5 上传/回读流

- **上行**：`UploadStream`（持久映射环形缓冲，每帧 ≤ 2 MB 预算，超限 `needsWaitForSync` 背压）——体素页、节点 scatter 写集、mesh 数据、stateTable 更新全走它；提交前 `ParticleGLUtil.prepareClientUpload()`。
- **下行**：`DownloadStream`（PBO + fence，N 帧延迟消费）——只有两种回读：①遍历产生的"请细分节点"请求（去重后 ≤ 64/帧），②驱逐候选列表。渲染路径零回读。
- worker 线程与渲染线程交接用"结果集原子引用"（voxy `RESULT_HANDLE` 模式 + 本模粒子引擎的 fence 纪律）。

---

## 8. 网格化（Meshing）

### 8.1 贪心合并网格化（greedy meshing）

标准三轴扫描：对每个轴 × 两个方向，做 32×32 的 2D 面罩动态合并（同 stateId 且同为"暴露面"合并为最大矩形，上限 32）。暴露面判定 = 面邻体素 `stateTable.opaque == false`（cutout 类视作非遮挡，与原版语义一致）。输出 8 B/quad 流 + 8 组计数。复杂度 O(32³/轴)，单 cube 微秒级。

**均匀 cube 的免费收益**：查询走 `uniform:stateId` 简写（§7.1）；全封闭 cube（六面邻居皆不透明）产出**零 quad**——空岛内部大量 cube 走此路径，mesher 与 arena 双双受益。

### 8.2 计算位置：CPU worker 起步，GPU compute 为优化目标

- **M0（起步）**：CPU worker 池 + 优先级队列（近相机、屏占比高者优先），voxy `RenderGenerationService` 模式。读 34³ 页（含 padding，无需跨页查询），产出 quad 流 → `UploadStream`。渲染线程零参与。
- **M1（优化）**：GPU compute mesher——workgroup 处理一个 cube（34³ 预取 shared memory 放不下 → 分轴 slice 处理），直接写 arena + `meshPtr` 原子发布，省去 CPU mesh 与上行带宽。作为阶段 6 优化项，**M0/M1 输出格式完全一致**，可灰度切换。
- 网格化触发：新页上传、页 padding 重填、`setBlock` 增量（单方块变更走"标记 cube 脏 → 重mesh 整 cube"，32³ 重mesh 足够便宜；不做逐 face 增量，避免复杂度）。

### 8.3 Meshlet 构建

mesher 尾 pass（或独立 compute）：顺序 quad 按 64/组切分，组 AABB = quad 包围盒并集 → `meshlet` 表。Tier B 不用（MDI 按 section 段提交），Tier A 的 task shader 按它剔除后 emit。

---

## 9. GPU-Driven 剔除、LOD 与 draw call 合并

### 9.1 视锥剔除 + 八叉树遍历（compute，分层 indirect dispatch）

`traversal.comp`（voxy `HierarchicalOcclusionTraverser` 模式）：
- 第 0 层 dispatch 由 CPU 发（顶层节点数，常量级）；此后**每层 `glDispatchComputeIndirect`**，dispatch 尺寸由上层通过 `queueMetaBuffer` 原子累加（双缓冲 scratchQueue 乒乓）。
- 每节点判定链：
  1. 距离（最近点 XZ + 垂直圆锥）；
  2. 视锥：节点 AABB vs 6 平面（平面来自 §6.5 的 JOML 提取，UBO 上传）；
  3. HiZ 遮挡：节点屏幕 AABB → 选 mip 层 → 取 max 深度比较（上一帧深度金字塔，§9.2）；
  4. **LOD 下钻**：投影屏幕面积（8 角点叉积和，voxy `screenspace.glsl`）> `subDivisionSize²` 且有子 → 压子节点入下层队列；否则渲染自身 mesh；
  5. 无子且需要细分 → `addRequest()`（去重、限量回读 → CPU/mesher 建网格，渲染自身保底防闪）。
- 输出：可见叶子按 `meshPtr` 原子 append 进 `renderQueue`（首 uint 兼作计数）+ 每节点可见性 `frameId`（供驱逐 LRU）。

### 9.2 HiZ 遮挡剔除与时间复用

- 每帧 G-Buffer pass 后构建深度金字塔（`glGenerateTextureMipmap` 不可用于 MIN/MAX——自写 5 pass compute 下采样取 max depth，~6 级）。
- **上一帧深度**做遮挡源 + **两阶段时间复用**防闪：`renderQueue` 分"上帧可见段 + 本帧新增段"；上帧可见节点不剔除直接渲染（保证遮挡物先在场），新增节点过 HiZ——标准 two-phase occlusion，同时保留 voxy 的 TEMPORAL 命令段实现（cmdgen 把上帧不可见者排后）。
- 保守性：HiZ 比较加屏幕空间膨胀（节点 AABB 投影外扩 1 texel），避免边缘闪烁。

### 9.3 LOD 选择小结

近场 L0（32³ 全分辨率）→ 远处按屏幕面积逐级升 L1/L2/L3（64/128/256³ 合并网格，mesher 对低层节点用 2×/4×/8× 体素步进采样父页生成"块状化"mesh——风格与 MC 远景一致）。切换阈值 `subDivisionSize` 可自动平衡（voxy `autoBalanceSubDivSize` 模式：目标帧率下自适应）。远处节点只存 mesh 不存体素页（§7.4）。

### 9.4 Draw call 合并：MDIC + 零回读（Tier B 主路径，Tier A 同样用于阴影 pass）

`cmdgen.comp`（128 线程/组，`glDispatchComputeIndirect`）每可见 section：
1. 读可见性（`visibilityData[id] == frameId`）；
2. 按 8 个面方向组生成 `DrawElementsIndirectCommand{count=quadCount*6, firstIndex=0, baseVertex=quadStartPtr*4, baseInstance=drawId}`（顶点着色器用 `gl_BaseInstance`/`gl_BaseVertex`（`ARB_shader_draw_parameters`）取 section 位置——**沿用粒子引擎踩坑 #30 的 20B 统一步长**）；
3. 命令 `atomicAdd(opaqueDrawCount)` 追加 → `drawCallBuffer`；
4. 半透明不生成命令，按 `曼哈顿距离 << detail` 分 1024 桶计数 → `prefixsum` + `buildtranslucents.comp` 生成从后往前命令段。

绘制：`glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, …)`，**实际数量从 `GL_PARAMETER_BUFFER_ARB` 读，CPU 全程不知道数量**。整场不透明地形 = 1 次 draw call；prep/cull/cmdgen 各 1 个 dispatch。Tier B 顶点路径 = 无属性 VAO + SSBO vertex pulling：

```glsl
// quad 顶点着色（Tier B，M0）
Quad q = decodeQuad(quadArena[quadIndex]);          // §7.3 的 52bit 解包
uint corner = gl_VertexID & 3u;
vec3 worldPos = cubeOrigin(q) + quadCornerOffset(q, corner); // 面轴重排 swizzle
outUV = quadUV(q, corner); outState = q.stateId; …      // flat 传给 fragment
```

### 9.5 Mesh Shader 路径（Tier A）

```
taskEXT（每 workgroup = 1 个 cube 的 meshlet 列表）
  ├ 读 meshlet 表（§8.3），AABB 视锥 + HiZ 剔除（同 §9.1 判定，GLSL 复用同一 chunk）
  └ EmitMeshEXT(count) —— 只 emit 存活 meshlet
meshEXT（local_size 32 = 64 quad × 4 corner / 8；或 64 quad/组 × 每线程 4 顶点布局按扩展上限调）
  ├ decodeQuad(quadArena[start + gl_GlobalInvocationID …])   // 与 Tier B 同一解码 chunk
  ├ gl_MeshVerticesEXT[corner].gl_Position = …               // 视锥体拼接，含相机相对浮点精度处理（±30M 坐标！见下）
  └ gl_PrimitiveIndicesEXT[tri] = kQuadPattern[…]
```

- 扩展探测：`GL_EXT_mesh_shader` 优先，`GL_NV_mesh_shader` 兜底（两者 GLSL 关键字 `taskEXT/meshEXT` vs `taskNV/meshNV` 用 PRELUDE 宏抹平）。
- **大坐标浮点精度**（±3000 万下 float32 顶点抖动）：所有 pass 一律"相机相对"坐标——quad 解码在 cube 局部（≤32），世界位置 = `dvec3(cubeOrigin) - dvec3(camPos)` 先在 CPU/GPU 用 double 相减得 `vec3` 相对量（经典 camera-relative rendering，voxy 亦如此）。cubeOrigin 从 21 bit 打包 int 还原为 int64 再转 double，全程不经过 float32 世界坐标。
- mesh shader 的 dispatch 参数同样由 compute 写（task workgroup 数 = 可见 cube 数，indirect）。

---

## 10. 延迟着色与 G-Buffer

### 10.1 MRT 布局与 FBO

自建 `gbufferFBO`（窗口尺寸变化重建；首帧 initPass 预渲染红线来自 multipass-buffer 教程）：

| 附件 | 格式 | 内容 |
|---|---|---|
| RT0 | RGBA8（sRGB 采样） | albedo.rgb（已乘 biome tint 与 vertex color）+ **渲染态 id 低 8 bit**（高 8 bit放 RT2 flags，共 16 bit stateId） |
| RT1 | RG16_SNORM + B8 + A8 | 八面体编码法线（面法线为主，为未来非轴对齐面预留）+ 粗 AO + 备用 |
| RT2 | RGBA8 | flags/stateId 高 8 bit + roughness + metallic + emissive 强度（均可由 stateTable 在 deferred 侧查，存这里省一次 SSBO 查询） |
| Depth | — | **借用 MC 主 RenderTarget 深度附着**（G-Buffer pass 直接绑为主深度 → 后续 vanilla 实体/粒子/方块实体自动正确遮挡） |

延迟光照输出到自管 `hdrColor`（RGBA16F）→ tonemap 后 blit 回 MC 主颜色。MC 主缓冲非 HDR，ACES 在 blit 内完成，与 vanilla 实体的加法混合风格一致。

### 10.2 延迟光照 pass（全屏 quad，fragment 核心）

```glsl
// 输入：RT0/1/2、MC 深度、体素页、LPV、探针 SH、CSM 阴影、点光 tile 列表、原版方块图集
GBuffer g = decodeGBuffer(uv);
vec3 albedo = fetchAtlasAnimated(g.stateId, g.uv);          // 原版图集 + 动画帧偏移
float ao    = voxelAo(voxelPage, voxelPos, faceAxis(g.N));  // shader-dev vertexAo：4 side+4 corner 邻居占用，
                                                             // 面内插值 pow(ao,1/3)——greedy quad 无逐顶点 AO 的解法
vec3 sun    = evaluateCSM(worldPos, g.N) ;                  // §11.1，4 级联 PCF
vec3 skySH  = evalIrradianceSH(probeVolume, worldPos, g.N); // §11.2
vec3 blockL = sampleLPV(lpvVolume, worldPos, g.N);          // §11.3
vec3 pointL = tiledLights(worldPos, g.N, g.rough, g.metal); // §11.3 延迟点光
// Cook-Torrance（lighting-model.md 原式）：D_GGX / F_Schlick / V_SmithGGX，太阳项跑 BRDF；
// skySH/blockL 走 diffuse（探针已含方向性）；emissive 直加（HDR，供 bloom）
color = PBR(albedo, …) * (sun + skySH + blockL) + pointL + emissive;
```

半透明像素不进 G-Buffer（depth write off 由 mesher 分流保证），延迟 pass 只照亮不透明。

### 10.3 半透明前向 pass（水面/玻璃/含水流）

时机 `AFTER_TRANSLUCENT_BLOCKS`（§6.3）。数据 = §7.3 的半透明分组 quad，经 §9.4 的距离桶排序 → 半透明 MDI（1 次 draw）。着色：前向采样同一套光照资源（CSM/探针/LPV/点光）+ Fresnel + 可选屏幕色折射（折射需 MC 主颜色拷贝一份作纹理，M2 优化）。排序粒度 = cube×面组，近似排序（voxy 同款，视觉足够）。

### 10.4 实体/方块实体/粒子照明（合成 LightTexture）

vanilla 实体着色器只认 16×16 `LightTexture`（sky, block → 颜色）。方案：
- mixin `LevelRenderer#getLightColor(Level, BlockPos)`：allay 内重定向 `AllvrLightSampler.sample(pos)` → 返回打包 `(sky, block)`：
  - **sky = 体素页垂直 DDA**（从实体位置向上有界窗口 K≈128 格射线，无遮挡 = 15）× 昼夜因子（vanilla lightmap 曲线自然表达）。**不能用"列最高面"法**——层叠空岛世界每列最上方恒有岛（+30M 附近），列顶法全维度恒 0；有界窗口法下，层间缝隙漏光处天光正确（物理一致）。近场无体素页的远处实体回退 15（误差可接受）；
  - block = 客户端光源空间哈希（服务端光源事件随 cube 包同步，§11.3 注册表）取最近光源衰减值。
- 实体阴影（blob shadow）照常工作。粒子引擎同样受益（其光照采样即 LightTexture）。
- 效果定位：实体用"够用的近似"；地形本体才是全保真（CSM+探针）。后续可把关键实体（玩家/Allay）迁入 G-Buffer（远期）。

### 10.5 后处理（可选增强，阶段 5 末）

Bloom（MIP 金字塔，emissive HDR 输出受益）→ ACES tonemap（blit 内）→ TAA（YCoCg 邻域 clamp 防鬼影，quad 边缘闪烁对症；与 TAA 冲突的 HUD 不受影响因只作用世界层）。默认 bloom+ACES 开，TAA 关（避免与 MC 自身 AA/TAA 叠加）。

---

## 11. 光照系统：完全取代原版光照引擎

> 原版光照 = 服务端 BFS 天空光/方块光逐 block 传播 + LightLayer 网络下发 + 客户端逐 section 存储。本维度**全部废除**：服务端不计算不下发光照（§5.2/§11.6），客户端 GPU 自建三件套——**CSM 阴影贴图（动态）+ SH 天光探针（预烘焙）+ LPV 方块光（预烘焙/增量）**，外加体素 DDA 射线阴影（近场可选）。shader-dev 的 DDA/锥追踪/AO/PBR 公式是着色内核的直接来源。

### 11.1 级联阴影贴图（太阳/月亮）

- 4 级联（如 16/48/128/384 格），2048² D16，每帧重绘（地形 mesh 全在 GPU——阴影 pass = 复用可见集，按级联视锥 GPU 再剔除，4 次 MDI；Tier A 时 4 次 mesh dispatch）。castFace 剔除（背光面不进阴影 pass）。
- 采样：级联选择按 viewDepth，PCF 3×3 + 级联边界混合；太阳角度由 `level.getTime()` 计算（维度自义昼夜参数）。
- 阴影 pass 写独立深度目标（不污染 HiZ/MC 深度）。替换 iris 的 ShadowRenderer（已在 §6.4 禁用）——阴影只服务我们的延迟/前向着色。
- **空岛世界的天然优势**：层间错位 + 缝隙漏光 → CSM 投下班驳光影，正是该视觉主题的核心画面。

### 11.2 天光探针：SH9 辐照度体积（预烘焙）

- 探针网格对齐 cube 坐标（每 32³ cube 角点即探针，稀疏：仅"含非空 cube 的节点"区域烘焙），存储 `3D 纹理 × 7 RGBA16F 切片`（SH9 RGB = 27 系数）。
- 烘焙（compute，异步预算切片）：
  1. **天空可见性**：每探针向半球发射 ~64 条 DDA 射线（shader-dev `castShadow` 的体素版，对体素页/粗节点 AABB 遍历）→ 天空遮蔽 + 方向分布 → 拟合 SH；
  2. **一弹太阳反弹**（增强项）：命中点取 albedo×CSM，再向探针方向 gather（锥追踪近似，采样低层节点 AABB 占用）；
  3. 输出**方向性可见性 SH**（不乘颜色）——运行时按昼夜/太阳色染色，昼夜循环零重烘焙。
- 插值：deferred/前向按 worldPos 三线性取 8 探针 SH（法线朝向权重），洞口/室内自然过渡（取代原版 skylight 的传播语义）。
- 层叠空岛注意事项：探针在岛屿表面下方/缝隙处的方向分布天然表达"侧向漏光"，无需特判。

### 11.3 方块光：LPV + 延迟点光源（预烘焙/增量，零 BFS）

- **光源注册表**：服务端在 cube 生成/`setBlock` 时维护 `getLightEmission>0` 方块集，随 cube 包增量下发（§7.1）→ 客户端 `lightSourceHash`（空间哈希）。
- **LPV（光传播体积）**：R11F_G11F_B10F 3D 纹理（网格对齐 16³），三步 compute 传播（6 面 + 对角泄漏抑制的经典 LPV；光源注入 = 注册表 → voxel 页发射强度）。**静态光（火把/岩浆）只在变更时重跑局部传播**（脏 16³ 区域级），红石灯切换等动态光走延迟点光。取代原版 blocklight BFS：无逐 block tick、无网络 LightLayer。
- **延迟点光**：注册表内光源（含动态：手持光源/实体发光）进 `lights[]` SSBO → compute 64×64 屏幕分桶 → deferred 逐像素累加（上限 128/tile）。近场高质量阴影可选：对 K 个最近点光做 16 步体素 DDA 射线阴影（shader-dev `castShadow` 直译，体素页在场即零额外数据）。

### 11.4 体素 DDA 近场软阴影（可选增强）

太阳 CSM 之外，近场（≤ 48 格）可用体素页 DDA 软阴影替代/增强第 1 级联（分辨率不受阴影贴图限制，`k·h/t` 半影风格）——M2 画质选项，默认关。

### 11.5 增量重烘焙与预算

- 脏链：`setBlock` → cube 脏（mesh）+ 探针脏（含 26 邻 cube 边界探针）+ LPV 脏（16³ 区域）。优先级队列（相机距离加权），每帧预算切片（如 0.5 ms GPU + 2 个 cube），大变更分帧消化——**任何时刻无全量重烘焙**。
- 烘焙 dispatch 在 §6.3 的 AFTER_SKY 计算相头部执行，复用 fence/预算框架（粒子引擎 EMA 节流模式动态调预算）。

### 11.6 服务端游戏性光照（后移至阶段 7，当前零 mixin）

原版游戏逻辑查光处（怪物生成 `getRawBrightness`、作物/树苗生长、雪傀儡存活等）依赖光照值。**决策：暂不接入玩法 ⇒ 阶段 1–3 服务端零光照 mixin**，这些查询在 allay 维度内返回原版空 LightEngine 的默认值（可玩性不承诺）。阶段 7 接入玩法时按以下预案实施：
- 天光 = **有界窗口向上暴露**（从查询位置向上 K 格内无遮挡 = 15；靠 SurfaceTracker"低于某 Y 的最高面"有界查询实现——**不可用列顶法**，层叠空岛下全维度恒 0，与 §10.4 客户端机制同理）；
- 方块光 = 光源注册表按距离衰减的 max（无需传播即保守上界，怪物生成判定偏保守 = 安全方向）；
- 网络含义不变：cube 包无光照位；进入维度时下发一次光源全量快照（按订阅范围），此后纯增量。

---

## 12. voxy 兼容：明确搁置（不投入，不破坏）

决策（2026-09-03）：**暂不考虑 voxy 兼容**。保留事实依据（§2.3）与影响面结论：

1. voxy 无 NeoForge 构建（Fabric + 硬依赖 Fabric Sodium）；玩家侧若经 Sinytra Connector 使用，其 ingest 链（Sodium `RenderSectionManager.onChunkAdded` + 原版光照 `LIGHT_AND_DATA` 检查）在 allay 维度**天然得不到数据**——列 chunk 是 2D 元数据壳、无原版光照、Sodium 地形已被 §6.4 禁用 → **voxy 地图在该维度为空，但不会 ingest 也不会污染数据库**（最重风险自动消解）。
2. 唯一残余风险：若未来恢复"窗口内原版渲染"形态（当前路线下不存在此状态）。当前路线下列 chunk 从第一天起就是 passThrough 空壳，无窗口地形概念。
3. 若未来重启兼容（全高 LoD 地图），按旧分析走 fork voxy 扩展 key（Y 8→更多 bit）+ 直灌 `VoxelIngestService.rawIngest` 路线；本节不展开。

---

## 13. 分阶段实施路线图

| 阶段 | 内容 | 交付物 | 验收标准 | 依赖 |
|---|---|---|---|---|
| **1. Cube 内核 + 维度注册** ✅ **已实现（待客户端冒烟测试）** | §4 JSON（flat 空 layers，零 mixin passThrough）+ §5.1 纯逻辑类（`dimension.cube.AllvrCubePos/AllvrCoords/AllvrCube`、`AllvrDimensionLimits`）+ `AllvrCubeMap`（自驱动加载：tick 预算 shell + 传送同步环 + 会话内不卸载保玩家改动）+ **3 个 mixin**（§5.2 实况）+ **`gen.AllvrIslandFieldGenerator`**（格点 XZ=2816/Y=512、p=8 超椭圆、FBM 边缘、草/土/石带） | 服务端可用：`/execute in` 进入（创造模式）、任意 Y 读写方块 | `/data get block` 越窗读写正确；重启后空岛确定性重生成；其它维度零回归。**测试发现**：维度类型 JSON 曾缺 4 个必填字段（§4.2）；`BlockPos.asLong` 12 bit Y 坑（§2.4） | 无（mixin 手写路线，实际 3 个） |
| **2. 票据 + 网络** ✅ **已实现，冒烟测试完成（2 个客户端 bug 已修复，§2.5）** | **无原版 ticket mixin**（阶段 1 的自驱动方案延续）：`AllvrCubeMap` 每玩家订阅跟踪（`Subscription.sent` 键集）+ shell 顺序流式下发（发送半径 xz=8/y=4、遗忘半径 +2 滞回、24 cube/tick 预算）+ `dimension.net.ClientboundAllvrCubePacket`（wire：cubePos long 直写 + 8×原版 `LevelChunkSection.write` 调色板编码 + BE 更新标签 + 发光体事件表；uniform cube ≈100B）+ `ClientboundAllvrForgetCubePacket` + 登出/换维重置订阅 + 客户端 `client.dimension.AllvrClientCubeCache`（主线程 apply，miss=void air 客户端从不生成；+ `setBlock` 镜像服务端语义）+ **`AllvrClientLevelMixin`**（client 组：ClientLevel 方块读 + `Level.setBlock` 4 参路由到缓存）+ **`AllvrBlockCollisionsMixin`**（common：碰撞迭代器 `BlockCollisions` 的 `LevelChunk` 直读旁路修复，双侧；§2.5）。增量同步（服务端 setBlock 推送）显式后置阶段 3 | 客户端内存拿到 cube 数据 | tp 到空岛后玩家站立不坠落（依赖 §2.5 碰撞修复）、破坏/放置方块不崩溃且预测生效、准星对岛面出方块轮廓、`[Allvr] cube ... streamed` debug 日志；多人各自垂直视距 | 阶段 1 |
| **3. ALLVR V0（本次实施终点）** ✅ **已实现（2026-09-04，待客户端冒烟测试）** | **落地范围（较原计划的 V0 简化见下）**：`client.dimension.render` 新增 `AllvrRenderStateMap`（懒建 16bit 渲染态：sprite rect + biome tint + 半texel inset + renderable 旗标）+ `AllvrMesher`（CPU 贪心，34³ 快照含 1 格边界；裁面规则镜像 `Block.shouldRenderFace` 去 hidesNeighborFace 项）+ `AllvrMesherWorker`（单守护线程 + 缓存锁下快照）+ `AllvrBuffers`（quad arena 8B/quad SSBO + cubeInfo 槽表（绝对 ivec3 原点，shader 整数域做相机相对，§9.5）+ 状态 TBO 3×vec4/id + 共享相对索引 8192quad + MDI 命令缓冲）+ `AllvrShaderCache`（raw GLSL，F3+T 热重载）+ `AllvrRenderer`（生命周期/泵结果/CPU 视锥/MDI 提交/相机相对 uniforms/雾/昼夜因子；**首次 mesh 时脏化已 mesh 邻居、setBlock 仅脏化跨界邻居**——防重mesh级联）+ `AllvrLightSampler` + `AllvrGetLightColorMixin`（实体合成光照 §10.4）+ 禁用 mixin ×2（§6.4 实况）。**V0 偏差（相对 §7–§9 全量设计）**：无 GPU 体素页/节点树/LOD/遮挡剔除/均匀零页简写（阶段 4–5）；无模型几何路径——非整块方块（楼梯/火把等）与半透明（水/玻璃）**不渲染**；quad 无逐顶点 AO（reserved 位保留）；双 worker/持久映射上行留待 profile | allay 维度内：地形由 ALLVR 渲染（仅 CPU 视锥剔除，无 LOD 无延迟），实体/粒子正常 | tp 至任意远 Y（如 ±100 万）数秒内空岛可见；pack 激活时地形在 AFTER_LEVEL 仍可见（提示出现）；其它维度零回归；F3+T 热重载 | 阶段 2；~~build.gradle 加 sodium compileOnly~~（字符串目标免依赖，§6.4）。**冒烟踩坑（2026-09-04）**：①`packed` 是 GLSL 保留字（NVIDIA 编译器级联报错）；②cubeInfo 槽表必须以 **int 位型**写入——`float[]` 写入被 shader 的 `ivec4` 按位误读（1408.0f→1181224960），`origin-uCamInt` 爆到 ~1e9，顶点全部飞出裁剪空间→任何配置下地形不可见（已修：`AllvrBuffers.allocSlot` 改 `int[]`；并加 triage 日志：caps 探测/首 3 条 mesh 结果/5s 帧统计，commands>0 但不可见指向 shader/变换，commands==0 指向几何管线）；③ 共享索引模式 `(0,1,2)(2,1,3)` 复用 v1–v2 边，故 **v1/v2 必须为对角顶点**（voxy corner 约定 `(id>>1,id&1)`）——terrain.vsh 初版把 corner 表写成周长序 (0,0)(1,0)(1,1)(0,1)，三角形 2 绕向翻转为 CW 被背面剔除、三角形 1 只盖 x≥y 半平面，**每个贪心 quad 只渲染一个下半三角**（已修：corner 表改 z 序 (0,0)(1,0)(0,1)(1,1)，2026-09-04 审查发现）；④ 草方块原版模型每侧面 2 个 culled quad（`grass_block_side` + tinted overlay）——"每方向恰 1 quad"判定把草判为不可渲染，又因草可遮挡、下方泥土顶面被剔除 → **岛顶洞穿**。材质表已改**逐面**（每 state 18 texel：6 面 × uvRect/tint+flag/inset，面序 = `AllvrMesher.FACES` = vsh faceIdx；每面取第一个 quad=基础面、tinted overlay 丢弃、tint 按面解析无 tint=白恒等；fsh/mesher 不动，准入仍为全立方门控；PRELUDE 生成 STATE_TEXELS/STATE_TEXELS_PER_FACE 步长 define 单源化）（已修：`AllvrRenderStateMap` 重写 + terrain.vsh 取数重排，2026-09-04 审查发现）；⑤ mesher out 扩容上限（6144）低于可构建图案最坏情况（棋盘格 ~5×10⁴ quad/cube），越界 AIOOBE 杀死 worker 线程且 handle 非 null 致永不重启、渲染永久停摆（已修：扩容去上限 + `AllvrMesherWorker.run()` catch-all 保活（失败任务不重试）+ `AllvrRenderer.draw()` 把 >8192 quad 的 cube 拆多条连续 MDI 命令，2026-09-04 审查发现）；⑥ 生成器按 `SECTIONS_PER_CUBE(8)`×16 线性映射写满 128 格 Y 而 `sectionAt` 仅按 Y 索引 → sections[2..7] 孤儿（4× 求值 + 包内重复数据 + 迭代陷阱）（已修：恢复 **3D section 索引**——`AllvrCube.sliceIndex`=(sy<<2)|(sz<<1)|sx 单源，访问器与生成器填充循环均按 2×2×2 切片，"8 section 每轴 2×2×2"注释回归事实；wire 走数组序自动对称，新旧构建互通会错位——开发期同构建无影响，2026-09-04 审查发现）；⑦ §2.5 第 4 条的增量同步已兑现：`ClientboundAllvrBlockUpdatePacket`（cubePos long + 15bit cell + `Block.getId` 状态 id）按原版 flags&2 语义原子推送（订阅集扇出），客户端经 `AllvrClientCubeCache.setBlock(pos, state, 19, 512)` 应用（镜像原版确认包路径）；机制较原计划"dirty cube 重发"改为**原子块更新包**（偏差注记）；**BE NBT 不同步**仍为已知限制（随全量 cube 包/阶段 4 解决）；⑧（2026-09-04 审查修复，机制详见 §2.4 修复块）BE 替换语义 + 双端 cube BE tick + 远距卸载 + 实体冻结，mesher 快照改锁内深拷贝（`PalettedContainer.copy()`，锁内 ~2–5ms→~0.5ms），arena 饿死改暂存重试（`AllvrBuffers.canFit` 与 allocRange 同判防碎片自旋 + `freeRange` 相邻合并/尾部回收） |
| **4. GPU-Driven 完全体**（grilling 评审定稿：切 4a/4b/4c，V0 以 `allvrGpuPipeline` 开关保留为回退，4c 验收后删除）✅ **4a 已实现（2026-09-04，待冒烟测试）** | **已定决策（2026-09-04 grilling）**：①远场数据 = **服务端降采样出 mesh**（S→C 非空节点位图（格点哈希 + 岛 AABB 测试，零 cube 生成）+ C2S mesh 请求 + 服务端 mesh 缓存 LRU（nodePos+level 键、多玩家共享、256MB 上限））；服务端复用 `AllvrMesher` 贪心扫描 + 服务端 VoxelSource（密度场粗采样 + 真实 cube 编辑覆盖），renderable 门控 = canOcclude + 全块；为阶段 6"远场可见所有改动"铺路——**编辑保留机制本期不做**（远场 LOD 显示重生成地形，与 R14 同口径，管线按"编辑数据可插拔"设计）。②视距 R=2048 默认（config 512–4096）；L4 列式层不做；subDivisionSize 固定 + 帧时 EMA 自适应；全分辨率流 Y 半径 4→8。③**体素页 atlas / 均匀简写 / 常驻上传回读流全部划归阶段 5**（阶段 4 无消费者；请求回读用单次小回读）。④HiZ 深度源 = **MC 主 RenderTarget 深度**（同帧 draw 后构建、上帧消费；每帧先清远平面——pack 下安全劣化纯视锥；2 texel 膨胀）。⑤服务端 LOD 构建跑服务端线程 + 2ms/tick 独立预算；客户端请求 in-flight 32 / 8 每 tick；稀疏缓冲不做；驱逐 = 距离 R×1.25 + frameId LRU；`MAX_COMMANDS` 8192→65536。<br>**4a 落地**：`AllvrNodeStore`（节点 CPU 镜像 + 脏集；**32 B/节点布局**——文档原 16 B 布局位预算不成立（4+26+24+24＞64）且无 quadCount 字段，改为 a=（x21b|y21b，x21b|level3b|flags8b，quadStart32，childPtr32）/ b=（quadCount32，visibleFrameId32，lastRequestFrame32，slot32），位置存 cube 坐标、shader `<< (5+level)` 还原）+ `AllvrBuffers` 扩展（node/queue/dispatch/cmdCount 四缓冲，`glMultiDrawElementsIndirectCount` 双路径 GL46/ARB）+ 4 个 compute（`gpu_cull_reset`/`gpu_cull_traversal`（仅视锥）/`gpu_cull_finalize`/`gpu_cull_cmdgen`）+ `AllvrShaderCache` compute 编译 + `AllvrRenderer.gpuDraw`（reset→traversal→finalize→cmdgen→MDIC 五步，脏节点 per-32B 上传、扩容全量重传；视锥平面 JOML Gribb–Hartmann、**相机小数部分折入 d、AABB 在整数域做相机相对**——±30M 不经 float32）+ `ClientConfig.allvrGpuPipeline`（默认 false）。**4a 偏差**：文档 §9.1 的"分层 indirect dispatch"未做——4a 为平铺 L0 直接派发，分层派发随 4c 层级结构落地；§7.3 的 lightSlot/reserved 位维持 V0 布局（quad 格式未动，terrain.vsh/fsh 零改动）；调试回读（5s 节流、debug log 门控）为 Q9 允许的 debug 专用通道。<br>**4b 落地（2026-09-04）**：HiZ 金字塔（R32F 12 级 mip 链、半分辨率 level-0、`glClearTexImage` 清远平面——未填充金字塔保守不遮挡任何节点）+ `gpu_hiz_first.comp`（2×2 depth quad 取 max；**深度源已源码核实为 1.21.1 `MainTarget` 的纯 `GL_DEPTH_COMPONENT/float` 纹理，`getDepthTextureId()` 直接 sampler2D 采样**，不走基类 stencil 分支）+ `gpu_hiz_downsample.comp`（CPU 循环逐级 image 绑定重挂）+ **两阶段时间复用**：队列改双段布局（[0]=上帧可见计数/[1]=新可见计数/[2..]/[2+CAP..]），上帧可见节点跳过 HiZ 直通 phase-1 前段，其余过 HiZ 入 phase-2 后段；cmdgen 按 p1→p2 段序发命令（单 MDI 内已知可见深度先落）；draw 后建新鲜金字塔 + `gpu_cull_revalidate.comp` 对 phase-1 戳重测（遮挡则清戳→下帧降为 phase-2，稳态仍剔岛背面）。**测试共享源**：`ShaderCache` 增加 `#pragma cmi_include chunks/*.glsl` 处理（镜像 ParticlePrograms），`chunks/node_common.glsl`（节点布局/解码单源）+ `chunks/hiz_test.glsl`（遮挡测试单源，traversal/revalidate 共用）。**保守性设计**：mip 选择 ceil（矩形覆盖 ≤1 texel）+ 屏幕矩形 2px 膨胀 + 近平面穿越节点直接通过 + 深度比较偏置按视距缩放（`1.5·near·far/(far−near)/w²`，JOML `perspectiveNear/Far` 每帧提取——常量 epsilon 在远处会闪烁）。**劣化路径**：iris pack 激活 / 金字塔分配失败 / 透视提取失败 → `uHizEnabled=0` 退化为 4a 行为（只失遮挡收益不丢地形）；金字塔分配即清 1.0，首帧/换窗不闪。**4b 偏差**：踩坑新增——GLSL 保留字再+1（`half`，下采样 kernel 局部变量名冲突，NVIDIA 编译器保留字表含 half/fixed）；采样 mip 用 `textureLod` 手动选级（MIN filter 必须为 mipmap 完整型，用 NEAREST_MIPMAP_NEAREST）。**验收（4b）**：无穿透无闪现（绕岛/穿岛/跨层）；debug 统计 p2 明显小于视锥通过量（岛背面被剔）；pack 下劣化生效地形仍可见。**验收（4a）**：与 V0 视觉等价；MDIC 1 次 draw；渲染线程 CPU 可测下降；其它维度零回归；开关双向可切。**冒烟踩坑（2026-09-04，4b 冒烟时暴露）**：`wantGpu()` 把 `buffers.gpuCullReady()`（nodeBuffer 非零）纳入 gate，而该缓冲在 `gpuDraw` 内部才分配——鸡生蛋死锁，config=true 时 GPU 路径也永远走不到（debug.log 证据：caps/程序编译全绿但统计行全程为 V0 格式 `[Allvr] frame:`，无任何 `gpu frame:` 行）；**用户 4a 会话实际跑的是 V0**（视觉等价设计使失败不可见——验收必须看统计行而非画面）。修复：gate 只查 config + MDIC caps + compute 程序就绪，缓冲由 gpuDraw 按需分配。 | 剔除/LOD/命令全 GPU；远距块状化 LOD | 4a：视觉与 V0 等价 + 1 draw call；4b：遮挡无穿透/无闪现；4c：整场 1 draw call + 渲染线程 CPU < 1 ms + 瞬移远环数秒填充 + 30 min 会话无泄漏斜率（粒子引擎计时环验证） | 阶段 3 |
| **5. 延迟着色 + 光照系统** | §10–11：G-Buffer MRT + 延迟 PBR + CSM + SH 探针烘焙 + LPV + 延迟点光 + 半透明前向 + bloom/ACES | 取代原版光照的完整视觉 | 光照更新零卡顿（变更场景帧预算内消化）；洞穴/缝隙漏光/昼夜/水面正确；实体检视无穿帮 | 阶段 4 |
| **6. 持久化 + Mesh Shader** | §5.4 region3d 存档定版（含光照/mesh 缓存序列化）+ §9.5 Tier A task/mesh 路径 + meshlet 剔除 + GPU mesher（§8.2 M1）+ 近场 DDA 软影（§11.4）+ TAA 选项 | 重启存续；Tier A 硬件最优路径 | 重启后玩家改动存续；Tier A/B 视觉一致；GPU 时间分布达标（计时环出报告） | 阶段 5；regionlib 评估 |
| **7. 玩法接入 + 远期生态** | 游戏性光照（§11.6 预案）、自定义 biome/`DimensionSpecialEffects` 天空、传送方块/出生点安全、风暴/燃烧器维度内容、Create 跨 cube 传动验证、voxy 兼容重评（§12） | 维度玩法闭环 | 按需求定义 | 按需求 |

里程碑口径：**1–2 服务端与数据通路**（不发布）；**3 第一个可视里程碑**（本次实施终点）；4–5 渲染完全体（一个大版本）；6 收尾（存档落地 + Tier A）；7 按玩法节奏。内存态重启丢改动是阶段 6 前的**已知限制**（确定性重生成缓解）。

---

## 14. 风险清单

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | CC3 未完成（光照/IO/地形 TODO），参考代码本身有坑 | 高 | 只移植 Core 纯逻辑类（AllvrCubePos/AllvrCoords/SurfaceTracker）；管线类只抄思路按 1.21.1 重写；光照问题被 §11 整体绕开；地形自研密度场（§5.3） |
| R2 | 1.21.1 与 CC3 目标 1.21.6 管线 API 差异 | 高 | §5.2 按 1.21.1 类名列表；每条 mixin 动工前对照 `.refs/neoforge-21.1.227` 核对签名 |
| R3 | mixin 面积大，与 Create/Embeddium/Iris 冲突 | 高 | 决策评审已砍掉出生点/游戏性光照/生成金字塔三块 mixin 面；剩余全部挂 `CMIMixinPlugin` 按 WorldStyle + 维度判定短路；渲染 mixin 集中在 `renderSectionLayer` 等少数入口；离场卫生纪律（粒子引擎踩坑 #32） |
| R4 | sodium/iris 禁用不彻底（阴影 pass/后处理残留干预） | 高 | §6.4 `AllvrIncompatDisabler` 注册表逐项验收：iris `isShaderPackInUse` 强制 false + ShadowRenderer 跳过；进出维度 `LevelEvent` 同步复位；每项带"维度切换往返"回归用例 |
| R5 | ±30M 坐标 float32 顶点抖动 | 高 | 全管线相机相对渲染（§9.5）：cube 局部解码 + int→double 相对相机；深度用对数/相机相对（浅景深远处精度实测调整） |
| R6 | Mesh Shader 扩展覆盖面（EXT/NV 差异、驱动成熟度） | 中 | Tier B MDI 为保底路径且长期保留（阴影 pass 恒用）；PRELUDE 宏抹平两套关键字；能力探测 + 自动回退 |
| R7 | G-Buffer/延迟与 vanilla 实体/半透明排序穿帮 | 中 | 借用 MC 主深度保证遮挡正确；半透明走原版时段槽位；验收用例：水实体互望、玻璃后实体 |
| R8 | 探针/LPV 烘焙在超大变更下抖动 | 中 | 脏区域分帧预算 + EMA 自适应（粒子引擎节流模式）；烘焙期间旧值服务渲染 |
| R9 | `region3d` 自定义格式无第三方工具支持 | 中 | 已后移阶段 6；届时列 chunk 保持标准 anvil、cube NBT 字段名对齐原版 section NBT |
| R10 | 体素页/arena 内存超预算（多层空岛 + 集束视距/高空俯瞰） | 中 | §7.1 均匀 cube 简写（岛内部零页）+ §7.4 LRU + 稀疏缓冲按页提交 + `RenderResourceReuse` 跨维度复用；预算超限自动收缩视距并提示 |
| R11 | 均匀简写正确性（mesher/查询/AO 对 uniform 邻居应答） | 中 | §7.1 页表项高位标记，查询路径统一入口；验收用例：uniform-页交界面的 mesh 与 AO 无缝 |
| R12 | Y ±3000 万下实体/寻路/掉落物等原版数值假设 | 中 | 软件边界内实测；**阶段 1 新确认**：`SectionPos` 20 bit Y（§2.4）使原版实体 section 存储在 |Y|>~840 万混叠——实体相关功能在超高 Y 的实际可信范围约 ±840 万，玩家本体与方块不受影响；远期需自建实体索引 |
| R16 | 自定义方块级存储的坐标 key 位宽（`BlockPos.asLong` Y 12 bit） | 高（已踩） | **已修复**：cube 内 BE 用 15 bit 局部索引（`AllvrCube.localIndex`）；后续任何方块级 map 禁用 `BlockPos.asLong`，一律 cube key + 局部索引 |
| R13 | 网络包体积/频率（cube 级 32³ 下发） | 低 | 调色板 + 仅 dirty section 重发 + 光源事件化 + 均匀 cube 数字节包 |
| R14 | 内存态存档：阶段 6 前重启**与远距 cube 卸载**（超出所有玩家 forget 半径，每 2s 扫描，§2.4 审查修复③）都丢玩家改动 | 中 | 已知限制（卸载方案 2026-09-04 评审接受丢弃）；密度场确定性保证地形可复现；创造测试可接受 |
| R15 | voxy（Connector 场景）残余交互 | 低 | §12 分析：ingest 链拿不到数据，自动失效不污染 |

---

## 15. 附录：参考代码索引

**CubicChunks3**（`.refs/CubicChunks3/`，MIT，目标 1.21.6 未完成）——服务端 Cube 层蓝本：
- 位置编码：`CubicChunksCore/src/main/java/io/github/opencubicchunks/cc_core/api/CubePos.java`、`utils/Coords.java`、`api/CubicConstants.java`
- 双工位置：`cc_core/world/level/CloPos.java`；WorldStyle：`cc_core/world/CubicLevelHeightAccessor.java`
- 无限高度图（已完成，直接移植）：`cc_core/world/heightmap/surfacetrackertree/{SurfaceTrackerNode,Branch,Leaf}.java`、`storage/InterleavedHeightmapStorage.java`
- 列 cube 映射：`cc_core/world/ColumnCubeMap.java`
- 管线 mixin：`src/main/java/io/github/opencubicchunks/cubicchunks/mixin/`（清单见 §3.3；DASM 重定向集 `mixin/dasmsets/`）
- 旧实现（≈1.21.1，含 regionlib IO——阶段 6 参考）：`src_old/main/java/`
- 生成器适配守则：`ModOverworldChunkGeneratorsandCC.md`

**voxy**（`.refs/voxy/`，Fabric 0.2.19-beta）——**GPU-Driven 渲染架构蓝本**（兼容性已搁置，§12）：
- 节点树 SSBO + 写入：`client/core/rendering/hierachial/{NodeStore,AsyncNodeManager,NodeCleaner}.java`；GLSL `assets/voxy/shaders/lod/hierarchical/{node.glsl,traversal_dev.comp,queue.glsl}`、`pos_util.glsl`
- GPU 遍历：`hierachial/HierarchicalOcclusionTraverser.java` + `screenspace.glsl`/`frustum.glsl`（HiZ 遮挡 + 屏幕面积下钻 + 分层 indirect dispatch）
- MDI 命令生成与提交：`section/backend/mdic/{MDICSectionRenderer,MDICViewport}.java`、`prep.comp`/`cmdgen.comp`/`buildtranslucents.comp`、`prefixsum/`；`glMultiDrawElementsIndirectCountARB` 零回读
- quad 格式与顶点解码：`shaders/lod/{quad_format.glsl,quad_util.glsl,gl46/quads3.vert}`（8B/quad + 共享索引 + 无属性 VAO）
- mesher：`rendering/building/{RenderGenerationService,RenderDataFactory}.java`（贪心面合并 + 邻居面遮挡 + 模型几何）
- 内存：`section/geometry/BasicSectionGeometryData.java`（巨型 arena + `ARB_sparse_buffer` 兜底）、`RenderResourceReuse`
- HiZ：`client/core/rendering/HiZBuffer.java`；上/下行流：`UploadStream`/`DownloadStream`
- sodium 禁用模板：`client/mixin/sodium/MixinDefaultChunkRenderer.java`（HEAD-cancel）；iris 运行时判定：`client/core/util/IrisUtil.java`

**本项目 GPU 粒子引擎**（复用清单见 §6.5）：
- 编译/常量/绑定管理：`src/main/java/com/iridium126/createmanaindustry/client/particles/engine/{ParticlePrograms,ParticleBuffers}.java`
- 帧编排/剔除/排序/回读纪律：`...engine/CMIParticleEngine.java`（`extractFrustum`、fence 轮询、计时环）、`...engine/{ParticleFrameProfiler,ParticleGLUtil,CollisionBake}.java`
- 帧挂载与闩锁：`...CreateManaIndustryClient.java`（AFTER_SKY/AFTER_LEVEL 双阶段）、`...mixin/render/LevelRendererBlockEntitiesMixin.java`
- 踩坑清单（35 条，全部适用）：`docs/particle-engine-dev.md` §5

**禁用目标源码**（mixin 签名核对）：
- `.refs/sodium/`（0.6.x for 1.21.1：`DefaultChunkRenderer`/`RenderSectionManager` 实际 FQCN 以此为准）
- `.refs/Iris/`（1.21.1：`isShaderPackInUse` 所在 API/state 类与 `ShadowRenderer.renderShadows`）

**原版 1.21.1**（`.refs/neoforge-21.1.227/`）：
- 高度常量链：`net/minecraft/core/BlockPos.java` → `net/minecraft/world/level/dimension/DimensionType.java`
- 管线类（mixin 目标核对用）：`net/minecraft/server/level/{ChunkMap,ChunkHolder,ServerChunkCache,DistanceManager,PlayerChunkSender}.java`、`net/minecraft/world/level/chunk/{LevelChunk,ChunkAccess,ChunkStatus}.java`、`net/minecraft/client/multiplayer/ClientChunkCache.java`、`net/minecraft/client/renderer/{LevelRenderer,ViewArea,LightTexture}.java`

**shader-dev skill**（`.agents/skills/shader-dev/`，着色内核取材，映射见 §6.5）：
- `techniques/voxel-rendering.md` + `reference/voxel-rendering.md`（DDA/vertexAo/castShadow/锥追踪）
- `techniques/{lighting-model,shadow-techniques,multipass-buffer,post-processing,terrain-rendering,ambient-occlusion}.md`

---

*本文档由 2026-09-02 的维度/Cube 架构分析与 2026-09-03 的现代体素渲染重写分析合并生成，并于 2026-09-03 经决策评审（grilling）定稿：阶段 0 取消、空岛密度场地形、纯原版传送、`Allvr` 命名、持久化后移阶段 6、实施终点 = 阶段 3。资料来源：CubicChunks3（含 src_old 与 CubicChunksCore）、voxy（渲染架构面）、NeoForge 21.1.227 反编译源、本项目 GPU 粒子引擎（代码 + `docs/particle-engine-dev.md`）与 shader-dev skill 技术库全量扫描。阶段 1 动工前按 §5.2 表逐条对照 1.21.1 源签名；阶段 3 动工前按 §6.5 复用表核对粒子引擎接口。*
