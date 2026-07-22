package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.content.fluids.condenser.CondenserBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerRenderer;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorRenderer;
import com.iridium126.createmanaindustry.content.kinetics.kineticmanagenerator.KineticManaGeneratorVisual;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

public final class CMIBlockEntityTypes {
    public static BlockEntityEntry<KineticManaGeneratorBlockEntity> KINETIC_MANA_GENERATOR;

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
