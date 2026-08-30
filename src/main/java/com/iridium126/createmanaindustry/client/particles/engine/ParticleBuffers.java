package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * Owns all GPU resources of the particle engine: the double-buffered particle
 * SSBOs (64 B/particle = 4 vec4), the emit-command ring, the emitter header
 * SSBO, the indirect draw command buffer (five commands at a uniform 20-byte
 * stride: additive billboards, OPAQUE cutout sprites, the two model segments
 * sharing one multi-draw, ALPHA blended billboards), the counter ring, the
 * counting-sort data/histogram/offset buffers, and the collision bake-meta
 * SSBO — plus the packless vertex array used for the instanced draws.
 * <p>
 * All callers must be on the render thread with a current GL context. Lazily
 * created by {@code CMIParticleEngine} on the first frame; effective capacity
 * is capped by {@code GL_MAX_SHADER_STORAGE_BLOCK_SIZE}.
 */
public final class ParticleBuffers {

    public static final int VEC4_PER_PARTICLE = 4;  // 4 vec4 = 16 floats
    /** Bytes per particle in the double-buffered particle SSBOs (4 vec4 × 4 floats × 4 B). */
    public static final int BYTES_PER_PARTICLE = VEC4_PER_PARTICLE * 4 * 4; // 64 B
    public static final int MAX_EMIT_COMMANDS = 256;
    /**
     * Floats per emit command: a(origin.xyz, count) + b(emitterId, seed,
     * prefixOffset, originRef) + c(lightPacked, 0, 0, 0). {@code originRef} is
     * 0 for an absolute {@code origin.xyz}, else (particle index + 1) whose
     * pool position emit.comp resolves as the spawn origin (tracking bursts).
     * {@code lightPacked} = blockLight + 16·skyLight sampled at spawn; combat
     * particles carry it in p2.w for the lightmap sample in textured.vsh.
     */
    public static final int EMIT_ENTRY_FLOATS = 12;
    /**
     * Emit-command ring depth. Deliberately aligned with {@link #COUNTER_RING}
     * (4): unlike the counter ring this ring has NO fence guarding the reuse of
     * its oldest slot, so its margin against a CPU run-ahead overwriting a slot
     * the GPU is still reading must not be smaller than the counter ring's.
     */
    public static final int EMIT_RING_SIZE = 4;
    /** Emitter header in vec4 — mirrors EmitterSpec.VEC4_PER_EMITTER. */
    public static final int VEC4_PER_EMITTER = EmitterSpec.VEC4_PER_EMITTER;
    /**
     * Counter-buffer ring. Each frame's counters live in one slot; the CPU reads
     * the slot written by the previous frame (1 frame old, producing no
     * read-after-write hazard against this frame's reset). 4 slots give the
     * previous frame's value plenty of margin before it is reused.
     */
    public static final int COUNTER_RING = 4;
    /**
     * Number of draw commands in the indirect buffer: additive, OPAQUE
     * cutout billboards, model opaque segment, model translucent segment,
     * ALPHA blended billboards.
     */
    public static final int INDIRECT_COMMANDS = 5;
    /**
     * Uniform byte stride of every indirect command. ELEMENT commands
     * ({@code glDrawElementsIndirect}, commands 2/3) read the full
     * DrawElementsIndirectCommand — 5 uints: indexCount, instanceCount,
     * firstIndex, baseVertex, baseInstance. Arrays commands (0/1) read only
     * the first 16 bytes, so their 5th uint is padding. A uniform stride
     * keeps both the draw offsets and the compute shaders' flat-uint SSBO
     * view (field j of command i = index {@code 5*i + j}) trivially aligned.
     */
    public static final int INDIRECT_STRIDE = 20;
    /** Counting-sort passes over the 9-bit key (one, by design). */
    public static final int RADIX_PASSES = 1;
    /**
     * Radix bin count. The sort key is 9 bits: bit {@link #SORT_TYPE_SHIFT}
     * selects the translucent item type (0 = MODEL, 1 = ALPHA sprite) and the
     * low byte carries the inverted log depth band. Binning over all 9 bits
     * makes the scatter place each type in its own CONTIGUOUS partition --
     * MODEL items at [0, N_model), ALPHA items at [N_model, N_total) -- so
     * every translucent draw command gets an exact instanceCount and no
     * vertex invocations are wasted filtering foreign-type items.
     */
    public static final int RADIX_BINS = 512;

    // The binding constants below are the SINGLE SOURCE OF TRUTH for the
    // GLSL side too: ParticlePrograms#commonPrelude generates #define lines
    // from them and injects the prelude into every shader source.
    /**
     * Read pool of the double-buffered particle SSBOs — update/gridbuild/keygen
     * (keygen via a WRITE-slot binding while pre-swap) source, and, after each
     * AFTER_SKY commit, ALSO every render pass's data source.
     */
    public static final int PARTICLE_BB_READ = 0;
    /**
     * Write pool of the double-buffered particle SSBOs — compute-phase output
     * target only (update/emit write it pre-swap). Since the frame split,
     * render passes must NEVER bind this side: by draw time the freshly written
     * generation already moved to the read side via {@link #swap()}.
     */
    public static final int PARTICLE_BB_WRITE = 1;
    public static final int INDIRECT_BB = 2;
    public static final int COUNTER_BB = 3;
    public static final int EMIT_BB = 4;
    public static final int EMITTER_BB = 5;
    // 6/7 = LSD radix sort data (read/write), rebound per pass
    // 8 = additive-only permutation (orderAdd) for the additive draw
    // 9 = radix histogram, 10 = radix offsets, 11 = collision bake meta
    public static final int SORTREAD_BINDING = 6;
    public static final int SORTWRITE_BINDING = 7;
    public static final int ORDERADD_BINDING = 8;
    public static final int HIST_BINDING = 9;
    public static final int OFFSET_BINDING = 10;
    public static final int BAKEMETA_BINDING = 11;
    /** Static baked model geometry (flat float array, MODEL particles). */
    public static final int MODELGEO_BINDING = 12;
    // 13 was RETIRED (former dense MODEL permutation; both model segments now
    // walk the combined sort array) and has been RE-PURPOSED for the boids
    // spatial-hash grid rather than renumbering anything else.
    /** Previous frame's counter slot, read by update.comp for the GPU-exact live count. */
    public static final int PREVCOUNTER_BINDING = 14;
    /** Dense permutation of visible OPAQUE cutout billboards (keygen's fourth bucket). */
    public static final int ORDEROPAQUE_BINDING = 15;
    /** Boids spatial-hash grid: heads table + next chains in ONE buffer. */
    public static final int GRID_BB = 13;
    /** Spatial-hash table size in cells (power of two; masked hashing). */
    public static final int GRID_TABLE = 16384;
    /** CPU melee-damage queue (see CMIParticleEngine.enqueueDamage). */
    public static final int DAMAGE_BB = 16;
    /** Per-frame crosshair hit-query result (see hit.comp / capture.comp). */
    public static final int HIT_BB = 17;
    /**
     * Storm player-repulsion source: up to {@link #MAX_STORM_PLAYERS} vec4
     * player positions (xyz), uploaded every frame the storm can steer. All
     * SYNCED players repel on every client (positions come from vanilla
     * entity sync — no extra packets), so the force field is near-identical
     * across clients; only each client's own local player is exact-to-itself.
     */
    public static final int PLAYERS_BB = 18;
    /**
     * Storm correction slots, indexed by member identity: two vec4 per member
     * ({target.xyz, arrivalTime}, {targetVel.xyz, strengthScale}; 0 = invalid).
     * Written sparsely by network snapshots / hit corrections, read O(1) by
     * update.comp; expiry is by timestamp, so slots never need clearing.
     */
    public static final int CORRECTION_BB = 19;
    /** Authority readback staging (see stormpos.comp): uint count + entry floats. */
    public static final int STORMPOS_BB = 20;
    /**
     * Storm member identity -> pool-slot map, rebuilt by update.comp every
     * frame (slot+1 per member present in the input pool, 0 = absent; sized
     * cap * 4 B like the correction slots). Combat spawn commands carry
     * memberIdx+1 in command word c.z and resolve their origin source through
     * this map (emit.comp styles 1/2), so origin tracking survives pool
     * recompaction across frames — the last pool-index consumer that outlives
     * a frame goes member-addressed, matching the damage queue / corrections /
     * hit reporting. Cleared on every generation-killing storm transition and
     * on pool resets; identity &gt;= capacity can never hold a slot (same
     * guard as the correction slots).
     */
    public static final int MEMBERMAP_BB = 21;
    /**
     * Wave-contact self-report staging (see wavecontact.comp): uint count +
     * entries of 5 floats {memberIdx, waveId, pos.xyz}. The result rides the
     * frame fence back; the runtime dedupes per member per wave and ships
     * ServerboundStormWaveContactPacket reports.
     */
    public static final int WAVECONTACT_BB = 22;
    /** Entries per wave-contact readback (the touches in ONE frame; tiny). */
    public static final int WAVECONTACT_CAP = 8;
    /** Floats per wave-contact entry: memberIdx, waveId, pos.xyz. */
    public static final int WAVECONTACT_ENTRY_FLOATS = 5;
    /**
     * Storm-member identity packing in p0.w: bits 0..17 hold memberIdx+1
     * (max 131072 = 2^17, so bit 17 IS used), and the next free bit —
     * {@link #MEMBER_LATCH_BIT}, bit 18 — is the dive-wave pass-completed
     * latch (set once, on the first contact frame). The packed value stays
     * float-exact (max 393216 &lt;&lt; 2^24). EVERY identity extraction must
     * mask with {@link #MEMBER_IDX_MASK}; the size-multiplier consumers are
     * exempt (they take the constant 1.0 on the storm branch).
     */
    public static final int MEMBER_IDX_BITS = 18;
    public static final int MEMBER_IDX_MASK = (1 << MEMBER_IDX_BITS) - 1;
    public static final int MEMBER_LATCH_BIT = 1 << MEMBER_IDX_BITS;
    /** Players fed to the storm repulsion / readback passes. */
    public static final int MAX_STORM_PLAYERS = 16;
    /** Entries per authority readback snapshot (nearest-to-players cap). */
    public static final int STORMPOS_CAP = 256;
    /** Floats per readback entry: memberIdx, pad, pos.xyz, vel.xyz. */
    public static final int STORMPOS_ENTRY_FLOATS = 8;
    /** Damage-queue capacity in entries (player melee clicks, a few per frame). */
    public static final int DAMAGE_QUEUE_CAP = 64;
    /**
     * CPU-side layout of the damage queue upload: an std430 uvec4 header
     * (x = entry count) followed by 24-byte entries of 6 floats each:
     * (key, damage, kbVecX, kbVecZ, lightPacked, flags). {@code key} is the
     * POOL INDEX for legacy local damage (non-storm MODEL debug particles,
     * client-authoritative) or the MEMBER INDEX for storm damage; flags bit 0
     * selects the interpretation and bit 1 carries the server-decided death
     * bit (storm path only — a client must never decide a storm member's
     * death from its local HP mirror). The light is the vanilla packed-light
     * levels sampled at the hit position; update.comp stashes it into the
     * target's p1.w so the GPU death chain can spawn poof particles with the
     * same lighting.
     */
    public static final int DAMAGE_HEADER_BYTES = 16;
    public static final int DAMAGE_ENTRY_BYTES = 24;
    /** Damage-entry flags: key is a storm MEMBER index (vs pool index). */
    public static final int DAMAGE_FLAG_MEMBER = 1;
    /** Damage-entry flags: server-authoritative death — force the corpse. */
    public static final int DAMAGE_FLAG_DIED = 2;
    /**
     * Vanilla pick forgiveness: GameRenderer inflates a picked entity's AABB
     * by this much (Entity.getPickRadius default). Unscaled, like vanilla.
     */
    public static final float HIT_INFLATE = 0.1f;
    /**
     * Hit-query result words: no hit. (Also the memberIdx sentinel in word 2
     * for non-storm MODEL particles.)
     */
    public static final int HIT_MISS = 0xFFFFFFFF;
    /** Hit-result key packing: 10 quantized distance bits above 22 index bits. */
    public static final int HIT_KEY_INDEX_BITS = 22;

    /**
     * Flat-uint index of field {@code f} of indirect command {@code c}: with
     * the uniform {@link #INDIRECT_STRIDE}, command i occupies bytes
     * {@code [i*STRIDE, (i+1)*STRIDE)} and field j sits at flat SSBO index
     * {@code 5*i + j}. All instanceCount indices below are DERIVED from this,
     * so a future stride/layout change propagates instead of desyncing.
     */
    public static int cmdField(int cmd, int field) {
        return cmd * (INDIRECT_STRIDE / 4) + field;
    }

    /** Flat-uint view of the indirect buffer: total uints ({@code 5 cmds x 5}). */
    public static final int INDIRECT_UINTS = INDIRECT_COMMANDS * (INDIRECT_STRIDE / 4);
    /** instanceCount of cmd0 — additive billboards. */
    public static final int IDX_CNT_ADD = cmdField(0, 1);
    /** instanceCount of cmd1 — OPAQUE cutout billboards. */
    public static final int IDX_CNT_SPRITE = cmdField(1, 1);
    /**
     * instanceCount of cmd2 — model opaque segment. EXACT count of MODEL
     * items: the sorted array is type-partitioned (MODEL first), so this
     * equals the model partition length. Its value also serves as the ALPHA
     * partition's start offset (read by textured.vsh from the indirect SSBO).
     */
    public static final int IDX_CNT_MODELOP = cmdField(2, 1);
    /**
     * instanceCount of cmd3 — model translucent segment. Same exact MODEL
     * item total as {@link #IDX_CNT_MODELOP} (both segments cover the same
     * partition, only their element ranges differ); keygen increments both.
     */
    public static final int IDX_CNT_XLU = cmdField(3, 1);
    /** instanceCount of cmd4 — ALPHA blended billboards. EXACT sprite-item count (the upper partition). */
    public static final int IDX_CNT_ALPHA = cmdField(4, 1);

    /**
     * Depth-band count of the sort key's LOW BYTE (one counting-sort pass).
     * Deliberately DECOUPLED from {@link #RADIX_BINS}: bins = translucent
     * types × bands, because the type bit rides above the low byte (see
     * {@link #SORT_TYPE_SHIFT}).
     */
    public static final int DEPTH_BANDS = 256;
    /**
     * Bit offset of the type bit inside the sort key: the low byte holds one
     * of {@link #DEPTH_BANDS} depth bands, so the type occupies the next bit.
     * Emitted into GLSL as SORT_KEY_TYPE_SHIFT.
     */
    public static final int SORT_TYPE_SHIFT = Integer.numberOfTrailingZeros(DEPTH_BANDS);
    /** Logarithmic quantization lower bound in blocks. */
    public static final float BAND_NEAR = 1.0f;

    private final int[] particleSSBOs = new int[2];
    private final int[] emitSSBOs = new int[EMIT_RING_SIZE];
    private final int[] counterSSBOs = new int[COUNTER_RING];
    private final int[] sortSSBOs = new int[2];
    private int emitterSSBO = -1;
    private int orderAddSSBO = -1;
    private int indirectSSBO = -1;
    private int histSSBO = -1;
    private int offsetSSBO = -1;
    private int bakeMetaSSBO = -1;
    private int modelGeoSSBO = -1;
    private int orderOpaqueSSBO = -1;
    private int gridSSBO = -1;
    private int damageSSBO = -1;
    private int hitSSBO = -1;
    private int playersSSBO = -1;
    private int correctionSSBO = -1;
    private int stormPosSSBO = -1;
    private int memberMapSSBO = -1;
    private int waveContactSSBO = -1;
    /** Static element indices for the MODEL sub-draws (bound into the VAO). */
    private int modelIndexBuffer = -1;
    private int vao = -1;

    private int readIndex = 0;
    private int ringIndex = 0;
    private int capacity = 0;
    private int maxEmitters = 0;
    private boolean initialized = false;

    private float[] emitterMirror;
    private boolean emittersDirty = false;

    // Reusable scratch for tiny uploads. The largest writer is the initial
    // indirect payload (INDIRECT_COMMANDS x INDIRECT_STRIDE) — pitfall #22
    // discipline: keep headroom above that, not just equality.
    private final ByteBuffer tmp4 = BufferUtils.createByteBuffer(160);
    /** Zeroes the whole radix histogram (RADIX_BINS x uint = 2 KiB). */
    private final ByteBuffer zero2048 = BufferUtils.createByteBuffer(RADIX_BINS * 4);
    /** Matches the "major.minor" prefix of a GL_VERSION string. */
    private static final java.util.regex.Pattern GL_VERSION_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)");

    // Dedicated read-back targets: glGetBufferSubData reads exactly
    // buffer.remaining() bytes, so these must be sized to the value widths.
    private final ByteBuffer readTmp = BufferUtils.createByteBuffer(4);
    private final ByteBuffer readTmp8 = BufferUtils.createByteBuffer(8);
    /** 16-byte hit readback: {key, hpBits, memberIdx, unused}. */
    private final ByteBuffer readTmp16 = BufferUtils.createByteBuffer(16);
    /** Authority snapshot readback: count header + capped entries. */
    private final ByteBuffer readTmpStorm = BufferUtils.createByteBuffer(
            4 + STORMPOS_CAP * STORMPOS_ENTRY_FLOATS * 4);
    /** Wave-contact readback: count header + capped entries. */
    private final ByteBuffer readTmpWave = BufferUtils.createByteBuffer(
            4 + WAVECONTACT_CAP * WAVECONTACT_ENTRY_FLOATS * 4);
    /** Staging for per-frame player positions (MAX_STORM_PLAYERS vec4). */
    private final FloatBuffer playersTmp = BufferUtils.createFloatBuffer(MAX_STORM_PLAYERS * 4);
    /** Staging for one correction-slot write (2 vec4). */
    private final FloatBuffer correctionTmp = BufferUtils.createFloatBuffer(8);

    /**
     * Allocates all buffers. Fails (logs + returns false) if the GPU cannot
     * provide a usable SSBO capacity or the max-width it supports is tiny.
     */
    public boolean init(int maxParticles, int maxEmitters) {
        // The pipeline needs OpenGL 4.3 (compute shaders + robust SSBOs). On an
        // older context the SSBO-size query below would just return 0/garbage —
        // fail with a clear message instead so the engine disables cleanly.
        String glVersion = GL11.glGetString(GL11.GL_VERSION);
        if (glVersion != null) {
            java.util.regex.Matcher m = GL_VERSION_PATTERN.matcher(glVersion);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                int minor = Integer.parseInt(m.group(2));
                if (major < 4 || (major == 4 && minor < 3)) {
                    CreateManaIndustry.LOGGER.warn(
                            "[CMI particles] OpenGL {}.{} found, compute shaders need 4.3+; engine disabled", major, minor);
                    return false;
                }
            }
        }
        int maxSSBO = GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        int cap = Math.min(maxParticles, Math.max(0, maxSSBO / BYTES_PER_PARTICLE));
        if (cap < 1000) {
            CreateManaIndustry.LOGGER.warn(
                    "[CMI particles] SSBO max size {} B too small for a usable particle pool (started {}); engine disabled",
                    maxSSBO, cap);
            return false;
        }
        this.capacity = cap;
        this.maxEmitters = maxEmitters;
        this.emitterMirror = new float[maxEmitters * VEC4_PER_EMITTER * 4];

        this.vao = GL30.glGenVertexArrays();
        for (int i = 0; i < 2; i++) {
            this.particleSSBOs[i] = createBuffer(cap * (long) BYTES_PER_PARTICLE, null);
        }
        for (int i = 0; i < EMIT_RING_SIZE; i++) {
            this.emitSSBOs[i] = createBuffer((long) MAX_EMIT_COMMANDS * EMIT_ENTRY_FLOATS * 4, null);
        }
        this.emitterSSBO = createBuffer((long) maxEmitters * VEC4_PER_EMITTER * 4 * 4, null);
        this.indirectSSBO = createBuffer((long) INDIRECT_COMMANDS * INDIRECT_STRIDE, null);
        for (int i = 0; i < COUNTER_RING; i++) {
            this.counterSSBOs[i] = createBuffer(16, null);
        }
        // Radix sort data: (key, payload) per translucent item, double-buffered.
        // One EXTRA tail slot per buffer (index == capacity) is a reserved METADATA
        // cell: capture.comp stores this generation's N_MODEL there, next to the
        // permutation it describes. The shader-pack merge reads N from the same
        // buffer it reads items from, so N and items are structurally always the
        // same generation -- an aborted frame leaves them stale TOGETHER instead of
        // pairing a fresh count with a stale permutation. Safety: radix scatter
        // writes [0, N_total), forward reads stay < N_total, the reversed cutout
        // read stays < N_model <= N_total -- the metadata slot at index 'cap' is
        // never touched by any of them. L0 shaders never fetch this slot either.
        for (int i = 0; i < 2; i++) {
            this.sortSSBOs[i] = createBuffer((cap + 1L) * 8L, null);
        }
        // Additive permutation (dense, uint per additive particle).
        this.orderAddSSBO = createBuffer(cap * 4L, null);
        // OPAQUE cutout-billboard permutation (dense, uint per particle).
        this.orderOpaqueSSBO = createBuffer(cap * 4L, null);
        this.histSSBO = createBuffer((long) RADIX_BINS * 4, null);
        this.offsetSSBO = createBuffer((long) RADIX_BINS * 4, null);
        this.bakeMetaSSBO = createBuffer(CollisionBake.MAX_SLICES * 16L, null);
        // Boids spatial hash: heads table followed by the per-live-index next
        // chain array, sized by POOL CAPACITY so every storm member (up to the
        // 131072 stress cap) keeps neighbour coupling regardless of how sprite
        // indices interleave in the dense prefix. heads is cleared every time
        // gridbuild.comp runs (the pass itself is storm-gated in the engine);
        // next needs no clearing (written before read via atomicExchange).
        this.gridSSBO = createBuffer((GRID_TABLE + (long) cap) * 4L, null);
        // CPU melee-damage queue: uvec4 count header + vec4 entries. NOT ringed:
        // the GL command stream orders this frame's update before the next
        // frame's CPU header rewrite, and the engine uploads the header every
        // frame it has entries plus one zero-count upload the frame after.
        this.damageSSBO = createBuffer(
                DAMAGE_HEADER_BYTES + (long) DAMAGE_QUEUE_CAP * DAMAGE_ENTRY_BYTES, null);
        // Crosshair hit-query result uvec4: {packed key, winner HP bits,
        // memberIdx (HIT_MISS for non-storm MODEL), unused}.
        this.hitSSBO = createBuffer(16, null);
        this.tmp4.clear();
        this.tmp4.putInt(HIT_MISS).putInt(0).putInt(HIT_MISS).putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.hitSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        // Storm player-repulsion source (vec4 per player, up to 16).
        this.playersSSBO = createBuffer(MAX_STORM_PLAYERS * 16L, null);
        // Storm correction slots: 2 vec4 per pool slot, indexed by member id.
        this.correctionSSBO = createBuffer((long) cap * 32L, null);
        // Authority readback staging: uint count + capped entry floats.
        this.stormPosSSBO = createBuffer(
                4L + (long) STORMPOS_CAP * STORMPOS_ENTRY_FLOATS * 4L, null);
        // Storm member identity -> pool-slot map (see MEMBERMAP_BB).
        this.memberMapSSBO = createBuffer((long) cap * 4L, null);
        // Wave-contact self-report staging: uint count + capped entry floats.
        this.waveContactSSBO = createBuffer(
                4L + (long) WAVECONTACT_CAP * WAVECONTACT_ENTRY_FLOATS * 4L, null);

        // initial indirect payload: one command per slot (6 verts / 0 inst
        // each; the 5th uint pads arrays commands to the uniform 20 B stride)
        this.tmp4.clear();
        for (int i = 0; i < INDIRECT_COMMANDS; i++) {
            this.tmp4.putInt(6).putInt(0).putInt(0).putInt(0).putInt(0);
        }
        this.tmp4.flip();
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
        GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 0, this.tmp4);

        // zero all counter slots
        this.tmp4.clear();
        this.tmp4.putInt(0).putInt(0);
        this.tmp4.flip();
        for (int id : this.counterSSBOs) {
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        }

        this.initialized = true;
        return true;
    }

    private static int createBuffer(long sizeBytes, FloatBuffer data) {
        int id = GL15.glGenBuffers();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
        if (data == null) {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, sizeBytes, GL15.GL_DYNAMIC_DRAW);
        } else {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        }
        return id;
    }

    public boolean initialized() {
        return this.initialized;
    }

    /**
     * Binds the NEWEST fully-written generation's backing buffer — the write
     * side while called inside the compute phase (pre-swap), which becomes the
     * read side for every consumer after the AFTER_SKY commit. The {@code
     * binding} ARGUMENT is a shader-declared binding POINT, not a side
     * selector: pass the constant the consuming GLSL block actually declares
     * (the L0 render vertex shaders use PARTICLE_BB_WRITE even though they
     * mean "fresh data"). Callers choose generation, this class owns buffers.
     */
    public void bindNewestPool(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.particleSSBOs[this.readIndex]);
    }

    public int capacity() {
        return this.capacity;
    }

    public int maxEmitters() {
        return this.maxEmitters;
    }

    public int readIndex() {
        return this.readIndex;
    }

    public int writeIndex() {
        return 1 - this.readIndex;
    }

    /** Call after a full frame: the freshly written buffer becomes the next read source. */
    public void swap() {
        this.readIndex = 1 - this.readIndex;
    }

    public void bindParticleRead(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.particleSSBOs[this.readIndex]);
    }

    public void bindParticleWrite(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.particleSSBOs[1 - this.readIndex]);
    }

    public void bindIndirect(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.indirectSSBO);
    }

    public void bindCounter(int binding, int slot) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.counterSSBOs[slot % COUNTER_RING]);
    }

    /**
     * Binds the given counter slot read-only as update.comp's exact live-count
     * source. The CALLER owns choosing which slot is authoritative — the engine
     * passes {@code lastGoodSlot}, the slot of the last frame whose output pool
     * was actually committed by a swap — so an aborted frame can never expose
     * its own partially-written counters as the read pool's live count.
     */
    public void bindPrevCounter(int binding, int slot) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding,
                this.counterSSBOs[slot % COUNTER_RING]);
    }

    public void bindEmitters(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.emitterSSBO);
    }

    /** Advances the emit-command ring and returns the next buffer id. */
    public int nextEmitBuffer() {
        this.ringIndex = (this.ringIndex + 1) % EMIT_RING_SIZE;
        return this.emitSSBOs[this.ringIndex];
    }

    public void bindEmitBuffer(int binding, int bufferId) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, bufferId);
    }

    public void bindSort(int binding, int sortBufferId) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, sortBufferId);
    }

    /** Physical GL buffer id of one of the two radix sort data buffers. */
    public int sortBuffer(int i) {
        return this.sortSSBOs[i];
    }

    /** Read-side pool backing buffer id (for the merged program's TBO view). */
    public int particleReadBufferId() {
        return this.particleSSBOs[this.readIndex];
    }

    // ---- merged-program TBO views (shader-pack path) --------------------
    // The pack-merged programs declare plain samplerBuffer uniforms instead of
    // SSBO interface blocks: ordinary global declarations survive every stage
    // of the in-game transform pipeline, interface blocks demonstrably do not.
    // Each view is {textureId, lastAttachedBufferId} and re-attaches lazily
    // when the backing buffer changes (pool flip / permutation rotation).

    private final int[][] mergedTbos = {
            {-1, -1}, // cmi_Geo      (R32F,    modelGeoSSBO)
            {-1, -1}, // cmi_Pool     (RGBA32F, read-side particle pool)
            {-1, -1}, // cmi_Emitters (RGBA32F, emitterSSBO)
            {-1, -1}, // cmi_Sorted   (RG32UI,  active permutation buffer)
    };

    private void ensureMergedTbo(int slot, int internalFormat, int bufferId) {
        if (bufferId <= 0)
            return;
        int[] t = this.mergedTbos[slot];
        if (t[0] == -1)
            t[0] = GL11.glGenTextures();
        if (t[1] != bufferId) {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, t[0]);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, internalFormat, bufferId);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            t[1] = bufferId;
        }
    }

    /** Binds the four TBO views to texture units baseUnit..baseUnit+3. */
    public void bindMergedTbos(int baseUnit, int poolReadBufferId, int permBufferId) {
        if (this.modelGeoSSBO <= 0 || this.emitterSSBO <= 0)
            return;
        ensureMergedTbo(0, GL30.GL_R32F, this.modelGeoSSBO);
        ensureMergedTbo(1, GL30.GL_RGBA32F, poolReadBufferId);
        ensureMergedTbo(2, GL30.GL_RGBA32F, this.emitterSSBO);
        ensureMergedTbo(3, GL32.GL_RG32UI, permBufferId);
        for (int i = 0; i < 4; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + baseUnit + i);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, this.mergedTbos[i][0]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Detaches the merged TBO views from their texture units. */
    public void unbindMergedTbos(int baseUnit) {
        for (int i = 0; i < 4; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + baseUnit + i);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Binds the additive-permutation buffer at its fixed binding for keygen/draw. */
    public void bindOrderAdd() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, ORDERADD_BINDING, this.orderAddSSBO);
    }

    /** Binds the OPAQUE cutout-billboard permutation at its fixed binding. */
    public void bindOrderOpaque() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, ORDEROPAQUE_BINDING, this.orderOpaqueSSBO);
    }

    /** Binds the static model geometry for the model draw pass (must be re-bound every frame — see {@link #unbindShaders()}). */
    public void bindModelGeo() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MODELGEO_BINDING, this.modelGeoSSBO);
    }

    /**
     * Uploads the static indexed model geometry (see {@link AllayModelGeometry}:
     * {@code VERTEX_FLOATS} stride) once after init: vertices go to the geo
     * SSBO, indices to an element buffer bound into the engine's VAO (harmless
     * for the unindexed particle draws), and draw commands 2/3 are rewritten as
     * element commands — cmd2 = opaque cutout segment from index 0, cmd3 =
     * translucent blended segment starting at {@code opaqueIndexCount}. Both
     * commands' instanceCounts stay GPU-written each frame (keygen sets both to
     * the exact MODEL partition length N_model; the disjoint element ranges make
     * the segment id derivable from partId alone).
     */
    public void uploadModelGeometry(float[] vertices, int[] indices, int opaqueIndexCount) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(vertices.length);
            buf.put(vertices).flip();
            this.modelGeoSSBO = createBuffer(4L * vertices.length, buf);
        }
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MODELGEO_BINDING, this.modelGeoSSBO);

        this.modelIndexBuffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.modelIndexBuffer);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer ib = stack.mallocInt(indices.length);
            ib.put(indices).flip();
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
        }
        GL30.glBindVertexArray(0);

        // two full 20-byte element commands (indexCount, instanceCount=0,
        // firstIndex, baseVertex=0, baseInstance=0): cmd2 = opaque cutout
        // segment from index 0, cmd3 = translucent blended segment starting
        // at opaqueIndexCount
        this.tmp4.clear();
        this.tmp4.putInt(opaqueIndexCount).putInt(0).putInt(0).putInt(0).putInt(0);
        this.tmp4.putInt(indices.length - opaqueIndexCount).putInt(0).putInt(opaqueIndexCount).putInt(0).putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
        GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 2L * INDIRECT_STRIDE, this.tmp4);
    }

    public void bindHist() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, HIST_BINDING, this.histSSBO);
    }

    public void bindOffsets() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, OFFSET_BINDING, this.offsetSSBO);
    }

    public void bindBakeMeta() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BAKEMETA_BINDING, this.bakeMetaSSBO);
    }

    /** Uploads the collision bake-meta array to its SSBO. */
    public void uploadBakeMeta(float[] meta) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(meta.length);
            buf.put(meta).flip();
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.bakeMetaSSBO);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        }
    }

    /** Zeroes the radix histogram (2 KiB) before each pass. */
    public void clearHist() {
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.histSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.zero2048);
    }

    /** Binds the boids spatial-hash buffer at its fixed binding. */
    public void bindGrid() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, GRID_BB, this.gridSSBO);
    }

    /** Binds the CPU melee-damage queue at its fixed binding. */
    public void bindDamage() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, DAMAGE_BB, this.damageSSBO);
    }

    /** Binds the crosshair hit-query result at its fixed binding. */
    public void bindHit() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, HIT_BB, this.hitSSBO);
    }

    /** Binds the storm player-position array at its fixed binding. */
    public void bindPlayers() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PLAYERS_BB, this.playersSSBO);
    }

    /**
     * Uploads up to {@link #MAX_STORM_PLAYERS} player positions (xyz packed
     * into vec4s, stride 4 floats). {@code count} entries are read from the
     * head of the flat array.
     */
    public void uploadPlayers(float[] xyz, int count) {
        int n = Math.min(count, MAX_STORM_PLAYERS);
        this.playersTmp.clear();
        for (int i = 0; i < n; i++) {
            this.playersTmp.put(xyz[i * 3]).put(xyz[i * 3 + 1]).put(xyz[i * 3 + 2]).put(0f);
        }
        this.playersTmp.flip();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.playersSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.playersTmp);
    }

    /** Binds the storm correction-slot buffer at its fixed binding. */
    public void bindCorrections() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, CORRECTION_BB, this.correctionSSBO);
    }

    /**
     * Writes one member's correction slot (32 bytes at
     * {@code memberIdx * 32}): {target.xyz, arrivalTime} and
     * {targetVel.xyz, strengthScale} (scale &gt; 2 marks a hit correction with
     * its fixed short window; 0 disables).
     */
    public void writeCorrection(int memberIdx, float tx, float ty, float tz, float arrival,
            float vx, float vy, float vz, float scale) {
        this.correctionTmp.clear();
        this.correctionTmp.put(tx).put(ty).put(tz).put(arrival);
        this.correctionTmp.put(vx).put(vy).put(vz).put(scale);
        this.correctionTmp.flip();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.correctionSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, memberIdx * 32L, this.correctionTmp);
    }

    /** Binds the authority readback staging buffer at its fixed binding. */
    public void bindStormPos() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, STORMPOS_BB, this.stormPosSSBO);
    }

    /** Zeroes the readback entry count ahead of a dispatch (4-byte write). */
    public void clearStormPosCount() {
        this.tmp4.clear();
        this.tmp4.putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.stormPosSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
    }

    /** Binds the storm member identity map at its fixed binding. */
    public void bindMemberMap() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MEMBERMAP_BB, this.memberMapSSBO);
    }

    /**
     * Zeroes the whole member identity map. Every generation-killing storm
     * transition (ACTIVATE restart / DEACTIVATE / STOP) and every pool reset
     * must call this: identities reshuffle or vanish, and a stale entry would
     * resolve a member-keyed combat origin onto whatever now occupies the old
     * slot.
     */
    public void clearMemberMap() {
        if (this.memberMapSSBO <= 0 || this.capacity <= 0)
            return;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer zero = stack.mallocInt(1);
            zero.put(0).flip();
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.memberMapSSBO);
            GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                    0, (long) this.capacity * 4L, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, zero);
        }
    }

    /**
     * Reads one completed readback snapshot (fence-covered — same stall
     * discipline as {@link #readbackCounts(int)}): returns a float array of
     * {@code min(count, STORMPOS_CAP)} entries at stride
     * {@link #STORMPOS_ENTRY_FLOATS} ({@code memberIdx, pad, pos.xyz, vel.xyz})
     * or {@code null} when the snapshot was empty.
     */
    public float[] readbackStormPos() {
        this.readTmpStorm.clear();
        this.readTmpStorm.limit(4);
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.stormPosSSBO);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmpStorm);
        int count = this.readTmpStorm.getInt(0);
        if (count <= 0)
            return null;
        int n = Math.min(count, STORMPOS_CAP);
        this.readTmpStorm.clear();
        this.readTmpStorm.limit(4 + n * STORMPOS_ENTRY_FLOATS * 4);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmpStorm);
        float[] out = new float[n * STORMPOS_ENTRY_FLOATS];
        for (int i = 0; i < out.length; i++)
            out[i] = this.readTmpStorm.getFloat(4 + i * 4);
        return out;
    }

    /** Binds the wave-contact staging buffer at its fixed binding. */
    public void bindWaveContact() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, WAVECONTACT_BB, this.waveContactSSBO);
    }

    /** Zeroes the wave-contact entry count ahead of a dispatch (4-byte write). */
    public void clearWaveContactCount() {
        this.tmp4.clear();
        this.tmp4.putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.waveContactSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
    }

    /**
     * Reads one completed wave-contact snapshot (fence-covered — same stall
     * discipline as {@link #readbackStormPos()}): entries of
     * {@link #WAVECONTACT_ENTRY_FLOATS} floats
     * ({@code memberIdx, waveId, pos.xyz}) or {@code null} when empty.
     */
    public float[] readbackWaveContact() {
        this.readTmpWave.clear();
        this.readTmpWave.limit(4);
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.waveContactSSBO);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmpWave);
        int count = this.readTmpWave.getInt(0);
        if (count <= 0)
            return null;
        int n = Math.min(count, WAVECONTACT_CAP);
        this.readTmpWave.clear();
        this.readTmpWave.limit(4 + n * WAVECONTACT_ENTRY_FLOATS * 4);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmpWave);
        float[] out = new float[n * WAVECONTACT_ENTRY_FLOATS];
        for (int i = 0; i < out.length; i++)
            out[i] = this.readTmpWave.getFloat(4 + i * 4);
        return out;
    }

    /**
     * Zeroes the heads table ({@link #GRID_TABLE} ints) ahead of each
     * gridbuild dispatch; the {@code next} chain half needs no clearing because
     * every slot is written before it can be traversed.
     */
    public void clearGridHeads() {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer zero = stack.mallocInt(1);
            zero.put(0).flip();
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.gridSSBO);
            GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                    0, (long) GRID_TABLE * 4, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, zero);
        }
    }

    /** Physical GL buffer id of the boids grid (heads+next). */
    public int gridBufferId() {
        return this.gridSSBO;
    }

    /**
     * Reads one ring slot's counters in an 8-byte readback:
     * {@code {writeSlot, spare}} = {@code {liveCount, translucentCensus}}. The
     * census is UNculled (every live ALPHA/MODEL particle, off-screen included)
     * — keygen counts it before the frustum test and {@code capture.comp}
     * copies it into {@code spare} at the end of each frame.
     * <p>
     * <b>Call this only when the fence covering that frame's GL work has
     * signalled</b> — {@code glGetBufferSubData} is otherwise a CPU-GPU
     * pipeline stall, however "lagged" the slot is.
     */
    public int[] readbackCounts(int slot) {
        this.readTmp8.clear();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.counterSSBOs[slot % COUNTER_RING]);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmp8);
        return new int[] { this.readTmp8.getInt(0), this.readTmp8.getInt(4) };
    }

    /**
     * Reads the crosshair hit-query snapshot (fence-covered): uvec4
     * {@code {key, hpBits, memberIdx, unused}} where key packs
     * {@code (quantized ray distance << 22 | particle index)} or
     * {@link #HIT_MISS}, hpBits holds the winner's HP as float bits and
     * memberIdx the storm member identity ({@link #HIT_MISS} = non-storm
     * MODEL particle — the legacy local damage path).
     */
    public int[] readbackHit() {
        this.readTmp16.clear();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.hitSSBO);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.readTmp16);
        return new int[] { this.readTmp16.getInt(0), this.readTmp16.getInt(4),
                this.readTmp16.getInt(8), this.readTmp16.getInt(12) };
    }

    /**
     * Uploads the damage-queue header (+ entries when {@code buf} carries any)
     * to the GPU. Must be enqueued BEFORE this frame's update dispatch; the
     * buffer layout matches the shader's std430 block (uvec4 count header at
     * offset 0, vec4 entries from offset 16).
     */
    public void uploadDamageQueue(ByteBuffer buf) {
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.damageSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
    }

    /** Replaces the given emit-command ring slot contents (data already flipped). */
    public void uploadEmits(int bufferId, FloatBuffer data) {
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufferId);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);
    }

    /** Records one emitter header; uploaded lazily on the next frame. */
    public void setEmitterHeader(int id, float[] block) {
        if (id < 0 || id >= this.maxEmitters)
            return;
        System.arraycopy(block, 0, this.emitterMirror, id * VEC4_PER_EMITTER * 4, VEC4_PER_EMITTER * 4);
        this.emittersDirty = true;
    }

    /** Re-writes one emitter header (e.g. a collision bake index changed). */
    public void updateEmitterHeader(int id, float[] block) {
        setEmitterHeader(id, block);
    }

    /**
     * Uploads dirty emitter headers into the existing SSBO (a single re-upload of
     * the mirror when anything changed; no delete/orphan churn).
     */
    public void uploadDirtyEmitters() {
        if (!this.emittersDirty)
            return;
        this.emittersDirty = false;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(this.maxEmitters * VEC4_PER_EMITTER * 4);
            buf.put(this.emitterMirror).flip();
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.emitterSSBO);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        }
    }

    /** Instantly drops every particle (all counter slots + instance counts zeroed). */
    public void clearParticles() {
        this.tmp4.clear();
        this.tmp4.putInt(0).putInt(0);
        this.tmp4.flip();
        for (int id : this.counterSSBOs) {
            GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        }

        this.tmp4.clear();
        this.tmp4.putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
        for (int i = 0; i < INDIRECT_COMMANDS; i++) {
            GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, i * INDIRECT_STRIDE + 4, this.tmp4);
        }

        // clear the damage queue and mark the hit query as a miss
        this.tmp4.clear();
        for (int i = 0; i < 4; i++)
            this.tmp4.putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.damageSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
        this.tmp4.clear();
        this.tmp4.putInt(HIT_MISS).putInt(0).putInt(HIT_MISS).putInt(0);
        this.tmp4.flip();
        GL30.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.hitSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, this.tmp4);
    }

    /**
     * Unbinds the engine's SSBO bases so they don't linger bound into the rest
     * of the world's rendering after our frame.
     */
    public void unbindShaders() {
        for (int i = 0; i <= WAVECONTACT_BB; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, 0);
        }
    }

    public void bindDrawIndirect() {
        GL30.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, this.indirectSSBO);
    }

    /** Draws draw-command {@code cmd} (offset in the indirect buffer). */
    public void drawIndirect(int cmd) {
        GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES, cmd * (long) INDIRECT_STRIDE);
    }

    /**
     * Draws BOTH MODEL sub-draws with one multi-draw: commands 2 and 3 are
     * contiguous element commands sharing program, VAO and state; their
     * per-command segment selection arrives through baseInstance + the mode
     * attribute. Zero-instance commands are skipped by the GPU, so the same
     * call also serves the fast path (translucent segment empty).
     */
    public void drawModelSegments() {
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                2L * INDIRECT_STRIDE, 2, INDIRECT_STRIDE);
    }

    /** Draws ONLY the CUTOUT MODEL sub-draw (command 2) -- shader-pack early path. */
    public void drawModelCutout() {
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                2L * INDIRECT_STRIDE, 1, INDIRECT_STRIDE);
    }

    /** Draws ONLY the GHOST MODEL sub-draw (command 3) -- shader-pack early path. */
    public void drawModelGhost() {
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT,
                3L * INDIRECT_STRIDE, 1, INDIRECT_STRIDE);
    }

    public void bindVao() {
        GL30.glBindVertexArray(this.vao);
    }

    public void free() {
        if (this.modelIndexBuffer > 0) {
            // detach the element binding from the VAO before deleting (the
            // ELEMENT_ARRAY binding is VAO state)
            if (this.vao >= 0)
                GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);
            GL15.glDeleteBuffers(this.modelIndexBuffer);
        }
        if (this.vao >= 0)
            GL30.glDeleteVertexArrays(this.vao);
        for (int id : this.particleSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        for (int id : this.emitSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        if (this.emitterSSBO > 0)
            GL15.glDeleteBuffers(this.emitterSSBO);
        if (this.indirectSSBO > 0)
            GL15.glDeleteBuffers(this.indirectSSBO);
        for (int id : this.counterSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        for (int id : this.sortSSBOs)
            if (id > 0)
                GL15.glDeleteBuffers(id);
        if (this.orderAddSSBO > 0)
            GL15.glDeleteBuffers(this.orderAddSSBO);
        if (this.orderOpaqueSSBO > 0)
            GL15.glDeleteBuffers(this.orderOpaqueSSBO);
        if (this.modelGeoSSBO > 0)
            GL15.glDeleteBuffers(this.modelGeoSSBO);
        if (this.histSSBO > 0)
            GL15.glDeleteBuffers(this.histSSBO);
        if (this.offsetSSBO > 0)
            GL15.glDeleteBuffers(this.offsetSSBO);
        if (this.bakeMetaSSBO > 0)
            GL15.glDeleteBuffers(this.bakeMetaSSBO);
        if (this.gridSSBO > 0)
            GL15.glDeleteBuffers(this.gridSSBO);
        if (this.damageSSBO > 0)
            GL15.glDeleteBuffers(this.damageSSBO);
        if (this.hitSSBO > 0)
            GL15.glDeleteBuffers(this.hitSSBO);
        if (this.playersSSBO > 0)
            GL15.glDeleteBuffers(this.playersSSBO);
        if (this.correctionSSBO > 0)
            GL15.glDeleteBuffers(this.correctionSSBO);
        if (this.stormPosSSBO > 0)
            GL15.glDeleteBuffers(this.stormPosSSBO);
        if (this.memberMapSSBO > 0)
            GL15.glDeleteBuffers(this.memberMapSSBO);
        if (this.waveContactSSBO > 0)
            GL15.glDeleteBuffers(this.waveContactSSBO);
        for (int[] t : this.mergedTbos)
            if (t[0] > 0)
                GL11.glDeleteTextures(t[0]);
        this.initialized = false;
    }
}
