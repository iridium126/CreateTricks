package com.iridium126.createmanaindustry.content.burner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.ParametersAreNonnullByDefault;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.mixin.BaseSpawnerAccessor;

import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.registry.RegisteredObjectsHelper;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Allay Burner's item, in two variants like Create's
 * {@code BlazeBurnerBlockItem}:
 * <ul>
 * <li>{@code empty_allay_burner} — placeable as an empty cage (NONE state),
 * right-click on a living allay or an allay spawner captures the allay and
 * turns the item into the filled burner.</li>
 * <li>{@code allay_burner} — the captured variant, placed in the IDLE state.</li>
 * </ul>
 * Only the captured variant registers the block→item mapping, so
 * {@code Block.asItem()} resolves to the filled burner (mirrors Create).
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class AllayBurnerBlockItem extends BlockItem {

    public static final TagKey<EntityType<?>> ALLAY_BURNER_CAPTURABLE = TagKey.create(Registries.ENTITY_TYPE,
        CreateManaIndustry.modLoc("allay_burner_capturable"));

    private final boolean capturedAllay;

    public static AllayBurnerBlockItem empty(Block block, Item.Properties properties) {
        return new AllayBurnerBlockItem(block, properties, false);
    }

    public static AllayBurnerBlockItem withAllay(Block block, Item.Properties properties) {
        return new AllayBurnerBlockItem(block, properties, true);
    }

    @Override
    public void registerBlocks(Map<Block, Item> blockToItemMap, Item item) {
        if (!capturedAllay)
            return;
        super.registerBlocks(blockToItemMap, item);
    }

    private AllayBurnerBlockItem(Block block, Item.Properties properties, boolean capturedAllay) {
        super(block, properties);
        this.capturedAllay = capturedAllay;
    }

    @Override
    public String getDescriptionId() {
        return capturedAllay ? super.getDescriptionId()
            : "item." + CreateManaIndustry.MODID + "." + RegisteredObjectsHelper.getKeyOrThrow(this).getPath();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (capturedAllay)
            return super.useOn(context);

        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockEntity be = world.getBlockEntity(pos);
        Player player = context.getPlayer();

        if (!(be instanceof SpawnerBlockEntity))
            return super.useOn(context);

        BaseSpawner spawner = ((SpawnerBlockEntity) be).getSpawner();
        BaseSpawnerAccessor accessor = (BaseSpawnerAccessor) spawner;

        List<SpawnData> possibleSpawns = accessor.createmanaindustry$getSpawnPotentials()
            .unwrap()
            .stream()
            .map(WeightedEntry.Wrapper::data)
            .toList();

        if (possibleSpawns.isEmpty()) {
            SpawnData nextSpawnData = accessor.createmanaindustry$getNextSpawnData();
            possibleSpawns = new ArrayList<>();
            if (nextSpawnData != null)
                possibleSpawns.add(nextSpawnData);
        }

        for (SpawnData data : possibleSpawns) {
            Optional<EntityType<?>> optionalEntity = EntityType.by(data.entityToSpawn());
            if (optionalEntity.isEmpty() || !optionalEntity.get().is(ALLAY_BURNER_CAPTURABLE))
                continue;

            spawnCaptureEffects(world, VecHelper.getCenterOf(pos));
            if (world.isClientSide || player == null)
                return InteractionResult.SUCCESS;

            giveBurnerItemTo(player, context.getItemInHand(), context.getHand());
            return InteractionResult.SUCCESS;
        }

        return super.useOn(context);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack heldItem, Player player, LivingEntity entity,
            InteractionHand hand) {
        if (capturedAllay)
            return InteractionResult.PASS;
        if (!entity.getType()
            .is(ALLAY_BURNER_CAPTURABLE))
            return InteractionResult.PASS;

        Level world = player.level();
        spawnCaptureEffects(world, entity.position());
        if (world.isClientSide)
            return InteractionResult.FAIL;

        giveBurnerItemTo(player, heldItem, hand);
        entity.discard();
        return InteractionResult.FAIL;
    }

    protected void giveBurnerItemTo(Player player, ItemStack heldItem, InteractionHand hand) {
        ItemStack filled = CMIBlocks.ALLAY_BURNER.asStack();
        if (!player.isCreative())
            heldItem.shrink(1);
        if (heldItem.isEmpty()) {
            player.setItemInHand(hand, filled);
            return;
        }
        player.getInventory()
            .placeItemBackInInventory(filled);
    }

    private void spawnCaptureEffects(Level world, Vec3 vec) {
        if (world.isClientSide) {
            world.addParticle(ParticleTypes.SOUL_FIRE_FLAME, vec.x, vec.y + 0.3, vec.z, 0, 0.05, 0);
            return;
        }

        BlockPos soundPos = BlockPos.containing(vec);
        world.playSound(null, soundPos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, .5f, .9f);
    }

    public boolean hasCapturedAllay() {
        return capturedAllay;
    }
}
