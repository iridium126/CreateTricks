# G-self 设计文档 —— 粒子引擎光影包兼容路线

> **命名映射**："G-self"仅为路线代号；代码与配置一律使用领域命名——配置项 shaderPackIntegration、命令 /cmip shaderpack status、类 CmiShaderPackHook / ShaderPackProgramCompiler / ParticleVertexInjector。

> 状态：**已实施（M1+M2，compileJava 通过）**——实施记录见文末 §12；运行验证（Photon 并排矩阵）待游戏内进行
> 证据链：主文档附录 B-1/B-2/B-3/B-4（S1 证伪、X1 塌缩、iris-flw-compat 分析、Round-4 终审）
> 参考实现：.refs/iris-flw-compat（MIT）、.refs/Colorwheel（LGPL，仅借鉴思路）、本引擎 MistIrisHook（已验证的合成窗口）

---

## 1. 目标与非目标

### 目标（按里程碑排序）
- G1：MODEL 材质在任意已装光影包下**正确合成**——可见、位置正确、接受包的雾与色调映射
- G2：程序经 Iris ShaderInstance 编译——包上下文 uniform（雾参数、相机、时间）由 Iris 自动供给
- G3：片元光照向包语义对齐（先通用 terrain 语义，后按包家族微调）
- S 轨：悦灵粒子的阴影投影（clrwl_shadow / ProgramId.Shadow 同构机制）

### 非目标
- 像素级延迟 G-buffer 契约写入（附录 B-4 已终审判死：钩子点附件为 RGBA8）
- CPU 姿势计算（十万实例约束）
- 逐包补丁文件分发（被 G-self 的运行时合并取代）
- sprite/additive 材质的包内增强（维持现状，L0 可接受；后续可选迁移）

## 2. 总体架构

双路径并存，运行时按环境选择：

    无光影包（或合并失败回退）：
      现有自绘管线（compute 模拟 + keygen 剔除 + 自托管程序间接绘制）——完全不变

    有光影包（G-self 启用）：
      模拟层不变（update.comp 全套照旧，含新增的可视变换写出）
      绘制层：
        执行点 = iris-veil-compat 世界渲染钩子（HOOK_ID_SCENE，雾场已验证）
        程序   = Iris ShaderInstance（由 包片元 + 注入后的我们顶点 合并编译）
        数据   = 现有粒子池 SSBO / 变换 SSBO / 发射器头，我方绑定高编号槽位

关键转变：从"事件点裸自绘"改为"钩子点 + Iris 管理的程序"。

## 3. 执行点设计（G1）

### 3.1 选型依据
Round-2~4 证明 RenderLevelStageEvent 系裸钩子在该管线中不可用（写入落入 RGBA8 天空缓冲）。
而 MistIrisHook 经 iris-veil-compat 的世界渲染钩子把雾场画进 colortex0，已被 Photon 的
composite 链正确采样——这是本项目中**唯一被运行时验证过存活的合成窗口**。

### 3.2 设计
- 新增 CmiIrisWorldHook（参考 MistIrisHook 骨架）：在 HOOK_ID_SCENE 时点，
  若 G-self 启用且有存活粒子，依次提交**全部四段**绘制：
    OPAQUE 精灵（cmd1）→ 模型 multi-draw（cmd2+3）→ ALPHA 精灵（cmd4）→ additive（cmd0，末位）
  ——additive 不再留守原路径：Round-2 已证明 AFTER_LEVEL 裸窗口对任意内容产生
  "滤镜式"错误表现，精灵类没有豁免理由；且分裂窗口会引入双路径行为差异。
- 绘制前绑定我方 SSBO/纹理到约定槽位（见 §5）；绘制后完整恢复状态（沿用引擎 finally 纪律）。
- 原 AFTER_LEVEL 路径在 G-self 启用期间跳过全部绘制提交（渲染仲裁，防双绘
  ——教训源自性能审查风险 2）。

### 3.5 帧相位（决策点 D2，新增）
renderWorldBorder 钩子位于 renderLevel **内部**，而现有计算块（模拟/keygen/排序）
挂在 AFTER_LEVEL——该事件在 renderLevel 返回后才触发。若计算不动，钩子只能画到
上一帧的数据（一帧延迟 + 姿势滞后）。定案：**将整个计算块前移至帧首阶段**
（RenderLevelStageEvent.BEFORE_ENTITIES 或更早的自定义时点），保证钩子消费当帧
模拟结果；ping-pong 交换、fence 轮询与节流逻辑不受影响（它们只依赖相对顺序）。

### 3.3 已知限制
- 该窗口晚于 deferred 表面光照计算：粒子表面不接受包的延迟光照/彩色光。
  对全亮语义的悦灵（blockLight=15）影响有限；G3 再评估是否补光项。
- **深度遮挡语义未定义（M1 必验项）**：compat framebuffer 是否挂接世界深度附件
  尚未证实——若无，硬件深度测试失效，粒子将隔墙可见。备选方案：着色器手动采样
  主深度纹理做深度比较（雾场已有同款主深度纹理手动采样的成熟用法）。

### 3.4 与 iris-flw-compat 执行窗口的关系（澄清）

两个 compat 同作者（top.leonx.*）但执行模型不同：

- iris-veil-compat：注册式固定点钩子——mixin 进 LevelRenderer.renderLevel
  （renderWorldBorder 之后），VeilCompatRegistry.renderWorldHooks 回调，
  每钩子经 bindCompatGbufferFramebuffer 指定 drawBuffers 并自动恢复主目标；
- iris-flw-compat：无自有 LevelRenderer 钩子，复用 Flywheel 原生绘制时机
  （实体阶段、被追踪流内），且必须实现 Flywheel 后端接口方可使用。

对本设计的影响：
1. G1 执行点（veil 钩子）与 G2 编译链配方（flw-compat 式 ShaderInstance）**正交可拼**
   ——ShaderInstance 的 uniform/目标管理由 Iris 负责，与使用窗口解耦；
2. veil 钩子的 bindCompatGbufferFramebuffer 顺带规避 Round-4 的 RGBA8 天空缓冲问题
   （由钩子负责绑定正确目标并恢复）；
3. 若 G3 追求真延迟受光，需追加一个 flw-compat 式早期窗口 mixin（唯一回归的自研
   mixin）；S 阴影轨同理需参考两者 MixinShadowRenderer 自建 shadow 窗口钩子。
## 4. 程序编译与合并（G2）

### 4.1 参考配方（IrisProgramLinker，MIT）
1. 经 IrisRenderingPipelineAccessor 取当前 ProgramSet
2. ProgramFallbackResolver 解析 ProgramId.Block（普通）/ ProgramId.Shadow（阴影轨）——
   resolver 自带回退链，**天然跨包**
3. vertPatcher.patch(包顶点源, 我方顶点源, ...) —— 我方姿势数学注入包顶点模板
4. JcppProcessor.glslPreprocessSource + 标准 environment defines
5. new ProgramSource(名称, 新vsh, ..., 新fsh, programSet, properties, blendOverride)
6. pipeline.callCreateShader(名称, source, programId, alphaTest,
   **IrisVertexFormats.TERRAIN**, fogMode, ...) —— 得到 Iris ShaderInstance
7. 包装为兼容 GlProgram 接口的适配器供引擎统一调用

### 4.2 我方侧改造（ParticlePrograms）
- 抽象出 ProgramSourceProvider 接口：默认实现 = 现有自托管编译；
  Iris 实现 = 上述配方。ready() 聚合两者。
- 顶点源码拆分：姿势数学块（现 model.vsh 主体）保持纯函数化，
  注入段只做 varying 桥接（喂包片元所需的 vertexColor/uv2/normal 等 varying）。
- 片元 v1 策略：直接采用包片元（PATCH_FRAG=false 同款）——我方只需保证
  varying 集合满足其输入。这自动获得包的光照/雾/色调语义。

### 4.3 顶点格式策略（决策点 D1）
callCreateShader 固定要求 IrisVertexFormats.TERRAIN。两案：
- 方案 A（推荐）：构建一个 TERRAIN 布局的占位属性 VAO（每顶点固定/索引化占位值），
  真实数据仍走 SSBO 顶点拉取；属性仅供格式合法性与包代码可能的部分读取。
  成本低；风险 = 包顶点若实际采样某属性（如 mc_Entity）得到占位值的影响需逐包核对。
- 方案 B：将几何烘焙为真实 TERRAIN 属性流（含 uv2 光照等），放弃纯 SSBO 拉取。
  更"正规"但要为每实例展开属性（生成风暴回归 Java 分配）——否决，除非 A 证实不可行。

### 4.4 光图（uv2）供给
悦灵语义 = blockLight 15 + 世界天空光。方案 A 下作为 uniform/常量注入注入段，
天空光分量按粒子出生时 CPU 采样或相机位置近似（G2 先取常量满档，G3 精化）。

## 5. 数据绑定与采样器协调

- 绑定槽位：避开 Iris 打包声明区与 Flywheel 槽位（若同栈），选高位（≥12）并在
  每次 hook 提交前重绑（防御性，教训来自 MixinProgramSamplers 的单元冲突案例）。
- 纹理单元：我方图集/碰撞纹理使用 Iris 分配区之外的单元，提交前 activeTexture 归位。
- 全部绑定动作集中在 CmiIrisWorldHook 单点，便于审计与恢复。

## 6. 阴影轨（S，独立里程碑）

- 参考 callCreateShadowShader + MixinShadowRenderer：在 shadow 渲染上下文
  （RenderLayerEventStateManager 式阶段标记）以 ProgramId.Shadow 解析源并编译影子变体；
  片元无语义要求（深度即可），顶点复用同一注入姿势数学。
- 产出即 L2a：地形接收悦灵投影。验收：晴天地面影子随时间方向正确。

## 7. 按包家族策略

- 首发目标：Photon v1.3b（用户实测环境）
- ProgramFallbackResolver + ProgramId.Block 的兜底链预期覆盖多数包；
  家族特化（transformer 追加分支）仅在实测偏差时按包追加——与彩色光适配器的
  维护模式一致。
- 回退链解析失败（极简包无 Block 源）→ 合并失败 → 引擎回落现有自绘路径（L0）。
- 与 Colorwheel 并存：cmi_* 槽位与 clrwl_* 槽位互不可见、互不冲突，可同列表共存
  （Flywheel 内容走 clrwl 路径，本引擎走 cmi 路径）。

## 8. 开关与回退

- ClientConfig.particles 段新增 gselfEnabled（默认 auto：有包且合并成功才启用）。
- 合并失败日志一次性告警 + 自动回落，不阻塞游戏。
- /cmip spike 保留；新增 /cmip gself status 显示当前路径与最近错误。

## 9. 里程碑与验收

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 | CmiIrisWorldHook + 三段绘制迁移 + 仲裁 | Photon 下粒子可见、无双绘、雾/色调映射生效 |
| M2 | ShaderInstance 编译链替换（Block 解析 + 顶点注入） | 粒子表面呈现包光照语义（非自算漫反射）；F3+T 重编译生效 |
| M3 | 片元光照对齐微调（视需要 PATCH_FRAG 或 varying 精化） | 附录 B 系列并排对比达观感一致 |
| S | 阴影变体 + MixinShadowRenderer 式接入 | 地面影子方向正确、SHADOW_QUALITY 低配不崩 |

量化预算参照附录 A 结论：G-self 增量（相对现路径）预期 < GPU 帧成本 8%；
M2 起以 /cmip gself status + 附录 A 场景矩阵复核。

## 10. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| TERRAIN 占位属性被包逻辑实际消费导致伪影 | 个别包表现异常 | 逐包核对注入段 varying 消费；必要时升级方案 B |
| iris-veil-compat 钩子在部分包/配置下时点偏移 | 合成异常 | 复用雾场的多 HOOK_ID 机制（scene/HDR/raw）按包选择 |
| Iris 版本追逐（accessor 触点变更） | 编译失败 | 访问器集中管理 + 版本签名校验 + 自动回落 |
| 双路径行为差异引发认知负担 | 维护成本 | 文档明确"无包=自绘，有包=G-self"；/cmip gself status 可观测 |
| 槽位冲突（包 SSBO/图像与我方高编号槽） | 渲染错乱 | 提交前重绑 + 冲突检测日志 |
| compat 目标深度附件语义未知 | 粒子隔墙可见/遮挡失效 | M1 先验证；备选着色器手动主深度纹理采样（雾场已有同款用法） |

## 11. 决策记录依赖

本文档取代主计划中的阶段四（S1 契约镜像）与阶段三的执行点部分；
阶段三的阴影能力并入 S 轨。生命周期税结论（附录 A）使 P-A′ 降为备选。

---

## 12. 实施记录（M1+M2，compileJava 通过）

经 grilling 流程逐项决策（Q1–Q5 + 折叠项确认），与原设计的差异与落地形态如下。

### 12.1 与本文件的决策修订

| 原条目 | 实施定案 | 理由 |
|---|---|---|
| §3.2 四段绘制全部迁入钩子 | **仅 MODEL 迁移**；精灵三段（additive/OPAQUE/ALPHA）留守 AFTER_LEVEL 自绘路径 | 用户裁定，回归 Q9 冻结语义。已知限制：精灵恒合成于悦灵之上且读不到其深度 |
| §3.5/D2 计算块前移 | **不前移**：钩子绑定最新完成池（readIndex 侧）+ 新增 lastFinalPermId 持久化排序缓冲 id；单帧滞后待实测（观察点：快速横移抖动/边缘弹入/相机急摇），出现可感知伪影再做前移补丁 | 验证帧相位前移是否必要 |
| §3.3 深度回退=着色器手动采样 | **双路自动**：钩子点纯 GL 查询 compat FBO 的 DEPTH_ATTACHMENT；有→按模式硬件深度测试；无→自绘回退模式的 model.fsh 走 uMainDepth 手动采样比较（雾场同款）。**包程序模式在无深度附件时整体让位给自绘回退**——遮挡正确性优先于受光 | M1 必验项的工程化 |
| §4.3/D1 方案 A | 确认 A，风险精确化为"包自定义 varying 携带垃圾"；但注入器采用**前置块 + gl_Vertex/ftransform 重定向**机制后，包顶点逻辑实际消费我们的真值，风险面进一步收窄 | Q3 |
| §7 验证范围 | Photon v1.3b 为 M2 通过门；Complementary 仅冒烟 | Q4 |
| §4.1 注入器来源 | 以 iris-veil-compat 的 GlslTransformerVeilPatcher（446 行，glsl-transformer **2.0.1** API，MIT）为基座改造，非 iris-flw-compat 版（避免 2.x→3.x 迁移税）；glsl-transformer 由 irisveil JarJar 提供，本项目仅 compileOnly 引入、**不打包分发**（该库为 AGPL v3） | Q5 + 事实核查 |

### 12.2 交付物清单

- client/particles/shaderpack/CmiShaderPackHook.java —— 执行点：注册 4 个家族 drawBuffers 世界钩子（复用 MistInjectionProfiles 选族）；双模式绘制——**pack programs**（编译成功且有硬件深度）走两条 Iris ShaderInstance 分别绘 cmd2（cutout）/cmd3（鬼影），否则**自绘回退**（引擎现有 modelRender 经钩子提交，无深度附件时启用 uMainDepth 手动深度）；独立 GL_TIME_ELAPSED 环并入节流 EMA；成功绘制即置仲裁闩。
- client/particles/shaderpack/ShaderPackProgramCompiler.java —— 编译链替换：ProgramSet → ProgramFallbackResolver(ProgramId.Block) → 注入 → jcpp → 双 BlendModeOverride（opaque=包默认 / ghost=new BlendMode(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)）→ invokeCreateShader(TERRAIN, FogMode.OFF)；gtexture 采样单元自动发现；失败粘滞到 pipeline 实例更换。
- client/particles/shaderpack/ParticleVertexInjector.java —— AST 注入器（基座 MIT 归属保留）：前置 SSBO(绑定 12–15)/uniform/globals + main 前缀块；重写 gl_Vertex→cmi_VertexView（视空间预烘）、gl_ModelViewMatrix→mat4(1)、ftransform→proj·viewPos、gl_Color→cmi_Tint、gl_MultiTexCoord0/1→常量 UV/满档光照、gl_Normal→世界法线、扩展属性中性化（照抄参考实现）。
- shaders/particles/chunks/allay_pose.glsl + model.vsh 重写 —— 姿势数学单一事实源：cmiAllayPartTransform / cmiKeyframeColor / 旋转件，L0 与合并程序共享；#pragma cmi_include 由 ParticlePrograms.loadResolved 解析。
- CMIParticleEngine 改造 —— lastFinalPermId 提交点晋升（success-only）；仲裁闩 hookModelsDrawn（钩子置位→跳过 drawModels→capture 前 re-arm）；externalHookGpuMs 并入节流样本；状态字段三枚供命令读取。
- ClientConfig / CMIParticleCommand —— particles.shaderPackIntegration（默认 true=auto）；/cmip shaderpack status 显示 config/irisveil/path/depth 与最近回退原因。
- CreateManaIndustryClient / build.gradle —— IRISVEIL_ACTIVE 门控 init；compileOnly io.github.douira:glsl-transformer:2.0.1 + mavenCentral。

### 12.3 已知偏差与技术备忘

- **apply() 哨兵**：NeoForge 映射下 ShaderInstance.MODEL_VIEW_MATRIX 为 final，无法像参考包装器那样预置 dummy；改为 try/catch 回退裸 glUseProgram(shader.getId())——我方后续上传不依赖 vanilla uniform 状态。
- **鬼影段混合**：Block 程序默认不透明，ghost 段以 BlendModeOverride 强制标准透明混合并保留深度写入（L0 同款取舍：隔墙遮挡正确，跨段排序由绘制顺序固定）。
- **D2 前移触发条件**（留观）：若实测出现可感知一帧滞后伪影，再实施计算块前移至 BEFORE_ENTITIES——数据通路已就绪（钩子侧全部经由显式绑定，无指针局部依赖）。
- **待运行验收**（M1/M2 通过线，需游戏内执行）：Photon 下 /summon minecraft:allay 并排对比矩阵（正午/夜晚/洞穴 × dance/spin/hold × 近/中/远景淡出缘）、无双绘抓帧、Complementary 冒烟、SHADOW_QUALITY 低配不崩属 S 轨另验。

