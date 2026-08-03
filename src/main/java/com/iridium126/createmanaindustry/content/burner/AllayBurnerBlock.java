package com.iridium126.createmanaindustry.content.burner;

import javax.annotation.ParametersAreNonnullByDefault;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CMIBlockEntityTypes;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllShapes;
import com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.ItemUseType;
import com.simibubi.create.foundation.block.IBE;

import net.createmod.catnip.lang.Lang;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.ExplosionCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The Allay Burner: a blaze-burner-style heat source that holds a captured
 * allay. The allay dances while the burner burns, providing heat to basins
 * above. Mirrors Create's {@code BlazeBurnerBlock} with a three-state heat
 * level (NONE / IDLE / ALLAYHEATED) instead of the five blaze levels.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class AllayBurnerBlock extends HorizontalDirectionalBlock
        implements IBE<AllayBurnerBlockEntity>, IWrenchable, SpecialBlockItemRequirement {

    public static final EnumProperty<HeatLevel> HEAT_LEVEL = EnumProperty.create("heat_level", HeatLevel.class);

    public static final BooleanProperty HAS_RECORD = BlockStateProperties.HAS_RECORD;

    public static final MapCodec<AllayBurnerBlock> CODEC = simpleCodec(AllayBurnerBlock::new);

    public AllayBurnerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(HEAT_LEVEL, HeatLevel.NONE)
            .setValue(HAS_RECORD, false));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HEAT_LEVEL, FACING, HAS_RECORD);
    }

    /**
     * Jukebox mirror: popping the record out on any real removal (mirrors
     * {@code JukeboxBlock.onRemove}). Create contraption capture removes blocks
     * with {@code UPDATE_MOVE_BY_PISTON} (isMoving = true), so the guard keeps
     * the record inside the block entity NBT to travel with the contraption
     * instead of dropping into the world (same pattern as Create's
     * {@code AxisPipeBlock.onRemove}).
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (!isMoving && level.getBlockEntity(pos) instanceof AllayBurnerBlockEntity burner)
                burner.popOutTheItem();
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean moved) {
        if (world.isClientSide)
            return;
        BlockEntity blockEntity = world.getBlockEntity(pos.above());
        if (!(blockEntity instanceof BasinBlockEntity basin))
            return;
        basin.notifyChangeOfContents();
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos,
            Player player) {
        return getEmptyOrCapturedStack(state);
    }

    @Override
    public Class<AllayBurnerBlockEntity> getBlockEntityClass() {
        return AllayBurnerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AllayBurnerBlockEntity> getBlockEntityType() {
        return CMIBlockEntityTypes.ALLAY_BURNER.get();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (state.getValue(HEAT_LEVEL) == HeatLevel.NONE)
            return null;
        return IBE.super.newBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (state.getValue(HEAT_LEVEL) == HeatLevel.NONE)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // In 1.21.1 an empty hand always enters useItemOn(ItemStack.EMPTY)
        // first — useWithoutItem is only reached as the PASS fallback (main
        // hand). Pass through so an empty-hand click pops the record (vanilla
        // jukebox behavior); a FAIL here would suppress the fallback entirely.
        if (stack.isEmpty())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // Jukebox mirror: insert a record disc before any fuel logic. Written
        // by hand because JukeboxPlayable.tryInsertIntoJukebox hardcodes
        // Blocks.JUKEBOX. Discs never reach the fuel path (no media value).
        if (!state.getValue(HAS_RECORD) && stack.has(DataComponents.JUKEBOX_PLAYABLE)) {
            if (!level.isClientSide) {
                ItemStack disc = stack.consumeAndReturn(1, player);
                if (level.getBlockEntity(pos) instanceof AllayBurnerBlockEntity burner) {
                    burner.setTheItem(disc);
                    level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, state));
                }
                player.awardStat(Stats.PLAY_RECORD);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        boolean doNotConsume = player.isCreative();
        boolean forceOverflow = !(player instanceof FakePlayer);

        InteractionResultHolder<ItemStack> res =
            tryInsert(state, level, pos, stack, doNotConsume, forceOverflow, false);
        ItemStack leftover = res.getObject();
        if (!level.isClientSide && !doNotConsume && !leftover.isEmpty()) {
            if (stack.isEmpty()) {
                player.setItemInHand(hand, leftover);
            } else if (!player.getInventory()
                .add(leftover)) {
                player.drop(leftover, false);
            }
        }

        if (res.getResult() == InteractionResult.SUCCESS)
            return ItemInteractionResult.SUCCESS;
        // A failed fuel click must not fall through to useWithoutItem while a
        // record is inserted, or the record would pop out on every failed feed
        // (e.g. feeding a full burner).
        return state.getValue(HAS_RECORD) ? ItemInteractionResult.FAIL
            : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Jukebox mirror: an empty-hand right-click pops the record out (mirrors
     * {@code JukeboxBlock.useWithoutItem}). Only the empty hand reaches this
     * method — clicks with an item in hand go through {@link #useItemOn}.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (state.getValue(HAS_RECORD) && level.getBlockEntity(pos) instanceof AllayBurnerBlockEntity jukebox) {
            jukebox.popOutTheItem();
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    public static InteractionResultHolder<ItemStack> tryInsert(BlockState state, Level world, BlockPos pos,
            ItemStack stack, boolean doNotConsume, boolean forceOverflow, boolean simulate) {
        if (!state.hasBlockEntity())
            return InteractionResultHolder.fail(ItemStack.EMPTY);

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof AllayBurnerBlockEntity burnerBE))
            return InteractionResultHolder.fail(ItemStack.EMPTY);

        if (burnerBE.isCreativeFuel(stack)) {
            if (!simulate)
                burnerBE.applyCreativeFuel();
            return InteractionResultHolder.success(ItemStack.EMPTY);
        }
        if (!burnerBE.tryUpdateFuel(stack, forceOverflow, simulate))
            return InteractionResultHolder.fail(ItemStack.EMPTY);

        if (!doNotConsume) {
            ItemStack container = stack.hasCraftingRemainingItem() ? stack.getCraftingRemainingItem() : ItemStack.EMPTY;
            if (!world.isClientSide) {
                stack.shrink(1);
            }
            return InteractionResultHolder.success(container);
        }
        return InteractionResultHolder.success(ItemStack.EMPTY);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        ItemStack stack = context.getItemInHand();
        Item item = stack.getItem();
        BlockState defaultState = defaultBlockState();
        if (!(item instanceof AllayBurnerBlockItem))
            return defaultState;
        HeatLevel initialHeat =
            ((AllayBurnerBlockItem) item).hasCapturedAllay() ? HeatLevel.IDLE : HeatLevel.NONE;
        return defaultState.setValue(HEAT_LEVEL, initialHeat)
            .setValue(FACING, context.getHorizontalDirection()
                .getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter reader, BlockPos pos, CollisionContext context) {
        return AllShapes.HEATER_BLOCK_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter reader, BlockPos pos,
            CollisionContext context) {
        if (context == CollisionContext.empty())
            return AllShapes.HEATER_BLOCK_SPECIAL_COLLISION_SHAPE;
        return getShape(state, reader, pos, context);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return Math.max(0, state.getValue(HEAT_LEVEL)
            .ordinal() - 1);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        return false;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
        if (state.getValue(HEAT_LEVEL) != HeatLevel.ALLAYHEATED)
            return;
        if (random.nextInt(10) == 0) {
            world.playLocalSound(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, SoundSource.BLOCKS,
                0.4F + random.nextFloat() * 0.2F, 1.0F + random.nextFloat() * 0.3F, false);
        }
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    public static HeatLevel getHeatLevelOf(BlockState blockState) {
        return blockState.hasProperty(AllayBurnerBlock.HEAT_LEVEL) ? blockState.getValue(AllayBurnerBlock.HEAT_LEVEL)
            : HeatLevel.NONE;
    }

    public static int getLight(BlockState state) {
        return state.getValue(HEAT_LEVEL) == HeatLevel.ALLAYHEATED ? 15 : 0;
    }

    public static LootTable.Builder buildLootTable() {
        LootItemCondition.Builder survivesExplosion = ExplosionCondition.survivesExplosion();
        AllayBurnerBlock block = CMIBlocks.ALLAY_BURNER.get();
        LootTable.Builder builder = LootTable.lootTable();
        LootPool.Builder poolBuilder = LootPool.lootPool();
        for (HeatLevel level : HeatLevel.values()) {
            ItemLike drop = level == HeatLevel.NONE ? CMIBlocks.EMPTY_ALLAY_BURNER.get() : CMIBlocks.ALLAY_BURNER.get();
            poolBuilder.add(LootItem.lootTableItem(drop)
                .when(survivesExplosion)
                .when(LootItemBlockStatePropertyCondition.hasBlockStateProperties(block)
                    .setProperties(StatePropertiesPredicate.Builder.properties()
                        .hasProperty(HEAT_LEVEL, level))));
        }
        builder.withPool(poolBuilder.setRolls(ConstantValue.exactly(1)));
        return builder;
    }

    @Override
    public ItemRequirement getRequiredItems(BlockState state, @Nullable BlockEntity blockEntity) {
        return new ItemRequirement(ItemUseType.CONSUME, getEmptyOrCapturedStack(state));
    }

    private static ItemStack getEmptyOrCapturedStack(BlockState state) {
        boolean hasAllay = state.getValue(HEAT_LEVEL) != HeatLevel.NONE;
        return (hasAllay ? CMIBlocks.ALLAY_BURNER : CMIBlocks.EMPTY_ALLAY_BURNER).asStack();
    }

    public enum HeatLevel implements StringRepresentable {
        NONE, IDLE, ALLAYHEATED;

        @Override
        public String getSerializedName() {
            return Lang.asId(name());
        }
    }
}
