# Allay 粒子渲染对齐 Minecraft 原版 — 分析报告与实施计划

> 范围：MODEL 材质粒子（程序化 Allay 实例渲染管线）
> 方法：以 .refs/neoforge-21.1.227（原版反编译源码）、.refs/Iris、.refs/photon、.refs/ComplementaryReimagined 为基准逐一核对
> 决策方式：grilling 逐项确认（Q1–Q8，Q8 为文档定稿后的性能补录；Q9 为游戏内实测后的范围修订），本文档为最终共识的固化
> 状态：**阶段一五项已实施落地**（法线烘焙/漫反射/爆发自旋/跳舞俯仰/图集 mip，含一项已知偏差待修，见阶段一末注）；阶段二已结案（S1 NO-GO 终审，见附录 B-4）；**范围修订（Q9）：游戏内实测确认现有自绘管线在任意光影包下均稳定兼容**——后续光影包适配收敛为仅 MODEL 粒子，精灵类粒子冻结不动

---

## 0. 结论摘要

| 场景 | 能否对齐 | 路线 | 置信度 |
|---|---|---|---|
| 无光影包 | ✅ 观感对齐可达 | 法线漫反射 + 爆发自旋 + 跳舞俯仰修复 + 图集 mipmap | 高（全部静态可验证） |
| Photon · 表面受光/发光 | ✅ **精确一致**可达 | S1 契约镜像：自绘程序输出同格式 G-buffer，进入同一延迟光照管线 | 中高（需一次时机 spike） |
| Photon · 投影 | ✅ 精确可达 | L2a 自绘深度进阴影通道 | 高 |
| Complementary · 投影 | ✅ 精确可达 | 同 L2a（畸变更简单，成本最低） | 高 |
| Complementary · 表面受光 | ⚠️ 仅观感近似 | C2 复刻其净效果；**缓办** | 中 |
| 精灵类粒子（加色/贴图）× 任意包 | ➖ 无需对齐 | **维持现状（Q9 实测定案）**：稳定叠加渲染，无包效果即预期形态 | 高 |

核心发现："原版悦灵的自发光感"在两包中的机制完全不同且都无特殊魔法：

- **Photon**（延迟渲染）：悦灵签名是 G-buffer 光照等级通道恒写 (1.0, sky)（来自 getBlockLightLevel=15），延迟 pass 据此给满方块光响应。**契约可镜像**。
- **Complementary**（前向渲染）：悦灵只是被钳到方块光 0.8 的普通受光实体（entityId 50080 分支），洞里呈暖色火光染色而非白亮。**没有中间契约可写**，只能近似。

### 0.1 范围修订（Q9，游戏内实测）

实测结论：现有自绘管线（加色精灵、贴图 ALPHA/OPAQUE 精灵、MODEL 模型三类全部）在**任意光影包**下均稳定渲染——自绘绘制既不被包捕获、也不破坏包的合成，等效于一个干净的"未受包效果叠加层"，无任何兼容性瑕疵。

据此修订范围：

- **精灵类粒子（ADDITIVE / ALPHA / OPAQUE）冻结现状**，永久不做光影包适配。理由：加色混合本就近似自发光（无受光可谈）；OPAQUE cutout 写深度，与包的深度消费交互良好；"没有叠加包效果"即这类粒子的预期形态。任何接入尝试都只有成本没有收益；
- **光影包适配的全部剩余工作收敛到 MODEL（悦灵）粒子**两条线：表面受光（G-self 路线，附录 B-3/B-4）与投影（阶段三 L2a）。本文档其余部分的 Photon/Complementary 解剖继续有效，但适用对象自此仅为 MODEL。

---

## 1. 对齐基准：原版悦灵渲染剖析

依据 .refs/neoforge-21.1.227/net/minecraft/client/：

### 1.1 渲染状态（renderer/RenderType.java）
- 整个模型走 ENTITY_TRANSLUCENT：alpha 混合 + **写深度** + NO_CULL + LIGHTMAP + OVERLAY
- 不透明纹素在混合+写深度下视觉等效 cutout；斗篷(α160)/翅膀正常混合——本引擎的双段拆分（cutout 段/鬼影段）与其视觉等效，鬼影段同样写深度 ✓

### 1.2 光照（renderer/entity/AllayRenderer.java + 原版实体着色器）
- getBlockLightLevel = 15：方块光拉满 → lightmap 近全白，这是"洞里也亮"的根源
- 天空光仍参与 lightmap 采样（夜晚轻微变暗）
- 顶点阶段 minecraft_mix_light 双方向光漫反射（Light0/Light1 为**视空间固定方向**常量，按法线着色：顶部亮于底面；漫反射随相机俯仰有轻微变化——移植时须在视空间计算或等效处理）——**立体感的主要来源**
- OVERLAY 受伤闪白（粒子无受伤态，排除）

### 1.3 动画（model/AllayModel.setupAnim + Allay.java）
- 各姿势系数与本引擎 GLSL 移植版逐项核对一致（root bob、wing f1、arm f12/f14、dance wobble f7–f11）
- **自旋为爆发式**：isSpinning() = dancingTicks % 55 < 15；窗口内进度线性上升、root.yRot = 4π × 进度；窗口关闭后钳制累加器 spinningAnimationTicks 还会再衰减一个窗口长度（Allay.tick 的递减规则）——期间 root 偏航已被 resetPose 归零，但三路 wobble 乘 (1−f9) 继续受其抑制，即自旋结束后仍有 15t 的摆动渐入，然后静止至下个周期
- HOLD 抬臂缓动 = holdingItemAnimationTicks 的**线性** lerp / 5.0（五 tick 线性斜坡；早期 smoothstep 实现不等价，已修正）
- **跳舞分支中 head.xRot 保持 0**（非跳舞才吃 headPitch）——本引擎当前多算了一项

### 1.4 几何与变换（model/geom/ModelPart.java + LivingEntityRenderer）
- createBodyLayer 七立方体尺寸/纹理偏移与本引擎烘焙完全一致 ✓
- 变换链 Ry(π−yaw)·S(−1,−1,1)·T(0,−1.501,0) 与 model.vsh 的 flipped=(−x, 1.501−y, z) 推导等价 ✓
- ModelPart 旋转序 Rx·Ry·Rz（rotationZYX）与 part() 一致 ✓
- 翅膀零厚度双绕序、斗篷 α160 ✓

### 1.5 本引擎已对齐项确认（无需改动）
几何/UV/变换链/姿势主体系数/cull 等效策略（双绕序壳）/鬼影段写深度语义/HOLD 线性缓动（0.2.5 起为线性，见阶段一实施记录补遗）。

---

## 2. 差异清单与决策（无光影包）

| # | 差异 | 决策 |
|---|---|---|
| D1 | 恒定全亮平涂，无法线漫反射、无天空光分量 | 补法线漫反射 + 固定近白基色（≈0.95–1.0 实测校准）；不绑 lightmap（block=15 时收益微小） |
| D2 | 自旋为 1s 连续循环 vs 原版爆发式 | 复刻原版节奏（55t 周期 / 前 15t 扫 4π / 其后一个窗口长度的衰减尾内 wobble 渐入 / 停顿）；0.2.5 补齐初版遗漏的衰减尾 |
| D3 | 跳舞时头部仍叠加速度俯仰 | 归零对齐原版分支语义 |
| D4 | 图集 NEAREST 无 mip，远距闪烁 | 仅 ALLAY 图集开 mip（单帧 32×32 无边界渗色风险）；cherry 多帧图集不动 |
| D5 | 贴地阴影（原版 0.4 blob）、手持物品、受伤闪白 | 全部排除（阴影实现贵收益低；后两者无粒子对应物） |
| D6 | 动画输入为程序化近似（速度驱动朝向、随机相位） | 保留——粒子群体无单一实体的良定义原值 |

---

## 3. 光影包架构事实（两包解剖）

### 3.1 Iris 阴影通道（.refs/Iris/.../shadows/ShadowRenderer.java）
时序：绑 ShadowRenderTargets → 关背面剔除 → 地形 section → 实体（走原版管线被路由到包的 shadow 程序）→ endBatch() → 半透明前拷贝 → 半透明地形 → mipmap → 恢复状态 → **shadowcomp 合成**。

- 阴影判定消费的是**深度通道**；颜色缓冲只服务彩影/光柱染色
- 自绘路线（L2a）只需：窗口内绘制 + 深度正确 + 复现包的顶点畸变。**不需要包的程序**
- 原版实体"搭便车"依赖 BufferSource 批次路由，本引擎 SSBO 实例化管线不可用该路径（L2b 排除依据）

### 3.2 Photon（.refs/photon/shaders/program/gbuffers_all_solid.{vsh,fsh}）
- **延迟渲染**：gbuffers 只写四通道打包数据，无任何针对悦灵的特殊代码
  - data_0 = (pack_unorm_2x8(albedo.rg), pack_unorm_2x8(albedo.b, mask·rcp255), pack_unorm_2x8(encode_unit_vector(normal)), pack_unorm_2x8(dither_8bit(light_levels)))
  - light_levels = clamp01(gl_MultiTexCoord1.xy · rcp(240)) → 悦灵 ≡ (1.0, sky)
- 打包/编码/dither 辅助全在 include/utility/* 可照抄 → **契约镜像成立（S1）**
- TAAU 有渲染缩放的片元坐标换算需同步复刻
- 待验证（spike）：实体 gbuffer 窗口内的钩子点（候选 RenderLevelStageEvent.AFTER_ENTITIES 或小型 Iris mixin）及该时点附件绑定状态

### 3.3 Complementary Reimagined（.refs/ComplementaryReimagined/shaders/）
- **前向渲染**：program/gbuffers_entities.glsl 内联调用 DoLighting() 直接产出最终颜色——**无可镜像的中间契约**（S1 不适用）
- 悦灵专属分支（lib/materials/materialHandling/entityIPBR.glsl，entityId == 50080）：
  - 正常图集（atlasSize ≥ 900）：lmCoordM.x = 0.8——仅压低方块光，**无 emission**；洞里呈暖色火光染色
  - 小图集（< 900）：光照清零 + 按纹素白度给 emission——行为分叉证明精确复刻必须运行其自身上下文
  - 另有全局实体钳制 lmCoord.x = min(lmCoord.x, 0.9)
- 阴影顶点变换（program/shadow.glsl vsh）为最简 BSL 系三行式：
  factor = len(xy)·bias + (1−bias)；xy /= factor；z ×= 0.2
  项目 ShadowDistortionRegistry 注释点名 Complementary（EuclideanShadowDistortion），bias/depthScale 已自动解析——L2a 适配即一个分支

### 3.4 既有设施可复用清单
ShadowDistortionRegistry + 各包参数解析｜ShadowRendererAccessor mixin 先例｜MistIrisHook 的 colortex 绘制窗口模式（注意：其对雾有效的 after-translucent 时点**晚于** deferred 消费，不能直接用于 S1）｜彩色光适配器的按包扩展模式（S1 适配器同构）。

---

## 4. 实施计划

### 阶段一：无光影包五项（预计一次会话内完成）

| 步骤 | 文件 | 改动 | 验收 |
|---|---|---|---|
| 1.1 法线烘焙 | client/particles/engine/AllayModelGeometry.java | 顶点 stride 6→7 floats：追加面法线轴 id（六向 ± 轴枚举，平面翅膀给其法向；fsh 按 gl_FrontFacing 翻转处理双绕序）。SSBO 尺寸随数组长度自适应；注意同步更新 model.vsh 中硬编码的顶点步长寻址——建议经 PRELUDE 以 #define 注入消除双处维护 | 几何重建后模型外观不变 |
| 1.2 漫反射 | shaders/particles/model.vsh / model.fsh | vsh 解码法线轴 → 世界空间法线（复用 yaw 旋转矩阵）传出并变换到视空间；fsh 移植 minecraft_mix_light 双方向光（Light0/Light1 视空间固定方向常量），基色乘近白系数（新常量，默认 0.97，实测校准口）；翅膀平面法线按 gl_FrontFacing 翻转 | 悦灵顶部亮于底面/背光面；洞穴内不再死白平涂 |
| 1.3 爆发自旋 | 共享块 allay_pose.glsl | phase=fract(age/2.75)；f9 三段式＝窗口内 phase/w、其后一个窗口长度内 1−(phase−w)/w、其余 0；rootYRot 仅窗口内 = 4π·f9（0.2.5 补齐衰减尾，对齐钳制累加器语义） | /cmip anim allay_dance：快转两圈→摆动渐入→停顿循环 |
| 1.4 跳舞俯仰 | model.vsh | dance 分支将 headXRot 置 0 | /cmip anim allay_dance 头部不带速度点头 |
| 1.5 图集 mipmap | engine/ParticleAtlas.java | 构造加 mipmap 开关；ALLAY 实例启用 glGenerateMipmap + MIN_FILTER=NEAREST_MIPMAP_LINEAR；CHERRY 不动 | 远距（64–96 格）翅膀边缘闪烁明显减轻 |

**验证矩阵（并排 /summon minecraft:allay 截图对比）**：正午户外 / 夜晚地表 / 全暗洞穴 / dance / hold / death / 近景(4格) / 中景(32格) / 远景(96格淡出缘)。通过标准：静止帧普通玩家不可分辨（D6 运动自由度除外）。

**实施记录**：五项均已落地（`AllayModelGeometry` stride 7 + 法线轴表、`model.fsh` 双方向光 + BASE_BRIGHTNESS 校准口、`model.vsh` SPIN_CYCLE_S/SPIN_WINDOW_FRACTION 爆发节奏、dance 分支 headXRot 归零、`ParticleAtlas.ALLAY` mipmap）。

**实施记录补遗（0.2.5 周期，对齐复审驱动；姿势数学已迁入共享块 allay_pose.glsl，L0 与 shaderpack 合并双路径同时生效）**：

- **A｜自旋衰减尾补齐**：原版 spinningAnimationTicks 为 [0,15] 钳制累加器——窗口结束后每 tick 递减，getSpinningProgress 在其后 15t 内由 1 线性回落 0；期间 root.yRot 已被 resetPose 归零，但三路 wobble 乘 (1−f9) 持续受抑。dance 分支 f9 改三段式（窗口升 / 衰减 / 零），rootYRot 仅窗口内驱动。本文件早先"停顿至下个周期"的表述漏掉了该段。
- **B｜HOLD 缓动改线性**：原版 getHoldingItemAnimationProgress = lerp(ticks0, ticks) / 5 为五 tick 线性斜坡（对称回落需持物解除事件，粒子无此输入）；初版 smoothstep(0, 0.25s) 不等价，改为 clamp(ageSec / 0.25)。
- **C｜舞蹈合并完整自旋 + 新增死亡动画**：动画 ID 重编号 FLY=0 / DANCE=1 / HOLD=2 / DEATH=3，删除独立 SPIN——原版 isDancing() 恒含 isSpinning() 节奏，无单独自旋姿态；allay_dance 即完整原版舞蹈，allay_spin 预设移除。DEATH 复用 FLY 待机微摆（LivingEntity.aiStep→travel 无死亡门控、重力照常、时钟驱动摆动不停），并在最外层（朝向 Ry 之后，同 setupRotations 的 Rz-after-Ry 合成序）绕世界 Z 轴翻转位置偏移与法线：sqrt(min(((t·20−1)/20)×1.6, 1)) × 90°。预设 allay_death ＝ 固定 1s 寿命（严格对齐 20 tick 死亡计时）+ 重力 −8 / drag 0.8 + 受击红闪颜色关键帧近似（(1, 0.42, 0.42)→白→白，前半程渐退）；结尾 POOF 未做（需要"到期联动第二发射器"的事件架构）。死亡翻转调用点两处：model.vsh 与 ShaderPackProgramCompiler 合并模板。**已知偏差已修复**：步骤 1.2 初版漫反射相对原版 `minecraft_mix_light` 少乘了 MINECRAFT_LIGHT_POWER（0.6）（对照 `.refs/neoforge-21.1.227/.../shaders/include/light.glsl`）——中间角度受光面偏亮（侧面 0.966 vs 原版 0.740）、立体感被压平；已按原版公式补齐（model.fsh，POWER/AMBIENT 常量命名与 light.glsl 对齐）。待办：BASE_BRIGHTNESS=0.97 的目测校准基于带缺陷的公式，重跑上述验证矩阵时确认是否需上调。

### 阶段二：光影包 spike（判定性实验，先行）
1. 在 RenderLevelStageEvent.AFTER_ENTITIES 时点以调试纯色画一个 MODEL 实例，Photon 启用状态下检查：写入是否幸存至合成、附件绑定是否为 gbuffer MRT、TAAU 坐标换算是否正确
2. 若 AFTER_ENTITIES 不落窗口 → 备选：小型 Iris mixin（项目已有 iris mixins 先例）
3. **产出**：go/no-go 结论 + 记录实际可用的绑定/时序参数

**Round-1 结果（已完成）**：drawFbo=2（Iris gbuffer FBO，非主缓冲）、写入存活至最终画面、glErr=0、viewport 为全分辨率。裸色写入在屏幕上呈现"滤镜般显示去除包效果后的背景"——与 colortex0 在该窗口的语义为**打包 G-buffer 数据**（`gbuffers_all_solid.fsh` 向 location=0 写 `gbuffer_data_0`）完全吻合，属 S1 前提的佐证而非否证。首轮探针的相机跟随锚点 bug 已修复为世界锚定。

**Round-2 判定协议（当前版本，三模式各 120 帧自动循环）**：mode0=原始品红裸写（基线复现）；mode1=按 Photon 契约打包写入（白反照率 + 法线八面体(0.5,1.0) + 光照等级(1,1)）；mode2=同打包但光照等级(0.05,0.05)。判定：mode1 呈现受包光照的白亮表面且 mode2 近黑 → **契约实证成立，S1 GO**；两模式与 mode0 同样异常 → 打包数据未被按预期消费 → 转 Iris mixin 备选方案。

**Round-2 结果（实测）：S1 自绘路线证伪（NO-GO）。** 三种截然不同的写入（裸色/全亮契约/近暗契约）在最终画面上完全不可区分，均呈现"该位置背后场景去除包效果"的滤镜状表现。结论：我们的片元输出内容对该窗口的最终成像**无因果影响**——最可能机制是 Iris 对实体阶段绘制调用的捕获/重放使裸 `glDrawArrays` 脱离自身程序上下文执行（机制细节不影响决策）。附带确认参数：drawFbo=2、viewport 全分辨率、glErr=0、世界锚定正常。**后续走向待决策**：(a) S1′ 变体——经 Iris 实体渲染流提交常规 NEW_ENTITY 格式批次（CPU 端姿势计算，模型粒子数量级小故成本可控），由 Iris 自动路由进 gbuffers_entities 获得完整契约；(b) 补一轮 readback 探针查明机制；(c) 放弃包内表面受光，仅保留 L0 并推进阶段三阴影自绘。**S1′ 已被否决：悦灵实例可达十万量级，CPU 姿势计算不可行（用户游戏经验佐证 Flywheel 类 CPU 更新路径即其性能瓶颈）。**

### 阶段二附录：Flywheel / Colorwheel 路线可行性验证（结论：有条件 GO）

参考 `.refs/Flywheel`（1.0）与 `.refs/Colorwheel` 源码逐项取证：

1. **实例更新模型**：间接后端按页粒度脏标记（`AtomicBitSet contentsChanged`），仅上传变更页。若 MODEL 实例结构为出生即静态的 `{particleSlot:uint}`，CPU 上传只发生在生成/回收时——十万实例零持续 CPU 成本，绕开"Flywheel 默认 CPU 更新"的性能坑。
2. **SSBO 驱动实锤**：间接管线顶点着色器自 `layout(std430, binding=1) InstanceBuffer` 解包实例；Flywheel 自占绑定槽 0–7（BufferBindings），材质源码可声明更高槽位的自有 SSBO/纹理（变换缓冲/碰撞纹理/发射器头），我方持久绑定 + 冷门编号防冲突。
3. **材质 GLSL 自由**：`MaterialShaders` 接受模组裸顶点/片段源——姿势数学移植到材质顶点器，GPU 端完成，十万无压力。
4. **Colorwheel 复用而非替换** Flywheel 编译产物（glsl-transformer AST 变换装入 `clrwl_gbuffers*`/`clrwl_shadow*` 程序，带回退链）：我们的自定义绑定与姿势源码在包路由下原样存活；阴影通道一并解决（L2a 并入）。

**迁移形态**：模拟层不动（update.comp 增加一次可视变换紧凑写出）；MODEL 材质迁入自定义 Flywheel InstanceType；Colorwheel 为可选前置（Create 自带 Flywheel 1.0.6）；无 Colorwheel 时包内表现退化待后续决策（是否保留现自绘路径作后备）。风险三项：Flywheel/GLSL 方言学习曲线、Colorwheel 对 Flywheel 小版本的 mixin 追逐、槽位冲突兜底。

### 附录 A：Flywheel vs 现有自绘路径性能对比

关键常量：Flywheel 间接后端 PAGE_SIZE=32（实例/页）、页级脏跟踪、`InstancePage` 内为真实 Java 对象数组。

**渲染吞吐（GPU 侧）：基本打平。** 模拟计算相同（Flywheel 路径多一次变换紧凑写出）；剔除各自存在量级相当；顶点姿势数学完全同构；提交方式同级。

**生命周期经济学（真正的分水岭）：**

| 场景 | 自绘 | Flywheel |
|---|---|---|
| 生成风暴（单帧数万） | CPU 仅写 32B 发射命令/批次，槽位 GPU atomicAdd 分配，零 Java 分配 | 每粒子 createInstance()：5 万粒子 = 5 万句柄+对象分配 + 约 1500 页插入 → 单帧数十万级 Java 分配，GC 压力与卡顿尖峰 |
| 持续死亡 | update.comp 致密化顺手丢弃，CPU 无感 | 死亡发生在 GPU，CPU 不知道——回收需 fence 逐槽对账（S1′ 同款成本换位）；或句柄池永久占用 + 着色器有效性位隐身 |
| 稳态存活 | 固定调度开销 | 静态实例零脏页零上传；每帧小常数簿记 |

`{slot:uint}` 极小实例结构使页放大效应（PAGE_SIZE=32 重传粒度）无关紧要——良性协同。

**内存/卡顿画像**：Flywheel 侧另加十万句柄+对象 ≈ 数十 MB Java 堆（预分配方案下恒定）；卡顿画像上自绘平滑（超容量时节流优雅降载），Flywheel 在生成/批量死亡风暴有 CPU 尖峰风险。

**结论**：渲染性能不构成迁移障碍亦非理由；决定性分歧在生命周期管理——自绘的"GPU 全权管理"在生成风暴与批量死亡场景有结构性优势。Flywheel 路径必须先完成"预分配句柄池 + CPU 预指派槽位 + 着色器有效性位"三件套改造才能收窄差距，属真实工程税。

### 附录 B：无 Flywheel、仅 Colorwheel 方案探索

源码事实：Colorwheel 引擎层（ClrwlDrawManager/ClrwlAbstractInstancer 等）整体是 Flywheel 后端重实现，类型强耦合 `flywheel.api.Instance`；`ClrwlProgramId` 是封闭枚举，无公开 API 供外部注册程序槽。因此"运行时只挂 Colorwheel、不经 Flywheel 直接借用"不存在受支持路径。可行形态分解为两个变体：

**X1 借壳（不推荐）**：依赖 Colorwheel，mixin 挂在其绘制冲刷点，绑定它编译好的 `clrwl_gbuffers` 程序后发我们的间接绘制。死穴：该程序的输入契约是 Flywell 接口（flw 属性 + 实例 SSBO 布局），我们的数据布局不匹配；若通过补丁改写程序内容适配我们，又会劫持同一插槽服务的所有 Flywheel 内容（Create 机械体），产生跨模组冲突。

**X2 自研扩展槽（推荐）**：复刻而非依赖——把 Colorwheel 的 Iris 扩展机制（ProgramSet/ProgramSource/ShaderPackSourceNames 三四个小 mixin + `cmi_gbuffers*`/`cmi_shadow*` 槽位命名约定 + 回退链）以自有命名空间实现进本 mod（若直接借鉴其 LGPL 代码需合规保留声明），并仿照 Colorwheel-Patcher 为 Photon 等目标包分发补丁程序文件。执行点 mixin 进实体渲染后已验证存活的窗口，绑定**属于包 ProgramSet 的 cmi 程序**（Iris 自动接管其雾/相机等 uniform 绑定），随后发既有 SSBO 间接绘制。与被证伪的裸自绘 S1 相比，两大失败因素被针对性消除：程序属于包集合（uniform 由 Iris 管理）、执行点保持程序所有权。十万实例 GPU 管线完整保留；代价是每目标包一份程序文件（初版可仅做基础漫反射+雾）与少量 Iris 版本追逐。

**建议排序**：X2 > P-A（Flywheel 迁移，受生命周期税制约）> X1 > 维持现状。X2 与阶段三阴影自绘可共享执行点 mixin（`cmi_shadow*` 槽位同步解决 L2a）。

### 附录 B-2：X1 借壳方案深挖 —— 塌缩分析

将 X1 拆解至实现层面的五个子变体，逐一定性：

| 子变体 | 思路 | 判定 |
|---|---|---|
| X1.a | 绑定 Colorwheel 编译好的 clrwl 程序，直接喂我们的 SSBO 数据 | ✗ 顶点契约是 Flywheel 接口（flw 属性 + binding=1 实例解包），数据错位纯垃圾输出 |
| X1.b | 反向适配：经 Flywheel API 注册 `{particleSlot}` 自定义 InstanceType，让 Colorwheel 编译管线自动特化出我们的程序版本 | ≡ **P-A 最小形态**——模板填充机制天然支持多类型特化，但类型注册即意味着进入 Instancer 句柄体系，生命周期税原样回归 |
| X1.c | 补丁改写 clrwl 程序内容适配我们布局 | ✗ 一个 `clrwl_gbuffers` 槽位服务所有 Flywheel 内容（Create 全家），按我们布局改写即劫持公共插槽；加自有命名空间文件则等价坍缩为 X2 |
| X1.d | 只借执行冲刷窗口，保留自有程序与绘制 | ⏳ **待 Round-4 读数裁决**：写入若"落地后被覆盖"→ 换窗口保所有权可救活；若"根本未光栅化"→ 与事件点同样必死 |
| X1.e | 借 clrwl_shadow 的矩阵/畸变做纯深度写入 | ≡ 阶段三原方案的子集（阴影只需深度正确，无需 Colorwheel 参与） |

**净结论**：X1 不构成独立第三条路——选项空间收敛为 P-A（含最小形态）/ X2 / 维持现状，外加待决的 X1.d 分支。Round-4 诊断（Photon + `/cmip spike` + 立方体保持视野内）成为决策树最短关键路径。

### 附录 B-3：iris-flw-compat 实现 —— 路线图重大修订

`.refs/iris-flw-compat` 现状**推翻了两处既有认知**：其一，Colorwheel README 称"Flywheel 1.0 无法移植"不实——该仓库已在 1.0 API 上实现完整方案（`IrisProgramLinker extends ProgramLinker`）；其二，其机制方向与 Colorwheel 相反且**覆盖面更广**：

- **Colorwheel**：要求包提供 `clrwl_*` 新程序槽（或经 Patcher 打补丁），deferred 类包有已知问题
- **iris-flw-compat**：把 Flywheel 顶点管线经 glsl-transformer **注入包的通用程序**（gbuffers_terrain 系，所有包必有），并把编译链整体替换为 **Iris ShaderInstance**（Iris 接管 uniform/渲染目标管理）→ 无需任何包适配

核心资产（均 MIT 许可，可借鉴）：`GlslTransformerVertPatcher`（464 行，AST 注入 + 属性重映射）、`IrisPipelineCompiler`/`IrisCompilationHarness`（编译链替换）、`IrisFlwCompatGlProgram`（ShaderInstance ↔ GlProgram 适配）、`IrisInstancedDrawManager`（保留阴影/OIT/crumbling 被追踪路径）。

**路线图修订**：新增主导路线 **G-self**——将"编译链替换为 Iris ShaderInstance + AST 合并包上下文"模式移植到本引擎 `ParticlePrograms`（我们的源码完全自控，可预置合并锚点；变换器规模同量级）。对比：X2 需逐包补丁分发，G-self 运行时自动合并任意已装包——分发与覆盖面占优，代价是跨包家族的变换器维护。P-A 若走则兼容层改选 iris-flw-compat（MIT）替代 Colorwheel。生命周期税结论（附录 A）不受影响，G-self 天然满足十万实例约束。

**修正后排序**：G-self > P-A′（Flywheel + iris-flw-compat 栈）> X2（降为手工兜底）> P-C。执行点 mixin 为各路线共同前提；Round-4 诊断仍为执行点选型的输入。

### 附录 B-4：Round-4 终审 —— 阶段二结案

实测数据：drawFbo(2).attachment0 = 纹理 id 5，**rgbaBits=8/8/8/8**；五点采样均为天空蓝（本轮相机未对准立方体，颜色样本无效，但格式判定与瞄准无关）；depth≈0.982（远平面）。

**终审三条**：
1. S1 自绘路线在 AFTER_ENTITIES 钩子 **NO-GO 终审**——RGBA8 附件无法承载 `pack_unorm_2x8` 契约（需 ≥16 位通道），格式层面即判死，与瞄准无关；
2. X1.d 分支同步死亡——问题在目标 FBO 本身而非窗口时机；
3. 执行点终审：RenderLevelStageEvent 系裸钩子在该管线中不可用，iris-flw-compat 与 Colorwheel 选择 mixin 进被追踪调用流是唯一正道。

**G-self 形态据此精确化**：① 编译链替换——ParticlePrograms 产物经 AST 合并包上下文后以 Iris ShaderInstance 存在；② 执行点 mixin——进入被追踪调用流（参考其 IrisInstancedDrawManager）；③ 十万实例 SSBO 管线原样保留。spike 探针保留为诊断工具（`/cmip spike`）。

### 阶段三：L2a 阴影自绘（两包共享；**仅 MODEL 粒子**，精灵类不参与投影——Q9 定案）
- 新 mixin：挂 ShadowRenderer 取绘制窗口（实体 endBatch 后、半透明地形之前——与不透明遮挡体同窗；源码注释明确警告此后绘制会被包按半透明彩影语义处理，纯深度写入虽不受染色，仍宜对齐）
- 新着色器变体 model_shadow.{vsh,fsh}：阴影 MVP + 畸变（参数取自 PackShadowParams）+ 仅深度输出 + polygon offset 防 acne
- ParticlePrograms 注册变体；Complementary 先行（畸变参数已有解析设施），Photon 届时按 shadow.glsl 同法核对
- 剔除策略（性能定案）：keygen 仅对 MODEL 项放宽为距离界——Q9 定案后仅 MODEL 有投影路径，ALPHA 精灵虽属半透明排序但不进阴影、维持视锥严格剔除（距离界 ≤淡出距+余量，默认 120 格，与典型阴影半平面 128 格基本重合；超出淡出界的粒子本身不可见，其远端长影属可接受近似），additive/OPAQUE 精灵仍走相机视锥严格剔除——排序数组增量极小，主路径效率基本不变，屏外粒子影子不再消失
- 计时补盲（性能定案）：每个钩子点挂独立 GL_TIME_ELAPSED query 环（沿用 TIMER_RING 轮询模式，零超时非阻塞），下一帧并入节流 EMA——消除光影包下新增成本的度量盲区
- 验收：晴天地面出现悦灵影子、随时间方向正确、SHADOW_QUALITY 低配档不崩；高角度俯视机位无缺影；/cmip stats 显示的 gpu 值含钩子贡献

### 阶段四：Photon S1 契约镜像（**仅 MODEL**；精灵类不进包管线——Q9 定案）
- 新变体 model_photon_gbuffer.{vsh,fsh}：照抄 include/utility 打包函数 + material_mask 取实体值 + light_levels 恒 (1.0, sky近似) + TAAU 坐标处理
- 按 ShaderColoredLightAdapters 同构模式建 GbufferContractAdapters（Photon 首发，后续包各一份布局适配器）
- **渲染路径仲裁**：S1 激活帧跳过 drawModels() 主模型段（MODEL 已进 Photon 光照管线，双绘既翻倍顶点成本又导致 colortex 叠加发亮）；additive/贴图精灵路径不受影响
- 验收：Photon 下悦灵粒子与 /summon 的真悦灵并排——洞内同等白亮、同时被彩色灯染色、日光下同等明暗；开启 bloom 后发光响应一致；抓帧确认 MODEL 无二次提交

### 源码组装去重约束（贯穿三四阶段）

    shaders/particles/chunks/
      allay_pose.glsl      ← 姿势数学 + 实例解析 + 关键帧插值（唯一一份）
      emitter_decode.glsl  ← 发射器头解码辅助

    model.vsh        = chunks + 主渲染输出
    model_shadow.*   = chunks + 阴影变换/深度输出
    model_photon.*   = chunks + gbuffer 打包输出

ParticlePrograms 编译时拼接（PRELUDE 机制的自然扩展）。禁止任何形式的拷贝复用。

### 明确不做（含理由存档）
精灵类粒子（ADDITIVE/ALPHA/OPAQUE）的光影包效果适配——Q9 实测定案：现有路径任意包稳定叠加渲染，无包效果即预期形态，改动零收益｜L1 手动采样受光（被 S1 取代）｜Complementary C1 真实接管（需放弃 SSBO 顶点拉取改喂 #version130 传统属性，动摇引擎根基）｜L2b 走包实体程序（架构不可达）｜S2 HDR 伪发光（神似非一致）｜贴地阴影 / OVERLAY（见 Q5）。~~手持物品~~ **已被 Q10 推翻并实装**（见 §7）。

---

## 5. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| Spike 判定 AFTER_ENTITIES 不在窗口内 | 阶段四延期 | 备选 mixin 路线已明确（G-self / X2）；最坏退化为 L0+C2——Q9 实测确认 L0 基线任意包稳定，可长期接受 |
| 包版本漂移（entityId 表 / gbuffer 布局 / 打包函数变动） | 适配器失效 | 适配器启动时校验签名（仿 ShadowDistortionRegistry.lastSignature 机制），失配即降级 L0 并告警 |
| 近白基色校准主观性 | 验收争议 | 校准口做成编译期常量，以验证矩阵截图为准 |
| 三变体性能叠加 | GPU 时间上升 | 各变体仅在各自窗口/有实例时分派；现有预算节流覆盖 |
| 节流器失明：钩子点新增成本在引擎计时括号之外，自适应降载可能失效 | 光影包下重度群超预算而 emission 不降 | 定案：各钩子点独立 timer query 环并入节流 EMA（见阶段三计时补盲） |

---

## 6. 决策记录索引

Q1 验收=B 观感对齐｜Q2 光照=法线漫反射+近白基色｜Q3 自旋=复刻爆发节奏｜Q4 mip=仅 ALLAY 图集｜Q5 排除三项｜Q6 分包=L2a+S1(Photon) 核心、C2 缓办、余排除｜工程约束=源码组装去重｜Q7 交付=报告+计划（本文档），开工另批。Q8 性能定案=MODEL 项 keygen 剔除放宽至距离界（精灵类保持视锥严格剔除）＋各钩子点独立 timer query 环并入节流＋S1 激活帧跳过主模型段。Q9 范围修订（游戏内实测）=现有自绘管线在任意光影包下兼容（正确渲染、仅不叠加包效果）；光影包适配收敛为仅 MODEL 粒子（表面受光 G-self ＋ 投影 L2a），ADDITIVE/ALPHA/OPAQUE 精灵类冻结不动。Q10 手持物品（2026-08-30，推翻 Q5 的排除项）=双路径共享机器（波次 uniform 寻址 + 头槽 17.z 寻址）、携带谓词 (a)、资产完全自持（私有图集 4×2）、glint 跳过、谓词早退顶点账目（+0.04%，keygen 携带者分区否决）——详见 §7。

---

## 7. 手持物品（Held Item）管线 — Q10，2026-08-30 拷问定案并实装

> 背景：Q5 曾把"手持物品"列入明确不做（当时无玩法调用方）。悦灵突袭小队（`docs/allay-storm-ai.md` §5.1） commission 后经 grilling 逐题定案（调用方/谓词/映射/双路径/烘焙深度/边缘语义），本节为共识固化。波次语义在 storm-ai 文档，本节管**渲染管线与对齐论证**。

### 7.1 原版基准（.refs/neoforge-21.1.227 逐行核对）

- 渲染链：`AllayRenderer` 挂 `ItemInHandLayer` → `renderArmWithItem(THIRD_PERSON_RIGHT_HAND)` → `AllayModel.translateToHand` → `ItemInHandRenderer.renderItem` → `ItemRenderer.renderStatic`（完整物品烘焙模型 + display JSON 变换）。
- **手部变换链**（`translateToHand`，逐字移植进 `cmiAllayPartTransform` 的 pid-7 分支）：`root → body → translate(0, 0.0625, 0.1875) → Rx(right_arm.xRot) → scale(0.7) → translate(0.0625, 0, 0)`，再叠 `Rx(-90°)·Ry(180°)·translate(1/16, 0.125, -0.625)`。**两个必须复刻的原版怪癖**：① 两只手同用 `right_arm.xRot`（= f12 抬臂量），手臂的 yRot/zRot **不进入**手部链；② 副手恒空 → 只渲染主手（右）一件。
- **display 变换**：`item/handheld.json` thirdperson_righthand（rotation [0,-90,55]、translation [0,4,0.5] 经反序列化器 ×0.0625、scale 0.85、rightRotation 恒等）+ `ItemRenderer.render` 的 `translate(-0.5)`，作为**一个常量 mat4**（`CMI_HELD_DISPLAY`）在 Java 侧以 JOML 逐字复刻 PoseStack 调用序（translate→mulPose(rotationXYZ)→scale→translate）一次算死注入着色器——四元数组合语义不靠手推。
- **平面几何**：`ItemModelGenerator.processFrames` 全元素 (0,0,7.5)..(16,16,8.5)，SOUTH 面 z=0.53125 UV 正、NORTH 面 z=0.46875 UV 镜像（背面镜像即原版生成物品的背面行为）；逐像素 run 的侧壳省略，满 quad + alpha cutout 片元等价。顶点/索引绕向沿用引擎 face() 约定（法线方向看 CCW，cull 开启）。
- **姿势**：原版悦灵**只要手上有物品**就抬臂——`Allay.tick` 的 `hasItemInHand()` 驱动 `holdingItemAnimationTicks`（5 tick 线性到满），f6 = lerp/5。抬臂是叠加在当前动画（FLY/DANCE）上的连续斜坡，不是独立姿势。`translateToHand` 的手链以 `right_arm.xRot = f12 = f6·lerp(f4, -π/3, -1.134464)` 为输入，剑自动跟随抬臂。
- **光照**：`AllayRenderer.getBlockLightLevel = 15` 同喂图层 → 物品与身体同源（引擎全亮 + 视空间漫反射路径白送，剑面法线走同一 `minecraft_mix_light`）。

### 7.2 定案清单（拷问 Q1–Q6）

| 项 | 定案 |
|---|---|
| 调用方 | **双路径共享机器**：波次路径（档位随 `ClientboundStormWavePacket` byte，谓词 = 波次窗口 (a)）+ 通用路径（`EmitterSpec.heldItem(...)` → 头槽 17.z，谓词 = `anim==HOLD`）；`ALLAY_HOLD` preset 默认钻石剑兼作对拍验收工具 |
| 携带谓词 (a) | 哈希成员 + `[assembleSec, diveUntilSec]` 全窗携带；f6 斜坡 5-tick 线性升降（`cmiStormCarryRamp`，C0 两端）；集结段持剑待命、锁存持剑归队；尸体不携带（`!corpse`） |
| 伤害→材质 | 服务端一处硬编码阈值（≥10 下界合金/≥7 钻石/≥5 铁/≥4 金/≥3 石/否则木），id 序 = `EmitterSpec.HeldItem`，三消费点（头槽/wave 包/UV 表）同一 id 域 |
| 资产 | **完全自持**：6 档原版剑贴图复制进 `textures/particle/sword_*.png`，并入 MODEL 图集（4×2 of 32×32 格，帧 0 悦灵、1..6 剑；16×16 小帧走图集既有的填格放大）。运行时零 `ModelManager` 依赖；资源包不能重塑剑外观——与悦灵身体同款资产自治，**这是特性** |
| 几何 | 6 档共享一份几何（同轮廓），顶点带**规范 [0,1] sprite UV**，`uHeldItemUV[7]` uniform 表按携带档位重映射（单 instanced draw 无法按实例选元素范围）；partId 7，走 OPAQUE cutout 段（`vSeg` 判定由 `pid>=4` 改显式集合 {4,5,6}） |
| glint | **跳过**，记录为已知偏差（伤害读数不是附魔物品；独立 glint 层只有成本无对齐收益） |
| 顶点开销 | 不携带实例**跳不过调用**（+8 顶点/实例 ≈ 现状 +0.04%，本就画全部 7 个悦灵部件），但谓词早退跳过全部手链/哈希/输出（无波次时 ≈ 4 次 uniform 比较）。手臂部件（pid 2/3）跑同一谓词取 f6 |
| 否决存档 | keygen"携带者分区 + 独立 indirect 命令 instanceCount=携带数"实现真零调用——需动 keygen/capture/排序槽/draw 命令四处机具，为省 0.04% 不成比例 |

### 7.3 实装落点

- Java：`EmitterSpec.HeldItem`/Builder/17.z 打包、`HeldItemGeometry`（quad 烘焙 + `CMI_HELD_DISPLAY` + UV 表）、`AllayModelGeometry`（UV 改图集空间、剑 quad 拼入 OPAQUE 段、ATLAS 常量）、`ParticleAtlas.ALLAY` 7 帧、服务端 `AllayStormManager.swordTier` + wave 包 byte、`AllayStormRuntime`（WaveClient 档位 + `waveTierUniform` 舞台）、`CMIParticleEngine`（`applyWave` 委托、`setFloatArrayUniform`、`uploadStormItemUniforms`）。
- 着色器：`chunks/allay_pose.glsl`（`cmiStormCarryRamp`、`cmiTranslate4/cmiScale4`、pid-7 手链分支、`cmiAllayPartTransform` 增 `carryRamp` 参数——**签名变更**，两处调用点同步）、`model.vsh`（谓词/itemId/早退/UV 重映射/vSeg 显式集合）。
- 光影包：`ShaderPackProgramCompiler.buildMergedSource` 镜像同款谓词与手链（TBO 读取形态）+ `CMI_HELD_DISPLAY` 以 const（非 #define，AST 移植不吃预处理器）注入；`CMIPackEntityMergeHook` 为 gbuffer 与 shadow 两轨上传 `uWave/uWaveTarget/uWaveTier/uHeldItemUV`。剑进阴影与受光管线免费获得（同一条合并顶点源）。
- 图集回归面：MODEL 图集从 1×1 变 4×2，悦灵 UV 烘焙同步改图集空间（帧 0 位于 (0,0)，纹素位置不变）；mip 渗色窗口在 level 6（128×64 的 2×1 档，全部帧混合）——发生时精灵已远小于像素，接受并记录。

### 7.4 验收（待进服/进图实测）

1. **对拍基准**：`/cmip spawn allay_hold` vs `/summon allay` 给同档剑——手部位置/剑朝向/缩放/抬臂斜坡节奏逐分量核对（重点核对 display 常量矩阵与手链怪癖）。
2. **波次冒烟**：波次发射 → 小队持剑掠袭 → 跨档改 `stormWaveDamage` → 新波换材质、在飞波次保持旧档；尸体不持剑。
3. **性能抽查**：131k 满编无波次帧加剑前后帧时间无可見差（预期：纯 +0.04% 早退顶点）。
4. **光影包**：至少一包（Photon/Complementary 任一）下剑随合并路径正确渲染并进阴影。

