package com.iridium126.createmanaindustry;

import org.slf4j.Logger;

import com.iridium126.createmanaindustry.compat.hexcasting.CMIHexActions;
import com.iridium126.createmanaindustry.compat.hexcasting.CMIHexIotaTypes;
import com.iridium126.createmanaindustry.compat.hexcasting.CMIHexTrickActions;
import com.iridium126.createmanaindustry.compat.hexcasting.CMISlatePatternRecipes;
import com.iridium126.createmanaindustry.compat.hexcasting.InlineTrickData;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.CircleSlateManaPool;
import com.iridium126.createmanaindustry.compat.hexcasting.circle.SlateKnotInteraction;
import com.iridium126.createmanaindustry.compat.trickster.CMITricksterIotaRegister;
import com.samsthenerd.inline.api.InlineAPI;
import com.iridium126.createmanaindustry.compat.trickster.KineticStressTrickRegister;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlock;
import com.simibubi.create.api.boiler.BoilerHeater;
import com.simibubi.create.api.stress.BlockStressValues;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorTooltipModifier;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;

import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.iridium126.createmanaindustry.network.ClientboundMistSyncPacket;

@Mod(CreateManaIndustry.MODID)
public class CreateManaIndustry {
    public static final String MODID = "createmanaindustry";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ---- optional dependency flags (set in constructor) -------------------

    public static boolean TRICKSTER_ACTIVE = false;
    public static boolean BNB_ACTIVE = false;
    public static boolean HEX_ACTIVE = false;
    public static boolean VEIL_ACTIVE = false;
    public static boolean ARS_ACTIVE = false;
    public static boolean IRISVEIL_ACTIVE = false;

    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MODID);

    static {
        REGISTRATE.defaultCreativeTab(CMICreativeModeTabs.MAIN_TAB.getKey());
        REGISTRATE.setTooltipModifierFactory(CreateManaIndustry::createTooltipModifier);
    }

    private static TooltipModifier createTooltipModifier(Item item) {
        TooltipModifier description = new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE);
        if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof KineticManaGeneratorBlock) {
            return description.andThen(new KineticManaGeneratorTooltipModifier(
                    KineticManaGeneratorBlockEntity.MIN_STRESS_PER_RPM,
                    KineticManaGeneratorBlockEntity.MAX_STRESS_PER_RPM));
        }
        return description.andThen(TooltipModifier.mapNull(KineticStats.create(item)));
    }

    public CreateManaIndustry(IEventBus modEventBus, ModContainer modContainer) {
        TRICKSTER_ACTIVE = ModList.get().isLoaded("trickster");
        BNB_ACTIVE = ModList.get().isLoaded("bits_n_bobs") && ModList.get().isLoaded("trickster");
        HEX_ACTIVE = ModList.get().isLoaded("hexcasting");
        VEIL_ACTIVE = ModList.get().isLoaded("veil");
        ARS_ACTIVE = ModList.get().isLoaded("ars_nouveau");
        IRISVEIL_ACTIVE = ModList.get().isLoaded("irisveil");

        REGISTRATE.registerEventListeners(modEventBus);
        modEventBus.addListener(CMICapabilities::register);
        modEventBus.addListener(CreateManaIndustry::registerPayloads);
        CMICreativeModeTabs.register(modEventBus);
        CMIRecipeTypes.register(modEventBus);
        CMIComponents.register(modEventBus);
        CMIAttachments.register(modEventBus);
        CMIBlocks.register();
        CMIFluids.register();
        CMIBlockEntityTypes.register();
        CMIItems.register();
        CMIPartialModels.register();
        CMISpriteShifts.register();
        modEventBus.addListener(CreateManaIndustry::registerDefaultsAfterRegistration);

        if (TRICKSTER_ACTIVE) {
            KineticStressTrickRegister.register();
        }
        if (HEX_ACTIVE) {
            CMIHexActions.register(modEventBus);
            NeoForge.EVENT_BUS.addListener(CMISlatePatternRecipes::onServerStarted);
        }
        if (HEX_ACTIVE && TRICKSTER_ACTIVE) {
            // TrickIota + inline spell-tree rendering + read_trick_from_item
            // (hexcasting hard-depends on inline, so the InlineAPI call is safe here)
            CMIHexIotaTypes.register(modEventBus);
            InlineAPI.INSTANCE.addDataType(InlineTrickData.InlineTrickDataType.INSTANCE);
            CMIHexTrickActions.register(modEventBus);
            // Trickster fragment storing a Hexcasting iota + read_iota trick
            CMITricksterIotaRegister.register();
            // execute_trick circle support: slate knot slot interactions + mana pool type
            SlateKnotInteraction.register();
            CircleSlateManaPool.ensureTypeRegistered();
        }

        // Server-authoritative gameplay + stress config (synced to clients), and
        // the client-only rendering config. ServerConfig.build() must run after
        // block registration so the stress defaults are populated.
        ServerConfig.build();
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        BlockStressValues.IMPACTS.registerProvider(ServerConfig::getImpact);
        BlockStressValues.CAPACITIES.registerProvider(ServerConfig::getCapacity);

        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    public static ResourceLocation modLoc(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MODID).versioned("1");
        registrar.playToClient(
                ClientboundMistSyncPacket.TYPE,
                ClientboundMistSyncPacket.STREAM_CODEC,
                ClientboundMistSyncPacket::handle);
    }

    /**
     * Registers runtime defaults that reference this mod's registered objects,
     * mirroring Create's own {@code registerDefaults} pattern: they must run after
     * registration has finished (hence {@code FMLCommonSetupEvent} + enqueueWork),
     * not in the constructor where entries are still unbound.
     */
    private static void registerDefaultsAfterRegistration(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Allay Burner provides seething-equivalent boiler heat while ALLAYHEATED.
            // The custom BoilerHeater reads the Allay Burner's own HEAT_LEVEL (it has
            // no BlazeBurnerBlock.HEAT_LEVEL, so Create's BLAZE_BURNER heater cannot be
            // reused) and takes priority over the passive boiler-heaters tag provider.
            BoilerHeater.REGISTRY.register(CMIBlocks.ALLAY_BURNER.get(),
                (level, pos, state) -> state.getValue(AllayBurnerBlock.HEAT_LEVEL)
                        == AllayBurnerBlock.HeatLevel.ALLAYHEATED ? 2 : BoilerHeater.NO_HEAT);
        });
    }
}
