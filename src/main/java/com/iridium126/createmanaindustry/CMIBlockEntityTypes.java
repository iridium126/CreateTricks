package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlockEntity;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerRenderer;
import com.iridium126.createmanaindustry.content.fluids.condenser.CondenserBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerRenderer;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorRenderer;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorVisual;
import com.iridium126.createmanaindustry.content.kinetics.manacogwheel.ManaCogwheelBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.manacogwheel.ManaCogwheelRenderer;
import com.iridium126.createmanaindustry.content.kinetics.manacogwheel.ManaCogwheelVisual;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

public final class CMIBlockEntityTypes {
    public static BlockEntityEntry<KineticManaGeneratorBlockEntity> KINETIC_MANA_GENERATOR;

    public static final BlockEntityEntry<AllayBurnerBlockEntity> ALLAY_BURNER = REGISTRATE
            .blockEntity("allay_burner", AllayBurnerBlockEntity::new)
            .validBlocks(CMIBlocks.ALLAY_BURNER)
            .renderer(() -> AllayBurnerRenderer::new)
            .register();

    public static final BlockEntityEntry<KineticAtomizerBlockEntity> KINETIC_ATOMIZER = REGISTRATE
            .blockEntity("kinetic_atomizer", KineticAtomizerBlockEntity::new)
            .visual(() -> SingleAxisRotatingVisual.of(CMIPartialModels.KINETIC_ATOMIZER_COG), true)
            .validBlocks(CMIBlocks.KINETIC_ATOMIZER)
            .renderer(() -> KineticAtomizerRenderer::new)
            .register();

    public static final BlockEntityEntry<CondenserBlockEntity> CONDENSER = REGISTRATE
            .blockEntity("condenser", CondenserBlockEntity::new)
            .validBlocks(CMIBlocks.CONDENSER)
            .register();

    public static final BlockEntityEntry<ManaCogwheelBlockEntity> MANA_COGWHEEL = REGISTRATE
            .blockEntity("mana_cogwheel", ManaCogwheelBlockEntity::new)
            .visual(() -> ManaCogwheelVisual::create, false)
            .validBlocks(CMIBlocks.MANA_COGWHEEL,
                    CMIBlocks.ANDESITE_ENCASED_MANA_COGWHEEL,
                    CMIBlocks.BRASS_ENCASED_MANA_COGWHEEL)
            .renderer(() -> ManaCogwheelRenderer::new)
            .register();

    private CMIBlockEntityTypes() {}

    public static void register() {
        if (CreateManaIndustry.TRICKSTER_ACTIVE) {
            KINETIC_MANA_GENERATOR = REGISTRATE
                    .blockEntity("kinetic_mana_generator", KineticManaGeneratorBlockEntity::new)
                    .visual(() -> KineticManaGeneratorVisual::new, false)
                    .validBlocks(CMIBlocks.KINETIC_MANA_GENERATOR)
                    .renderer(() -> KineticManaGeneratorRenderer::new)
                    .register();
        }
    }
}
