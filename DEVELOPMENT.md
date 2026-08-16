# Create: Mania Industry — 熔盐堆燃料储罐开发文档

本文档记录 **熔盐堆燃料储罐（Molten Salt Fuel Tank）** 功能的开发进度、设计决策、实现细节与验证方式。适用于后续继续开发、重构或排查问题。

> 版本状态：`0.2.3-fix` · 分支 `main` · 最后更新 2026-08-15

---

## 1. 功能概述

新增多方块流体储罐方块 `molten_salt_fuel_tank`（中文名「熔盐堆燃料储罐」），作为未来熔盐堆（MSR）系统的燃料存储组件。它与 Create 流体储罐（Fluid Tank）视觉同源（暂复制其模型/贴图），但有三个核心差异：

1. **连接不要求实心方盒** —— face 相邻的任意形状（菱形、长方形、上下不同形）都能连成一组，共享叠加容量。
2. **液面用盆地模拟（接雨水/流域模型）** —— 倒U形注入单侧分支时只升该侧；液面到达连通处才溢流到另一侧；抽取只降该侧。
3. **渲染用合并流体盒** —— 每盆地按层贪心矩形合并 + 纵向叠合，只渲染暴露面，液面盒高度一个 float 动画。

---

## 2. 设计决策（定稿）

以下决策经逐项确认，全部落地实现。

| 项 | 决定 |
|---|---|
| 注册 ID | `molten_salt_fuel_tank`（方块 / 方块实体 / 储罐类型同 ID） |
| 显示名 | EN「Molten Salt Fuel Tank」/ 中文「熔盐堆燃料储罐」 |
| blockstate | **无属性、单变体**（统一六面模型 `block_single_window.json`）：全高侧框（`#1` 整幅纹理 1:1）+ 8×8 侧窗（`#5`，y4-12）+ 1 单位厚顶环框（y15-16，`#0` 边框 UV，带 8×8 顶窗格 y15.05）+ 1 单位厚实心底板（y0-1，`#0`，无窗）。顶/底剔除与侧面同逻辑（同组相邻即剔除，`FuelTankModel` 六面），无需 top/bottom 状态 |
| 交互 | `IWrenchable`：普通扳手 no-op（默认旋转对无朝向方块无效）、**潜行扳手拆除**（默认 `onSneakWrenched`：BreakEvent + 掉落入背包 + WRENCH_REMOVE 音效）；视窗恒开、无开窗切换；红石比较器 = 填充度；`getLightEmission` 读控制器亮度 |
| 连接算法 | 自写 BFS 连通分量（任意形状）；控制器 = 组内字典序最小块（Y→X→Z）；容量 = 块数 × `fuelTankCapacity` |
| 流体模拟 | priority-flood 盆地分解（局部最低平台 = 汇）+ 盆地间鞍部高度；按连通器**动态水位**灌注/抽取（seed 盆地起，水位淹没鞍部即溢流/供液，连通盆地液面统一）；体积守恒 |
| 渲染 | 每盆地单盒集：贪心 2D 矩形合并 + 纵向叠合；暴露面；顶盒高度动画；几何缓存 |
| 动态装置 | `mountedFluidStorage` + `MovementBehavior`（镜像 Create，罐可装上火车并携带液体） |
| 弃用 | 锅炉、CTM 连接纹理、角窗、扳手切换、自动摆放（`FluidTankItem` 多格放置）、配方、advancement |
| Config | `fuelTankCapacity`（桶/格，默认 8 = 8000 mB）、`fuelTankMaxBlocks`（组内总块数上限，默认 4096） |

---

## 3. 架构与文件

```
content/fluids/fueltank/
├── FuelTankBlock.java              # 方块：单变体无状态属性、放置连接/拆除拆分、比较器、光照
├── FuelTankBlockEntity.java        # 方块实体：控制器/part 模式、流体库存、8-tick 合流同步、盆地状态
├── FuelTankConnectivity.java       # 核心算法：BFS 连通 + 盆地分解 + 灌注/抽取级联 + NBT
├── FuelTankModel.java              # 自定义 BakedModel：剔除同组相邻面（六面，去 CTM）
├── FuelTankRenderer.java           # 渲染：合并盒、暴露面、液面盒高度动画、几何缓存
├── FuelTankMovementBehavior.java   # 动态装置：客户端液面 chaser 随动
└── storage/
    ├── FuelTankMountedStorage.java     # 移动结构上的流体存储（镜像 Create）
    └── FuelTankMountedStorageType.java # 挂载类型：仅控制器可挂载
```

**注册接入**（修改）：
- `CMIBlocks.java` —— `MOLTEN_SALT_FUEL_TANK` 方块 + 物品 + 变换（`blockModel`/`mountedFluidStorage`/`movementBehaviour`/`cutoutMipped`）。
- `CMIBlockEntityTypes.java` —— `MOLTEN_SALT_FUEL_TANK` 方块实体 + 渲染器。
- `CMICapabilities.java` —— `Capabilities.FluidHandler.BLOCK`，所有 part 暴露控制器库存，并按所在格记录注入点。
- `CMIMountedStorageTypes.java`（新增）—— `mountedFluidStorage("molten_salt_fuel_tank", ...)`。
- `ServerConfig.java` —— `fuel_tank` 配置段。
- `CreateManaIndustry.java` —— 调用 `CMIMountedStorageTypes.register()`。

**资源**（复制 Create 流体储罐并改命名空间 `create:` → `createmanaindustry:`）：
- `models/block/fueltank/block_{single,top,bottom,middle}_window.json`（4 个）
- `textures/block/fluid_tank{,_top,_inner,_window,_window_single}.png`（5 张）
- `blockstates/molten_salt_fuel_tank.json`（手写 4 变体）、`models/item/molten_salt_fuel_tank.json`（父级 block_single_window）
- `lang/en_us.json` / `zh_cn.json` 各加 `block.createmanaindustry.molten_salt_fuel_tank`

---

## 4. 核心算法

### 4.1 连通分组（任意形状）

- `findGroup`：BFS 遍历 face 相邻的燃料罐，`fuelTankMaxBlocks` 截断（超限部分自成一组）。
- 控制器 = 组内字典序最小块（Y 优先，再 X、Z）；**只有控制器持有流体与盆地状态**。
- 合并（`updateConnectivity`）：吸收的旧控制器流体并入新控制器；**不兼容流体（>1 种非空）不合并**，桥接块只并入**首个非空邻居所在组**（按 `Direction.values()` 顺序：DOWN→UP→NORTH→SOUTH→WEST→EAST），另一组保留墙壁、不刷新。
- 拆分（`split`）：被拆块移除后，剩余块按连通分量重分组，每个分量一个控制器；流体**按盆地保留**——从旧 `surfaces` 反推每格液量（满层每格 `perBlock`，部分层按份分摊、余数给前几格），被拆格液量随块消失（与 Create 预留 1 格损失一致），其余每格映射到新组件盆地、`surfaceForVolume` 反推液面（Σ 盆地体积 == 库存总量精确一致）；旧盆地数据不可用（跨区块挂起/未算）时回退 `settle`。
- `top`/`bottom` 与相邻面剔除均按**同组相邻**判定（不是"是否贴着一个燃料罐"）——在 config 上限处断开的两组之间保留墙壁。

### 4.2 盆地分解（接雨水 / 流域）

- **汇（sink）** = 无严格更低邻居的"局部最低平台"（等高相邻的极小细胞连通成一片）。
- 每个细胞沿**严格下降路径**归属到它最终汇入的汇 → 盆地（`computeBasins`）。
- **脊盆地（ridge / 鞍部）** = 盆内全是极小细胞，**邻接 ≥2 个盆地**。倒U顶梁、桌子顶板、口字环顶杆、非对称顶杆、test1 中板都属此类（只要邻≥2 即成鞍部，不看高度）。
- **搁板合并**（后处理）：盆内全是极小细胞、但**邻接 <2 个盆地**（死端侧枝/臂，如树、T、加号形）→ 并入底座最低的邻居。
- **鞍部截断**（后处理）：每个细胞并入**相邻且高度 ≤ 自身 Y 的最高鞍部**（顶杆先拿走腿顶、中板再拿烟囱，与迭代顺序无关）。这会切断穿过鞍部层的立柱——腿保留鞍部以下部分，上方（烟囱/连通器）并入鞍部。
- **脊合并**（后处理）：截断后**同层且面相邻的脊盆地**合并为一个鞍部平台（流域会把一个平台绕非局部最低格拆开，如 test1 的两个顶杆经腿顶格相邻）。
- **谷地重拆**（后处理）：被鞍部切开的谷地按「原始盆地 id + 连通分量」拆成独立盆地（相邻但不同盆地不合）。
- 产出：每格盆地 id、每盆地细胞/层表（Y→细胞）、盆地邻接表、每盆地溢流结构。

```
树状 → 1 盆地（侧枝搁板合并）
对称倒U（两腿同高 h、顶梁 y=h） → 3 盆地：{腿1 y0..h-1}、{顶梁 y=h}、{腿2 y0..h-1}
非对称倒U（高腿 y0..5、矮腿 y0..2、顶杆 y2） → 3 盆地：{高腿下部 y0..1}、{矮腿 y0..1}、{顶杆+高腿烟囱 y2..5（1条烟囱并入）}
桌子（4腿+顶板） → 5 盆地：4 腿 + 顶板（脊）
test1（3腿+中板+双顶杆） → 5 盆地：{腿1下}、{腿2下}、{中板+腿1/腿2上方（连通器并入）}、{腿3}、{顶杆（两段合并）}
```

### 4.3 灌注与抽取（连通器动态水位，符合水压规则）

灌注/抽取不再是静态排序的级联，而是**动态水位扫描**：从注入/抽取格所在盆地（seed）出发，维护一个「连通区」，液面统一上升/下降；每到达一个盆地顶或**盆地间鞍部高度**（`adjHeight` = 交界格对最低可通过高度），对应盆地满或邻居溢流/供液加入连通区。`rebalanceOverSpill` 收尾：液面高于鞍部而干侧低于鞍部的边界，液体溢流到干侧直到液面降到鞍部或干侧填到鞍部（连通器平衡，如倒 U 从梁注入 → 液体最终流到两腿）。`expandRegion` 只在**源盆地实际有液体**（液面高于自身底）时传播，避免空盆地提前淹没鞍部。

- **灌注**（`fillCascade` → `fillWater`）：连通区液面从 seed 盆地表面上升，先补足区内低于统一水位的盆地，再按「最低事件」（盆地满或鞍部溢流）抬升；水位超过鞍部时邻居入区。
- **抽取**（`drainCascade` → `drainWater`）：连通区液面统一下降，先抽到统一水位，再按「最高事件」（盆地见底或鞍部暴露）下降；空盆地断开、仍高于暴露鞍部的盆地入区供液。**seed 连通区耗尽后补抽剩余盆地**（当前液面最高先抽，逐个抽到见底或抽量耗尽）——不困水，**从任意抽取位置都能排干全部**；`drain` 保留 `rebalanceOverSpill`（液体流向低处，持续抽液时高盆地经鞍部流回抽点被抽走）。
- 每盆地液面 = 绝对世界 Y；液量↔液面用 `surfaceForVolume`/`basinVolume` 互算（层表 + 高度）。
- 存档加载无灌注历史 → `settle`（沉淀态）：从最低盆地起按连通器水位分配，确定性。
- 结论：填充/抽取顺序**只由形状（盆地结构 + 鞍部高度）与注入/抽取位置（seed 盆地）唯一决定**，与建造顺序无关；分布符合连通器 + 水压规则。

### 4.4 合并渲染

- 每盆地 `层→细胞` → 贪心 2D 矩形合并（`mergeLevel`）→ 纵向叠合同足迹矩形为盒（`stackFullLevels`）。
- 只渲染暴露面：4 侧（合并后无同层内部面）+ 液面顶 + 悬挂底（`renderBottom=false` 已足够：无同组下方的格自带底座模型，底隐藏）。
- **液面盒高度 = 一个 float**，由控制器同步的 `LerpedFloat` chaser 驱动；单格内液面变化只改高度，**零重算**；跨格只重算该层矩形。
- 几何缓存于 `BasinData.renderCache`（仅形状变化重建）；**只有控制器渲染**，`shouldRenderOffScreen=true`。
- 方盒盆地 ≈ 1 盒（顶 + 4 侧 = 5 面）；菱形 = 每层矩形叠合；倒U = 每腿 1 盒，各自独立液面。

### 4.5 同步与数据流

| 事件 | 服务端 | 客户端 |
|---|---|---|
| 形状变化（放置/拆除） | 重分组 → `recomputeBasins` → `markBasinsDirty` → 全量同步盆地几何+液面 | `read` 收到 `BasinGeometry` 重建 `BasinData`、初始化 chaser |
| 流体变化（管道灌/抽） | `SeedTrackingHandler` 记录注入格 → `applyDelta` → 级联 → `markSurfacesDirty` → 8-tick 合流同步液面 | `read` 收到 `Surfaces` 更新 chaser 目标 |
| 区块加载 | `read` 设 `updateConnectivity` → 首次 tick 重组成组、恢复 `savedSurfaces` | 控制器经同步拿几何 |

能力（capability）：每个 part 的 `FluidHandler.BLOCK` 都返回一个 `SeedTrackingHandler`（包裹控制器库存、以本格为注入点），管道可从任意面连接。

---

## 5. 配置（`createmanaindustry-server.toml` → `fuel_tank` 段）

| 键 | 默认 | 上限 | 说明 |
|---|---|---|---|
| `fuelTankCapacity` | 8 | 100 | 每格容量 = 值 × 1000 mB；组总容量 = 块数 × 每格。上限与 `fuelTankMaxBlocks` 耦合：`blocks × capacity × 1000 ≤ Integer.MAX_VALUE`（21,474 × 100 × 1000 = 2,147,400,000 ✓） |
| `fuelTankMaxBlocks` | 4096 | 21,474 | 组内总块数上限（性能闸门，同时框住 BFS/盆地重算最坏开销）；上限 = 2,147,483 / 100，保证 int 容量不溢出 |

---

## 6. 当前状态

- [x] 配置键（`fuelTankCapacity` / `fuelTankMaxBlocks`）
- [x] 资源：复制 4 模型 + 5 贴图、手写 blockstate / 物品模型 / lang（en/zh）
- [x] `FuelTankConnectivity`：BFS 连通 + 盆地分解 + 灌注/抽取级联
- [x] `FuelTankBlockEntity` / `FuelTankBlock`
- [x] `FuelTankRenderer`（合并盒 + 缓存 + 液面动画）+ `FuelTankModel`（同组面剔除）
- [x] 动态装置：`FuelTankMovementBehavior` + `storage/FuelTankMountedStorage(Type)` + `CMIMountedStorageTypes`
- [x] 注册接入：`CMIBlocks` / `CMIBlockEntityTypes` / `CMICapabilities` / `CreateManaIndustry`
- [x] `./gradlew build` 通过、`runData` 生成掉落表 + 镐类标签
- [ ] **游戏内手动验收**（见第 8 节，需 `runClient`）

**代码审查修复（2026-08-15，对照 `.refs/Create` 逐文件核对）**：

- [x] **渲染器层级顺序**：`buildLevelRects` 内层 `Map` 由 `HashMap` 改 `TreeMap`（`stackFullLevels` 依赖升序 `break`，高塔/垫高罐曾漏渲染液面以下的实体盒）。
- [x] **亮度传播**：`getLightEmission` 改读控制器 `luminosity`（Create 逐 part 传播，燃料罐此前只控制器发光）；流体亮度变化时对全组 `checkBlock`。
- [x] **比较器刷新**：流体变化置 `needsNeighborRefresh`，在 `sendData` 实际发包（8-tick 节流）时对全组 `updateNeighbourForOutputSignal`（此前比较器输出陈旧）。
- [x] **装置精确几何**：`BasinData` 序列化细胞改**相对 `minCell`** 坐标，`readFromNBT` 加 `base` 重定位（正常 BE offset=0 零回归，装置 BE 落到局部坐标）；客户端两条反序列化路径（chunk/装置走 `read(false)`、网络包走 `read(true)`）都直接重建完整盆地。
- [x] **渲染器统一液面回退**：`basins == null` 时按 `填充度 × 储罐高度`（挂起用存档 `savedHeight`=maxY−minY+1，否则块数）渲染液面盒，O(1) 逐帧；挂起期存 `savedMin`/`savedMax` 足迹，**按组水平跨度画整组盒**（不再是一根细柱），无足迹时回退控制器单列。
- [x] **装置液面动画**：`afterSync` 用 `settle` 按新总量重分配逐盆地液面；`FuelTankMovementBehavior` 补 `basins.tickChasers()`。
- [x] **`settle` 逐盆地 `minY` 初始化**（原 `basins.get(0).minY` 在盆地 0 非最低时会把低盆地误判为满）。
- [x] **跨区块挂起（方案 B）**：`read` 存 `savedCount`/`savedHeight`/`savedMin`/`savedMax`；`updateConnectivity`/`recomputeBasins` 在「组块数 < 存档块数且边界仍有未加载区块」时**不合并/不裁剪/不重算**，`lazyTick` 每 10 tick 重试，全组齐备时恢复存档分布，确认真缩小（边界全加载）才 `settle`。顺带修掉半组先合并导致的**近满罐跨区块加载流体被裁丢**的既有 bug。
- [x] **正U/倒U 横梁顶 z-fight**（2026-08-15）：`buildLevelRects` 按格的 `(top,bottom)` 遮挡状态**分组**后再 `mergeLevel`，保证每盒状态均匀；`renderBox` 逐盒做 **Lid 顶收**（`y2-CAP`）与**底座内缩**（`y1+CAP+PUDDLE`），并移除原 `renderSafe` 里只按首格判定的 `hasLid`。正U横梁中格（单格变体、带 Lid）液体停在 Lid 底面，不再与横梁顶闪烁；腿格无 Lid 照常满。
- [x] **低液量隐藏**（2026-08-15）：`renderBox` 底座内缩后若盒退化但该格确有液体（`yMax > box.y1`），钳 `yMax` 到 `yMin + PUDDLE` 渲染一条可见细缝（对齐 Create 底部细缝；此前 0~31% 显示为空）。
- [x] **拆除残留方块**（2026-08-15）：`findGroupSameController` 漏掉种子块（`start` 未入 `out`）→ 每个 split 组件都缺种子、种子保留指向被拆块的旧控制器（客户端渲染成孤立整罐、墙错误）。已把 `start` 加入并校验。
- [x] **最后放置墙面未剔除**（2026-08-15）：`FuelTankModel` 的面剔除读 BE 控制器，而放置时区块重建先于控制器同步 → 最后放置的罐墙面不剔除、之后一直残留。修复：`setController` 控制器变化时 `sendDataImmediately`；客户端 `read` 收到控制器变化时 `sendBlockUpdated` 强制重渲染。
- [x] **中置搁板 / 复杂形状（test1.nbt）**（2026-08-15）：真鞍部判据收紧为「全局部最低 + 邻≥2 + 高度≥所有邻居最高格」。被邻居延伸到上方的中板（如 test1 的 y2 板）不再是脊、并入底座最低的邻居——此前它被当脊最后填充、还经鞍部截断吞掉立柱上部，填充顺序完全错乱。
- [x] **负高度鞍部截断失效（地下储罐）**（2026-08-15，诊断日志定位）：截断的 `bestH = -1` 哨兵值对负世界 Y 失效（`-59 > -1` 恒假）→ 地下罐（y<0）的鞍部永不吸收腿顶/烟囱。修复为 `Integer.MIN_VALUE`。**此前所有负 Y 储罐的盆地分解都是错的**（腿顶未并入、连通器未合成、填充顺序乱）。
- [x] **鞍部 puddle 渲染钳制**（2026-08-15）：低液量显示一层液体的 puddle 钳制只对**谷盆地**生效；鞍部盆地（含被吸收的腿上）与腿共用一个平液面，强制最小液层会使其凸出液面。`renderBox` 增加 `saddle` 参数并在鞍部跳过 puddle。
- [x] **液面水平缝隙 + 隐藏竖直面（逐面渲染）**（2026-08-15）：合并盆地内鞍部格与相邻同盆地格的共享竖直面被 `renderFluidBox` 整盒渲染并做 `HULL` 墙厚内缩，而相邻储罐的墙已剔除 → 液面间出现水平缝隙、隐藏竖面浪费。`renderBox` 改为**逐面渲染**（`FluidRenderHelper.renderStillTiledFace`）：某水平面的**同层相邻格**若同盆地且有液体，则该面**齐平（HULL=0）且不渲染**；否则正常 HULL + 渲染。贴图/颜色/光照/轻于空气翻转复刻自 `FluidRenderHelper.renderFluidBox`。
- [x] **拆分按盆地保留液面**（2026-08-15）：`split` 从旧 `surfaces` 反推每格液量并映射到各组件新盆地（被拆格液量随块消失，与 Create 预留 1 格损失一致）；旧盆地不可用回退 `settle`。此前为「先到先得按组件分发」，会一组灌满、另一组全空。
- [x] **同形判据收紧**（2026-08-15）：`recomputeBasins` 从「盆地数相等」改为「`basinByCell.keySet()` 相等」才按索引贴回旧液面——形状变了但盆地数碰巧相等时不再把旧液面贴到新盆地上。
- [x] **级联日志防御**（2026-08-15）：`fillCascade`/`drainCascade` 返回剩余量非零时 `LOGGER.warn`，暴露盆地邻接图体积不守恒，避免液体静默丢失。
- [x] **柱子液面侧面误剔**（2026-08-15，模拟器定位）：`stackFullLevels` 以 `Rect`（仅坐标）为合并键，把**不同遮挡（top/bottom）但同坐标**的 rect 错误纵向叠成一个盒（如 3×3 底座的中心格与柱底格）；`renderBox` 用盒底层的邻居判剔，中心格被底座液体包围 → 整盒 4 面全剔，柱底格液体侧面消失。修复：`Rect` 带 `occlusion` 字段（equals/hashCode 含它）；`isFaceHidden` 改为**逐层**检查跨层盒的每一面——只要某一层朝组外/不同盆地/液面上，该面就渲染（跨层盒底在组内、上层朝组外的柱体不再整面误剔）。
- [x] **连接空罐后液面搬家 / 注入顺序错**（2026-08-15）：连接新空罐后 `recomputeBasins` 无脑 `settle`，把旧液体按新形状重新分配（最低盆地先填），观感为液体「跑」到新罐/高盆地；且 `fillOrder` 按 `maxY` 静态排序，从高盆地（新罐）注入时液体却先填低盆地。修复：形状变化且旧 `surfaces` 有效时**按格映射**旧液面到新盆地（复用 `cellAmounts`，仅当映射总和精确等于库存总量才生效，合并两个带液组回退 `settle`）；`fillOrder` 改为**注入盆地（seed）优先填充**，其余按 `maxY` 升序。
- [x] **液面格侧面误剔 + 不收缩墙厚**（2026-08-15）：`isFaceHidden` 逐层检查用 `floor(box.y2)`，对部分填充的液面盒（`y2` 小数）`floor == box.y1` → 空循环返回 `true`，液面盒 4 面全剔且 `HULL=0` 齐平（不向内收缩墙面厚度）。修复：改 `ceil(box.y2)`，液面所在层也纳入检查（模拟器验证液面盒 4 面可见 + `HULL` 内缩）。
- [x] **填充/抽取顺序依赖建造顺序**（2026-08-15）：`computeBasins` 把 `findGroup` 的 `HashSet`（迭代顺序跟随 BFS 访问顺序 = 建造顺序）`toArray` 后直接当遍历序；盆地 id 分配（`sinkRootToBasin`）与 `fillOrder`/`drainOrder` 的平局 BFS 序都由它派生 → 同一形状不同建造顺序，顺序不同。修复：`computeBasins` 开头把 `cells` 按 `comparePos`（Y→X→Z）**排序**，分解成为形状的纯函数（模拟器验证不同插入序得到相同单元数组）。
- [x] **S 形储罐注入/抽取顺序不唯一 → 重写为连通器动态水位**（2026-08-15）：旧的 `fillOrder`/`drainOrder` 静态排序（seed 优先 + maxY 排序）无法处理倒U+正U 拼接（S 形）等多鞍部交错形状，注入/抽取顺序随位置时对时错。重写为**动态水位扫描**：`BasinData` 增 `adjHeight`（每对相邻盆地的最低溢流高度 = 交界格对 `max(p.y,q.y)` 的最小值）；`fillWater`/`drainWater` 从 seed 盆地维护连通区、统一水位逐事件升降，水位淹没鞍部时邻居入区（`expandRegion` 要求源盆地实际有液体才传播，避免空盆地提前淹没鞍部）；`rebalanceOverSpill` 收尾让液面高于鞍部而干侧低于鞍部的边界溢流平衡。顺序/分布只由「形状 + 注入/抽取位置」唯一决定，符合连通器与水压规则（Python 模拟器验证倒 U、S 链、底座+柱+矮平台的体积守恒与连通器平衡）。
- [x] **test2/test3 抽液液面不移动、抽干瞬间全消失**（2026-08-15）：`drainWater` 只抽 seed 连通区，液体困在「液面低于鞍部」的盆地（虹吸限制）→ `drainWater` 返回 leftover 抽不动，但 `SeedTrackingHandler.drain` 已把 `tankInventory` 物理抽走 → **surfaces 卡住（显示还有液体）、库存却持续变空**，抽干瞬间 `renderSafe` 的 `fluid.isEmpty()` 直接 return，所有渲染液体消失。修复：`drainWater` 在 seed 连通区耗尽后**补抽剩余盆地**（当前液面最高先抽，逐个抽到见底或抽量耗尽）——从任意抽取位置都能排干全部；`drain` 保留 `rebalanceOverSpill`；`applyDelta` 的困水放回保留为浮点误差保险（Python 模拟器验证 test3 从鞍部/谷抽均全程 `leftover 0` 排干）。

**代码审查修复（2026-08-16，第二轮，逐项定案后落地）**：

- [x] **桥接路径重写为组件级分配**（`updateConnectivity` 不兼容分支）：旧逻辑只假设「单个空的新方块」——首个邻居并入，装置卸货到异种流体罐旁时会把新方块（带流体）的液体静默销毁（`assignGroup` 清空被吸收 part 的罐），并把同批空 part 碎成多个小组（每个 part 只跑一次桥接、永不收敛）。现改为：**带液组各自独立成组；空块按面连通片 BFS，从片内字典序最小格出发，按 `Direction.values()` 方向序遇到第一个非空组即整片并入**——零丢液、无碎裂、确定性。
- [x] **配置上限收紧防 int 溢出**：`fuelTankCapacity` 1..100、`fuelTankMaxBlocks` 1..21,474（乘积 2,147,400,000 ≤ Integer.MAX_VALUE；旧上限 1,000,000 × 262,144 × 1000 ≈ 2.6e14 会溢出为负容量）。
- [x] **跨区块拆块三分支**（`split`）：组件边界触及未加载区块时——拆的不是控制器 → **延迟拆分**（流体与逐盆地分布留在旧控制器，part 加载后 `updateConnectivity` 重组；被拆格份额也守恒、后续 settle 摊入）；拆的恰是控制器 → **settle 回退**（从全额按组件容量分摊，只丢超出已加载总容量的部分）；全加载 → 现状逐格映射。旧行为在「控制器已加载、远端 part 未加载」时静默销毁未加载 part 的份额。
- [x] **挂起路径形状守卫**：`read` 反序列化存档 `Basins` 到 transient `savedBasins`（客户端即时几何直接复用，省一次反序列化）；`recomputeBasins` 挂起分支要求 `keySet` 与存档一致才 `restoreSurfaces`，同数异形（控制器卸载期间远端被改建）走 `settle`——旧逻辑按索引粘贴破坏体积不变量。
- [x] **`rebalanceOverSpill` 超限告警**：guard（`n*4`，n = 盆地数）耗尽仍有未平衡边界时 `LOGGER.warn`（与 `fillCascade`/`drainCascade` 同风格），上限不动。
- [x] **哨兵修正**：`maxSurface`/`drainWater` 的 `Float.MIN_VALUE`（最小正浮点）→ `Float.NEGATIVE_INFINITY`——地下罐（全负液面）不再依赖「≈0 恰为满罐液位」的巧合；`Float.MAX_VALUE`（取最小的初值，正确）保留。
- [x] **扳手交互**：`FuelTankBlock implements IWrenchable` 零覆盖——普通扳手默认旋转对无朝向方块为 no-op；潜行扳手走默认 `onSneakWrenched` 拆除（BreakEvent 可取消、掉落入背包、WRENCH_REMOVE 音效），与 Create 流体罐交互一致（Create 的扳手是开窗切换，本罐视窗恒开故仅保留拆除语义）。删除空钩子 `clientBasinsChanged`（方法 + 两处调用点）；`removeController(keepFluids)` 保留（接口抽象方法）。
- [x] **渲染器零世界读取**（装置坐标修复）：`occlusionKey` 与 `renderBox` 的盖/底座内缩改从 `basinByCell` 推导（TOP = 上方无同组格、BOTTOM = 下方无同组格，与 `notifyMultiUpdated` 的判定同源）——装置 BE 的盆地是局部坐标，旧代码用局部坐标读真实世界方块导致装置上液体顶面与罐盖 z-fight、底面插进罐底。

**模型六面统一（2026-08-16，逐项定案后落地）**：

- [x] **统一模型**：4 变体（single/top/bottom/middle）→ 单一 `block_single_window.json`（Python 脚本从 middle 全高框 + single 8×8 窗确定性合成，18 元素）：侧边 = 全高框（`#1` 整幅纹理 1:1）+ 8×8 窗（`#5`，y4-12）；顶面 = 1 单位厚环框（y15-16，`#0` 边框 UV 1:1）+ 8×8 顶窗格（y15.05）；底面 = 1 单位厚实心底板（y0-1，`#0`，**无窗**——液体渲染无底面，开底窗需改渲染，暂缓）。删除 `#4 inner` 纹理引用与其余 3 个变体文件。
- [x] **顶/底剔除对齐侧面**：`FuelTankModel.CullData` 改 `get3DDataValue()` 存全部 6 面，`gatherModelData` 遍历 6 方向——同组相邻即剔除，取代旧 top/bottom 变体机制。
- [x] **TOP/BOTTOM 属性删除**：`FuelTankBlock` 状态定义与 `registerDefaultState` 去掉两属性；`notifyMultiUpdated` 精简为仅 `setChanged()`（连通性重渲染由 `sendDataImmediately`/`sendBlockUpdated` 覆盖）；blockstate JSON 改单变体 `""`。
- [x] **液体内缩常量随薄板调整**：`CAP = 1/4`（4 单位盖）→ `1/16 + 1/128`（1 单位环框/底板）；底座内缩 `CAP + PUDDLE` → `CAP`（renderBox + fallback 两处）；fallback 的 `BOTTOM` 状态读取 → `isSameGroup(level, c, c.below())` 判定（语义等价）。

---

## 7. 已知限制

1. **轻于空气流体**仍从底部填充（未实现 Create 的上下反转）。
2. **无右键桶灌装**（仅管道/喷口）。
3. **未做 O(1) 增量合并**：每次放置整组重算（O(n)，`fuelTankMaxBlocks` 有界），连续快速建造大型储罐可能有轻微卡顿。
4. **多管道并发**在不同位置灌/抽时，分布按各自注入点级联，极端并发下略不物理（每格独立应用增量）。
5. **脊/谷判定**：真鞍部 = 全局部最低 + 邻≥2 + 高度≥邻居最高格最小值；搁板（低于所有邻居）并入底座最低邻居；单盆地（邻0）不算脊。**鞍部截断**只收同层格、**脊合并**合并同层相邻脊、**谷地重拆**按盆地id+连通分量拆。复杂多层鞍部的截断边界由循环迭代收敛（相邻脊高度取该脊当前 maxY），极端形状可能不完全符合直觉。
6. **跨区块挂起窗口**：组跨区块且控制器所在区块先加载时，半组期间渲染为**按存档足迹铺开的整组盒**（非精确盆地），且该窗口内管道灌入只会改总量、不更新逐盆地分布；区块齐备后恢复精确分布。若块在离线期间被拆，需等相邻区块加载完成后才确认缩小并 `settle`（`savedCount` 不匹配时会一直挂起到该区块出现）。同数异形（卸载期间被改建且块数巧合不变）经 `savedBasins` keySet 守卫走 `settle`，不再按索引贴错液面。
7. **装置上液面分布为沉淀态**：移动结构只携带总液量（`MountedStorage`）；`afterSync` 仅在总量实际变化时用 `settle` 重分配（`surfacesRepresent` 判断），首次装配保留序列化的动态分布；移动中灌/抽后分布变为沉淀态（与 Create 装置上单液面等价）。
8. **跨区块拆块（2026-08-16 起）**：拆非控制器且组可增长 → 延迟拆分，流体留在控制器侧、被拆格份额也保留（比全加载语义更慷慨）；拆控制器且可增长 → settle 回退，只丢超出已加载组件总容量的部分。
9. **不兼容流体桥接（2026-08-16 起）**：组件内存在多种流体时，带液块各自独立成组、组间保留墙壁；空块整片并入首个非空组（方向序）。桥接块即使之后一侧排空也不会自动重新合并——需放置/拆除任一方块或区块重载触发 `updateConnectivity`。
10. **载入当 tick 的 capability 亚 tick 窗口**：part 的控制器区块未加载时，`handlerForCapability` 短暂返回空罐（`FluidTank(0)`），同 tick 内排在前面的管道操作会重试一次；首 tick 后部分组成形即恢复。
11. **逐 tick 算法成本**：每次管道灌/抽跑 `fillWater`/`drainWater`（部分灌注触发 48 次二分）+ `rebalanceOverSpill`；常规形状微秒级，上千盆地的病态形状 + 持续泵灌可达毫秒级/tick（`fuelTankMaxBlocks` 上限兜底）。
12. **存档体积**：控制器 NBT 携带整组几何（细胞相对坐标，~20-25 字节/格，4096 格 ≈ 80-100KB）——装置捕获必须带几何（装置上无世界可反查），故不做省格优化。

---

## 8. 验证指南

运行 `./gradlew runClient`，手动测试：

1. **单格灌/抽**：放置一格，用管道或喷口灌入 → 液面平滑上升；抽取 → 平滑下降；**装满后液面停在 Lid 底面下方（CAP 空隙），不与储罐上表面 z-fight**。
2. **形状连接**：2×2、长方形、L形、菱形 → 连接成立、容量叠加、无幽灵液体、组内壁不渲染。
3. **倒U形盆地验收**（每格容量 C，腿 y0..h-1 各 3 格、顶梁 y=h 3 格，总容量 9C）：
   - 从左腿注液：左腿满（3C）前，右腿与顶梁为空；
   - 左腿满（3C）→ 右腿从底升起（4.5C 时右腿底半）；右腿满（6C）→ 顶梁从底升起；全满 9C；
   - 从满罐的左腿抽液：顶梁先降 → 左腿 → 右腿；抽空全部平滑到底、**无瞬间消失**（液面恒等于总量）。
4. **跨格重算**：高柱液面跨过格层时，仅该层重算（性能观察：不卡顿）。
5. **上限分裂**：`fuelTankMaxBlocks` 调小后建超大组 → 超限处断开、各自独立。
6. **比较器**：填充度 → 红石信号。
7. **动态装置**：罐装上火车/移动结构移动，流体随行；移动中管道可连可抽。
8. **视觉**：视窗恒开、扳手无效、同组内壁剔除、无 CTM 接缝。

---

## 9. 后续工作（候选）

- ~~盆地抽取的反向级联细化（含复杂鞍部）~~ ✅（drain 已全盆地级联 + 鞍部截断）
- O(1) 增量放置（放置查 6 邻居直接并入，桥接/拆断才 O(n)）。
- 右键桶灌装（`GenericItemFilling/Emptying`）。
- 轻于空气流体支持。
- 正式纹理/模型替换（当前为 Create 占位复制）。
- 熔盐堆反应堆本体：加热、产能、燃料消耗联动。
