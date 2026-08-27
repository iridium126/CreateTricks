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

> 实施勘误注记（0.2.6 周期）：本版本 NeoForge 的 RenderLevelStageEvent 全部为
> `AFTER_*` 系、**不存在 BEFORE_ENTITIES**；实际落地取最早的 `Stage.AFTER_SKY`
>（见 §15）。另注意 Stage 是普通常量类而非枚举、不能作 switch case 标签。

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
- **D2 前移触发条件**（留观 → **已触发并实施**）：快转视角下 MODEL 入场迟一帧已实测确认；计算块前移至 AFTER_SKY 已落地（NeoForge 无 BEFORE_ENTITIES；勘误与完整定案链见 §15）——数据通路如预期：钩子侧全部经由显式绑定，无指针局部依赖。
- **待运行验收**（M1/M2 通过线，需游戏内执行）：Photon 下 /summon minecraft:allay 并排对比矩阵（正午/夜晚/洞穴 × dance/spin/hold × 近/中/远景淡出缘）、无双绘抓帧、Complementary 冒烟、SHADOW_QUALITY 低配不崩属 S 轨另验。


---

## 13. 对齐审查与运行期修复记录（0.2.4 → 0.2.5 周期）

> 对照基线：.refs/Flywheel 1.0.6（让位式，无共存实现）、.refs/iris-flw-compat 2.4.0、
> .refs/iris-veil-compat、.refs/Iris（与运行时 1.8.14-beta.1 同源）。审查方法：静态逐行对照 +
> 运行时 jar 离线全管线复现 + 游戏侧多跳捕获（ProgramCompileCapture / GlShaderSourceLogger /
> GlmShaderSourceCapture / TransformPatcher 缓存转储，均已随问题闭环移除）。

### 13.1 审查结论

| 维度 | 结论 |
|---|---|
| 执行窗口 | 与 Flywheel LevelRendererMixin 完全一致（ldc=blockentities，priority 1001） |
| 编译链配方 | accessor→resolver→注入→jcpp→createShader 七步同构；偏差与修复见 13.2 |
| 注入器骨架 | 与 veil GlslTransformerVeilPatcher 同构；glsl-transformer 停留 2.0.1 为既定决策 |
| 绘制期协议 | flw 式 apply 后补传 iris_* 矩阵/法线一致，且中性化 entityId/entityColor 超出参考 |
| 槽位隔离 | Flywheel SSBO 0–7 / UBO 0–4 / 纹理 T0–T9 数字级核对；TBO 化后采样器单元 10–13 经自有 MixinProgramSamplers 进入 Iris 预留集（flw 同款手法，原 13.4 观察项已闭环） |
| 阴影轨 | **已实施**：compileShadow 以 ShadowEntities 优先、plain Shadow 兜底解析，invokeCreateShadowShader 编译第三程序；MixinIrisShadowRenderer 锚定 ldc="draw entities"，并以 shouldRenderEntities 门控（实体阴影被包禁用时粒子同步停投，原生悦灵同等待遇） |

### 13.2 已落地修复

1. **P0 指令继承**：合成程序名永远匹配不到 name-keyed 包属性（ProgramDirectives 按
   source.getName() 查表，ShaderCreator:53 取 orElse(fallback)），原注释"包指令优先"
   与事实相反。修复：makeSource(...) 链 .withDirectiveOverride(refProgram.getDirectives())
   （veil 式整体继承；flw 式 ProgramDirectives mixin 为备选）。日志输出 inherited alphaTest。
2. **P2-1 矩阵折叠撤销（A1 包络）**：改发相机相对 level-space 顶点（cmi_VertexLevel），
   删除 gl_ModelViewMatrix→mat4(1.0) 重写，ftransform 显式三连乘——对齐两参考的
   "保留真实矩阵"契约，包内非定位用途的 MV 不再拿到单位阵。
3. **P2-2 双程序拆分**：cutout（指令全继承）/ ghost（私有指令副本经
   CMIProgramDirectivesAccessor getter 短路钉死标准透明混合）；绘制拆两次单段 multi-draw；
   ghost 保持 L0 深度写入。
4. **SSBO → TBO 迁移（根因规避）**：游戏内存在无法离线复现的环节会在变换产物进入驱动前
   剥除接口块声明（cache_135 转储证明变换输出完好、驱动仍报 C1503@使用行；本地同 jar 全管线
   复现保真，sodium/FML 差异为唯一环境变量）。合并程序改为四个 samplerBuffer
   （Sorted 用 usampler）+ texelFetch——普通全局声明经全链路实证不可剥除；引擎侧惰性 TBO
   视图（ParticleBuffers.mergedTbos，单元 MERGED_SAMPLER_UNIT_BASE=10..13，绘制后归位）。
   L0 自绘路径不变。
5. **诊断基建**：describeCompileFailure 解开 FakeChainedJsonException 的恒空消息包装
   （Iris 以 super("", e) 包装 ShaderCompileException，裸 toString 必然丢失驱动日志）；
   invokeCreateShader / merge ready 观测行接入 /cmip shaderpack status。

### 13.3 工程备忘（本轮教训）

- Mixin 保留整个已声明 mixin 包：duck 接口必须放在包外（本项目新增 accessor 包），
  否则常规代码直接引用即 IllegalClassLoadError；
- 构造器 init 的 HEAD 注入（super 之前）处理器必须 static；
- Route-A 的 withDirectiveOverride 是同一指令对象的共享引用——需要差异化时必须先以
  withOverriddenDrawBuffers(getDrawBuffers()) 克隆（ghost 混合钉死依赖此细节）；
- glsl-transformer AST 不保留预处理指令：注入源的 #define 一律改为 const 声明
  （姿势块已同步改造，L0 共享语义不变）；
- 原版 Program.compileShader 失败抛 IOException，经 iris MixinProgram 转成
  ShaderCompileException(名+扩展名, 驱动日志)——异常文件名带 .vsh 即源于此。

### 13.4 遗留观察项

- gtexture 单元 0 图集不归还（原版每绘制重绑，暂无实害）；
- "接口块剥除者"身份未定（与 sodium/FML 活跃相关），TBO 化已绕开；未来若需真 SSBO 再议。

---

## 14. 实施记录：MODEL cutout 近先序绘制（方案 A / R5a）

> 决策方式：grilling 逐项确认（Q1–Q6）；状态：**已实施（compileJava 通过）**；运行验收待游戏内（L2 观察点见 14.5）
> 关联：§13 绘制协议的延伸；D2 一帧滞后体系不受影响（数据通路未动）

### 14.1 问题与目标

- 症状：玩家视角进入约一万悦灵的重叠区域严重掉帧，GPU 压力集中在片元阶段；
- 根因：cmd2（cutout）与 cmd3（ghost）共享远→近排序排列。该顺序是 ghost 透明合成的
  承重结构，却让不透明段吃满最坏情况过绘制——每像素被着色的片元数 = 平均重叠层深 k，
  远处悦灵的包实体 fsh 全额执行后被更近几何整体覆盖；
- 约束：包 fsh 逐片元成本不可触碰（PATCH_FRAG=false 契约），唯一杠杆是减少进入包 fsh 的片元数。

### 14.2 定案链（grilling 裁定记录）

| 决策点 | 定案 | 主要否决项 |
|---|---|---|
| 实现变体 | 键序不动 + 仅合并路径 cmd2 反向取槽（近→远） | dense 近似（H_k≈ln k 残留）；双份排序数组（机器翻倍） |
| L0 路径 | 完全不动：合并 multi-draw 不拆分，model.vsh 零改动 | L0 同步改造（无明显压力） |
| N_model 通道 | **R5a**：sort 缓冲尾部元数据槽，capture 发布，结构性同代 | 第 5 TBO 直读 indirect（aborted 帧存在跨代错位窗口）；同单元换绑（cmd2 同绘制内需 Sorted+N 并存，数学不可行）；滞后 CPU 回读（ΔN 代际错位） |
| 区分机制 | 逐次绘制 uniform `cmi_ReverseInstance` | gl_BaseInstance（驱动/包环境可用性风险） |
| 验收口径 | L1 静态本轮 + L2 运行待验（§14.5） | 抓帧逐像素 diff（对单次着色改动过重） |

### 14.3 实现清单

1. `ParticleBuffers`：sort 缓冲分配 `(cap+1)*8`；尾槽（下标 = cap）为保留元数据格。
   安全区论证：radix 散射写 `[0, N_total)`、正向读 `< N_total`、反向读 `< N_model ≤ N_total`
   ——元数据槽永不被有效读取触及；L0 着色器不读该槽。
2. `capture.comp`：新增 IndirectBuf 声明与 `uMetaSlot`，发布
   `N_model = indirect.cmd[IDX_CNT_MODELOP]` 至 `kv[uMetaSlot]`。引擎侧无条件绑定合法 sort
   缓冲（finalPerm ≥ 0 取提交缓冲，否则 scratch 0），meta 与 perm 同缓冲同代——aborted 帧
   二者一起停留旧代，不存在"新计数配旧排列"的组合。
3. 合并顶点源（`ShaderPackProgramCompiler.buildMergedSource`）：新增
   `uniform int cmi_MetaSlot / cmi_ReverseInstance`；实例解析改为
   `slot = (reverse==1 && iid<N) ? N-1-iid : min(iid, N-1)`。钳制把 aborted 帧下
   "indirect 计数 > 已提交分区 N"的可能越界读（真 UB）降级为一帧无害重复实例。
4. 钩子（`CMIPackEntityMergeHook.uploadSegmentMode`）：cutout=true（近先序）、ghost=false
   （保持正向远→近混合语义）、shadow=false（深度主导，顺序无关）。status 行追加
   "model near-first" 供 `/cmip shaderpack status` 观测。
5. 明确不动：keygen/radix/census、L0 全链路、预留集（维持 4 单元）、Iris 编译交互面。

### 14.4 深度排序能否优化（分析结论：维持现状）

1. **成本份额**：现有管线为单遍计数排序（RADIX_PASSES=1，O(N+512)），万级条目在 GPU 上
   为微秒级，相对片元压力可忽略——优化排序本身对本次问题收益上限≈0；
2. **微优化全部否决**：时间相干复用上一帧排列（失效检测比排序更贵）、warp 聚合原子
   （驱动已做）、缩减 bin 数（9 位键已是最小信息量）；
3. **新依赖关系**：本方案之后排序从 ghost 的附属升级为 cmd2 的承重结构——反向读的
   near-first 收益以"分区按深度带有序"为前提；若去除排序，early-Z 收益从 ~1× 退化到
   无序 H_k≈ln k；
4. **唯一语义级杠杆（仅记录，不立项）**：ghost 若接受"仅最近壳层参与混合"（near-first
   到达时 depth-write 天然使每像素只剩一层），blend 片元成本随 k 归一；但混合不可交换、
   乱序会帧间闪烁，确定性近→远仍需排序——即该取舍也不解除对排序的需求，且属视觉变更，
   应单独走 grilling。

### 14.5 预期收益与 L2 待验观察点

- **量化预期**：重叠区每像素 cutout 着色次数 k → ~1（k 为平均重叠层深；k=30 即约 30×）；
- `/cmip shaderpack status` 应显示 "(…, model near-first)"；
- ghost 合成并排截图应与改前逐像素一致（cmd3 正向读取未动）；
- 10k 重叠场景前后 hook 环 GPU ms 对比（externalHookGpuMs 已并入节流样本）；
- 抓帧核对 shaded fragments 数量级；
- 已知边界：aborted 帧 + 计数增长同时发生时，多余实例退化为一帧重复渲染（钳制保证无越界读）。

---

## 14. 对齐修复第二轮（审查驱动，A–F）

> 独立复审以 .refs/Flywheel 1.0.6 / iris-flw-compat 2.4.0 / iris-veil-compat / Iris 运行时同源逐行核对后落地的六项修正。

| # | 结论与处置 |
|---|---|
| A | makeSource 原丢弃 geometry/tessControl/tessEval——改为三处合并程序（cutout/ghost/shadow）全部透传 refProgram 的可选阶段，对齐 flw 的 programSourceOverrideVertexSource；仅顶点段被注入，几何段原样消费注入后 varying |
| B | 阴影 fallbackAlpha 按变体拆分：ShadowEntities → GREATER 0.1（逐位对齐原生 SHADOW_ENTITIES_CUTOUT）；plain Shadow → 保持 ALWAYS（其原生子键无单一忠实值，且与 flw 一致） |
| C | GLSL 下限 430→400（两参考同为 400）：注入器 floor、cmiTransformer 词法 Version.GLSL40、合并源占位头三处同步；"std430 SSBO"注释失实删除——TBO 迁移后最高语言需求仅 usamplerBuffer（330） |
| D | gl_Vertex=level-space 为固有契约而非缺陷：flywheel 式 inverse(proj*mv) 反变换在本引擎无意义（全实例共享单一 gbuffer MV，无法重建逐实例模型系），保持数学不动；契约边界（模型局部坐标语义不支持、判别方法）固化于注入器 javadoc 与本文档 |
| E | 新增 META-INF/accesstransformer.cfg 去 MODEL_VIEW_MATRIX 的 final；编译后按 flw 同款播种 dummy Uniform("ModelViewMat",10,16)；applyGuarded 裸 glUseProgram 回退整体删除——apply() 异常上抛走钩子失败记录路径，当帧回落自绘，静默降级面清零 |
| F | MixinIrisShadowRenderer 补 @Final @Shadow shouldRenderEntities 门控（用户裁定取实体门控而非 flw 的方块实体门控：粒子内容属实体流）；玩家单独开启的包不给非玩家实体影子 |


---

## 15. 实施记录：计算块前移 AFTER_SKY（D2 触发，grilling 定案链）

> 决策方式：grilling 逐项确认；状态：**已实施（compileJava 通过）**；游戏内验收清单见文末

### 15.1 症状与根因
- 症状（用户实测确认）：光影包路径快转视角，新入视野的悦灵迟一帧出现；其他类型粒子与 L0 路径均正常。
- 根因：`CMIPackEntityMergeHook` 在 renderLevel 中段绘制模型时消费 `lastFinalPermId`——那是上一帧 keygen 针对**上一帧相机**算出的视锥剔除/深度带排列；其晋升发生在帧尾 AFTER_LEVEL。位置数据与变换矩阵均为当帧，故滞后只体现在"可见集 + 排序键"，与症状签名完全闭环。
- 文档呼应：§3.5 D2 早已预判该偏差并给出"计算块前移"预案，留观触发条件正是"实测出现可感知一帧滞后伪影"——本轮成立。

### 15.2 定案记录（Q&A）
| 决策点 | 定案 | 备注 |
|---|---|---|
| Q1 前移时点 | **AFTER_SKY**（主轨） | NeoForge 21.1 无 BEFORE_ENTITIES（全部 `AFTER_*`，Stage 为常量类非枚举）；阴影轨在 renderSky **之前**运行（Iris MixinLevelRenderer 实测），任何事件点都覆盖不到 → 阴影轨维持现状、记为已知例外 |
| Q2 拆分方式 | **最小拆分，绘制全留 AFTER_LEVEL** | 计算段（请求排空/烘焙维护/storm/reset→grid→update→emit→keygen→radix→capture/fence/swap/promote）整体随 beginFrame 迁移；成功才提交纪律原样搬迁 |
| 计时口径 | 双 GL_TIME_ELAPSED 环求和入节流预算 | computeTimer@AFTER_SKY + drawTimer@AFTER_LEVEL（各读 3 帧旧样本）+ externalHookGpuMs；节流语义与拆分前等价 |
| latch 生命周期 | 清理点挪至绘制段尾部 | merge hook 在同帧内晚于 AFTER_SKY 早于 AFTER_LEVEL 触发，仲裁语义不变（aborted 帧 frameArmed 门禁防脏绘） |
| 验收标准 | 目测 + status 观测项 + 回归清单 | 见 15.3 |

### 15.3 新增观测面与验收清单
- `/cmip shaderpack status` 新增 `permutation:` 行：gbuffer 消费应为 age **0f**；shadow 轨打印 `>=1 expected`（设计例外可视化了）。
- 游戏内待验：① 光影包下快转视角无入场闪烁；② aborted 帧（可用 /cmip storm start+stop 与瞬时高负载诱导）不死粒子复活、storm stop 下帧生效不变；③ 双环 GPU 时间之和 ≈ 拆分前单环值（EMA 对比）；④ L0 无光影路径观感零变化。

### 15.4 行为差与风险登记
- 同帧序列化取代跨帧解耦：hook 绘制前本帧排序必然完成（GPU 提交顺序保证），代价为微秒级（§14.4 已证），换取正确性。
- 绘制段失败不再连带丢弃模拟提交（swap 已提前）：池/计数器一致性不受影响，仅当帧渲染损失部分片段——较旧单相行为更稳。
### 15.5 回归修复：L0 绘制相池代际错绑（拆帧次日修复）
- 症状：光影包正常无闪烁；关闭光影包后悦灵持续闪烁。
- 根因：原实现 swap 在绘制**之后**，drawPass/drawModels 绑"写侧"即刚写好的当代池；计算块前移后 swap 提前到 AFTER_SKY 尾部，绘制时读侧才是新世代——L0 自绘路径仍绑写侧（=上一代），当帧置换/计数的槽位语义作用在旧代行数据上，逐帧错位渲染残行 → 闪烁。光影包路径无此病，因其 TBO 视图本就钉在 `particleReadBufferId()`。
- 修复：drawPass/drawModels 改绑 `bindParticleRead(0)`（已提交世代）；ParticleBuffers 常量注释同步勘误。绑定全表复查：其余 bindParticleWrite 调用点均在 swap 前（grid/update/emit/keygen），语义不变。
### 15.6 二次回归修复：L0 全灭 = 绑定点与世代选择混淆
- 症状：光影包正常；关闭光影包后悦灵**完全不可见**。
- 根因：15.5 的修复选对了世代（读侧）却挂错了绑定点——L0 渲染 vsh 的池接口块声明在 `BIND_POOL_WRITE`(=1，变量名 ParticleRead 实指"新数据")；把读侧缓冲挂到绑定号 0 使声明槽位空置，顶点拉取读到未绑定内存 → 全部实例不可见。本引擎既有约定：Java 选代际、绑定点参数须等于着色器声明。
- 修复：新增 `ParticleBuffers.bindNewestPool(binding)`（写侧→swap 后即读侧的"最新已完整写入"语义），drawPass/drawModels 以 `PARTICLE_BB_WRITE` 绑定点调用；注释固化两次教训。
