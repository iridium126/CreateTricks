package com.iridium126.createmanaindustry.content.burner;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.fluids.mist.MistEmitter;
import com.iridium126.createmanaindustry.compat.hexcasting.HexCompat;
import com.iridium126.createmanaindustry.config.Config;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ContainerSingleItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The Allay Burner's block entity: burns solid media items (amethyst dust /
 * shard / charged amethyst) fed by right-click, or Liquid Media drawn from a
 * 1-bucket internal tank. Solid fuel always takes priority — liquid is only
 * consumed when the burn time has run out. Fuel/overfill logic mirrors
 * Create's {@link BlazeBurnerBlockEntity} (INSERTION_THRESHOLD semantics;
 * overflow is uncapped — players may keep adding fuel indefinitely). Burn
 * durations derive from {@code Config.mediaConsumedPerTick} media consumed
 * per tick.
 */
public class AllayBurnerBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation, ContainerSingleItem.BlockContainerSingleItem {

    public static final int INSERTION_THRESHOLD = 500;
    public static final int TANK_CAPACITY = 1000;

    public static final String SONG_ITEM_TAG_ID = "RecordItem";
    public static final String TICKS_SINCE_SONG_STARTED_TAG_ID = "ticks_since_song_started";

    protected FuelType activeFuel;
    protected int remainingBurnTime;
    protected boolean isCreative;

    protected SmartFluidTankBehaviour tank;

    // Jukebox mirror: the inserted record and the server-side song player
    // (exactly like JukeboxBlockEntity).
    protected ItemStack item = ItemStack.EMPTY;
    protected final JukeboxSongPlayer jukeboxSongPlayer = new JukeboxSongPlayer(this::onSongChanged, this.getBlockPos());

    // Liquid Soul mist emission while burning, synced to the client like the
    // Kinetic Atomizer's mist field.
    private boolean wasActive = false;
    private int currentRadius = 0;

    // Client-side: the allay's facing angle (mirrors Create's blaze headAngle).
    // Idle burners look at the nearest player; burning burners face FACING.
    protected LerpedFloat headAngle;

    public AllayBurnerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        activeFuel = FuelType.NONE;
        remainingBurnTime = 0;
        isCreative = false;
        headAngle = LerpedFloat.angular();
        headAngle.startWithValue((AngleHelper.horizontalAngle(
            state.getOptionalValue(AllayBurnerBlock.FACING).orElse(Direction.SOUTH)) + 180) % 360);
    }

    public FuelType getActiveFuel() {
        return activeFuel;
    }

    public int getRemainingBurnTime() {
        return remainingBurnTime;
    }

    public boolean isCreative() {
        return isCreative;
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide) {
            // Client-side animation and particles (mirrors BlazeBurnerBlockEntity).
            if (level != null && level.isClientSide) {
                tickHeadAngle();
                if (!isVirtual())
                    spawnParticles(getHeatLevelFromBlock(), 1);
            }
            return;
        }

        // Jukebox mirror: tick the song player (fires 1011 + game events when
        // the song ends; mirrors JukeboxBlockEntity.tick).
        jukeboxSongPlayer.tick(level, getBlockState());

        // Burning burners emit a Liquid Soul mist field as a byproduct.
        // Uses the computed heat level (not the block state) so the mist turns
        // on the same tick fuel is applied and off the tick it runs out.
        boolean burning = getHeatLevel() == AllayBurnerBlock.HeatLevel.ALLAYHEATED;
        if (burning) {
            int radius = Config.allayBurnerMistRadius;
            if (!wasActive) {
                MistEmitter.activate(level, worldPosition, new FluidStack(CMIFluids.LIQUID_SOUL.get(), 1), radius);
                currentRadius = radius;
                wasActive = true;
                sendData();
            } else if (currentRadius != radius) {
                MistEmitter.updateRadius(level, worldPosition, radius);
                currentRadius = radius;
                sendData();
            }
            MistEmitter.addCapacity(level, worldPosition, Config.allayBurnerMistPerTick);
        } else if (wasActive) {
            MistEmitter.deactivate(level, worldPosition);
            wasActive = false;
            currentRadius = 0;
            sendData();
        }

        if (isCreative)
            return;

        if (remainingBurnTime > 0)
            remainingBurnTime--;

        if (remainingBurnTime == 0) {
            boolean wasBurning = activeFuel != FuelType.NONE;
            activeFuel = FuelType.NONE;
            // Liquid Media is consumed only when no solid fuel remains (solid priority).
            // One mB is converted into its share of burn ticks so Liquid Media is
            // consumed at mediaConsumedPerTick media per tick (1 mB = max(1,
            // mediaPerMb / mediaConsumedPerTick) ticks).
            if (hasLiquidMedia()) {
                IFluidHandler handler = tank.getPrimaryHandler();
                // Drain by amount, not by fluid identity: the tank may hold
                // either the source or flowing variant of Liquid Media
                // (pipes/buckets/condenser inject source; the Hexcasting media
                // battery injects flowing), and FluidTank.drain(FluidStack)
                // matches on the exact fluid instance.
                FluidStack drained = handler.drain(1, IFluidHandler.FluidAction.EXECUTE);
                if (drained.getAmount() > 0) {
                    remainingBurnTime = liquidBurnTicksPerMb();
                    activeFuel = FuelType.LIQUID;
                }
            }
            if (wasBurning || activeFuel == FuelType.LIQUID)
                updateBlockState();
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Fuel tank: pipes/buckets may only insert Liquid Media; the burner
        // itself drains it internally (external extraction is forbidden).
        tank = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.TYPE, this, 1, TANK_CAPACITY, false)
            .forbidExtraction()
            .whenFluidUpdates(() -> {});
        behaviours.add(tank);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        IFluidHandler capability =
            level.getCapability(Capabilities.FluidHandler.BLOCK, worldPosition, null);
        containedFluidTooltip(tooltip, isPlayerSneaking, capability);
        return true;
    }

    @Override
    public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        if (!isCreative) {
            compound.putInt("fuelLevel", activeFuel.ordinal());
            compound.putInt("burnTimeRemaining", remainingBurnTime);
        } else {
            compound.putBoolean("isCreative", true);
        }
        // Jukebox mirror: persist the record and song progress (vanilla tag names).
        if (!item.isEmpty())
            compound.put(SONG_ITEM_TAG_ID, item.save(registries));
        if (jukeboxSongPlayer.getSong() != null)
            compound.putLong(TICKS_SINCE_SONG_STARTED_TAG_ID, jukeboxSongPlayer.getTicksSinceSongStarted());
        if (clientPacket) {
            compound.putBoolean("MistActive", wasActive);
            compound.putInt("MistRadius", currentRadius);
        }
        super.write(compound, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        activeFuel = FuelType.values()[compound.getInt("fuelLevel")];
        remainingBurnTime = compound.getInt("burnTimeRemaining");
        isCreative = compound.getBoolean("isCreative");
        if (compound.contains(SONG_ITEM_TAG_ID, CompoundTag.TAG_COMPOUND))
            item = ItemStack.parse(registries, compound.getCompound(SONG_ITEM_TAG_ID)).orElse(ItemStack.EMPTY);
        else
            item = ItemStack.EMPTY;
        if (compound.contains(TICKS_SINCE_SONG_STARTED_TAG_ID, CompoundTag.TAG_LONG))
            JukeboxSong.fromStack(registries, item).ifPresent(song ->
                jukeboxSongPlayer.setSongWithoutPlaying(song, compound.getLong(TICKS_SINCE_SONG_STARTED_TAG_ID)));
        if (clientPacket) {
            wasActive = compound.getBoolean("MistActive");
            currentRadius = compound.getInt("MistRadius");
            MistEmitter.notifyClientSync(worldPosition,
                wasActive ? new FluidStack(CMIFluids.LIQUID_SOUL.get(), 1) : FluidStack.EMPTY, currentRadius);
        }
        super.read(compound, registries, clientPacket);
    }

    // ---- Jukebox mirror: exact port of JukeboxBlockEntity -------------------

    public JukeboxSongPlayer getSongPlayer() {
        return jukeboxSongPlayer;
    }

    public void onSongChanged() {
        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        setChanged();
    }

    private void notifyItemChangedInJukebox(boolean hasRecord) {
        if (level != null && level.getBlockState(worldPosition) == getBlockState()) {
            level.setBlock(worldPosition, getBlockState().setValue(AllayBurnerBlock.HAS_RECORD, hasRecord), 2);
            level.gameEvent(GameEvent.BLOCK_CHANGE, worldPosition, GameEvent.Context.of(getBlockState()));
        }
    }

    public void popOutTheItem() {
        if (level != null && !level.isClientSide) {
            ItemStack itemstack = getTheItem();
            if (!itemstack.isEmpty()) {
                removeTheItem();
                Vec3 vec3 = Vec3.atLowerCornerWithOffset(worldPosition, 0.5, 1.01, 0.5)
                    .offsetRandom(level.random, 0.7F);
                ItemEntity itementity = new ItemEntity(level, vec3.x(), vec3.y(), vec3.z(), itemstack.copy());
                itementity.setDefaultPickUpDelay();
                level.addFreshEntity(itementity);
            }
        }
    }

    public void setTheItem(ItemStack item) {
        this.item = item;
        boolean hasRecord = !this.item.isEmpty();
        Optional<Holder<JukeboxSong>> optional = JukeboxSong.fromStack(level.registryAccess(), this.item);
        this.notifyItemChangedInJukebox(hasRecord);
        if (hasRecord && optional.isPresent())
            this.jukeboxSongPlayer.play(this.level, optional.get());
        else
            this.jukeboxSongPlayer.stop(this.level, getBlockState());
    }

    @Override
    public ItemStack getTheItem() {
        return item;
    }

    @Override
    public ItemStack splitTheItem(int amount) {
        // Mirrors vanilla: removing the record also stops the song and clears
        // HAS_RECORD (hopper extraction included).
        ItemStack itemstack = this.item;
        this.setTheItem(ItemStack.EMPTY);
        return itemstack;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public BlockEntity getContainerBlockEntity() {
        return this;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.has(DataComponents.JUKEBOX_PLAYABLE) && getItem(slot).isEmpty();
    }

    @Override
    public boolean canTakeItem(Container target, int slot, ItemStack stack) {
        return target.hasAnyMatching(ItemStack::isEmpty);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        // Removes the mist field on break / chunk unload / contraption capture
        // (SmartBlockEntity.setRemoved is final and calls invalidate). The song
        // player is intentionally NOT stopped here: on contraption capture the
        // client sound must keep playing so the movement behaviour can adopt it.
        if (level != null && !level.isClientSide)
            MistEmitter.deactivate(level, worldPosition);
    }

    public AllayBurnerBlock.HeatLevel getHeatLevelFromBlock() {
        return AllayBurnerBlock.getHeatLevelOf(getBlockState());
    }

    public void updateBlockState() {
        setBlockHeat(getHeatLevel());
    }

    protected void setBlockHeat(AllayBurnerBlock.HeatLevel heat) {
        AllayBurnerBlock.HeatLevel inBlockState = getHeatLevelFromBlock();
        if (inBlockState == heat)
            return;
        level.setBlockAndUpdate(worldPosition, getBlockState().setValue(AllayBurnerBlock.HEAT_LEVEL, heat));
        notifyUpdate();
    }

    /**
     * @return true if the burner updated its burn time and an item should be
     *         consumed
     */
    protected boolean tryUpdateFuel(ItemStack itemStack, boolean forceOverflow, boolean simulate) {
        if (isCreative)
            return false;

        long media = getMediaAmount(itemStack.getItem());
        if (media <= 0)
            return false;

        int newBurnTime = burnTicksForMedia(media);

        // Solid fuel always takes over liquid fuel (solid priority).
        if (activeFuel == FuelType.LIQUID) {
            if (simulate)
                return true;
            activeFuel = FuelType.SOLID;
            remainingBurnTime = newBurnTime;
        } else if (activeFuel == FuelType.SOLID) {
            if (remainingBurnTime <= INSERTION_THRESHOLD) {
                newBurnTime += remainingBurnTime;
            } else if (forceOverflow) {
                newBurnTime += remainingBurnTime;
            } else {
                return false;
            }
            if (simulate)
                return true;
            activeFuel = FuelType.SOLID;
            remainingBurnTime = newBurnTime;
        } else {
            if (simulate)
                return true;
            activeFuel = FuelType.SOLID;
            remainingBurnTime = newBurnTime;
        }

        if (level.isClientSide) {
            spawnParticleBurst(true);
            return true;
        }

        AllayBurnerBlock.HeatLevel prev = getHeatLevelFromBlock();
        playSound();
        updateBlockState();

        if (prev != getHeatLevelFromBlock())
            level.playSound(null, worldPosition, SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, SoundSource.BLOCKS,
                .125f + level.random.nextFloat() * .125f, 1.15f - level.random.nextFloat() * .25f);

        return true;
    }

    /**
     * Adds burn time from the Light Burner hex action: burn time is added
     * unconditionally (no cap, matching the burner's existing fuel design) and
     * the burner is lit if it was idle.
     */
    public void addBurnTime(int ticks) {
        activeFuel = FuelType.SOLID;
        remainingBurnTime += ticks;
        if (level.isClientSide) {
            spawnParticleBurst(true);
            return;
        }
        playSound();
        updateBlockState();
        setChanged();
    }

    public boolean isCreativeFuel(ItemStack stack) {
        if (!CreateManaIndustry.HEX_ACTIVE)
            return false;
        return stack.getItem() == getHexItem("creative_unlocker");
    }

    public void applyCreativeFuel() {
        activeFuel = FuelType.NONE;
        remainingBurnTime = 0;
        isCreative = true;

        if (level.isClientSide) {
            spawnParticleBurst(true);
            return;
        }

        playSound();
        setBlockHeat(AllayBurnerBlock.HeatLevel.ALLAYHEATED);
    }

    /** Exposed capability — filters to Liquid Media only. */
    public IFluidHandler getFluidCapability() {
        return new AllayBurnerFluidHandler(tank.getCapability());
    }

    protected AllayBurnerBlock.HeatLevel getHeatLevel() {
        if (isCreative)
            return AllayBurnerBlock.HeatLevel.ALLAYHEATED;
        return remainingBurnTime > 0 ? AllayBurnerBlock.HeatLevel.ALLAYHEATED : AllayBurnerBlock.HeatLevel.IDLE;
    }

    /**
     * Burn ticks granted per millibucket of Liquid Media: one mB holds
     * {@code mediaPerBucket / 1000} media, consumed at
     * {@code mediaConsumedPerTick} media per tick → 1 mB =
     * max(1, mediaPerMb / mediaConsumedPerTick) ticks. With defaults
     * (400000 media/bucket, 50 media/tick) that is 8 ticks per mB — a full
     * bucket burns 8000 ticks.
     */
    protected int liquidBurnTicksPerMb() {
        long mediaPerMb = Math.max(1, Config.mediaPerBucket / 1000L);
        return (int) Math.max(1, mediaPerMb / Config.mediaConsumedPerTick);
    }

    protected int burnTicksForMedia(long media) {
        return (int) Math.max(1, media / Config.mediaConsumedPerTick);
    }

    protected long getMediaAmount(Item item) {
        if (item == Items.AMETHYST_SHARD) {
            // The shard's value comes from Hexcasting's config; without Hexcasting
            // the item can still burn using the stock default (SHARD_UNIT).
            return CreateManaIndustry.HEX_ACTIVE ? HexCompat.getShardMediaAmount() : 50_000L;
        }
        if (!CreateManaIndustry.HEX_ACTIVE)
            return 0;
        if (item == getHexItem("amethyst_dust"))
            return HexCompat.getDustMediaAmount();
        if (item == getHexItem("charged_amethyst"))
            return HexCompat.getChargedCrystalMediaAmount();
        return 0;
    }

    @Nullable
    private static Item getHexItem(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("hexcasting", path);
        Registry<Item> registry = BuiltInRegistries.ITEM;
        return registry.containsKey(id) ? registry.get(id) : null;
    }

    private boolean hasLiquidMedia() {
        return tank != null && !tank.getPrimaryHandler().getFluidInTank(0).isEmpty();
    }

    public boolean isValidBlockAbove() {
        if (isVirtual())
            return false;
        BlockState blockState = level.getBlockState(worldPosition.above());
        return BasinBlock.isBasin(level, worldPosition.above()) || blockState.getBlock() instanceof FluidTankBlock;
    }

    protected void playSound() {
        level.playSound(null, worldPosition, SoundEvents.ALLAY_ITEM_GIVEN, SoundSource.BLOCKS,
            .5f + level.random.nextFloat() * .5f, .9f + level.random.nextFloat() * .2f);
    }

    /**
     * Client-side facing: idle burners smoothly chase the nearest player,
     * burning burners hold FACING (mirrors {@code BlazeBurnerBlockEntity.tickAnimation}).
     */
    @OnlyIn(Dist.CLIENT)
    private void tickHeadAngle() {
        boolean active =
            getHeatLevelFromBlock() == AllayBurnerBlock.HeatLevel.ALLAYHEATED && isValidBlockAbove();

        if (!active) {
            float target = 0;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && !player.isInvisible()) {
                double x;
                double z;
                if (isVirtual()) {
                    x = -4;
                    z = -10;
                } else {
                    x = player.getX();
                    z = player.getZ();
                }
                double dx = x - (getBlockPos().getX() + 0.5);
                double dz = z - (getBlockPos().getZ() + 0.5);
                target = AngleHelper.deg(-Mth.atan2(dz, dx)) - 90;
            }
            target = headAngle.getValue() + AngleHelper.getShortestAngleDiff(headAngle.getValue(), target);
            headAngle.chase(target, .25f, Chaser.exp(5));
        } else {
            headAngle.chase((AngleHelper.horizontalAngle(getBlockState().getOptionalValue(AllayBurnerBlock.FACING)
                .orElse(Direction.SOUTH)) + 180) % 360, .125f, Chaser.EXP);
        }
        headAngle.tickChaser();
    }

    /**
     * Idle-burner particles, mirrors {@code BlazeBurnerBlockEntity.spawnParticles}
     * at SEETHING heat (the heat level this burner reports to basins).
     */
    protected void spawnParticles(AllayBurnerBlock.HeatLevel heatLevel, double burstMult) {
        if (level == null)
            return;
        if (heatLevel != AllayBurnerBlock.HeatLevel.ALLAYHEATED)
            return;

        RandomSource r = level.getRandom();

        Vec3 c = VecHelper.getCenterOf(worldPosition);
        Vec3 v = c.add(VecHelper.offsetRandomly(Vec3.ZERO, r, .125f)
            .multiply(1, 0, 1));

        if (r.nextInt(4) != 0)
            return;

        boolean empty = level.getBlockState(worldPosition.above())
            .getCollisionShape(level, worldPosition.above())
            .isEmpty();

        if (empty || r.nextInt(8) == 0)
            level.addParticle(ParticleTypes.LARGE_SMOKE, v.x, v.y, v.z, 0, 0, 0);

        double yMotion = empty ? .0625f : r.nextDouble() * .0125f;
        Vec3 v2 = c.add(VecHelper.offsetRandomly(Vec3.ZERO, r, .5f)
            .multiply(1, .25f, 1)
            .normalize()
            .scale((empty ? .25f : .5) + r.nextDouble() * .125f))
            .add(0, .5, 0);

        level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, v2.x, v2.y, v2.z, 0, yMotion, 0);
    }

    /**
     * Particle burst on fuel application, mirrors
     * {@code BlazeBurnerBlockEntity.spawnParticleBurst}. The Allay Burner has
     * a single solid fuel tier, so callers always pass soulFlame = true.
     */
    public void spawnParticleBurst(boolean soulFlame) {
        Vec3 c = VecHelper.getCenterOf(worldPosition);
        RandomSource r = level.random;
        for (int i = 0; i < 20; i++) {
            Vec3 offset = VecHelper.offsetRandomly(Vec3.ZERO, r, .5f)
                .multiply(1, .25f, 1)
                .normalize();
            Vec3 v = c.add(offset.scale(.5 + r.nextDouble() * .125f))
                .add(0, .125, 0);
            Vec3 m = offset.scale(1 / 32f);

            level.addParticle(soulFlame ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME, v.x, v.y, v.z, m.x, m.y,
                m.z);
        }
    }

    public enum FuelType {
        NONE, SOLID, LIQUID
    }
}
