package com.iridium126.createmanaindustry.content.kinetics.depositionlid;

import java.util.List;
import java.util.Optional;

import com.iridium126.createmanaindustry.content.recipes.VaporDepositionRecipe;
import com.simibubi.create.AllParticleTypes;
import com.simibubi.create.content.fluids.particle.FluidParticleData;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Operator for the deposition lid — modeled on Create Diesel Generators'
 * {@code BasinLidBlockEntity}. Sits one block above the basin and reuses the
 * whole {@link BasinOperatingBlockEntity} pipeline (recipe trie lookup,
 * {@link #applyBasinRecipe()}, deferral scheduling, NBT progress).
 * <p>
 * The lid has no shaft and never spins, so {@link #updateBasin()} deliberately
 * omits the speed check. It must also self-schedule its basin check every idle
 * tick, because the basin's own {@code getOperator()} only looks two blocks up
 * (at press/mixer height) and will not find a lid directly above it.
 * <p>
 * Once started, a recipe runs to completion: mid-process heat or mist loss does
 * not cancel it. Only opening the lid, or the basin below being removed, resets
 * progress.
 */
public class DepositionLidBlockEntity extends BasinOperatingBlockEntity {

    /** Fallback processing time (ticks) when a recipe omits {@code processing_time}. */
    public static final int DEFAULT_PROCESSING_TIME = 200;

    public boolean running = false;
    public int processingTime = -1;
    public float progress = 0f;

    /**
     * The mist fluid being deposited — resolved server-side from the recipe's
     * {@code mist_requirement} when processing starts and synced to the client
     * via the client packet, so particles don't depend on {@code currentRecipe}
     * (which the client never has).
     */
    private FluidStack depositingFluid = FluidStack.EMPTY;

    public DepositionLidBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("ProcessingTime", this.processingTime);
        tag.putBoolean("Running", this.running);
        tag.putFloat("Progress", this.progress);
        if (clientPacket && !this.depositingFluid.isEmpty())
            tag.put("DepositingFluid", this.depositingFluid.saveOptional(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.processingTime = tag.getInt("ProcessingTime");
        this.running = tag.getBoolean("Running");
        this.progress = tag.getFloat("Progress");
        if (clientPacket) {
            this.depositingFluid = tag.contains("DepositingFluid")
                    ? FluidStack.parseOptional(registries, tag.getCompound("DepositingFluid"))
                    : FluidStack.EMPTY;
        }
    }

    @Override
    protected void onBasinRemoved() {
        if (!this.running)
            return;
        this.processingTime = 0;
        this.currentRecipe = null;
        this.running = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (currentRecipe != null) {
            progress = (float) processingTime / getDuration();
        } else {
            // Idle: re-find a matching recipe (also re-resolves currentRecipe on
            // chunk reload, since recipes are not serialized).
            if (processingTime != -1) {
                List<Recipe<?>> recipes = getMatchingRecipes();
                if (!recipes.isEmpty())
                    currentRecipe = recipes.get(0);
                else
                    processingTime = -1;
            }
            progress = 0;
        }

        // Cancel when idle, opened, or the basin below is gone. Self-schedules the
        // recipe trie poll via the deferral behaviour. Sync the stop (one packet,
        // only on the true→false transition) so the client stops spawning particles.
        if ((!level.isClientSide && (currentRecipe == null || processingTime == -1))
                || getBlockState().getValue(BlockStateProperties.OPEN)
                || !(level.getBlockEntity(worldPosition.below()) instanceof BasinBlockEntity)) {
            if (this.running && !level.isClientSide)
                sendData();
            this.running = false;
            this.processingTime = -1;
            this.basinChecker.scheduleUpdate();
        }

        if (running && level != null) {
            if (!level.isClientSide && processingTime <= 0) {
                processingTime = -1;
                applyBasinRecipe();
                sendData();
            }
            if (processingTime > 0)
                processingTime--;
        }

        if (level != null && level.isClientSide && running)
            spawnDepositionParticles();
    }

    @Override
    protected boolean updateBasin() {
        if (this.running)
            return true;
        if (this.level == null)
            return true;
        if (this.getBasin().filter(BasinBlockEntity::canContinueProcessing).isEmpty())
            return true;

        List<Recipe<?>> recipes = this.getMatchingRecipes();
        if (recipes.isEmpty())
            return true;
        this.currentRecipe = recipes.get(0);
        this.startProcessingBasin();
        this.sendData();
        return true;
    }

    @Override
    public void startProcessingBasin() {
        if (this.running && this.processingTime > 0)
            return;
        super.startProcessingBasin();
        this.running = true;
        this.processingTime = getDuration();
        this.depositingFluid = resolveDepositingFluid();
    }

    /** The recipe's mist fluid, or empty when the recipe has no mist requirement. */
    private FluidStack resolveDepositingFluid() {
        if (currentRecipe instanceof VaporDepositionRecipe vaporRecipe) {
            var requirement = vaporRecipe.getMistRequirement();
            if (requirement != null) {
                Fluid fluid = BuiltInRegistries.FLUID.get(requirement.fluidId());
                if (fluid != null && !fluid.isSame(Fluids.EMPTY))
                    return new FluidStack(fluid, 1);
            }
        }
        return FluidStack.EMPTY;
    }

    @Override
    protected boolean isRunning() {
        return running;
    }

    @Override
    protected Optional<BasinBlockEntity> getBasin() {
        if (level == null)
            return Optional.empty();
        BlockEntity basinBE = level.getBlockEntity(worldPosition.below(1));
        if (!(basinBE instanceof BasinBlockEntity))
            return Optional.empty();
        if (getBlockState().getValue(BlockStateProperties.OPEN))
            return Optional.empty();
        return Optional.of((BasinBlockEntity) basinBE);
    }

    @Override
    protected boolean matchStaticFilters(RecipeHolder<? extends Recipe<?>> recipe) {
        return recipe.value() instanceof VaporDepositionRecipe;
    }

    private static final Object depositionRecipesKey = new Object();

    @Override
    protected Object getRecipeCacheKey() {
        return depositionRecipesKey;
    }

    /** Recipe duration, falling back to {@link #DEFAULT_PROCESSING_TIME} when unspecified. */
    private int getDuration() {
        if (currentRecipe instanceof ProcessingRecipe<?, ?> processed) {
            int duration = processed.getProcessingDuration();
            if (duration > 0)
                return duration;
        }
        return DEFAULT_PROCESSING_TIME;
    }

    /** Client-side mist drip particles while depositing (mirrors the condenser). */
    @OnlyIn(Dist.CLIENT)
    private void spawnDepositionParticles() {
        // Uses the synced depositing fluid — currentRecipe is never available on
        // the client (recipes are not synced, and the client's mist field is empty).
        if (depositingFluid.isEmpty())
            return;

        double cx = worldPosition.getX() + 0.5;
        double cz = worldPosition.getZ() + 0.5;
        // The basin opening sits just below the lid block.
        double surface = worldPosition.getY() - 0.1;
        var particle = new FluidParticleData(AllParticleTypes.FLUID_DRIP.get(), depositingFluid);

        for (int i = 0; i < 2; i++) {
            double x = cx + (level.random.nextDouble() - 0.5) * 0.7;
            double z = cz + (level.random.nextDouble() - 0.5) * 0.7;
            if (level instanceof ClientLevel) {
                ParticleEngine engine = Minecraft.getInstance().particleEngine;
                var p = engine.createParticle(particle, x, surface, z, 0, 0, 0);
                if (p != null) {
                    p.scale(0.35f);
                    engine.add(p);
                }
            }
        }
    }
}
