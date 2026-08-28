# 高性能 GPU 粒子引擎 — 开发文档

> 模组：CreateManaIndustry（机械动力：魔法工业）
> 版本基线：`0.2.3-fix`（Minecraft 1.21.1 / NeoForge 21.1.227 / Java 21）
> 状态：**可用**（用户实测：开/关光影包均正常显示粒子；流时长=真实秒数；编译打包通过）
> 本轮新增：**MODEL 粒子血量与玩家近战**（maxLife 槽复用为 HP=20、每帧 GPU 视线×AABB 命中查询 + fence 快照零停等回读、CPU level.clip 遮挡、`Player.attack` 逐项对齐的伤害管线、vanilla 2 HP/s 回血、尸体态 1s 死亡动画、`allay_death` 预设删除、MODEL 视觉尺寸对齐原版）；此前：Allay Storm（boids/涡旋）、MODEL 材质实例化渲染、ALPHA 材质 + GPU 深度排序、块碰撞、GPU 视锥剔除。

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
| `client/particles/engine/ParticleBuffers.java` | GPU 资源：双缓冲粒子 SSBO、发射命令环 ×3、计数器环 ×4、发射器头 SSBO（20×vec4）、间接命令 ×3（additive/alpha/**model**）、排序数据/直方图/偏移 SSBO、bakeMeta SSBO、模型几何 SSBO、orderModel 排列、无属性 VAO；上传/回读/解绑/free |
| `client/particles/engine/ParticlePrograms.java` | 自托管 GLSL：读 `assets/.../shaders/particles/*`，原生编译/链接 9 个程序（reset/update/emit/keygen/hist/scan/scatter/additive/alpha），脏标记+F3+T 重编译 |
| `client/particles/engine/ParticleFrameProfiler.java` | 帧耗时 EMA + 迟滞节流控制器（预算默认 5ms） |
| `client/particles/engine/ParticleShaderReloadListener.java` | 客户端资源重载监听（F3+T → 请求重编译着色器） |
| `client/particles/engine/ParticleAtlas.java` | 自托管 sprite 图集：vanilla `cherry_0..11` 与 `allay_0` 拷入本 mod 资源，`NativeImage` 解码拼 GL 图集，懒加载 |
| `client/particles/engine/AllayModelGeometry.java` | **MODEL 材质几何烘焙**：原版 `AllayModel.createBodyLayer` 的 7 个立方体 → **索引化**顶点数组（pos/uv/partId，6 float/顶点，4 顶/quad + 双三角索引，136 顶/276 索引；翅膀双绕序索引保持双面、零顶点成本），UV/绕序与 `ModelPart.Cube` 完全一致；**不透明段（头/皮肤/双臂）与半透明段（斗篷+双翅）拆成连续索引区间** |
| `client/particles/engine/CollisionBake.java` | 块碰撞占用烘焙：单张 `GL_TEXTURE_3D`（48×32×(48×K)，K=8），按锚点格子分配切片、同锚共享、LRU 淘汰、每秒**后台线程**重建（渲染线程仅解析区块引用与上传） |
| `client/particles/emitter/EmitterSpec.java` | 不可变发射器规格 + builder；`pack()` 打成 20×vec4 GPU 头（material/collide/flutter/spin/spriteCount/bakeIndex/**animation**） |
| `client/particles/emitter/EmitterShape.java` | `POINT / BOX / SPHERE / CONE` |
| `client/particles/emitter/EmitterPresets.java` | 7 个内置蓝本：`mana_spark / ember / ash / soul_flame / mana_burst / cherry_leaves / flood` |
| `client/particles/command/CMIParticleCommand.java` | `/cmip spawn|stream|bench|clear|stats|budget`（NeoForge 客户端命令） |

接线（`CreateManiaIndustryClient.java`）：原生 `RenderLevelStageEvent.AFTER_LEVEL` 帧钩子、进出世界/跨维清池、`GameShuttingDownEvent` 释放资源。
配置（`config/ClientConfig.java` — `particles` 段）：`enabled`（总开关，关闭即清池）、`maxParticles`(2_000_000)、`frameBudgetMs`(16.6)、`autoThrottle`(true)、`fadeDistance`(96，可见到 +24 格)。

### 着色器 — `src/main/resources/assets/createmanaindustry/shaders/particles/`

| 文件 | 用途 |
|---|---|
| `reset.comp` | 1 线程：计数器归零（含半透明普查）+ 五条间接绘制实例数全部归零 |
| `update.comp` | 物理积分（重力/恒加速度/风/阻力/`flutter` 飘摇）、寿命、致密化回收、**块碰撞（占用纹理，积分前轴分离 sweep，bakeMeta 每粒子切片扫描）**、原子追加；存活数由 GPU 从上一帧计数槽（binding 14）直读早退。**MODEL（allay）HP 语义**：maxLife 槽 = HP（emit 写 20.0），伤害队列消费在粒子自身线程内（跨线程无竞争）、alive 回血 2 HP/s 封顶 20、hp≤0 转尸体（倒计时 0→−1 对齐 vanilla 20 tick，尸体走 vanilla 死亡物理：重力 32 b/s²、垂直保留 0.98/tick、水平 0.91/tick，不吃风暴转向）、p2.w = 受击红闪计时器（0.5s） |
| `emit.comp` | 读 CPU 发射命令（按 b.z 前缀偏移二分定位），按形状/随机初始化新粒子；**MODEL：maxLife 槽写 20.0（HP）、p2.w 写 0（受击计时器）** |
| `keygen.comp` | **每帧运行**（兼作快路径的剔除 pass）：先做 **GPU 视锥剔除**（粒子球 vs CPU 从 Proj×View 提取的 6 归一化平面），再按材质**三桶**分派：additive→`orderAdd`、OPAQUE 贴图→`orderOpaque`、半透明（ALPHA 精灵 + MODEL 粒子）→各写一项 `sortData`；排序项 payload=`粒子索引<<2|类型`，`key = 255 − 对数深度带`（远带小 key → 升序即远→近）；**9-bit 类型分区键**：`key = (排序类型 << SORT_KEY_TYPE_SHIFT) | (255 − 对数深度带)`，排序类型 **MODEL=0、ALPHA 精灵=1**；初始数组经 `counter.sortItems` 追加游标致密化，随后按类型累加**精确**计数——MODEL 项加 `IDX_CNT_XLU`+`IDX_CNT_MODELOP`（=N_model，两段同用），ALPHA 项加 `IDX_CNT_ALPHA`（=N_alpha）；散射后数组按类型分区（MODEL 在 `[0,N_model)`、ALPHA 在其后），每条命令只启动自己的实例 + 未剔除半透明普查（`counter.translucentCensus`，ALPHA+MODEL） |
| `radix_hist.comp` / `radix_scan.comp` / `radix_scatter.comp` | **单趟 9-bit 分区计数排序**三阶段（直方图 → RADIX_BINS=512 线程排他前缀和 → 散列），以两类精确计数之和为守；bin 空间 = 类型位×256 深度带，散列天然把 MODEL 写入 `[0,N_model)`、ALPHA 写入其后（各分区内远→近）。同带内乱序只影响同类重叠项的混合次序（带内深度差 <~2%，视觉无碍） |
| `hit.comp` | **每帧视线命中查询**（玩家近战目标选取）：单线程/粒子遍历本帧新写池，对可命中 allay（MODEL 且 hp>0，尸体排除）做原版比例 AABB（0.35×0.6×渲染缩放 + 0.1 原版拾取膨胀，与 model.vsh 同一尺寸公式）slab 测试，`atomicMin` 归约最近命中；key = 距离量化(1/256 块, 10 位)<<22 \| 池索引(22 位)，0xFFFFFFFF=miss（reset 清）；capture 把胜者 HP 补进第二字供 CPU 选音效。无半透明粒子帧整段跳过（零成本）；遮挡由 CPU `level.clip` 对真实世界补测（原版语义、不依赖烘焙体积） |
| `additive.vsh` | `gl_InstanceID` 经 orderAdd 排列（keygen 视锥剔除后的可见加法粒子集）从粒子 SSBO 取数、相机朝向 billboard、尺寸/颜色/透明度关键帧、每发射器 glow |
| `additive.fsh` | 软圆衰减 + 距离淡出 + 叠加输出 |
| `textured.vsh` | 纹理牌面双模式（uMode）：0=ALPHA 混合——走**排序数组的 ALPHA 分区**取粒子（起点 = cmd[IDX_CNT_MODELOP] 即精确 N_model，从 indirect SSBO 直读；命令实例数=N_alpha，vsh 类型检查仅剩防损坏哨兵）、按 seed 选 sprite 帧、vanilla 自旋（roll0+ωt+½αt²）；1=OPAQUE cutout——走 orderOpaque 稠密排列 |
| `textured.fsh` | 双模式：0=采样 sprite 图集、贴花 alpha 混合、远淡出；1=硬 cutout（0.5 丢弃）+ 不透明输出（配合写深度，免排序） |
| `model.vsh` | **MODEL（Allay）顶点器**：一次 `glMultiDrawElementsIndirect` 渲染两段——**两段的实例解析完全一致**：都遍历排序数组的 MODEL 分区 `[0, N_model)`（sort-type 0，instanceCount=N_model 精确），差异仅在 element 索引区间（cmd2 只引用不透明部件顶点、cmd3 只引用斗篷+翅膀顶点），因此**段 id 可从 partId 自身推导**（`pid>=4` 即半透明，flat varying 传给 fsh），无 per-draw uniform/attribute；先取 partId → 过滤非 MODEL 实例 → **实例级远距早退** → 程序化推导动画输入（速度→limbSwingAmount/朝向/头部俯仰，seed 相位）→ **GLSL 移植 `AllayModel.setupAnim`**（FLY/DANCE/SPIN/HOLD 四姿势）→ **按 partId 只构建所需的那一个部件矩阵**（6→1）→ 原版变换（`Ry(π−yaw)·S(−1,−1,1)·T(0,−1.501,0)`）→ 经 EBO 索引拉取顶点输出；高度=2×size |
| `model.fsh` | 双段（flat varying `vSeg` 承接模式属性）：**不透明段**全亮 cutout（贴图 × tint，alpha×远淡出 <0.5 丢弃，不混合、写深度，自遮挡正确）；**半透明段**（斗篷 alpha=160 + 翅膀边缘）alpha 混合、测深度**且写深度**、alpha<0.02 丢弃 |

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
`origin(占位)` / `shape,speed,radius` / `gravity,drag` / `accel,windStrength` / `windDir,rotation` / `life,sizeStart,sizeEnd` / `sizeEase,coneTanHalf,colorCount,glow` / `material(0 ADD/1 ALPHA/2 MODEL/3 OPAQUE),collideMode,flutter,spin` / `8×RGBA 颜色关键帧` / `bakeIndex,spriteCount,0,0` / `animation(0 FLY..3 HOLD),0,0,0` / 保留

### 发射命令（32 B / 条，环 ×3）：`origin.xyz + count` / `emitterId + seed`
### 间接命令（5×20 B 统一步长，总 100 B）：cmd0/1/4 为 arrays 命令（读前 16 B：`count=6, instanceCount(GPU原子累计), first=0, baseInstance=0`，cmd0=additive、cmd1=OPAQUE 贴图、cmd4=ALPHA 混合，第 5 uint 为填充）；cmd2/3 为 **element 命令**（读满 20 B：`indexCount, instanceCount, firstIndex=段起始索引, baseVertex=0, baseInstance=0`；两段覆盖同一 MODEL 分区、instanceCount 均为精确 N_model——仅 element 区间不同，故一次 multi-draw 渲染两段）。着色器 SSBO 视图为扁平 `uint cmd[25]`（命令 i 字段 j = `cmd[5i+j]`；instanceCount 索引以命名常量 `IDX_CNT_ADD/SPRITE/MODELOP/XLU/ALPHA` = 1/6/11/16/21 引用）。**计数全部精确**：keygen 对 MODEL 项累加 `IDX_CNT_XLU` 与 `IDX_CNT_MODELOP`、对 ALPHA 项累加 `IDX_CNT_ALPHA`——类型分区排序保证各命令 instanceCount 恰等于自身实例数，无空转顶点；textured.vsh 另从 `cmd[IDX_CNT_MODELOP]` 读 ALPHA 分区起点
### 计数器（16 B / 槽，环 ×4）：`writeSlot, spare, translucentCensus, sortItems`（spare 由 capture 记录上一帧**未剔除**半透明普查；translucentCensus 由 keygen 在剔除测试前累加，ALPHA+MODEL；sortItems 为本帧初始排序数组的追加游标，仅 reset/keygen 声明并使用，其余 compute 的结构体声明保持前三成员不变——成员偏移不受追加影响）
### 排序数据（双缓冲，按半透明项数紧凑使用）：`sortData` 8 B/项 = `(9-bit 键 = 排序类型<<8 | 反转深度带, 粒子索引<<2|排序类型)`（排序类型 **0=MODEL 半透明段、1=ALPHA 牌面**；散射后按类型连续分区）；`orderAdd` 4 B/粒子 = additive 排列；`orderOpaque` 4 B/粒子 = OPAQUE 贴图排列（MODEL 无独立排列——两段共用 MODEL 分区）；直方图/偏移 各 512×uint；模型几何 SSBO 静态（~136 顶点 × 24 B + EBO，binding 12）
### 碰撞纹理（GL_TEXTURE_3D R8）：`48 × 32 × (48×K)`，K=8 个 3D 切片堆叠；bakeMeta（每槽 origin.xyz + presence）

---

## 4. 每帧渲染管线（`AFTER_LEVEL`）

### 快速路径（无 ALPHA 粒子存活）

```
1. 清空/合并客户端请求（pending 队列：burst / stream / clear）
2. 轮询帧末 fence：就绪才回读计数快照 {aliveKnown, translucentKnown}（未就绪用旧快照，
   CPU 侧发射增量维持派发上界；`glGetBufferSubData` 只在 fence 就绪后调用，杜绝流水线停等）
3. 按节流 scale 构建发射命令（bursts + streams），按剩余容量裁剪
4. upload 发射命令到环槽；若发射器头脏则整块增量上传
5. compute：reset(1线程) → update(aliveRead 线程) → emit(totalSpawn 线程)
   → keygen(视锥剔除 + orderAdd 排列) → memoryBarrier
6. draw（全部深度写入者先行）：OPAQUE 贴图 cutout（cmd1，写深度）→ 模型 multi-draw
   （cmd2 不透明段 + cmd3 半透明段；无半透明粒子时 cmd3 实例数为 0 被 GPU 跳过）
   → additive 程序经 orderAdd 排列（cmd0，**末位**）→ 叠加混合 + 深度测试（不写深度）
7. swap ping-pong（**仅成功帧**）+ GPU 计时回读 → 节流更新；SSBO/纹理/program/VAO/混合状态的恢复统一放 finally，帧中途异常同样保证离场状态干净（见已修复 #32）
```

### 排序路径（存在半透明粒子（ALPHA/MODEL）时，由半透明闩锁判定：发射置位、新鲜普查为 0 才清位）

```
0. 帧首轮询 fence → 新鲜快照 {aliveKnown, 未剔除半透明普查}（未就绪保持旧快照）
1..5. 同快速路径；update 额外做 flutter 飘摇 + 块碰撞（update/emit 恒不累加
   间接实例数——keygen 独占计数）
6. keygen=partition(sortUpper 上界)：视锥剔除后三桶——additive→orderAdd[IDX_CNT_ADD]、
   OPAQUE→orderOpaque[IDX_CNT_SPRITE]、半透明各写 sortData 项
   ((类型<<8)|(255−对数深度带), g<<2|排序类型)（排序类型 MODEL=0 / ALPHA=1），
   写入位置来自 counter.sortItems 追加游标；计数按类精确——MODEL 项加
   IDX_CNT_XLU+IDX_CNT_MODELOP（=N_model），ALPHA 项加 IDX_CNT_ALPHA（=N_alpha）；
   剔除测试前给 counter.translucentCensus 累加未剔除半透明（ALPHA+MODEL）普查
7. 若 translucentUpper = min(cap, 普查+增量+余量) > 0：**单趟 9-bit 类型分区计数排序**
   （hist→scan→scatter 以两类精确计数之和为守；bin = 类型位×深度带 → 散射把数组
   分区为 MODEL [0,N_model) 与 ALPHA [N_model,N_total)，分区内 key 已反转、升序即远→近。
   两个半透明绘制**按类型分批**执行——先鬼影段后精灵段——跨类型合成由绘制序列唯一
   决定，与带内到达顺序无关，完全确定）
8. draw（全部深度写入者先行）：OPAQUE 贴图（cmd1，cutout+写深度）→ 模型 multi-draw
   （一次调用渲染两段；两段实例解析一致——都遍历 MODEL 分区 [0,N_model)，仅 EBO
   区间不同，段 id 由 partId 推导：cmd2 不透明段写深度 → cmd3 鬼影段**写深度的混合**）
   → ALPHA 牌面（cmd4，混合不写深度，走 ALPHA 分区 [N_model,N_total)，起点直读
   cmd[IDX_CNT_MODELOP]）→ additive（cmd0，**末位**：
   顺序无关的加法混合放最后零成本，且鬼影已写的深度会挡住其后的辉光——磨砂玻璃语义）
9. capture(1线程) 把普查写入本帧 counter 槽 spare（供下帧非阻塞读取）；fence 入队 → swap ping-pong（仅成功帧）；状态恢复统一走 finally
```

- 致密前缀不变量：写缓冲 `[0, liveCount)` 恒为全部存活粒子；死粒子在 update 中被跳过即移除
- 存活计数走**原子计数器环**（×4）：本帧写 `simFrame%4`；CPU 不再逐帧阻塞回读——帧末 `glFenceSync`，下帧零超时轮询，就绪才读该槽 `{writeSlot, spare}`（消除"读-再-改同一缓冲"竞态的同时消除 CPU-GPU 流水线停等）。GPU 侧 update.comp 经 binding 14 直读上一帧槽的 writeSlot 做精确早退，CPU 只需"快照 + 发射增量"上界派发（死亡只减不增，上界恒安全）
- 发射线程用 `g >= uTotalSpawn` 提前返回；`atomicAdd` 分配槽位，`slot >= uCapacity` 丢弃（CPU 侧已预留 2048 安全余量，理论上不触发）
- **双模回退**：`sorted = translucentLatched`（发射 ALPHA/MODEL 置位，仅新鲜快照普查为 0 清位），普查为**未剔除**半透明计数（相机转开再转回不会欠派发）；无半透明时跳过 radix/两个半透明绘制——keygen 本身两条路径都跑（快路径的剔除 pass）
- **类型分区半透明排序**：ALPHA 牌面与 MODEL 半透明段作为同一排序项集（每粒子一项，类型进键高位），一趟 radix 同时完成分区内深度排序与跨类型分区；每条命令的 instanceCount 精确等于自身项数（N_model / N_alpha），**零空转顶点**（旧方案三命令同写合并总数、vsh 过滤异类型实例，代价 = 异类型实例数 × 6 顶点线程）。vsh 的类型检查保留为防损坏哨兵。**深度语义**：排序项之间真混合（远→近逐个叠加）；MODEL 半透明段**写深度**——项内部部件顺序由深度几何解决（斗篷/翅膀双绕序 + 写深度使壳体任意视角单次混合、内侧可见），且对后续绘制的半透明牌面表现为"玻璃式遮挡"（后面的牌面被挡住而非透过变暗）。花瓣在斗篷前→正确混合、在后→被遮挡；两斗篷之间因排序仍是真混合
- 排序 key 为 **9-bit：类型位×对数深度带**（低 8 位在 [1, far] 上量化，far = ceil(fade+24) 取 2 的幂，随 `fadeDistance` 配置自适应；~2%/带相对分辨率，近细远粗与感知一致）；计数排序只排半透明项，real work ∝ 项数；同带内乱序只影响**同类**重叠项的混合次序（跨类型顺序由按类型分批的绘制序列固定）→ 无需 LSD 多趟与稳定性
- **常量单一来源**：全部 SSBO 绑定号、间接布局索引（`IDX_CNT_*`）、结构尺寸由 `ParticleBuffers` 的 Java 常量经 `ParticlePrograms.PRELUDE` 生成 `#define` 前注入每个着色器源（`#version` 之后）——用宏而非 `const` 是为了在 `layout(binding=N)` 里保持纯文本替换的驱动兼容性；改布局只需动 Java 一处，F3+T 生效
- **模型双段合并 multi-draw**：cmd2/cmd3 物理相邻、同程序同状态、**实例解析一致**（都遍历排序数组的 MODEL 分区 [0,N_model)，instanceCount 精确），差异只剩 EBO 索引区间——段 id 因此可从 partId 推导（`pid>=4`=半透明），flat varying 传给 fsh；一次 `glMultiDrawElementsIndirect` 渲染两段，零实例命令 GPU 自动跳过，快慢路径同一调用。**踩坑教训**：最初用 baseInstance 寻址的 divisor=1 `{0,1}` 模式属性选段——实例属性取址为 `baseInstance + gl_InstanceID/divisor`，多粒子时后面的实例读到错误/越界项，表现为"两个悦灵靠近只渲染一个"；该技巧只对单实例绘制成立，已废弃并删除该属性（binding 13 的 orderModel 排列随之退役）
- **GPU 视锥剔除**：keygen 内对每粒子做球测试（保守半径 = max(sizeStart,sizeEnd)×尺寸倍数），平面由 CPU 从 Proj×View 按 Gribb–Hartmann 提取并归一化上传；屏幕外粒子不进排列、不进顶点着色（典型省 50–75% 实例）

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
| 混合/材质 | ADDITIVE（叠加，无序）+ OPAQUE（纹理 cutout + 写深度，无序，纯 0/255 贴图如樱花瓣）+ ALPHA（纹理混合，进排序）+ MODEL | 每发射器一档 `material`；OPAQUE 把"看似需要排序"的贴图粒子零成本移出排序路径；ALPHA/MODEL 半透明进合并排序 |
| 深度排序 | **合并半透明单趟 8-bit 对数深度带计数排序**（key 反转 = 远带小 key，升序即远→近；ALPHA 牌面 + MODEL 半透明段同场排序，类型在 payload；两个半透明绘制共用排序数组按类型过滤） | real work ∝ 半透明项数，与 additive/OPAQUE 池大小解耦；单趟无 LSD 稳定性要求（同带内乱序无害）；对数量化使 ~2% 相对深度分辨率恒定；曾用 24-bit key × 3 趟 LSD，既有方向反又有原子散列不稳定问题 |
| GPU 剔除 | **keygen 内置视锥剔除**（球 vs 6 归一化平面，Gribb–Hartmann 提取自 Proj×View；快路径无独立 cull pass——keygen 本身就是） | 借鉴 voxy `frustum.glsl`；屏幕外粒子不进排列/顶点着色，典型省 50–75% 绘制实例 |
| 可见距离 | `fadeDistance` 配置（16..256，默认 96；+24 渐变后全隐） | 排序远平面 = ceil(fade+24) 取 2 的幂自适应，配置再高也不会出现“可见但排序范围不足”；不做原版雾匹配（低视距+高 fade 时粒子与雾化地形会有边界，已知取舍） |
| MODEL 材质 | **Allay 实例化**：几何静态烘焙 SSBO + **静态 EBO 索引绘制**（`glDrawElementsIndirect`，136 顶/只）+ `setupAnim` GLSL 直译（FLY/DANCE/SPIN/HOLD，动画 id 在头 vec4 17，`/cmip anim` 热切换走 pending 队列 → 头增量重上传，存活粒子下帧生效）+ **按 partId 只建所需部件矩阵** + **实例级远距早退**；全亮（vanilla `getBlockLightLevel=15`）；**双子绘制**：不透明段（头/皮肤/双臂）cutout+写深度（自遮挡/互挡正确）+ 背面剔除（翅膀双绕序索引保双面），半透明段（斗篷 alpha=160 + 翅膀边缘）alpha 混合、测深不写深、**在所有粒子之后绘制**；keygen 镜像累加 cmd2.y/cmd3.y 共享同一排列；朝向=水平速度方向（低速回退 seed 朝向+慢漂移），身高=2×size（默认 0.60 格，见 MODEL 尺寸对齐行）；无硬上限靠 autoThrottle | 3D 模型必须写深度否则部件穿插错序（billboard 的"不写深度"约定不适用）——半透明段不写深度靠不透明段的深度兜底；全亮是 vanilla 事实而非风格选择（原版对斗篷用 cutout 渲染成不透明，半透明是有意忠于纹理数据的选择）；持物渲染留作后续（SSBO arena 纯增量） |
| MODEL 血量/近战 | **maxLife 槽复用为 HP**（emit 写 20.0 = `Allay.createAttributes` MAX_HEALTH；age 不动、永生、无老死闸门——对齐原版实体）；**死亡 = hp 翻负倒计时**（击杀帧钳 0 → 每帧 −dt，≤−1.0 压实回收 = vanilla 20 tick；死亡翻滚计时 = −hp，age 继续驱动空闲动画——动画零跳变，对齐"尸体继续播 idle"）；尸体物理按 `LivingEntity.travel` 递推逐轴实现（重力 32 b/s²、垂直 0.98/tick、水平 0.91/tick），击杀冲量随尸体衰减；**命中 = GPU 每帧查询**（hit.comp，结果 8B 随 fence 快照回读，点击零停等，≤1 帧相机陈旧——对比原版命中链路自身 50ms+ 的位置滞后不可感）+ **CPU `level.clip` 遮挡**；**伤害 = `Player.attack` 逐项镜像**（冷却缩放 0.2+f²·0.8 吃基础与附魔加成、武器加成不吃缩放、暴击 ×1.5 走 CriticalHitEvent、冲刺击退+攻击者减速×0.6、冷却重置在伤害计算后——对齐 NeoForge 时机）；附魔走客户端等价实现（`EnchantmentValueEffect.process` 无条件效果迭代——锐锋/击退精确，条件类亡灵/节肢对 allay 本就无效；`EnchantmentHelper.modifyDamage` 需要 ServerLevel 故不可直接调用）；**判定框 = 0.35×0.6×渲染缩放**（+0.1 原版膨胀，不随朝向旋转、feet 对齐）；击退经伤害队列进 update.comp 原位应用（`vel/2 − dir·strength`，boids 转向 ~0.5s 自然吸收）；伤害队列 = 64 槽×16B 单缓冲（无环，流序保证安全，帧首消费后下帧补零清空）；受击反馈 = p2.w 红闪 0.5s + `ALLAY_HURT`/`ALLAY_DEATH`（按快照 hp−伤害判生死）+ 攻击方 `PLAYER_ATTACK_*` 本地音效；点击拦截 = `InputEvent.InteractionKeyMappingTriggered`（v1 只处理按下，按住连打不连击粒子；未命中放行原版 miss swing）；**无任何网络包** | 粒子 p3 四字段全占用且动画 id 在 per-emitter 头——"逐粒子死亡信号"只能编码进现有槽位；age 是动画时钟（受伤倒带不可接受）故 HP 只能落在 maxLife；风暴停止/清池会使池索引移位，命中快照在这些路径上由 CPU 主动作废（stopStorm/kill 排队后立即置 miss，防伤害落到移位后的错误粒子）；横扫不做（扫描框点击时才确定，会重新引入 4b 淘汰的同步停等——钉为已知偏差）；riptide 旋转伤害走 `LivingEntityAccessor`（字段 `autoSpinAttackDmg` 是 LivingEntity 的 protected 成员、无公共访问器——首版误标 `@Mixin(Player)`，字段不在 Player 上导致 InvalidAccessorException 启动崩溃，已修） |
| MODEL 尺寸对齐 | model.vsh 的 scale divisor 0.625 → **`MODEL_ABOVE_FEET`（由 AllayModelGeometry 烘焙的静止姿势 Y 范围派生 ≈0.594）**，使"脚线以上高度 = 2×size"精确成立；预设 size 0.33 → 0.30（Storm 同步）→ 默认渲染 0.60 格 = 原版悦灵逐格等大；判定框 = 0.6×scale ≈ 视觉高度，与原版"判定框=模型顶"关系一致 | 0.625 来自文档口径"vanilla body ~10 units"的错误估算；实测 `AllayModel.createBodyLayer`：头顶尖 y=14.51、feet 对应 y=24.016（1.501×16）、翅膀/斗篷下摆垂到脚线下 0.094 格（几何与变换同原版一致，无需处理）；`AllayRenderer` 无 scale 覆写 |
| allay_death 预设删除 | spec、`byName`、`names()`、`/cmip anim` 的 death 分支全部移除；死亡动画代码路径保留，改由尸体态（hp<0）驱动；击杀红闪由 p2.w 承担（替代原三关键帧近似），尸体下坠参数由 vanilla 递推替代（原 gravity −8/drag 0.8 为目测值，重力只有原版 1/4） | 预设本只为测试死亡动画而存在；删除后"头 anim==3 走旧语义"的区分器不复存在，MODEL 粒子统一 HP 语义，消除未来 MODEL 预设静默踩 maxLife 槽的陷阱（契约：MODEL 预设的 size/color 关键帧不得依赖 maxLife 槽——现全为常数曲线） |
| 双模回退 | lagged `prevAlpha`（capture 写入 counter.spare，下帧非阻塞滞回读）判定 `sortedDraw` | alpha 死光自动回恒等快路径，消除“永久排序路径”开销；无新增同步点 |
| 块碰撞 | 3D 占用纹理（48×32×48/K 切片） + **积分前** X/Y/Z 轴分离 sweep；**切片由 update.comp 每粒子按包含关系扫描 bakeMeta 选择**（头不携带位置状态，同 spec 多 site 不互抢） | 真 SDF 距离场成本高；占用纹理实现快、够用，后需可升级。sweep 必须从移动前位置起测：曾放在 `pos += vel*dt` 之后，导致无碰撞时速度×2、受阻时嵌入方块 |
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
15. **审查修复：alpha 排序方向反了** → 旧 24-bit key 升序 = 近→远绘制，远处花瓣错误盖在近处之上；key 反转（`255 − 带号`）后单趟即远→近 back-to-front
16. **审查修复：碰撞双重积分** → `pos += vel*dt` 后又从已移动位置起 sweep，无碰撞路径速度×2、受阻路径嵌入方块；重构为积分前 sweep，sweep 拥有全部位移（两条路径每帧位移恒为 `vel*dt`）
17. **审查修复：LSD 原子散列非稳定** → 单趟 8-bit 带计数排序取代 3 趟 LSD（等带乱序无害），同时少 2 个 compute 派发
18. **审查修复：bake 按 spec 键控** → 同预设两个远距发射点逐帧互抢锚、每帧 73k 次 getBlockState 重建；改为按锚点格子键控（同址共享、异地分片）+ 每帧最多重建 1 片 + presence 门控（重建完成前不启用碰撞，杜绝旧占用体积）
19. **审查修复：`enabled` 配置是死开关** → `renderFrame` 从未读取；现在关闭即清池停止渲染。另加 GL 4.3 版本检查（老 GPU 明确禁用而非静默失败）
20. **审查修复：节流器测的是 CPU 提交耗时** → dispatch 异步、GPU 真实开销不可见；改用 `GL_TIME_ELAPSED` 查询环（4 深、读 3 帧前样本、不阻塞），首查完成前回退 CPU 耗时
21. **审查修复：emit 每线程线性扫命令表**（≤256 次 SSBO 读）→ CPU 把排他前缀偏移写进命令 b.z，GPU 二分定位（O(log N)）；顺带修 CONE 轴平行守卫（先 normalize 再判长度是死代码）与 cherry 自旋加速度换算（deg/tick² 应 ×400 而非 ×20）
22. **回归：`INDIRECT_COMMANDS` 2→3 后 `tmp4`（32 B）写 3×16=48 B 初始命令 → `BufferOverflowException`、GPU init 失败**（踩坑 #1 同款：容量常量与命令数解耦）。`tmp4` 扩至 64 B；教训：`tmp4` 容量必须 ≥ `INDIRECT_COMMANDS × 16`
23. **症状"所有粒子不可见"（live 正常、`gpu≈0.0x ms`）：手写 Gribb–Hartmann 平面提取取错了 JOML 矩阵元素**——`mAB()` 是**列 A 行 B**约定，取"第 3 行"应读 `m03/m13/m23/m33`，误取了第 3 列 `m30/m31/m32/m33`（平移列）→ 垃圾平面把所有粒子判为屏幕外，keygen 全部剔除（实例数 0）。改用 JOML 内置 `Matrix4f.frustumPlane(i, Vector4f)`（同算法、约定免疫）+ 手动归一化。**诊断特征**：存活计数正常但 GPU 耗时趋近于零 = 剔除/绘制段产出为零
24. **症状"仅 MODEL 不可见（首帧闪现后消失）"：模型几何 SSBO（binding 12）只在 init 上传时绑定过一次，而 `unbindShaders()` 每帧把 binding 0..13 全清**——其余绑定（粒子池/发射器头/orderAdd/orderModel/sort）都在各自 pass 每帧重绑，唯独几何漏了 → 第 2 帧起顶点拉取落空、draw 静默失败。修复：`drawModels` 每帧 `bindModelGeo()`。**纪律**：新增任何常驻 SSBO 绑定，必须在消费它的 pass 里每帧重绑（`unbindShaders` 全清是卫生约定）
25. **审查修复：计数回读是隐式 CPU-GPU 同步点** → `glGetBufferSubData` 每帧在帧首强等 GPU 队列（"滞后一帧"只消除了与本帧 reset 的竞态，并非非阻塞）；bench 过载时恰在最需要节流数据的时刻卡住渲染线程。改为**GPU 自持计数 + fence 懒快照**：update.comp 经 binding 14 直读上一帧计数槽的 writeSlot 精确早退（CPU 只派发上界，超派发线程零成本退出）；CPU 快照走 `glFenceSync` 零超时轮询，未就绪时用「旧快照 + CPU 侧发射增量」推上界（死亡只减不增，上界恒安全）；sorted 双模判定改闩锁（alpha 发射置位、新鲜普查为 0 才清位），fence 滞后不会出现 alpha 粒子漏画帧
26. **审查修复：发射器头 bakeIndex 与位置身份冲突** → 头按 spec 去重但 bakeIndex 是世界位置相关的量：同 spec 双远距 site 同时流式发射时，两 site 每帧轮流改写同一头的 bakeIndex（碰撞隔帧失效，/cmip 单点演示不可见、接入玩法必炸）。碰撞切片改为**每粒子 GPU 端选择**：update.comp 扫 bakeMeta 8 切片，取「presence=1 且 48³ 体积包含粒子坐标」的第一片做 sweep；头不再携带任何世界位置状态（16.x 废弃为保留位，`packedWithBake` 与 `bakedSliceByEmitter` 删除），附带消除 `packedWithAnimation`/`packedWithBake` 互不清空的隐患；语义改进：漂出自己体积、落入其他 site 体积的粒子会在该处继续碰撞
27. **审查修复：uniform 位置每帧重查** → ~30 次/帧 `glGetUniformLocation` 驱动调用（每次 uniform 设置都走 `loc()`）；改为两级 Map 缓存（program id → name → location，`computeIfAbsent` 惰性填充），程序重建（F3+T）与引擎关闭时清空——链接后位置恒定，缓存一次终身有效
28. **审查修复：碰撞烘焙 73k 次 getBlockState 跑在渲染线程** → 每片重建 ~毫秒级尖峰，正是帧预算的竞争者。移到后台守护线程（同时至多 1 片在途）：**渲染线程仅解析所覆盖区块的 `LevelChunk` 引用**（客户端区块映射不可被非渲染线程遍历，捕获引用后工作线程只做纯数组读，卸载的区块靠引用存活、读到旧数据无害——体秒级刷新本就是快照语义）；成品体素回渲染线程上传 GL；跨维/换世界/切片被逐出的过期结果直接丢弃（切片保持 dirty 自然重试），工作线程异常同样返回空由下轮重试
29. **斗篷半透明 + MODEL 性能重做** → 纹理事实：斗篷 cube（texOffs 0,16）alpha=160、翅膀 5 个边缘像素 220/243，其余全为 0/255；原版 `AllayModel` 用 `entityCutoutNoCull` 把斗篷渲染成**不透明**（cutout 0.1 阈值直通），半透明是有意忠于纹理的选择。实现：几何**索引化**（204 顶 → 136 顶 + 静态 EBO，`glDrawElementsIndirect`，顶点着色 −33%）并按材质段拆连续索引区间——不透明段（头/皮肤/双臂）cutout+写深度+背面剔除，半透明段（斗篷+双翅，partId≥4）alpha 混合、测深不写深、在所有粒子之后绘制（keygen 镜像累加 cmd2.y/cmd3.y，间接命令 3→4 条，`tmp4` 扩至 96B 防 #22 复发）；vsh 先取 partId 做段过滤（段外顶点零成本）、**按 partId 只建所需部件矩阵**（6→1，矩阵乘 ~2.5× 削减）、实例级远距早退（fade+24 外整三角形裁出）；翅膀双绕序**仅索引加倍**（同 4 顶点补反向三角）在开 cull 下保持双面——绕序若反回退 `glFrontFace` 换向
30. **症状"翅膀和斗篷完全不可见 + `GL_INVALID_OPERATION: Bound draw indirect buffer is not large enough`"：`DrawElementsIndirectCommand` 是 5 个 uint（20 B：count/instanceCount/firstIndex/baseVertex/**baseInstance**），不是 arrays 命令的 4 个 uint/16 B** → #29 按统一 16 B 步长排布，cmd3 落在偏移 48，20 B 命令需要 [48,68) 而间接缓冲只有 4×16=64 B → 驱动拒绝整个 cmd3 绘制（半透明段恰好全在 cmd3，不透明段 [32,52) 在界内照常渲染——与"只剩身体可见"症状吻合）。修复：**全部命令统一 20 B 步长**（缓冲 4×20=80 B，arrays 命令只读前 16 B、第 5 个 uint 为填充），着色器 SSBO 视图从 `uvec4 cmd[]` 改为**扁平 `uint cmd[20]`**（命令 i 字段 j = `cmd[5*i+j]`，instanceCount=字段 1 → 索引 1/6/11/16），Java 侧初始化/清零/上传/绘制偏移全部改用 `INDIRECT_STRIDE`。**教训**：arrays 与 elements 两种 indirect 命令结构不同长，混用时步长必须按 20 B 取齐；驱动对"命令越界"是整条 draw 拒绝而非截断
31. **翅膀/斗篷前后错序 + 斗篷内侧不可见 → 材质四分类 + 合并半透明排序**（用户方案） → 症状根因：半透明段不写深度，混合结果只由索引顺序决定，与几何远近无关。事实：樱花 12 帧全为纯 0/255（本就是 cutout 素材，整套 radix 几乎只为它存在）。重构：①新增 **OPAQUE 材质**（纹理 cutout<0.5 + 写深度，免排序，走 orderOpaque 排列），cherry 切换过去，排序路径只服务真半透明；②**合并排序**：ALPHA 牌面与 MODEL 半透明段作为同一排序项集（每粒子一项，payload=`索引<<2|类型`，类型不进 key 保持 256 bin 单趟），两个半透明绘制共用排序数组、vsh 首行过滤异类型实例；③**MODEL 半透明段写深度**：排序项之间真混合（远→近逐个叠加、两斗篷互相透视），项内部部件顺序由深度几何解决；斗篷+翅膀**双绕序 + 开 cull**（数学上等价于关 cull 单绕序：同平面正反两份只有面向相机的过剔除）+ 写深度 → 壳体任意视角**单次混合**且**内侧可见**；对后续绘制的半透明牌面表现为"玻璃式遮挡"（挡住而非透过去变暗——真·全互通混合需单 draw 交错发射，超出间接绘制能力，已知取舍）。间接命令 4→5 条（100 B，`tmp4` 第三次扩容→160 B），census 扩为 ALPHA+MODEL 未剔除计数，闩锁改名 translucentLatched
32. **审查修复：帧中途异常留下脏 GL 状态** → `runFrame` 的 GL 工作段（计时查询 begin→end 及之后的 capture/fence/计时/swap）原先没有 finally：中途抛异常会①把计时查询留在 active 态（下帧 `glBeginQuery` 报 INVALID_OPERATION）、②SSBO 绑定 0-15/纹理单元/program/VAO/混合状态全部泄漏进后续世界渲染与 Iris 管线、③fence/swap 语义悬空。重构：查询 begin 起至帧末全部纳入 try/finally——finally **分组隔离**地结束半截查询（环游标不动，残缺样本下帧被同槽覆盖而非污染节流 EMA）、恢复 program/VAO/SSBO 0-15/纹理单元/depthMask/blend 到成功帧的离场状态（各组独立 try/catch，次级异常不掩盖原异常也不跳过其余组）；**swap 移入 try 尾部**（中断帧不翻转双缓冲，上一完整池继续作下帧读取源）；**出生增量记账提前到任何派发入队之前**——GL 命令入队后即使 Java 异常 unwind 也照常执行，增量必须先记，否则中断帧后的「快照+增量」派发上界低估、update 压实会把超出上界的存活粒子永久丢弃

---

## 6. 命令与配置

```
/cmip spawn <preset> [count]      爆发（受节流）
/cmip stream <preset> <rate> [sec] 流式（秒数=真实秒；<=0 为无限，直到 /cmip clear）
/cmip anim <preset> <animation>   MODEL 粒子动画热切换（fly/dance/hold/death，存活粒子下帧生效）
/cmip bench <count>                不受节流压测（默认用 mana_burst）
/cmip clear                        清空粒子与流
/cmip stats                        存活/容量、streams、emission%、GPU 帧耗时 EMA、预算
/cmip budget <ms>                  覆盖节流预算
```
预设：`mana_spark / ember / ash / soul_flame / mana_burst / cherry_leaves / flood` + MODEL 系 `allay_fly / allay_dance / allay_hold`（0.2.5 起：舞蹈含完整原版爆发自旋节奏，独立 allay_spin 预设移除；`allay_death` 预设已删除——死亡动画由 HP 击杀的尸体态驱动，见第 5 节 MODEL 血量/近战行）。
`cherry_leaves` 为 **OPAQUE 材质（cutout + 写深度，免排序）+ 碰撞（触地即移除）** 演示：`/cmip stream cherry_leaves 200 20` 可在任意位置看持续飘落；花瓣纯 0/255 纹素，与原版 cutout 语义一致。
`allay_*` 为 **MODEL 材质**演示：`/cmip spawn allay_fly 50` 后 `/cmip anim allay_fly dance` 可当场切换群舞；对准任意 allay 挥剑即可造成原版规则的近战伤害（致死播放 1s 死亡动画），`/cmip allaystorm` 的群成员同样可被击杀。
配置文件 `run/config/createmanaindustry-client.toml` → `[particles]`：`enabled / maxParticles / frameBudgetMs / autoThrottle / fadeDistance`

---

## 7. 性能特征（对照验收基线）

- 显存：默认 2M = 2×128MB 粒子 SSBO + 40KB 发射器头(20×vec4) + 3×8KB 命令环 + 4×16B 计数环 + 排序数据 2×(8B/粒子) + 直方图/偏移 + 碰撞纹理(≤0.6MB)
- 每帧 CPU：发射命令（≤256 条目 × 32B，含二分前缀偏移）+ 计数器回读（1 帧旧，滞回）
- 每帧 GPU（快速路径）：reset/update/emit 3 次极小 compute + 1 次间接绘制；空载 `gpu≈0.0x ms`
- 每帧 GPU（排序路径）：reset/update/emit + keygen + 计数排序（hist/scan/scatter 各 1 次）共 ~7 次小 compute + 2 次间接绘制；alpha 粒子不多时开销仍极小
- 帧耗时计量：`GL_TIME_ELAPSED` 查询环（×4，读 3 帧前样本，`GL_QUERY_RESULT_AVAILABLE` 不阻塞），GPU 真实耗时而非 CPU 提交耗时
- 碰撞烘焙：后台单守护线程，同时至多 1 片在重建（73k 次 getBlockState 不占渲染线程；渲染线程仅解析所覆盖区块的引用并上传 73KB 成品体素），8 片满载按 LRU 轮换无尖峰
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
- MODEL（Allay）已知取舍：斗篷/翅膀半透明段无跨实例排序（不透明段深度兜底，斗篷间叠加按绘制序）；HOLD 不渲染手中物品；背面剔除开启（翅膀靠双绕序索引双面可见；若烘焙绕序与 GL 约定相反表现为模型内翻，回退手段 `glFrontFace` 换向）；不透明段死亡瞬间整体消失（cutout 无法淡出，可用 sizeOverLife 收缩过渡；半透明段有 alpha 淡出）
- 开发期验证命令 `/cmip` 归入客户端命令，随模组发布（不影响服务端稳定性）

---

## 10. 参考资料

- 架构迭代与探针结论见会话记录（Veil 空转定位、时钟错位源码级证据）
- 着色器编写参考 `.agents/skills/shader-dev`（粒子系统/乒乓缓冲/亮度预算章节）
- Veil 源码镜像：`.refs/Veil/`（`VeilShaderBlockState`、`ShaderProgramImpl`、`Timer` 语义）

---

## 11. 变更记录

### 斗篷顶/底帽面烘焙剔除（0.2.5 周期，grilling 确认后实施）

- **事实核查**：`allay_0.png`（32×32 调色板 PNG，alpha 走 tRNS）斗篷**顶面** x[5,8)×y[16,18)、
  **底面** x[2,5)×y[16,18) 全部纹素 alpha = 0；对照侧面主体 alpha 131–160（此前文档"160"
  的说法修正为区间）。两面在任何合理管线中本就不可见。
- **实施**：`AllayModelGeometry.cube()` 新增按 normalAxis 的 `skipFaces` 位掩码，斗篷烘焙时
  剔除 UP/DOWN 两面——每实例省 **8 个双绕序三角形（ghost 段约 20%）+ 8 个专属顶点**，
  L0 与光影包合并路径共享几何、同时生效。
- **动机**：正常 alphaTest（GREATER 0.1 回退 / L0 的 a<0.02 discard）下这些片元全部被丢，
  纯浪费插值与采样；宽松 alphaTest 的包（实体程序定义 off/ALWAYS 并不罕见）下它们会以
  ghost 段 depthMask(true) 写深度，形成**隐形遮挡壳**伪影。
- **契约**：依赖 `allay_0.png` 该两矩形保持全透明——替换纹理必须复核 UV 矩形
  （class javadoc 与调用点注释均已钉死）。
- **验证**：compileJava 通过（L1）；游戏内并排视觉比对待验（L2，重点：正下方视角斗篷底缘
  无可见变化、ghost 合成不变）。

### 翅膀透明面烘焙剔除（同周期，grilling 确认后实施）

- **事实核查**：零厚度翅膀（dx=0）经退化面跳过后仅剩 EAST/WEST 两张共面四边形，但二者采样
  **不同的 UV 矩形**——WEST x[16,24)×y[19,24) 含 10 个不透明纹素（全部翼绘），
  EAST x[24,32)×y[19,24) 为 **0/40 不透明（整块全透明）**。
- **实施**：两只翅膀的 `skipFaces` 传 `(1 << AXIS_EAST)`，各保留一张双绕序 WEST 四边形
  服务双侧（`model.fsh` 以 `gl_FrontFacing` 翻转着色法线）；每实例再省 **8 tris + 8 verts**。
- **行为说明**：doubleSided 下任意一侧可见的纹素集合本就是两矩形的并集 = 仅 WEST 有贡献，
  合并后渲染结果逐像素等价，且消除了共面片段的深度竞争；与原版可见输出一致。
- **契约**：与斗篷条目相同——替换 `allay_0.png` 需复核该矩形（class javadoc 已合并覆盖
  斗篷与翅膀的全部被剔矩形）。
- **验证**：compileJava 通过（L1）；L2 同上（正/背侧翅膀观感不变、ghost 合成不变）。
- **累计效果**：ghost 段三角形 40 → 24（约 −40%），与近先序 early-Z 叠加后重叠区片元
  压力进一步下降。

### MODEL 粒子血量与玩家近战（本轮，grilling 逐题共识后实施）

- **范围**：全部 MODEL 材质粒子有血量、可被玩家近战命中；**永生**（无老死闸门，对齐原版实体），
  池满后新粒子由容量守卫静默丢弃（已确认接受）。
- **存储**：maxLife 槽 ⇒ HP（emit 写 20.0 = `Allay.createAttributes` MAX_HEALTH）；age 只当动画时钟；
  p2.w（intensity，MODEL 渲染路径不用）⇒ 受击红闪计时器（0.5s = vanilla hurtTime）。
  **契约**：MODEL 预设的 size/color 关键帧不得依赖 maxLife 槽。
- **死亡编码**：hp≤0 转尸体——击杀帧钳 0、每帧 −dt、≤−1.0（vanilla 20 tick）压实回收；
  翻滚计时 = −hp（`cmiDeathRoll` 参数由 age 改为 time-since-death），age 不重置 → 动画连贯。
  尸体物理按 `LivingEntity.travel` 原版递推逐轴实现（重力 32 b/s²、垂直 0.98/tick、水平 0.91/tick）。
- **命中管线**：hit.comp 每帧常驻（单 workgroup×64 线程/粒子，slab×AABB，`atomicMin` 最近命中，
  key = 距离 1/256 量化(10b)<<22 | 索引(22b)，miss=0xFFFFFFFF）；capture 把胜者 HP 补进第二字；
  结果随 fence 快照回读——**点击零停等**，≤1 帧相机陈旧（对比原版命中链路 50ms+ 位置滞后不可感）；
  遮挡由 CPU `level.clip` 对真实世界补测（4a-B 烘焙纹理遮挡因覆盖空洞/秒级陈旧/形状粒度被否）。
- **伤害管线**：`InputEvent.InteractionKeyMappingTriggered` 拦截 → 合成攻击镜像 `Player.attack`
  （冷却缩放、附魔加成同缩放、武器加成不缩放、暴击 ×1.5 走 CriticalHitEvent、冲刺击退、
  攻击者减速 ×0.6、冷却重置在计算后）；附魔 = 客户端无条件 `EnchantmentValueEffect` 迭代
  （`EnchantmentHelper.modifyDamage` 需要 ServerLevel）；击退/伤害经 64×16B 单缓冲队列在
  update.comp 粒子自身线程内原位应用；代理 Allay 实体（不 join world）供附魔/暴击的类型查询；
  riptide 伤害经新增 `LivingEntityAccessor`（`autoSpinAttackDmg` 声明在 LivingEntity 上——accessor 必须指向声明类而非使用类）。**无任何网络包**；横扫钉为已知偏差。
- **尺寸对齐**：scale divisor 0.625 → `MODEL_ABOVE_FEET`（烘焙静止姿势实测 ≈0.594，
  旧值高估 5.5%）；预设 size 0.33 → 0.30 → 默认渲染与原版悦灵逐格等大。
- **预设清理**：`allay_death` 删除（spec/命令映射/anim 分支）；死亡动画路径保留、改由尸体态驱动。
- **验证**：compileJava 通过（L1）；L2 待游戏内验证：挥剑命中/音效/红闪/击退、致死 1s 尸体动画、
  风暴成员可击杀、隔墙不可命中、开/关光影包双路径的尸体与红闪渲染。
- **修复（游戏内崩溃 #33）**：`enqueueDamage` 绝对 `putFloat` 抛 IndexOutOfBoundsException——
  Java NIO 绝对 put 按 **limit**（而非 capacity）越界检查，而伤害队列上传路径缩小 limit 后未恢复，
  且 position 停在 16 导致 `glBufferSubData` 从 position 起读、头部计数实际未上传。修复：上传统一
  `clear → 绝对写头部 → limit(used)·position(0) → 上传 → limit(capacity)`，`enqueueDamage` 防御性
  先恢复 limit=capacity。教训：LWJGL `glBufferSubData` 语义 = 从 position 读 remaining 字节；
  绝对写缓冲的 limit 是隐式边界，缩小后必须还原。
- **修复（症状三联 #34）**："打不到 Allay、打不到原版实体、挖不了方块"——两个新增 SSBO 绑定违反踩坑
  #24 纪律（`unbindShaders()` 每帧全清 0..17，每个 pass 声明的绑定必须在派发前重绑）：① reset 派发
  漏 `bindHit()` → `reset.comp` 的 MISS 写进未绑定 SSBO 被 NVIDIA 静默丢弃 → 首次真实命中后命中 key
  永久锁存 → 之后每次点击都被 `handlePlayerAttack` 以陈旧快照消费、事件被取消（实体攻击/挖掘全部失灵）；
  ② update 派发漏 `bindDamage()` → update.comp 读未绑定 SSBO 返回 0 → 伤害队列恒空 → 打不到 allay。
  修复：两处补绑 + 防御性"消费即作废"（syntheticAttack 成功后清 hitKeySnapshot，防同一快照在
  fence 滞后窗口内二次触发）。教训：新增任何 SSBO 绑定，grep 该 binding 常量的全部声明着色器，
  逐一核对派发点的 bind 调用。

### 审查修复三则（本轮）

- **model.fsh 手动深度死分支删除**：`uManualDepth/uMainDepth` 全仓库无赋值/绑定方（L0 与 pack 合并
  路径都走硬件深度），且该分支本身带潜伏 bug——`texture(uMainDepth, gl_FragCoord.xy)` 把窗口像素
  坐标当归一化 UV 采样（无目标尺寸 uniform、无除法），启用即产生错误遮挡。整段删除；将来若真要
  服务无深度附件目标，必须补 uTargetSize 并按 `gl_FragCoord.xy / uTargetSize` 重写。
- **drawPass 无条件绑 BIND_INDIRECT**：textured.vsh 静态声明 indirect SSBO，而 compute 尾部
  `unbindShaders()` 已清 0..17；此前仅 mode==2（ALPHA 牌面）补绑，mode==1（OPAQUE cutout）未绑——
  uniform 分支虽不执行该 load，但属 #34 同类"声明即须绑定"隐患，现两模式均绑定（additive.vsh
  不声明该块，多绑无害）。
- **L0 四处 sizeEase 防御钳制**：`pow(life, 0)` 在 life=0 为 GLSL 未定义行为；pack 合并路径已有
  `max(sizeEase, 0.001)`，additive.vsh / textured.vsh / model.vsh / hit.comp 四处对齐。
  compileJava 通过（L1）；着色器改动待 F3+T / runClient L2 验证。
