# 高性能 GPU 粒子引擎 — 开发文档

> 模组：CreateManaIndustry（机械动力：魔法工业）
> 版本基线：`0.2.3-fix`（Minecraft 1.21.1 / NeoForge 21.1.227 / Java 21）
> 状态：**可用**（用户实测：开/关光影包均正常显示粒子；流时长=真实秒数；编译打包通过）
> 本轮新增：**ALPHA 材质（带贴图）+ GPU LSD 深度排序 + 块碰撞（3D 占用纹理）**，并用 `cherry_leaves` 预设忠实复刻了原版樱花粒子作为集成演示。

---

## 1. 定位与目标

一套**客户端独有、纯 GPU** 的百万级粒子引擎：

- **计算着色器更新**（物理/寿命/回收）+ **GPU 实例化绘制**（单次 `glDrawArraysIndirect`）
- **CPU 零逐粒子上传**：每帧只写少量“发射命令”（每发射器 8 浮点），粒子状态全程留在显存
- 验收基线：中端卡（RTX 3060 级）1440p，`更新 + 绘制 ≤ 5ms/帧`，默认预算 2M 粒子（上限 4M 可配）
- **自托管 GL**：着色器程序由本 mod 自带 GLSL 资源用原生 LWJGL 编译，不依赖 Veil 的 ShaderManager
- 叠加混合为主（材质类型槽位预留）；暂不接入现有机器视觉（动力雾化器等后续接）

---

## 2. 代码清单（当前实际文件）

### Java — `src/main/java/com/iridium126/createmanaindustry/`

| 文件 | 职责 |
|---|---|
| `client/particles/engine/CMIParticleEngine.java` | 引擎单例：帧钩子、`spawn/stream/clear/budget/stats`、ping-pong 编排、reset/update/emit + keygen/radix 排序路径、双段绘制（additive + alpha）、块碰撞烘焙编排、自适应节流、`close()` |
| `client/particles/engine/ParticleBuffers.java` | GPU 资源：双缓冲粒子 SSBO、发射命令环 ×3、计数器环 ×4、发射器头 SSBO（20×vec4）、间接命令 ×2（additive/alpha）、排序数据/直方图/偏移 SSBO、bakeMeta SSBO、无属性 VAO；上传/回读/解绑/free |
| `client/particles/engine/ParticlePrograms.java` | 自托管 GLSL：读 `assets/.../shaders/particles/*`，原生编译/链接 9 个程序（reset/update/emit/keygen/hist/scan/scatter/additive/alpha），脏标记+F3+T 重编译 |
| `client/particles/engine/ParticleFrameProfiler.java` | 帧耗时 EMA + 迟滞节流控制器（预算默认 5ms） |
| `client/particles/engine/ParticleShaderReloadListener.java` | 客户端资源重载监听（F3+T → 请求重编译着色器） |
| `client/particles/engine/ParticleAtlas.java` | 自托管 sprite 图集：vanilla `cherry_0..11` 拷入本 mod 资源，`NativeImage` 解码拼 4×3 GL 图集，懒加载 |
| `client/particles/engine/CollisionBake.java` | 块碰撞占用烘焙：单张 `GL_TEXTURE_3D`（48×32×(48×K)，K=8），按 spec 分配切片、同锚共享、LRU 淘汰、每秒重建 |
| `client/particles/emitter/EmitterSpec.java` | 不可变发射器规格 + builder；`pack()` 打成 20×vec4 GPU 头（新增 material/collide/flutter/spin/spriteCount/bakeIndex） |
| `client/particles/emitter/EmitterShape.java` | `POINT / BOX / SPHERE / CONE` |
| `client/particles/emitter/EmitterPresets.java` | 7 个内置蓝本：`mana_spark / ember / ash / soul_flame / mana_burst / cherry_leaves / flood` |
| `client/particles/command/CMIParticleCommand.java` | `/cmip spawn|stream|bench|clear|stats|budget`（NeoForge 客户端命令） |

接线（`CreateManiaIndustryClient.java`）：原生 `RenderLevelStageEvent.AFTER_LEVEL` 帧钩子、进出世界/跨维清池、`GameShuttingDownEvent` 释放资源。
配置（`config/ClientConfig.java` — `particles` 段）：`enabled`、`maxParticles`(2_000_000)、`frameBudgetMs`(5.0)、`autoThrottle`(true)。

### 着色器 — `src/main/resources/assets/createmanaindustry/shaders/particles/`

| 文件 | 用途 |
|---|---|
| `reset.comp` | 1 线程：计数器归零 + 两条间接绘制实例数归零 |
| `update.comp` | 物理积分（重力/恒加速度/风/阻力/`flutter` 飘摇）、寿命、致密化回收、**块碰撞（占用纹理，轴分离解析）**、原子追加 |
| `emit.comp` | 读 CPU 发射命令，按形状/随机初始化新粒子 |
| `keygen.comp` | 生成 `(key,index)` 排序对：`key = materialRank<<30 | 视图深度量化`，并按材质原子累计两条间接命令实例数 |
| `radix_hist.comp` / `radix_scan.comp` / `radix_scatter.comp` | 8 位 LSD 基数排序三阶段（直方图 → 512 线程前缀和 → 散列），4 趟完成 32-bit key |
| `additive.vsh` | `gl_InstanceID`（或排序排列）从粒子 SSBO 取数、相机朝向 billboard、尺寸/颜色/透明度关键帧、每发射器 glow |
| `additive.fsh` | 软圆衰减 + 距离淡出 + 叠加输出 |
| `alpha.vsh` | 纹理（ALPHA）牌面：按 seed 选 sprite 帧、vanilla 自旋（roll0+ωt+½αt²）、经排序排列远→近取粒子 |
| `alpha.fsh` | 采样 sprite 图集、贴花 alpha 混合、远淡出 |

（着色器已迁出 Veil 的 `pinwheel/`，与 Veil 完全解耦；Veil 仅继续服务雾墙/后处理。）

---

## 3. 数据与内存布局

### 粒子（64 B = 4×vec4，双缓冲 ping-pong，各 `cap×64B`）
| vec4 | 内容 |
|---|---|
| p0 | `position.xyz, size`（size 为每粒子尺寸随机倍数） |
| p1 | `velocity.xyz, roll`（滚动角） |
| p2 | `tint.rgb, intensity` |
| p3 | `age, maxLife, seed, emitterId(uint bits)` |

### 发射器头（320 B = 20×vec4 / emitter，SSBO，按 spec equals 去重缓存）
`origin(占位)` / `shape,speed,radius` / `gravity,drag` / `accel,windStrength` / `windDir,rotation` / `life,sizeStart,sizeEnd` / `sizeEase,coneTanHalf,colorCount,glow` / `material,collideMode,flutter,spin` / `8×RGBA 颜色关键帧` / `bakeIndex,spriteCount,0,0` / 保留

### 发射命令（32 B / 条，环 ×3）：`origin.xyz + count` / `emitterId + seed`
### 间接命令（2×16 B）：`count=6, instanceCount(GPU原子累计), first=0, baseInstance=0`（cmd0=additive, cmd1=alpha）
### 计数器（16 B / 槽，环 ×4）：`writeSlot, spare`（spare 由 capture 记录上一帧 alphaCount）
### 排序数据（双缓冲，按 ALPHA 数量紧凑使用）：`sortData` 8 B/alpha = `(key, index)`；`orderAdd` 4 B/粒子 = additive 排列；直方图/偏移 各 256×uint
### 碰撞纹理（GL_TEXTURE_3D R8）：`48 × 32 × (48×K)`，K=8 个 3D 切片堆叠；bakeMeta（每槽 origin.xyz + presence）

---

## 4. 每帧渲染管线（`AFTER_LEVEL`）

### 快速路径（无 ALPHA 材质，与原始引擎一致）

```
1. 清空/合并客户端请求（pending 队列：burst / stream / clear）
2. 从计数器环读上一帧 slot → aliveRead
3. 按节流 scale 构建发射命令（bursts + streams），按剩余容量裁剪
4. upload 发射命令到环槽；若发射器头脏则整块增量上传
5. compute：reset(1线程) → memoryBarrier → update(aliveRead 线程) → emit(totalSpawn 线程) → memoryBarrier
6. draw：additive 程序 + 新鲜写入缓冲(binding1) + 发射器头(binding5) + VAO，uUsePerm=0（gl_InstanceID 直取）
   → 叠加混合 + 深度测试（不过深度写）→ glDrawArraysIndirect(cmd0)
7. 解绑 SSBO base 0-11；swap ping-pong；记录帧耗时 → 节流更新
```

### 排序路径（存在 ALPHA 粒子时，由 lagged prevAlpha / 本帧 alpha 发射 逐帧判定）

```
0. 帧首从计数器环滞回读 {alive, alphaCount} → aliveRead、prevAlpha（非阻塞，与 aliveRead 同机制）
1..5. 同快速路径；update 额外做 flutter 飘摇 + 块碰撞；uSort=1 时 update/emit 不再累加间接实例数
6. keygen=partition(sortUpper 上界)：additive→orderAdd[atomicAdd(cmd0.y)]=g；
   alpha→sortData[atomicAdd(cmd1.y)]=(视图深度key, g)（深度仅 alpha 计算）
7. 若 alphaUpper = min(cap, prevAlpha+本帧新增+余量) > 0：LSD 基数 3 趟（24 位深度 key）
   只作用于 alpha 段（hist/scan/scatter 以 GPU 的 cmd1.y 为守，派发线程数=alphaUpper）
8. draw：additive→orderAdd（cmd0）恒等排列；alpha→排序后的 sortData（cmd1，远→近）
   ALPHA 段绑定 cherry sprite 图集(unit1)，正常 alpha 混合；无 basePerm 同步回读
9. capture(1线程) 把 cmd1.y 写入本帧 counter 槽 spare（供下帧非阻塞读取）；解绑 SSBO 0-12 与纹理
```

- 致密前缀不变量：写缓冲 `[0, liveCount)` 恒为全部存活粒子；死粒子在 update 中被跳过即移除
- 存活计数走**原子计数器环**（×4）：本帧写 `simFrame%4`，回读 `(simFrame-1)%4`（上一帧应产生的最终值），消除“读-再-改同一缓冲”
- 发射线程用 `g >= uTotalSpawn` 提前返回；`atomicAdd` 分配槽位，`slot >= uCapacity` 丢弃（CPU 侧已预留 2048 安全余量，理论上不触发）
- **双模回退**：`sortedDraw = (prevAlpha>0) || 本帧有 alpha 发射`；否则全池只有 additive，跳过 keygen/radix/capture 副作用，additive 用 gl_InstanceID 恒等绘制（快速路径）——alpha 死光后自动回退，不再永久走排序
- 排序 key 为 24-bit 深度量化（0..2048 格）；radix 只排 alpha 段，real work ∝ alphaCount（与 additive 池大小解耦）

---

## 5. 关键设计决策与踩坑记录

| 决策 | 结论 | 原因/教训 |
|---|---|---|
| 着色器程序来源 | **自托管原生 LWJGL 编译**（`ParticlePrograms`） | Veil `ShaderManager` 包装的 compute 在本环境**空转**（探针证实：程序合法、派发无 GL 错误、内核首行探针都不写）。自托管后 `layout(binding=N)` 由 GL 直接生效，开/关光影包均正常 |
| 帧钩子 | **NeoForge 原生 `RenderLevelStageEvent.AFTER_LEVEL`** | 与 Veil 桥为同一阶段；无 Veil 也可用（引擎不再依赖 Veil） |
| 着色器资源位置 | `assets/.../shaders/particles/`（迁出 pinwheel） | 与 Veil 资产彻底解耦；删除 4 个 Veil 程序 JSON |
| 粒子/流计时 | `dt = getRealtimeDeltaTicks()×0.05`（**真实墙钟**） | 曾误用 `getGameTimeDeltaPartialTick(false)`（tick 插值相位 ∈[0,1)，非每帧耗时）→ 100s 流 ~50s 提前结束、粒子速度随 FPS 漂移 |
| 容量 | 默认 2M / 上限 4M 可配；`min(配置, GL_MAX_SHADER_STORAGE_BLOCK_SIZE/64)` 自适应 | SSBO 单块上限硬约束 |
| 计数器 | 环 ×4 | 消除同帧“读计数器 + 立刻重置同一缓冲”的竞态耦合 |
| 混合/材质 | ADDITIVE（旧路径零改动）+ ALPHA（带 sprite 贴图、正常 alpha 混合） | 每发射器一档 `material`；ALPHA 需 GPU 深度排序才能正确叠加 |
| 深度排序 | **partition + 8 位 LSD 基数排序**（3 趟，24 位深度 key），只作用于 **alpha 段**（compact sortData），additive 走独立 orderAdd 恒等排列 | real work ∝ alphaCount，与 additive 池大小解耦；同距离粒子乱序无害 |
| 双模回退 | lagged `prevAlpha`（capture 写入 counter.spare，下帧非阻塞滞回读）判定 `sortedDraw` | alpha 死光自动回恒等快路径，消除“永久排序路径”开销；无新增同步点 |
| 块碰撞 | 3D 占用纹理（48×32×48/K 切片） + X/Y/Z 轴分离解析 | 真 SDF 距离场成本高；占用纹理实现快、够用，后需可升级 |
| cherry_leaves | 忠实复刻 `CherryParticle`：300t 寿命/0.3 块/s² 重力/0.075 尺寸/flutter 螺旋/±30°自旋/12 帧/触地移除 | flutter 换算成块/s²（×1.0 系数）；自旋/选帧在 vsh 由 seed 解析，不占粒子数据 |
| 亮度预算 | 每发射器 `glow` 生效（F1）+ 关键帧 alpha（F2） | 预设已按不洗白重调：`mana_spark 1.1 / soul_flame 1.4 / mana_burst 1.2 / ember 0.9 / ash 0.6 / flood 0.7` |

### 已修复问题清单（历史）
1. 初始化崩溃：`tmp4` 4B 写 16B → `BufferOverflow`（扩至 32B + init 进 try）
2. 回读崩溃：`glGetBufferSubData` 按 `remaining()` 读 32B 越界 → GL_INVALID_VALUE → BufferUnderflow（专用 4B `readTmp`）
3. 绘制读旧缓冲（应读本帧 write 缓冲）
4. LWJGL 常量/函数归属（GL11/15/30/40/42/43 修正）
5. Camera 1.21.1 API（无 `getRightVector`，用 `-getLeftVector`）
6. **Veil 包装 compute 空转** → 自托管 GL 程序（根治）
7. 流时长/粒子速度时钟错位 → 真实墙钟
8. 跨维/进出世界清池、退出释放、SSBO 解绑、线程可见性（volatile）、发射器上传不再整块重建
9. 本轮：`GL_TEXTURE_3D` 走 GL12（GL11 无此常量/函数，编译期即拦）
10. 本轮：flutter 转换成块/s² 时用了错误的 0.05 系数 → 应为 **×1.0**（0.0025×20×20），否则飘摇比 vanilla 弱 20 倍
11. 本轮：排序路径的 addCount 分段偏移通过每帧一次 4B 回读（`cmd0.y`）取得（radix 完成后 barrier + `glGetBufferSubData`）——后续已由双缓冲分离方案取代，此回读删除
12. **回归：发射器头扩到 20×vec4 后，5 个 shader 的取头步长仍是 `eid*16u`** → id≥2 的发射器字段全部错位（material/bakeIndex/spriteCount/速度波形），keygen 把后生成的类别错误分桶，表现为“同 runClient 只见先生成的粒子类别、/cmip stats 正常”。改为 `eid*20u`（须与 `EmitterSpec.VEC4_PER_EMITTER` 保持一致）
13. **崩溃：首次跑 cherry 时 nvoglv64 `EXCEPTION_ACCESS_VIOLATION`（栈顶 `CollisionBake.rebuild → glTexSubImage3D`）** → MC 帧后处理/贴图管线会残留 `GL_PIXEL_UNPACK_BUFFER` 与 `GL_UNPACK_ROW_LENGTH/IMAGE_HEIGHT` 等 pixel-store 状态；客户端内存上传时 GL 会把 ByteBuffer 指针当 PBO 偏移/按错误步长读越界 → 驱动崩溃。新增 `ParticleGLUtil.prepareClientUpload()`，所有客户端内存贴图上传（图集 2D、碰撞 3D）前重置 PBO 绑定与 unpack 状态
14. **历史容量 bug：粒子 SSBO 只按 16 B/粒子分配（应为 64 B）** → `cap = maxSSBO/16` 与 `createBuffer(cap*VEC4_PER_PARTICLE*4)` 各少乘一个 4；上报容量看似 2M，实际只分 32MB、真实只能装 ~52 万粒子 → 存活数长期卡在 50 万附近（本 bug 在本轮改动前就存在）。引入 `BYTES_PER_PARTICLE=64`，`cap = maxSSBO/64`，缓冲 `cap*64 B`，使池子真正达到配置上限（默认 2M = 2×128MB，符合第 7 节文档）

---

## 6. 命令与配置

```
/cmip spawn <preset> [count]      爆发（受节流）
/cmip stream <preset> <rate> [sec] 流式（秒数=真实秒；<=0 为无限，直到 /cmip clear）
/cmip bench <count>                不受节流压测（默认用 mana_burst）
/cmip clear                        清空粒子与流
/cmip stats                        存活/容量、streams、emission%、帧耗时 EMA、预算
/cmip budget <ms>                  覆盖节流预算
```
预设：`mana_spark / ember / ash / soul_flame / mana_burst / cherry_leaves / flood`。
`cherry_leaves` 为 **ALPHA 材质 + 碰撞（触地即移除）** 演示：`/cmip stream cherry_leaves 200 20` 可在任意位置看持续飘落。
配置文件 `run/config/createmanaindustry-client.toml` → `[particles]`：`enabled / maxParticles / frameBudgetMs / autoThrottle`

---

## 7. 性能特征（对照验收基线）

- 显存：默认 2M = 2×128MB 粒子 SSBO + 40KB 发射器头(20×vec4) + 3×8KB 命令环 + 4×16B 计数环 + 排序数据 2×(8B/粒子) + 直方图/偏移 + 碰撞纹理(≤0.6MB)
- 每帧 CPU：发射命令（≤256 条目 × 32B）+ 计数器回读（1 帧旧，非阻塞）+ 排序路径 1 次 4B addCount 回读（radix 后同步一次）
- 每帧 GPU（快速路径）：reset/update/emit 3 次极小 compute + 1 次间接绘制；空载 `frame≈0.0x ms`
- 每帧 GPU（排序路径）：reset/update/emit + keygen + radix(4×~10n) 约 ~10 次小 compute + 2 次间接绘制；alpha 粒子不多时开销仍极小
- 节流：EMA（0.9/0.1）+ 迟滞（>预算 ×0.85 降 / <预算×0.5 ×1.05 升，钳制 0.05..1）
- 验收：默认预算 2M 时 1440p ≥60fps、更新+绘制 ≤5ms（中端卡）；`/cmip bench 1000000` 可压测

---

## 8. 后续开发方向

1. **真 3D SDF 碰撞**：把占用纹理升级为距离场（跳跃填充/距离变换），粒子沿梯度平滑推出、支持反弹材质
2. **世界接入**：本引擎的 `cherry_leaves` 预设已就绪，可经 mixin/事件把原版樱桃叶方块的粒子改走本引擎渲染（自然飘落）
3. **alpha 材质扩展**：多图集/多帧动画、非相机朝向的 3D 叶片牌面、per-emitter 半透明深度排序参数

---

## 9. 已知限制与注意事项

- 引擎是**纯客户端、世界锚定**：跨维/进出世界自动清池；多人下各客户端独立渲染（装饰性，不联网同步）
- 坐标用 float：远距离（>~50k 方块）粒子位置精度下降（可接受）
- 粒子位置为绝对世界坐标；模型视图为相机旋转矩阵 + 相对坐标换算（vsh 内 `worldPos - uCamPos`）
- 无 Veil 时引擎可独立工作（自托管），但雾墙/后处理仍依赖 Veil（互不影响）
- 叠加混合粒子上限受 8bit 目标钳制；超过 1 即饱和为白（glow 已按预算重调）
- 开发期验证命令 `/cmip` 归入客户端命令，随模组发布（不影响服务端稳定性）

---

## 10. 参考资料

- 架构迭代与探针结论见会话记录（Veil 空转定位、时钟错位源码级证据）
- 着色器编写参考 `.agents/skills/shader-dev`（粒子系统/乒乓缓冲/亮度预算章节）
- Veil 源码镜像：`.refs/Veil/`（`VeilShaderBlockState`、`ShaderProgramImpl`、`Timer` 语义）
