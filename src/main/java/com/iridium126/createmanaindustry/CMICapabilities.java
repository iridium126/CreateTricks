package com.iridium126.createmanaindustry;

import net.minecraft.resources.ResourceLocation;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlockEntity;
import com.iridium126.createmanaindustry.content.fluids.EsotericManaFluidHandler;
import com.iridium126.createmanaindustry.content.fluids.MediaBatteryFluidHandler;
import com.iridium126.createmanaindustry.content.fluids.TricksterKnotFluidHandler;
import com.iridium126.createmanaindustry.content.items.TricksterKnotItemHandler;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlockEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import com.hollingsworth.arsnouveau.api.source.AbstractSourceMachine;
import com.iridium126.createmanaindustry.compat.ars.SourceJarFluidHandler;
import com.iridium126.createmanaindustry.compat.trickster.ConstructMediaItemHandler;
import com.iridium126.createmanaindustry.compat.trickster.TricksterKnotUtils;

public final class CMICapabilities {
    private CMICapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        // Allay Burner fluid handler — all faces, filtered to Liquid Media by
        // the wrapper (insert only; extraction refused).
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                CMIBlockEntityTypes.ALLAY_BURNER.get(),
                (be, side) -> ((AllayBurnerBlockEntity) be).getFluidCapability());

        // Kinetic Atomizer fluid handler — only accepts input from the bottom face.
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                CMIBlockEntityTypes.KINETIC_ATOMIZER.get(),
                (be, side) -> ((KineticAtomizerBlockEntity) be).getFluidHandler(side));

        // Media Battery fluid handler — bridges Create fluid system to Hexcasting media.
        // Uses data components only, no Level context needed.
        // Only registered when Hexcasting is present (guarded by HEX_ACTIVE to avoid
        // class-loading Hexcasting API types at runtime).
        if (CreateManaIndustry.HEX_ACTIVE) {
            ResourceLocation batteryId = ResourceLocation.fromNamespaceAndPath("hexcasting", "battery");
            Item mediaBattery = BuiltInRegistries.ITEM.get(batteryId);
            if (mediaBattery != Items.AIR) {
                event.registerItem(Capabilities.FluidHandler.ITEM,
                        (stack, ctx) -> new MediaBatteryFluidHandler(stack), mediaBattery);
            }
        }

        // Source Jar — bridges Liquid Source to Ars Nouveau source.
        // Only UP and DOWN faces are exposed so pipes connect to top/bottom only.
        if (CreateManaIndustry.ARS_ACTIVE) {
            BlockEntityType<?> sourceJarType = BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .get(ResourceLocation.fromNamespaceAndPath("ars_nouveau", "source_jar"));
            if (sourceJarType != null) {
                event.registerBlockEntity(Capabilities.FluidHandler.BLOCK,
                        sourceJarType,
                        (be, side) -> side == Direction.UP || side == Direction.DOWN
                                ? new SourceJarFluidHandler((AbstractSourceMachine) be)
                                : null);
            }

            BlockEntityType<?> creativeJarType = BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .get(ResourceLocation.fromNamespaceAndPath("ars_nouveau", "creative_source_jar"));
            if (creativeJarType != null) {
                event.registerBlockEntity(Capabilities.FluidHandler.BLOCK,
                        creativeJarType,
                        (be, side) -> side == Direction.UP || side == Direction.DOWN
                                ? new SourceJarFluidHandler((AbstractSourceMachine) be)
                                : null);
            }
        }

        if (!CreateManaIndustry.TRICKSTER_ACTIVE)
            return;

        Item esotericMana = BuiltInRegistries.ITEM.get(EsotericManaFluidHandler.ESOTERIC_MANA_ID);
        if (esotericMana == Items.AIR)
            esotericMana = null;

        if (esotericMana != null)
            event.registerItem(Capabilities.FluidHandler.ITEM, (stack, ctx) -> new EsotericManaFluidHandler(stack),
                    esotericMana);

        Item[] knotItems = BuiltInRegistries.ITEM.stream()
                .filter(TricksterKnotUtils::isKnotItem)
                .toArray(Item[]::new);
        if (knotItems.length > 0)
            event.registerItem(Capabilities.FluidHandler.ITEM, (stack, ctx) -> new TricksterKnotFluidHandler(stack), knotItems);

        registerTricksterKnotItemHandler(event, "charging_array", TricksterKnotItemHandler.Mode.ALL_SLOTS);
        if (CreateManaIndustry.HEX_ACTIVE) {
            // Constructs also absorb hex media from inserted items (eval_iota charging).
            registerConstructMediaItemHandler(event, "spell_construct", TricksterKnotItemHandler.Mode.FIRST_SLOT);
            registerConstructMediaItemHandler(event, "modular_spell_construct", TricksterKnotItemHandler.Mode.FIRST_SLOT);
        } else {
            registerTricksterKnotItemHandler(event, "spell_construct", TricksterKnotItemHandler.Mode.FIRST_SLOT);
            registerTricksterKnotItemHandler(event, "modular_spell_construct", TricksterKnotItemHandler.Mode.FIRST_SLOT);
        }
    }

    private static void registerTricksterKnotItemHandler(RegisterCapabilitiesEvent event, String path,
            TricksterKnotItemHandler.Mode mode) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("trickster", path);
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
        if (type != null)
            registerTricksterKnotItemHandler(event, type, mode);
    }

    @SuppressWarnings("unchecked")
    private static void registerTricksterKnotItemHandler(RegisterCapabilitiesEvent event, BlockEntityType<?> type,
            TricksterKnotItemHandler.Mode mode) {
        BlockEntityType<BlockEntity> blockEntityType = (BlockEntityType<BlockEntity>) type;
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, blockEntityType,
                (blockEntity, side) -> TricksterKnotItemHandler.create(blockEntity, mode));
    }

    @SuppressWarnings("unchecked")
    private static void registerConstructMediaItemHandler(RegisterCapabilitiesEvent event, String path,
            TricksterKnotItemHandler.Mode mode) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("trickster", path);
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
        if (type != null) {
            BlockEntityType<BlockEntity> blockEntityType = (BlockEntityType<BlockEntity>) type;
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, blockEntityType,
                    (blockEntity, side) -> {
                        var knotHandler = TricksterKnotItemHandler.create(blockEntity, mode);
                        return knotHandler != null ? new ConstructMediaItemHandler(blockEntity, knotHandler) : null;
                    });
        }
    }
}
