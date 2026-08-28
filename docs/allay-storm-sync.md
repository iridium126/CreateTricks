# Allay Storm 数据持久化与网络同步 — 开发文档

> 模组：CreateManaIndustry（机械动力：魔法工业）
> 版本基线：1.21.1 / NeoForge 21.1.227 / Java 21
> 前置阅读：`docs/particle-engine-dev.md`（GPU 粒子引擎本体）
> 状态：**实现完成，编译通过，待进服实测**

---

## 1. 定位与设计目标

Allay Storm 是未来的 **BOSS 本体**——风暴由最多 131072 只 allay 粒子组成，不存在独立的 Boss 实体。本设计为其打下三块地基：

1. **持久化**：风暴定义、成员死亡集合、HP 状态随存档保存，重启后风暴原样续接（成员数量严格一致）。
2. **同步**：所有客户端看到"同一场风暴"——同身份、同数量、同死亡时刻、宏观结构一致。
3. **性能优先**：成员**位置永不过网**（131k 成员 × 12 B × 20 t/s ≈ 31.5 MB/s 的逐位置广播被彻底排除）；全部协议事件驱动，**零每 tick 常规流量**。

核心定理（设计出发点）：涌现式群体模拟（弹簧/boids）不传位置就不可能给出精确的跨端个体位置。本设计用三件事组合逼近一致性：**确定性身份派生 + 共享时钟 + 权威客户端位置修正流（软收敛）**。

## 2. 权威模型总览

| 状态 | 权威方 | 存储位置 | 同步方式 |
|---|---|---|---|
| 风暴定义（锚点/数量/半径/模式/ω/种子） | 服务器 | `StormData`（Level Attachment，随存档 NBT） | 事件包（ACTIVATE/UPDATE） |
| 成员死亡集合 | 服务器 | 同上（BitSet） | ACTIVATE 携带位图；死亡由 DAMAGE 包的死亡位驱动 |
| 成员 HP | 服务器 | 同上（稀疏 map，缺省 = 满血 20） | 客户端只持有镜像（快照驱动），不参与死亡判定 |
| 成员位置/速度 | **各客户端 GPU** | 显存粒子池（64 B/成员） | 权威客户端 5 Hz 快照 → 服务器转发 → 软修正槽；**永不定时广播** |
| 受伤/死亡/击退事件 | 服务器判定，客户端演出 | — | DAMAGE 广播（~14 B/次，含攻击者） |

**为何伤害由客户端上报**：成员位置是客户端 GPU 状态，服务器没有 GPU 无法验证命中（与 Minecraft 反作弊对真实实体的验证同级——记录在案的信任面）。服务器做的是**廉价校验**：每秒 ≤12 次节流、伤害上限（线格式 63.75）、死成员/维度外玩家拒绝。

**为何客户端 HP 不是废数据**：它是 ① 尸体状态机的载体（0→-1 倒计时驱动死亡翻滚动画），② 命中资格门（`hit.comp` 的 `hp <= 0` 排除尸体），③ 致命一击音效预测（点击瞬间本地决定播放 hurt/death 音效，等不及服务器裁决）。数值允许跨端漂移，因为没有任何可观测行为依赖精确值——**死亡一律由服务器的死亡位强制**，不信本地算术。

## 3. 网络协议（5 个 payload，全部事件驱动）

注册于 `CreateManaIndustry.registerPayloads`（`versioned("1")`，与 MistSync 同一 registrar）。

### 3.1 `ClientboundStormStatePacket`（S→C，生命周期四合一）

| 动作 | 载荷 | 大小 | 触发 |
|---|---|---|---|
| ACTIVATE | 参数 + seed + 死亡位图 + authority 位 + 修正频率 | ~18 B + 位图（最坏 16 KB，仅死亡很多时，一次性） | 进入激活范围 / 登录 / 切维度 / 风暴重启 |
| UPDATE | 参数 + authority 位 + 修正频率 | ~18 B | 参数变更（不改数量）；authority 交接 |
| DEACTIVATE | 无 | 2 B | 离开激活范围（服务器状态不动） |
| STOP | 无 | 2 B | 风暴结束 |

线格式：flags 字节（低 2 位动作 + 0x04 authority 位）、修正频率 varint×10、锚点 `BlockPos` VAR_LONG、count varint、radius 字节（×2 = 0.5 格步长）、mode varint、ω 有符号字节（÷16 rad/s）、seed varint、死亡位图 length-prefixed 裸字节（BitSet.toByteArray 小端序）。

### 3.2 `ServerboundStormHitPacket`（C→S，攻击者上报，~13 B）

`memberIdx` varint + damage 字节（×4 = 0.25 步长）+ kbX/kbZ 有符号字节（×64，预乘方向×强度的击冲量）+ light 字节（`blockLight + 16·skyLight`，天然 0..255）+ 命中位置相对锚点 3×short（×16 = 1/16 格步长）。

### 3.3 `ClientboundStormDamagePacket`（S→C，广播给含攻击者的全部激活玩家，~14 B）

同上字段 + **死亡位**。命中位置用于全员强修正（见 §6.3）。广播路由：仅当前激活玩家（`StormManager.Runtime.tracks`）。

### 3.4 `ServerboundStormPositionsPacket` / `ClientboundStormPositionsPacket`（权威上行 → 服务器原样转发）

| 字段 | 步长 | 线格式 |
|---|---|---|
| gameTime | — | varlong（外推时钟戳） |
| memberIdx | — | varint |
| pos.xyz（相对锚点） | 1/16 格 | 3×short |
| vel.xyz | 1/16 格每秒 | 3×byte（±8 格/s 覆盖 6 格/s 成员上限） |

条目 stride = 8 浮点（`memberIdx, pad, pos.xyz, vel.xyz`——与 GPU 暂存布局一致，offset 1 是 pad 不上线）。每快照 ≤256 条 → **~3 KB；5 Hz ≈ 15 KB/s 权威上行 + 各激活客户端下行**（配置可调 0.5–20 Hz）。

服务器转发前做粗检（每条 |相对坐标| ≤ 320 格，否则整包丢弃）——它无法验证算不出的东西，这是文档化的信任面。

## 4. 服务器侧组件（纯 Java，零 GL 类引用）

### 4.1 `storm/StormData`（Level Attachment，`CMIAttachments.STORM_DATA`）

```
{ active, anchor(BlockPos), count, radius(0.5步长), mode(1|2),
  omega(带符号, 1/16步长), stormSeed(24bit), dead(BitSet), hp(Int2FloatOpenHashMap) }
```

- **canonical 量化**：创建时一次性量化（`quantizeRadius`/`quantizeOmega`），持久化、线格式、客户端三方永远一致，无重量化漂移。
- **旋向符号**：由 seed 最低位决定（涡旋模式），重启后不变，零额外状态。
- **稀疏 HP**：只存受损未亡成员；`defaultReturnValue(MAX_HP)` 使缺省键读作满血。存档格式：`Dead` 变长 int 数组 + `HpIdx`/`HpBits` 平行数组（float 位型）。
- 停止后（active=false 且无死亡无受损）序列化返回 null，存档零残留。

### 4.2 `storm/StormManager`（静态管理器，`LevelTickEvent.Post` 驱动）

- **激活门控**（性能主杠杆）：每 20 tick 扫描，玩家距锚点 ≤ **196 格**激活（默认客户端 fade 终点 96+24 + 风暴最大伸展 76；fade 是客户端配置服务器读不到，故为服务器常量），**+64 格滞回**防边界抖动。ACTIVATE 包同时服务进距/登录/切维度三种场景——协议没有独立的 login-resync 路径。玩家切维度/登出：跨维度补发 DEACTIVATE 后清除 track。
- **权威选举**：激活顺序最早且仍激活的客户端。交接（断线/出范围）时向新权威发 UPDATE 翻 authority 位；新权威的本地状态成为新基准，其余客户端经同一软修正层平滑跟随（无瞬移）。
- **命中处理**：节流 → 死成员/索引校验 → 扣血 → 死亡判定（只在这里发生）→ 广播（含攻击者，纯服务器回路）。
- **HP 再生**：原版 2 HP/s（每 10 tick +1），只迭代稀疏受损表——13 万满血成员的成本为零；变更即 `setData` 标脏（NeoForge 附件脏标即存档时机）。
- **快照转发**：仅权威客户端的包被接受（authority 位校验），粗检后原样转发给其余激活客户端。

### 4.3 `storm/StormCommand`（服务器命令，权限 2）

`/cmip allaystorm [ball|vortex] [count ≤131072] [radius 2..64] [omega]` / `stop`。**已从客户端命令迁移**（客户端 `CMIParticleCommand` 保留 spawn/stream/anim/bench/clear/stats/budget/shaderpack）。

- 同数量重跑 = 参数 UPDATE（身份/HP/死亡保留，弹簧重定向）；
- 数量变化 = 整场重启（新 seed、清空 HP/死亡——测试阶段语义，身份空间重塑）。

### 4.4 配置

`ServerConfig.allay_storm.stormCorrectionHz`（默认 **5**，0.5–20）。数值随权威指派 UPDATE 包下发，服务器按同一数值节流上行——两端永远同一个数，改动对在场权威下一张快照即生效。

## 5. 客户端引擎改造（`client/particles/`）

### 5.1 成员身份（跨端一致的基石）

- 成员 i 的种子 = **两步哈希** `hash(hash(i·0.6123+0.7) + stormSeed·17.17)`（大整数索引不侵蚀 seed 尾数精度；全 IEEE 确定性运算，任何客户端逐位相同）。
- 身份编码进 **p0.w**（= memberIdx + 1，0 不可能出现）：MODEL 粒子该槽位原为恒 1.0 的尺寸乘数，风暴成员征用后，**全部尺寸消费点**改由 emitter 头的风暴槽 `18.x > 0.5` 判定常量 1.0 乘数——共 5 处：`keygen.comp`、`model.vsh`、`hit.comp`、`update.comp`（死亡 poof 的 poof 尺寸）、`ShaderPackProgramCompiler`（光影包合并路径顶点源）。
- 身份不受池压缩影响（池索引每帧 `atomicAdd` 重排，身份不重排）。

### 5.2 共享时钟

`timeSec = (gameTime mod 2^21) / 20`——整数模 + 除法，每客户端逐字节相同（无累计漂移），驱动游走中心 `G_i(t)`、涡旋相位、修正槽时间戳。29 小时回绕一次（回绕瞬间轨道相位跳变一次，Boss 战不会跨越；float 精度在回绕界仍 ~8 ms）。废弃了旧的本地逐帧累计。

### 5.3 解析出生点（晚加入 = 零瞬态）

`emit.comp` 新增 spawnStyle 3（风暴成员，头槽 `17.y=3`）：

- 涡旋成员直接出生在**当前时刻的种子轨道上**（homeR/homeY/相位 φ 全部由 mseed 决定，轨道角 = ωt + φ），初速度 = 匹配的轨道切向速度——与 update.comp 弹簧的稳态解一致，晚加入者无需集结过程；
- 命令字 c.y 携带成员索引基址，GPU 侧 `memberIdx = base + inner` 派生身份与种子；
- 发射调度（`runCompute` 风暴滴入块）按服务器死亡位图**分割 run**——批次绕过死成员，成员编号与服务器严格对齐；~60 帧滴入节奏保留。

### 5.4 玩家排斥：全体玩家

新增 `PLAYERS_BB`(18) SSBO（≤16 个 vec4 玩家位置，每帧从 `mc.level.players()` 收集距锚点最近的 16 个上传）。update.comp 的 ball/vortex 两处排斥循环改为遍历该数组。**零新增网络包**（位置来自原版实体同步）；除"各自的本地玩家泡泡精确"外，力场全端一致。

### 5.5 修正层（软收敛，永不瞬移）

- `CORRECTION_BB`(19)：按成员身份寻址的槽位（2 vec4/成员 = 32 B：`{target.xyz, arrivalTime}, {targetVel.xyz, strengthScale}`），稀疏写入、按时间戳过期、无需清空。buffer 尺寸 = 池容量 × 32 B。
- update.comp 在碰撞扫掠前读取自己的槽：朝 **速度外推目标** 加弹簧加速度 + 速度混合，位置始终经积分器演化并受 6 格/s 限速——1 格修正 ~0.2 s 缓合，读作"飞回编队"。常规快照（scale=1）强度随槽龄坡升（窗口头弱=本地物理说话，尾强=下张快照前归位）；命中修正（scale=10）固定 0.25 s 强窗口。
- 越界守卫双保险：GLSL 端 `memberIdx < uCapacity`、Java 端 `memberIdx < gpu.capacity()`（小容量客户端对高身份成员安全降级为不修正）。

### 5.6 伤害队列双键

条目 word 5 新增 flags：bit0 = 键是**成员身份**（风暴路径）还是池索引（本地遗留 MODEL 调试粒子）；bit1 = 服务器死亡位（无条件钳 hp→0 启动尸体倒计时；本地算术永远不决定风暴成员的死亡；落在尸体上的条目幂等忽略）。匹配在目标粒子**自己的线程**内完成（无跨线程竞争）。

### 5.7 权威端读回与命中双索引

- `stormpos.comp`（新 pass，仅 authority + 活跃风暴时调度）：每线程对新鲜池测与 ≤16 玩家的距离（4 格 = 近战可达全覆盖），达标者原子紧凑追加 `{memberIdx, pad, posRelToAnchor.xyz, vel.xyz}`，上限 256 条；稳态成本 = 距离测试本身。
- `hitSSBO` 从 8 B 扩到 16 B（uvec4）：`{打包距离+池索引, HP 位, 成员身份, unused}`——池索引用于战斗粒子的 originRef 追踪，**成员身份用于服务器上报**。`capture.comp` 发布 z 字（p0.w ≥ 1.5 ⇒ 身份，否则 HIT_MISS = 非风暴走遗留路径）。
- 读回节奏：快照间隔由 `stormCorrectionHz` 决定，fence 零停（与计数读回同一 fence、同一轮询点）。
- `syntheticAttack` 分流：风暴成员 → **只上报**（`ServerboundStormHitPacket`，含命中点相对锚点坐标），本地不落伤；非风暴 MODEL → 遗留本地队列。战斗粒子/音效本地照常（攻击者即时反馈，与原版一致）。

### 5.8 客户端状态机（`applyStormState`）

- **ACTIVATE**：kill 现存世代（uKillEmit 路径，下一帧 update 先压缩旧员、emit 再补新员，容量天然腾出）→ 重建死员位图 → 滴入活员 → 命中快照作废（身份重排）。
- **UPDATE**：仅参数 + authority + 频率；若头已写过则重打包（锚点/半径/ω/模式即时重定向），身份/HP/死亡不动。
- **DEACTIVATE / STOP**：本地驱散（kill 路径），服务器状态非本侧业务。

接线：`client/particles/storm/StormClientHandler`（payload 处理在渲染线程 enqueueWork，直接调引擎公开方法）。

## 6. 关键一致性场景推演

- **新玩家中途加入**：≤1 s 内扫描激活 → ACTIVATE（参数+seed+死员位图）→ 解析出生点直接落在当前时刻的轨道位置上 → 与所有老客户端同构开局。
- **服务器重启**：存档恢复 StormData → 玩家进范围 → ACTIVATE（同一位图）→ 数量严格一致；HP 严格连续（稀疏表持久化，杜绝重启刷血）。
- **围殴同一只**：各自上报，服务器串行扣血，死亡只判一次；DAMAGE 广播含死亡位 → 所有客户端同一帧语义内转尸。
- **"A 打中 #42"的可见位置**：快照层持续收敛（5 Hz 亚格级）+ 命中搭车强修正（全员把 #42 缓合到 A 的命中点）→ 闪红/死亡动画/poof 在同一位置演出，**修正只喂弹簧、受速度钳制，永不瞬移**。
- **权威断线交接**：次早激活者接管；空窗 ≤1 快照间隔内各端自由模拟（软层自愈），新权威快照到达后自动收敛。

## 7. 性能账目

| 项 | 成本 |
|---|---|
| 常规每 tick 网络 | **0**（全部事件驱动） |
| 位置同步 | 5 Hz × ≤256 条 × ~12 B ≈ **15 KB/s**（权威上行 = 各客户端下行；1 Hz 时 ~3 KB/s） |
| 命中/死亡 | ~14 B/次（近战每秒个位数） |
| 风暴启动/激活 | ~18 B + 死亡位图（满血 0 B；击杀 1 万 ≈ 1.2 KB，一次性） |
| 服务器 CPU | 每 20 tick 一次玩家距离扫描 + 稀疏 HP 再生；**无物理、无粒子**——无 GPU 服务器完全胜任 |
| 服务器存档 | 风暴定义 ~100 B + 死亡位图 ~12.5 B/千死员 + 稀疏 HP |
| 客户端显存新增 | 修正槽 2 MB（200 万池容量时；身份寻址稀疏使用）+ 玩家/读回暂存 ~10 KB |

## 8. 明确不做 / 已知限制（记录在案）

1. **信任面**：命中上报与权威位置流均不可服务器验证（与原版反作弊同级）；恶意权威客户端可摆布他人看到的近距 Boss 位置。
2. **ball 模式**：boids 混沌 + 访问预算截断 → 个体位置跨端仅宏观看齐（测试用途，非 Boss 关键路径；vortex 保留分离力故同理有个体级漂移，由修正层收敛到亚格级）。
3. **`/cmip allaystorm` 在客户端配置 `particleEnabled=false` 的客户端上**：包被静默忽略（引擎 available 检查），该玩家看不到风暴；中途切换配置是已记录的边缘场景。
4. **clock 回绕**：29 h 一次的轨道相位跳变；修正槽时间戳在回绕帧被 `cage >= 0` 守卫跳过一次。
5. **数量变化 = 整场重启**（身份空间重塑，HP/死亡清零）——测试阶段语义，Boss 阶段若需"增援不改身份"再扩展协议。
6. 模拟距离剔除：未做（战斗中命中率≈0；将来若需要走 **LOD 分级**——fade 外跳过 27 格扫描与碰撞子步、保留弹簧——纯 shader 改动，与本协议正交）。

## 9. 未来 Boss 技能的地基（本设计预留的接缝）

- **服务器可算任意成员位置**：解析出生 + 修正流使服务器能以"解析轨道近似 + 最近快照"估算近玩家成员位置——聚拢/碰撞类技能的地基。
- **成员身份全端稳定**：弱点标记、锁定仇恨、按索引分组演出的技能（"奇数环成员俯冲"）可直接以 memberIdx 寻址。
- **风暴参数热更新**（UPDATE 保留身份）：Boss 阶段切换（半径收缩、ω 加速、锚点迁移）零重启。
- **死亡位图**即 Boss 血量账本：`aliveCount()` 就是"打掉多少 allay 才伤核心"类机制的数据源。

## 10. 文件清单

| 文件 | 性质 |
|---|---|
| `storm/StormData.java` | 新增 — Level Attachment 状态 + NBT 序列化 |
| `storm/StormManager.java` | 新增 — 激活门控/权威选举/命中/再生/转发 |
| `storm/StormCommand.java` | 新增 — 服务器命令（权限 2） |
| `network/ClientboundStormStatePacket.java` | 新增 |
| `network/ServerboundStormHitPacket.java` | 新增 |
| `network/ClientboundStormDamagePacket.java` | 新增 |
| `network/ServerboundStormPositionsPacket.java` | 新增 |
| `network/ClientboundStormPositionsPacket.java` | 新增 |
| `client/particles/storm/StormClientHandler.java` | 新增 — 客户端包接收端 |
| `shaders/particles/stormpos.comp` | 新增 — 权威读回 pass |
| `CMIAttachments.java` / `CreateManaIndustry.java` / `ServerConfig.java` | 修改 — 注册/配置 |
| `client/particles/engine/CMIParticleEngine.java` | 修改 — 状态 API/时钟/调度/双键伤害/双索引命中/修正层/权威读回 |
| `client/particles/engine/ParticleBuffers.java` | 修改 — 3 新 SSBO + 16 B hit + 上传/读回 |
| `client/particles/engine/ParticlePrograms.java` | 修改 — stormpos 程序 + prelude 常量 |
| `client/particles/allaystorm/AllayStormSpec.java` | 修改 — spawnStyle 3 + seed 掩码 |
| `client/particles/command/CMIParticleCommand.java` | 修改 — allaystorm 子树迁出 |
| `client/particles/shaderpack/ShaderPackProgramCompiler.java` | 修改 — 风暴尺寸守卫 |
| `shaders/particles/{reset,emit,update,keygen,hit,capture}.comp`、`model.vsh` | 修改 — 身份/双键/排斥/修正/解析出生/uvec4 |
