# 高性能 GPU 粒子引擎 — 开发文档

> 模组：CreateManaIndustry（机械动力：魔法工业）
> 版本基线：`0.2.3-fix`（Minecraft 1.21.1 / NeoForge 21.1.227 / Java 21）
> 状态：**可用**（用户实测：开/关光影包均正常显示粒子；流时长=真实秒数；编译打包通过）

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
| `client/particles/engine/CMIParticleEngine.java` | 引擎单例：帧钩子、`spawn/stream/clear/budget/stats`、ping-pong 编排、三 pass compute、间接绘制、自适应节流、`close()` |
| `client/particles/engine/ParticleBuffers.java` | GPU 资源：双缓冲粒子 SSBO、发射命令环 ×3、计数器环 ×4、发射器头 SSBO、间接命令 SSBO、无属性 VAO；上传/回读/解绑/free |
| `client/particles/engine/ParticlePrograms.java` | 自托管 GLSL：读 `assets/.../shaders/particles/*`，原生编译/链接 4 个程序（reset/update/emit/additive），脏标记+F3+T 重编译 |
| `client/particles/engine/ParticleFrameProfiler.java` | 帧耗时 EMA + 迟滞节流控制器（预算默认 5ms） |
| `client/particles/engine/ParticleShaderReloadListener.java` | 客户端资源重载监听（F3+T → 请求重编译着色器） |
| `client/particles/emitter/EmitterSpec.java` | 不可变发射器规格 + builder；`pack()` 打成 16×vec4 GPU 头 |
| `client/particles/emitter/EmitterShape.java` | `POINT / BOX / SPHERE / CONE` |
| `client/particles/emitter/EmitterPresets.java` | 6 个内置蓝本：`mana_spark / ember / ash / soul_flame / mana_burst / flood` |
| `client/particles/command/CMIParticleCommand.java` | `/cmip spawn|stream|bench|clear|stats|budget`（NeoForge 客户端命令） |

接线（`CreateManiaIndustryClient.java`）：原生 `RenderLevelStageEvent.AFTER_LEVEL` 帧钩子、进出世界/跨维清池、`GameShuttingDownEvent` 释放资源。
配置（`config/ClientConfig.java` — `particles` 段）：`enabled`、`maxParticles`(2_000_000)、`frameBudgetMs`(5.0)、`autoThrottle`(true)。

### 着色器 — `src/main/resources/assets/createmanaindustry/shaders/particles/`

| 文件 | 用途 |
|---|---|
| `reset.comp` | 1 线程：计数器归零 + 间接绘制实例数归零 |
| `update.comp` | 物理积分（重力/恒加速度/风/阻力）、寿命、致密化回收、原子追加 |
| `emit.comp` | 读 CPU 发射命令，按形状/随机初始化新粒子 |
| `additive.vsh` | `gl_InstanceID` 从粒子 SSBO 取数、相机朝向 billboard、尺寸/颜色/透明度关键帧、每发射器 glow |
| `additive.fsh` | 软圆衰减 + 距离淡出 + 叠加输出 |

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

### 发射器头（256 B = 16×vec4 / emitter，SSBO，按 spec equals 去重缓存）
`origin(占位)` / `shape,speed,radius` / `gravity,drag` / `accel,windStrength` / `windDir,rotation` / `life,sizeStart,sizeEnd` / `sizeEase,coneTanHalf,colorCount,glow` / 保留 / `8×RGBA 颜色关键帧`

### 发射命令（32 B / 条，环 ×3）：`origin.xyz + count` / `emitterId + seed`
### 间接命令（16 B）：`count=6, instanceCount(GPU原子累计), first=0, baseInstance=0`
### 计数器（16 B / 槽，环 ×4）：`writeSlot, spare`

---

## 4. 每帧渲染管线（`AFTER_LEVEL`）

```
1. 清空/合并客户端请求（pending 队列：burst / stream / clear）
2. 从计数器环读上一帧 slot → aliveRead（当前读缓冲的存活前缀长度；1 帧滞后，无读-改冲突）
3. 按节流 scale 构建发射命令（bursts + streams），按剩余容量裁剪
4. upload 发射命令到环槽；若发射器头脏则整块增量上传（32KB）
5. compute：reset(1线程) → memoryBarrier → update(aliveRead 线程) → emit(totalSpawn 线程) → memoryBarrier
6. draw：绑定 render 程序 + 新鲜写入缓冲(binding1) + 发射器头(binding5) + VAO
   → 设 ModelView/Proj/uCamPos/uCamRight/uCamUp/uFadeDist
   → 叠加混合 + 深度测试（不过深度写）→ glDrawArraysIndirect(TRIANGLES)
7. 解绑 SSBO base 0-5；swap ping-pong；记录帧耗时 → 节流更新
```

- 致密前缀不变量：写缓冲 `[0, liveCount)` 恒为全部存活粒子；死粒子在 update 中被跳过即移除
- 存活计数走**原子计数器环**（×4）：本帧写 `simFrame%4`，回读 `(simFrame-1)%4`（上一帧应产生的最终值），消除“读-再-改同一缓冲”
- 发射线程用 `g >= uTotalSpawn` 提前返回；`atomicAdd` 分配槽位，`slot >= uCapacity` 丢弃（CPU 侧已预留 2048 安全余量，理论上不触发）

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
| 叠加混合为主 | 材质槽位预留（`ShaderFeature`/define） | v1 不做深度排序；alpha 混合+排序留后续 |
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
配置文件 `run/config/createmanaindustry-client.toml` → `[particles]`：`enabled / maxParticles / frameBudgetMs / autoThrottle`

---

## 7. 性能特征（对照验收基线）

- 显存：默认 2M = 2×128MB 粒子 SSBO + 32KB 发射器头 + 3×8KB 命令环 + 4×16B 计数环
- 每帧 CPU：发射命令（≤256 条目 × 32B）+ 一次 4B 计数器回读（1 帧旧，非阻塞）
- 每帧 GPU：3 次极小 compute 派发 + 1 次间接绘制；空载实测 `frame≈0.0x ms`
- 节流：EMA（0.9/0.1）+ 迟滞（>预算 ×0.85 降 / <预算×0.5 ×1.05 升，钳制 0.05..1）
- 验收：默认预算 2M 时 1440p ≥60fps、更新+绘制 ≤5ms（中端卡）；`/cmip bench 1000000` 可压测

---

## 8. 后续开发方向

### 短期 / 优先（建议顺序）
1. **Iris 光影包正式路由**：当前 vanilla/无包路径已验证；开包后建议照 `MistIrisHook` 模式新增 `ParticleIrisHook`（经 iris-veil-compat 画入 `colortex0` + 采样 `depthtex`），与雾墙共用同一条验证过的通路
2. **百万级压测与节流标定**：`/cmip bench 1000000` + `stats` 实测，在 5ms 预算下校准各蓝图 `glow`/颜色，防叠加洗白
3. **接入现有机器**：`CMIParticleEngine.INSTANCE.spawn/stream(...)` API 已就绪，接动力雾化器 / 悦灵燃烧室 / 冷凝管视觉（原计划的延后项）
4. **soft-particle**：在 AFTER_LEVEL 采样主目标深度，做近场景软边淡出（深度可用，成本低）

### 中期 / 扩展
5. **JSON 数据驱动预设**：仿 quasar 的 emitters/modules，让艺术/策划可配（原 Q7 延后项）
6. **alpha 混合 + GPU 深度排序**：材质槽位已预留；引入 bitonic/基数排序 pass 支持非叠加材质
7. **速度拉伸 / 尾迹**：vsh 按速度拉伸 billboard（流星/拖尾），或帧反馈 motion trail
8. **噪声/涡旋力场**：v1 排除的高质感力场，叠加到 update.comp
9. **块碰撞**：把周边地形烘焙为 3D SDF 纹理供采样（成本高，低优先）

### 长期 / 工程化
10. 每发射器预算权重（`budgetWeight`）纳入节流分配，按重要度削峰
11. GL 上下文重建/资源重获取兜底（罕见驱动重置）
12. 移除/泛化剩余一次性诊断辅助；为发射器上传改按块增量（当前整 32KB 上传，已可接受）
13. 发布维度：确认无 Veil/低端 GL 时优雅降级（当前具备）；补充 Ponder/文档向玩家说明 `/cmip`

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
