package com.iridium126.createmanaindustry;

import org.slf4j.Logger;

import com.iridium126.createmanaindustry.config.CMIStress;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlock;
import com.simibubi.create.api.stress.BlockStressValues;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorTooltipModifier;
import com.iridium126.createmanaindustry.hexcasting.CMISlatePatternRecipes;
import com.iridium126.createmanaindustry.trickster.KineticStressTrickRegister;
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
import net.neoforged.neoforge.common.ModConfigSpec;
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
        BNB_ACTIVE = ModList.get().isLoaded("bits_n_bobs");
        HEX_ACTIVE = ModList.get().isLoaded("hexcasting");
        VEIL_ACTIVE = ModList.get().isLoaded("veil");

        REGISTRATE.registerEventListeners(modEventBus);
        modEventBus.addListener(CMICapabilities::register);
        modEventBus.addListener(CreateManaIndustry::registerPayloads);
        CMICreativeModeTabs.register(modEventBus);
        CMIRecipeTypes.register(modEventBus);
        CMIComponents.register(modEventBus);
        CMIBlocks.register();
        CMIFluids.register();
        CMIBlockEntityTypes.register();
        CMIItems.register();
        if (TRICKSTER_ACTIVE) {
            KineticStressTrickRegister.register();
        }
        if (HEX_ACTIVE) {
            NeoForge.EVENT_BUS.addListener(CMISlatePatternRecipes::onServerStarted);
        }
        CMIPartialModels.register();
        {
            ModConfigSpec.Builder stressBuilder = new ModConfigSpec.Builder();
            CMIStress.INSTANCE.registerAll(stressBuilder);
            modContainer.registerConfig(ModConfig.Type.SERVER, stressBuilder.build());
            BlockStressValues.IMPACTS.registerProvider(CMIStress.INSTANCE::getImpact);
            BlockStressValues.CAPACITIES.registerProvider(CMIStress.INSTANCE::getCapacity);
        }
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
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
}
