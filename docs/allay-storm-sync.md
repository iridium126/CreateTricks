# Allay Storm 数据持久化与网络同步 — 开发文档

> 模组：CreateManaIndustry（机械动力：魔法工业）
> 版本基线：1.21.1 / NeoForge 21.1.227 / Java 21
> 前置阅读：`docs/particle-engine-dev.md`（GPU 粒子引擎本体）
> 状态：**实现完成，编译通过，待进服实测**（2026-08 实测前修复轮：身份哈希塌缩、涡旋伺服重构、维度切换同步重置、协议解码上限、命中邻近校验 + 服务器音效——详见 §5.1 / §5.9 / §5.8 / §3 / §4.2 标注）

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

**为何伤害由客户端上报**：成员位置是客户端 GPU 状态，服务器没有 GPU 无法验证命中（与 Minecraft 反作弊对真实实体的验证同级——记录在案的信任面）。服务器做的是**廉价校验**：死成员/索引、**攻击者邻近**（命中点距服务端位置 ≤ reach+8，见 §4.2）、每秒 ≤12 次节流、伤害上限（线格式 63.75）、维度外玩家拒绝。

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

线格式：flags 字节（低 2 位动作 + 0x04 authority 位）、修正频率 varint×10、锚点 `BlockPos` VAR_LONG、count varint、radius 字节（×2 = 0.5 格步长）、mode varint、ω 有符号字节（÷16 rad/s）、seed varint、死亡位图 length-prefixed 裸字节（BitSet.toByteArray 小端序）。**解码防御**：位图长度 > `MAX_DEAD_BYTES`（16 KB = MAX_COUNT/8）即抛 `DecoderException` 断开连接——长度字段驱动数组分配，无上限时单个恶意包即可让对端 OOM。

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

**解码防御**：两个方向都有 `MAX_ENTRIES = 256` 上限，count 为负或超界即 `DecoderException` 踢连接。上行方向尤其关键——count 驱动条目数组分配且**无速率限制**，无上限时任意客户端可单包打爆服务器（分配在转发校验之前发生，OOM 挡不住）。

服务器转发前做粗检（每条 memberIdx ∈ [0, count) 且 |相对坐标 xyz| ≤ 320 格，否则整包丢弃——初版校验错位：查了恒零的 pad 槽和 x/y、漏了 z，已修正）——它无法验证算不出的东西，这是文档化的信任面。memberIdx 上界取当前种群数量：刚缩编的世代残留快照会在此被拒，软修正层按设计自愈。

## 4. 服务器侧组件（纯 Java，零 GL 类引用）

### 4.1 `storm/StormData`（Level Attachment，`CMIAttachments.STORM_DATA`）

```
{ active, anchor(BlockPos), count, radius(0.5步长), mode(1|2),
  omega(带符号, 1/16步长), stormSeed(24bit), dead(BitSet), hp(Int2FloatOpenHashMap) }
```

- **canonical 量化**：创建时一次性量化（`quantizeRadius`/`quantizeOmega`），持久化、线格式、客户端三方永远一致，无重量化漂移。
- **读侧重重量化**：序列化读入时 radius/omega 再跑一遍量化（omega 先取模再补符号——`quantizeOmega` 是纯模长函数，其 0.05 下限钳制会把负号吞成 +0.05，直接调用会把所有负向漩涡读成正转）。写入侧恒为 canonical 形式，读侧防手改存档/未来格式泄露越界值到线格式（radius 线格式字节的无符号回绕同样依赖 0.5 步长钳制兜底）。
- **旋向符号**：由 seed 最低位决定（涡旋模式），重启后不变，零额外状态。
- **稀疏 HP**：只存受损未亡成员；`defaultReturnValue(MAX_HP)` 使缺省键读作满血。存档格式：`Dead` 变长 int 数组 + `HpIdx`/`HpBits` 平行数组（float 位型）。
- 停止后（active=false 且无死亡无受损）序列化返回 null，存档零残留。

### 4.2 `storm/StormManager`（静态管理器，`LevelTickEvent.Post` 驱动）

- **激活门控**（性能主杠杆）：每 20 tick 扫描，玩家距锚点 ≤ **196 格**激活（默认客户端 fade 终点 96+24 + 风暴最大伸展 76；fade 是客户端配置服务器读不到，故为服务器常量），**+64 格滞回**防边界抖动。ACTIVATE 包同时服务进距/登录/切维度三种场景——协议没有独立的 login-resync 路径。玩家切维度/登出：track 清理条件 = 玩家离线 **或** `player.serverLevel() != 本维度`——`getPlayerList().getPlayer` 是**跨维度全局查找**，只查离线会留下永不清理的 stale active track（霸占 authority 位，旧维度修正流断供，且切走者收不到 DEACTIVATE、客户端羊群成幽灵）——跨维度者补发 DEACTIVATE 后清除 track。
- **权威选举**：激活顺序最早且仍激活的客户端。交接（断线/出范围）时向新权威发 UPDATE 翻 authority 位；新权威的本地状态成为新基准，其余客户端经同一软修正层平滑跟随（无瞬移）。
- **命中处理**：死成员/索引校验 → **攻击者邻近校验**（上报命中点距攻击者**服务端位置** ≤ `entityInteractionRange() + 8`，+8 覆盖服务端视角滞后快速移动客户端 ~1-2 格 + 延迟抖动——封堵激活圈内隔空点杀任意成员；对锚点迁移/半径收缩天然免疫：成员飞往新锚点需数秒，但攻击者只能打够得着的目标；刻意近战形，未来远程报告路径一行圈定或删除）→ 节流（垃圾包在校验阶段被拒，不消耗节流预算）→ 扣血 → 死亡判定（只在这里发生）→ **服务器音效**（`playSound(攻击者, 命中点, died ? ALLAY_DEATH : ALLAY_HURT, ...)`——该重载语义 = 广播给附近所有人但**排除**指定玩家；攻击者已在点击瞬间本地预测播放，其余激活玩家听到与 DAMAGE 包同 tick、与受击闪白对齐的声音，vanilla 对齐）→ 广播（含攻击者，纯服务器回路）。
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

- 成员 i 的种子 = **宽跨度双通道组合**：`s0 = hash(seed + 31.7)`；`q = i·0.6123 + 0.7 + s0·97`（跨度 ~8 万，`hash` 首步 `fract(p·0.1031)` 折叠 ~8285 次）；`mseed = hash(q)·0.618 + hash(q·7.31)·0.382`。
  - **教训（实测前修复）**：初版 `hash(hash(i·0.6123+0.7) + stormSeed·17.17)` 有两处灾难——① `stormSeed·17.17` 最大 ~2.9e8，float 该量级 ULP = 32，加法直接吞掉 [0,1) 的成员哈希：**94% 的种子下全体成员同一身份 → 单点堆叠**，且分离力的 `d² > 1e-8` 守卫让重合成员受力恒等（位置对称的力场永不破缺），堆叠自锁；② `cmiHash1` 输出仅 ~13.7K 有效桶（[0,1) 级输入首步不折叠、雪崩不足），单次哈希撑不起 131072 成员。修复经严格 IEEE 模拟量化验证：2048 成员全种子全离散，131072 时 ~95%（残余两两成对，视觉不可辨）。
  - **FMA 纪律**：派生链每条语句都是单次乘/加，无任何 `a*b+c` 形式——`a*b+c` 可被合法收缩为 FMA，跨厂商舍入差 1 ULP 经哈希雪崩放大成不同身份（~8e4 量级 1 ULP = 0.0078）。`cmiHash1` 本体（`p *= p + 33.33` 是 `a*(b+c)` 形式）天然安全。
- 身份编码进 **p0.w**（= memberIdx + 1，0 不可能出现）：MODEL 粒子该槽位原为恒 1.0 的尺寸乘数，风暴成员征用后，**全部尺寸消费点**改由 emitter 头的风暴槽 `18.x > 0.5` 判定常量 1.0 乘数——共 6 处：`keygen.comp`、`model.vsh`、`hit.comp`、`update.comp`（死亡 poof 的 poof 尺寸）、`emit.comp`（`sourceMetrics`，combat 粒子以风暴成员为 originRef 源——实测前修复轮漏掉此处，暴击星/伤害红心的出生偏移按 memberIdx+1 缩放、生成在几十上百格外不可见，已补）、`ShaderPackProgramCompiler`（光影包合并路径顶点源）。
  - 身份不受池压缩影响（池索引每帧 `atomicAdd` 重排，身份不重排）。

### 5.2 共享时钟

`timeSec = (gameTime mod 2^21) / 20`——整数模 + 除法，每客户端逐字节相同（无累计漂移），驱动游走中心 `G_i(t)`、涡旋相位、修正槽时间戳。29 小时回绕一次（回绕瞬间轨道相位跳变一次，Boss 战不会跨越；float 精度在回绕界仍 ~8 ms）。废弃了旧的本地逐帧累计。

### 5.3 解析出生点（晚加入 = 零瞬态）

`emit.comp` 新增 spawnStyle 3（风暴成员，头槽 `17.y=3`）：

- 涡旋成员直接出生在**当前时刻的种子轨道上**（homeR/homeY/相位 φ 全部由 mseed 决定，轨道角 = ωt + φ），初速度 = 匹配的轨道切向速度——与 update.comp 伺服的**平衡态严格一致**（§5.9），晚加入者无需集结过程；
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
- `hitSSBO` 从 8 B 扩到 16 B（uvec4）：`{打包距离+池索引, HP 位, 成员身份, unused}`——池索引用于战斗粒子的 originRef 追踪，**成员身份用于服务器上报**。`capture.comp` 发布 z 字（p0.w ≥ 1.5 ⇒ 身份，否则 HIT_MISS = 非风暴走遗留路径）。命中键的距离位：**1/32 格步长 × 10 位（上限 31.99 格）**——覆盖 Hexcasting 32 格施法范围等模组 reach（旧 1/256 步长上限 3.999，超界命中全部折叠成同键、最近目标退化为池序号最小者）；引擎解包（`handlePlayerAttack`）与打包同一步长，`capture.comp` 不消费距离位。
- 读回节奏：快照间隔由 `stormCorrectionHz` 决定，fence 零停（与计数读回同一 fence、同一轮询点）。
- `syntheticAttack` 分流：风暴成员 → **只上报**（`ServerboundStormHitPacket`，含命中点相对锚点坐标），本地不落伤；非风暴 MODEL → 遗留本地队列。战斗粒子/音效本地照常（攻击者即时反馈，与原版一致）。

### 5.7a combat 源的成员身份寻址（identity → slot 映射，池索引漂移修复）

combat 爆发（追踪星 0.15 s 骑乘窗口 + 一次性红心）的出生源原以**命中查询时的池索引**寻址。深入核查后的暴露面：红心在常态下精确（下一次 emit 解析读的读池 = 命中查询扫描的那一代池），但 ① 追踪星跨越 ~9 次 emit 派发（60 fps 下 0.15 s），第 2 次起每次解析的都是重新压缩过的池——环境粒子周转（爆发/流/poof 过期）即令风暴成员索引每秒位移数槽，击杀（尸体 1 s 后压出）加剧；② GPU fence 滞后帧（重负载轮询超时保留 k 帧前快照）下连红心也会解析到错误槽。修复沿用伤害队列/修正槽/命中上报已有的"成员身份寻址"惯例，补上最后一个还在用池索引的跨帧消费者：

- **`MEMBERMAP_BB`(21)**：成员身份 → 池槽位映射（cap × 4 B，值 = 槽位+1，0 = 缺席），与修正槽同尺寸惯例、同越界守卫（`memberIdx < uCapacity`）。显存 +4 B × 池容量。
- **写入侧（update.comp）**：每帧重建——输入池中每个风暴成员线程（emitter 头风暴槽 `18.x > 0.5` 门控，与 gridbuild/stormpos/hit 同一惯例）写 `map[memberIdx] = 输入槽 idx+1`。**关键：写输入槽而非压缩后的输出槽**——同一次派发中 emit.comp 解析读的正是这代输入池，逐帧推导保证映射与解析池永远同代。
- **解析侧（emit.comp 样式 1/2）**：命令字 **c.z = memberIdx+1**（对样式 1/2 空闲；0 = 遗留池索引路径，非风暴调试粒子继续走 `originRef`）。成员键 ≥1 时经映射 O(1) 解析；**缺席条目（未生成/LRU 逐出/越界）丢弃该颗生成**，绝不落到陈旧槽位上的无关粒子。
- **清零点**：ACTIVATE（新种子重排身份）/DEACTIVATE/STOP（世代结束）、`resetPoolState()`（clear/换维度/池清空；`initialized` 守卫——close 先 free GPU）。UPDATE 不清（身份保留）。
- **死亡时序界（无需排空守卫）**：被击杀成员的尸体在池内存活 1 s，映射条目此期间保持有效且事实正确——致命一击自己的追踪星（点击时已入队，窗口 0.15 s ≪ 尸体窗 1 s）继续骑在尸体上收敛于击杀点，视觉自然；尸体压出后不存在任何仍活跃的爆发引用该身份。
- **性能账目**：每风暴成员每帧 +1 次 4 B 写（13 万成员 ≈ 0.5 MB/帧，噪声级）；combat 生成时 +1 次 O(1) 读。借此核查了全部身份相关路径的运行时成本——伤害队列扫描（O(N×Q) 但被"非空队列"门控，空帧零成本，有条目帧为 GPU 缓存友好的小缓冲广播读）、修正槽/boids（已预算封顶）、stormpos/hit（廉价测试 + 命中才原子）——**均无值得现在动手的优化点**。
- **残留边缘（记录在案）**：LRU 逐出的**存活**成员在自愈重滴入前，其映射条目为缺席 → 该成员身上的星/心丢弃一帧（缺席即丢弃语义保证绝不错位）；非风暴 MODEL 调试粒子保留池索引路径（数量个位数，无漂移场景）。

### 5.8 客户端状态机（`applyStormState`）

- **ACTIVATE**：kill 现存世代（uKillEmit 路径，下一帧 update 先压缩旧员、emit 再补新员，容量天然腾出）→ 重建死员位图 → 滴入活员 → 命中快照作废（身份重排）。
- **UPDATE**：仅参数 + authority + 频率；若头已写过则重打包（锚点/半径/ω/模式即时重定向），身份/HP/死亡不动。
- **DEACTIVATE / STOP**：本地驱散（kill 路径），服务器状态非本侧业务。
- **维度切换 / 登录登出的同步重置**：`LevelEvent.Unload/Load`（客户端自己的 ClientLevel；NeoForge 在 `Minecraft.setLevel` 内、**加载画面之前**同步触发）直接调 `onLevelChanged()` = `dropAll()` + `CollisionBake.reset()`（后者清烘焙槽位——旧维度的占用体积 presence 旗标跨维度会幽灵碰撞、并赖住 8 槽中的 4 个）。必须**同步**于事件内：旧实现走排队的 `clear()`（新维度首个计算帧才消费），而加载画面期间 enqueueWork 先行处理了新维度的 ACTIVATE——风暴先生效再被清空，服务器不重发（track 已 active），风暴就此隐身。同维度死亡重生两事件都不触发，Boss 战重生不清场。

接线：`client/particles/allaystorm/StormClientHandler`（payload 处理在渲染线程 enqueueWork，直接调引擎公开方法）。

- **运行时类拆分（本轮重构）**：引擎主类的全部可变风暴状态与行为（17 个同步字段、共享时钟 `timeSec`/`clockSeconds`、准星命中快照四件套、同步玩家三件套、权威读回派发、~55 行滴入调度）迁入 `allaystorm/AllayStormRuntime`——组合持有 + 引擎回引，构造器只存引用不解引用。定性依据：MODEL 材质整体为风暴服务（非风暴 allay 是早期测试载体），故命中快照与姿势时钟按风暴资产归类。引擎保留**帧骨架触点**并读 storm 访问器：`tickClock`、`killPending`/`dropHitKeys`（kill 帧）、`needsGridPass`（grid 门控）、`killEmitId`/`omega`/`seed`（uniform）、`retireKill`（swap 成功尾）、`onHitReadback`/`pollPositionSnapshot`（fence 轮询）、`dropHitSnapshots`（resetPoolState）、`dispatchStormPosReadback`（引擎算好池普查派发上界后传入）。引擎 public API（`applyStormState`/`applyCorrections`/`applyStormDamage`/`timeSec`）保留一行委托——`StormClientHandler` 与光影包 merge hook **零改动**。伤害队列写入器泛化为 `enqueueDamageEntry(key, …, flags)`（遗留路径 flags=0，storm 路径 flags 由协议语义计算），缓冲所有权留引擎。**循环纪律**（零回归契约）：逐元素循环只触碰自有字段或循环前捕获的上下文（`EmitSchedule` 携带命令数组引用与可变计数器），引擎访问器不出现在循环内；调用点全部单态（单例 + 单实现），JIT 预热后委托与 getter 全内联——GPU 路径零变化（着色器零改动），CPU 增量低于测量噪声。consume-once 语义保持精确：点击路径 `consumeHitSnapshot()` 只清 `hitKeySnapshot`，`crosshairHitKey` 镜像保留（准星不闪烁）。

### 5.9 涡旋转向：世界系伺服（替换参考系伪力）

初版在旋转参考系内取真实力（家圆弹簧 + 分离 + 斥力）并加入离心/科氏伪力，再把结果**当作世界系加速度**加到世界速度上——参考系换算没有闭合（该格式下伪力恰应相互抵消，属双重计入）。后果：径向平衡外移 ~15-40%（随 ω 增长）；实际转速 0.4~0.78ω（切向无净力 → 世界系角动量守恒，转速由出生动量决定，**ω 参数形同装饰**）。现改为**世界系伺服到旋转家点**：

- 目标点 = `G + R(ωt)·(homeR·cosφᵢ, homeY, homeR·sinφᵢ)`，相位哈希（`cmiHash1(p3.z·11.3)·2π`）逐字镜像 emit.comp 出生——**平衡态 = 解析出生态**，晚加入零瞬态；
- `a = 24·(target − pos) + 6·(vT − vel) + 分离 + 玩家斥力`（半隐式积分器在 0.25 s 极端 dt 下谱半径恰 1、不放大；稳态向心滞后 ω²·homeR/24 ≈ 0.13 格 @默认参数）；
- **ω 的客户端物理钳制**：`ω_eff = sign(ω)·min(|ω|, MAX_SPEED/radius)`（ACTIVATE/UPDATE 时钳，见 §5.8）——共转需切向速度 ω·homeR，成员限速 6 格/s，超出则伺服目标永久不可达；按**风暴半径**而非成员各自 homeR 钳，图案保持刚性，两端从同步参数确定性钳出同值，服务器定义不动；
- **设计让步（记录在案）**：刚性共转没有差速剪切螺旋臂——旧代码同样没有（转速全员一致、无差速可剪），"伪力互作用蚀刻螺旋臂"的注释承诺从未兑现；未来可用结构化 φᵢ（按 3~4 臂量化）做视觉加强。

### 5.10 涡旋 ω_eff 的边界行为

radius=8、ω=0.625（默认）→ 5 ≤ 6 不触发；radius=8 + ω=3 → ω_eff = 0.75；radius=64 → ω_eff ≤ 0.094（大风暴庄重慢转——这是 6 格/s 成员限速下的诚实行为，旧实现同受此限只是表现为漂移）。emit.comp 出生侧的 6 格/s 速度钳制在 ω_eff 生效后成为死代码（无害保留）。

### 5.11 命中管线原版对齐 + 武器耐久（逐行审计 `.refs/neoforge-21.1.227` 的 `Player.attack`）

**已对齐项**：基础伤害（autoSpin/属性）、冷却缩放 0.2+f²·0.8、附魔伤害×冷却、武器加成在冷却缩放之后且不缩放、疾跑击退音效+标记、暴击条件逐字一致+NeoForge `CriticalHitEvent`、击退数学（空中目标语义、÷2 预乘方向）、暴击星/附魔星、红心阈值（f8>2 → 数量=f8·0.5）、冷却计数器末尾重置。

**本轮补齐**：

- **武器耐久（服务端专属）**：`StormManager.handleHit` 校验全部通过后走原版 `Player.attack` 服务端半边的形状——`weapon.hurtEnemy(代理, player)`（成功即 ITEM_USED 统计）→ `postHurtEnemy`（剑/三叉戟/锤 1 点、挖掘类 2 点、无覆写 0——各物品差异数值由物品自身的覆写携带，非本模组硬编码）→ 破碎时 `onPlayerDestroyItem` + 槽位清空。耐久附魔/创造模式豁免/破碎 shrink+音效全部活在原版 `hurtAndBreak` 内部，免费继承。**被拒报告不扣耐久**（死员/邻近失败/节流——类比原版 hurt() 返回 false）；**击杀命中照扣**（原版语义）。一次性 `Allay` 代理传 target（原版实现不读它，模组物品可能读）。客户端半边零耐久逻辑——原版客户端预测半边同样不扣耐久，这才是完全对齐。
- **`DAMAGE_DEALT` 统计（服务端）**：f8 = min(命中前 HP, 伤害)——服务器自有的稀疏 HP 无护甲/吸收项，该式即精确实际伤害。
- **`causeFoodExhaustion(0.1F)`（双端）**：镜像原版命中管线的客户端预测半边 + 服务端权威半边。
- **STRONG/WEAK 音效拆分**：非暴击时满蓄力 STRONG、否则 WEAK（原版分支；横扫分支不适用）。
- **攻击者减速门控**：`×0.6 减速 + 停止疾跑` 仅在击退强度 >0 时施加（原版 f4>0 门控；空手非疾跑命中无反馈）。
- **零伤害路径静音**：原版 `f≤0 && f1≤0` 无任何音效（NODAMAGE 是 hurt() 被拒的音效，GPU 目标无可对应的被拒案例）。

**明确不做（记录在案）**：横扫（需要多成员上报 + SweepAttackEvent，改动面大，Boss 蜂群受益者可疑）；POST_ATTACK 附魔效果（火附魔点燃等——粒子无燃烧状态）；无敌帧（原版 hurtTime 10t——快照节奏连击是 Boss 设计的一部分）。`EnchantmentHelper.modifyDamage/modifyKnockback` 需要 `ServerLevel`，客户端镜像维持"仅无条件效果"（原版条件性伤害附魔对悦灵无一可触发，模组条件性效果为已记录缺口）。

### 5.11a 基础攻击力的客户端镜像（实测修复：满蓄力伤害塌缩至 1.0）

实测症状：下界合金剑满蓄力打风暴成员 0 颗红心、伤害 1；锋利 V 打出 2 颗红心、伤害 4（原版应为 11）。数值唯一解 = `base` 读到裸基础值 1.0 而附魔加成正确。根因链（逐行核对原版）：

- `Attributes.ATTACK_DAMAGE`/`ATTACK_KNOCKBACK` 注册时**没有 `setSyncable(true)`**——攻击类属性从不进入 `ClientboundUpdateAttributesPacket`；
- 喂给它们的两组修正源又都是**服务端专属套用**：装备修正（`LivingEntity.collectEquipmentChanges` → `addTransientModifier`，整个 `detectEquipmentUpdates` 路径在 `!isClientSide` 分支内）与药水效果修正（`onEffectAdded` 同门控）；
- 所以客户端 `getAttributeValue(ATTACK_DAMAGE)` **恒等于裸基础值 1.0**。原版自己不在乎——真实实体的伤害由服务器重算——但本引擎的客户端预测要**上报伤害**，预测值就是实值。

修复：`clientAttackAttributeValue(player, weapon, attribute)`——武器 MAINHAND 组件修正（原版自己的客户端先例：`Mob.getApproximateAttackDamageWithItem`）+ 药水效果修正（`MobEffect.createModifiers`），按 `AttributeInstance` 运算序（ADD_VALUE → ADD_MULTIPLIED_BASE → ADD_MULTIPLIED_TOTAL）作用于基础值。`ATTACK_KNOCKBACK` 同门控同修（原版玩家基础 0 + 组件驱动的击退附魔恰好掩盖了问题，一并校正）。`ATTACK_SPEED` 是 syncable 的，冷却读数本就正确。附魔加成本就读自物品组件（`DataComponents.ENCHANTMENTS`），从未受影响——这正是"附魔对、基础错"的分裂症状的来源。

### 5.11b 准星拾取对齐（crosshair pick：Allay 粒子按原版实体方式接管准星）

需求：准星指向 Allay 粒子时给出攻击提示、阻止准星透过粒子选中身后的方块、按住攻击时不挖掘身后的方块——即原版实体在准星拾取（`GameRenderer.pick`）中的全部行为。实现 = **替换 `Minecraft.hitResult` + `crosshairPickEntity` 为指向客户端代理实体的 `EntityHitResult`**，三个需求全部由原版消费者免费达成：

- **攻击提示**：`Gui.renderCrosshair` 在 `crosshairPickEntity != null` 时渲染攻击样式准星，攻击蓄力条（sword charge meter）同理；
- **方块描边**：`LevelRenderer` 只描边 `BlockHitResult`——Allay 挡住的身后方块不再被选中；
- **挖掘抑制**：`Minecraft.continueAttack` 只对 `BLOCK` 类型命中挖掘——按住攻击 Allay 永不挖掘身后方块。

注入点：新 mixin `vanilla.GameRendererPickMixin`（`pick(F)V` 的 TAIL，`vanilla.` 子包无条件加载），调用 `CMIParticleEngine.injectCrosshairPick`。逐条设计决策：

- **严格更近才替换**：原版 pick 取实体/方块结果的**更近者**；注入只在本引擎命中距离（1/32 格量化）严格小于当前命中距离时替换——真实实体、更近的墙照常获胜，**遮挡语义与原版逐字节一致且此处零射线检测**（身前有墙 ⇒ 方块命中更近 ⇒ Allay 落选）。
- **射线基准逐字对齐**：hit.comp 本就以 `player.getEyePosition()` 为原点、相机旋转为方向、`entityInteractionRange()` 为上限——与原版 pick 的实体射线完全同构；注入点用同一构造重建命中点，距离比较共享同一基准（第三人称同样成立）。
- **非消费读取**：新增 `crosshairHitKey` 作为 `hitKeySnapshot` 的镜像（同一次 fence 轮询刷新、同一组失效点清除：ACTIVATE/STOP/resetPoolState/stormKill 帧），点击路径的 consume-once 语义不变，**准星在点击后的那一帧不闪烁**。
- **代理实体**：注入的是 `proxyFor` 的客户端代理（位置随命中点更新）。漏网的原版攻击（仅在点击快照刚被消费的帧发生）会向上游发送未知实体 id 的 Interact 包（服务器静默忽略），且 `LivingEntity.hurt` 在客户端早退——无副作用。右键指向粒子：`gameMode.interact` 发的包同样被忽略，物品 `interactLivingEntity` 默认 PASS 回落到正常物品使用；创造模式中键拾取会拾取 Allay 刷怪蛋（与指向真实实体一致）。
- **守卫**：引擎不可用/旁观者/相机实体非玩家（freecam）时跳过；快照失效（世代切换）时准星自然回落到原版行为。

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
| 客户端显存新增 | 修正槽 2 MB（200 万池容量时；身份寻址稀疏使用）+ 玩家/读回暂存 ~10 KB + 成员身份映射 4 B × 池容量（每帧重建，见 §5.7a） |

## 8. 明确不做 / 已知限制（记录在案）

1. **信任面**：命中上报与权威位置流均不可服务器完全验证（与原版反作弊同级）。命中上报已加**攻击者邻近校验**（服务端位置 ± reach+8，见 §4.2）——残余：高延迟玩家的合法命中可能落在余量外被误拒（有 12 次/秒节流兜底，代价个位数次挥空）；恶意权威客户端仍可摆布他人看到的远场成员位置（近场由修正流收敛）。
2. **ball 模式**：boids 混沌 + 访问预算截断 → 个体位置跨端仅宏观看齐（测试用途，非 Boss 关键路径）。**vortex 已改刚性共转伺服**（§5.9）：图案相位 = 共享时钟纯函数，跨端宏观一致性优于 ball；个体级漂移仍由修正层收敛到亚格级。
3. **`/cmip allaystorm` 在客户端配置 `particleEnabled=false` 的客户端上**：包被静默忽略（引擎 available 检查），该玩家看不到风暴；中途切换配置是已记录的边缘场景。
4. **clock 回绕**：29 h 一次的轨道相位跳变；修正槽时间戳在回绕帧被 `cage >= 0` 守卫跳过一次。
5. **数量变化 = 整场重启**（身份空间重塑，HP/死亡清零）——测试阶段语义，Boss 阶段若需"增援不改身份"再扩展协议。
6. 模拟距离剔除：未做（战斗中命中率≈0；将来若需要走 **LOD 分级**——fade 外跳过 27 格扫描与碰撞子步、保留弹簧——纯 shader 改动，与本协议正交）。
7. **游走中心 G 的跨厂商哈希差异（故意保留）**：`cmiStormCenter` 的相位哈希输入是 `a*b+c` 形式（可被 FMA 收缩，跨厂商差 1 ULP 经雪崩放大，成员 G 相位跨厂商可差数格）——已决策：G 未来由服务端权威生物 AI 驱动（§9 预留接缝），届时客户端哈希相位被网络状态取代，现在不做 `precise`/拆行投入。死亡 poof / combat 粒子的哈希偏置同理跳过（纯本地装饰，无跨端一致性预期）。
8. **131072 成员身份离散度 ~95%**：`cmiHash1` 输出有效桶 ~13.7K，宽跨度双通道组合后 13 万成员仍有 ~5% 两两成对同身份（伺服目标重合两支，视觉不可辨）；2048 默认规模全种子全离散。彻底消除需 CPU 每成员种子上传（~512 KB/次激活），按需再做。

## 9. 未来 Boss 技能的地基（本设计预留的接缝）

- **服务器可算任意成员位置**：解析出生 + 修正流使服务器能以"解析轨道近似 + 最近快照"估算近玩家成员位置——聚拢/碰撞类技能的地基。
- **成员身份全端稳定**：弱点标记、锁定仇恨、按索引分组演出的技能（"奇数环成员俯冲"）可直接以 memberIdx 寻址。
- **风暴参数热更新**（UPDATE 保留身份）：Boss 阶段切换（半径收缩、ω 加速、锚点迁移）零重启。
- **死亡位图**即 Boss 血量账本：`aliveCount()` 就是"打掉多少 allay 才伤核心"类机制的数据源。
- **游走中心服务端权威化**（本轮决策预留）：G(t) 未来由服务器生物 AI 驱动（跟随玩家、走位型技能）并随事件包广播——客户端哈希相位退役，跨厂商哈希差异（§8.7）随之消除。伺服/渲染管线已按"目标点"组织（§5.9），替换成本 = 一条同步流 + 客户端读包，转向/迁移零重构。

## 10. 文件清单

| 文件 | 性质 |
|---|---|
| `storm/StormData.java` | 新增 — Level Attachment 状态 + NBT 序列化 |
| `storm/StormManager.java` | 新增 — 激活门控/权威选举/命中/再生/转发/武器耐久+统计+饥饿（§5.11） |
| `storm/StormCommand.java` | 新增 — 服务器命令（权限 2） |
| `network/ClientboundStormStatePacket.java` | 新增 |
| `network/ServerboundStormHitPacket.java` | 新增 |
| `network/ClientboundStormDamagePacket.java` | 新增 |
| `network/ServerboundStormPositionsPacket.java` | 新增 |
| `network/ClientboundStormPositionsPacket.java` | 新增 |
| `client/particles/storm/StormClientHandler.java` | 新增 — 客户端包接收端 |
| `shaders/particles/stormpos.comp` | 新增 — 权威读回 pass |
| `CMIAttachments.java` / `CreateManaIndustry.java` / `ServerConfig.java` | 修改 — 注册/配置 |
| `client/particles/engine/CMIParticleEngine.java` | 修改 — 状态 API/时钟/调度/双键伤害/双索引命中/修正层/权威读回/ω_eff 钳制/onLevelChanged 同步重置/combat 爆发携带成员身份 + 映射清零（§5.7a）/命中管线对齐（§5.11）；风暴运行时状态整体拆出至 AllayStormRuntime（§5.8），本类保留帧骨架触点 + 一行委托 |
| `client/particles/engine/ParticleBuffers.java` | 修改 — 4 新 SSBO（含 MEMBERMAP_BB）+ 16 B hit + 上传/读回/映射清零 |
| `client/particles/engine/CollisionBake.java` | 修改 — `reset()`（维度切换清烘焙槽位与在途构建） |
| `client/particles/engine/ParticlePrograms.java` | 修改 — stormpos 程序 + prelude 常量 |
| `client/particles/allaystorm/AllayStormSpec.java` | 修改 — spawnStyle 3 + seed 掩码 |
| `client/particles/allaystorm/AllayStormRuntime.java` | 新增 — 引擎主类的全部可变风暴运行时状态（同步状态机/共享时钟/命中快照/玩家收集/权威读回派发/滴入调度，见 §5.8 运行时类拆分） |
| `client/particles/command/CMIParticleCommand.java` | 修改 — allaystorm 子树迁出 |
| `client/particles/shaderpack/ShaderPackProgramCompiler.java` | 修改 — 风暴尺寸守卫 |
| `CreateManaIndustryClient.java` | 修改 — LevelEvent.Unload/Load 同步接 `onLevelChanged()`（旧为排队 clear，存在 ACTIVATE 抹除竞态） |
| `shaders/particles/{reset,emit,update,keygen,hit,capture}.comp`、`model.vsh` | 修改 — 身份/双键/排斥/修正/解析出生/uvec4/伺服/1-32 量化；update.comp 每帧重建身份→槽位映射、emit.comp combat 成员键解析（§5.7a） |
