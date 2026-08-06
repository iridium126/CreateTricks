package com.iridium126.createmanaindustry.ponder;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.burner.AllayBurnerBlock;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Ponder scenes for the Allay Burner, mirroring Create's
 * {@code ProcessingScenes.blazeBurner} / {@code emptyBlazeBurner} structure.
 */
public class AllayBurnerPonderScenes {

    private static final BlockPos BURNER = new BlockPos(2, 1, 2);
    private static final BlockPos BASIN = new BlockPos(2, 2, 2);

    private static void showBase(SceneBuilder scene, SceneBuildingUtil util) {
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);
    }

    private static void lightBurner(SceneBuilder scene) {
        scene.world().modifyBlock(BURNER,
            s -> s.setValue(AllayBurnerBlock.HEAT_LEVEL, AllayBurnerBlock.HeatLevel.ALLAYHEATED), false);
    }

    /** Scene 1: feeding the burner with amethyst materials. */
    public static void solidFuel(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("allay_burner/solid_fuel", "Feeding Allay Burners");
        showBase(scene, util);

        scene.world().showSection(util.select().position(BURNER), Direction.DOWN);
        scene.idle(10);

        scene.overlay().showText(60)
            .attachKeyFrame()
            .text("Amethyst materials can be used as fuel for the Allay Burner")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(70);

        scene.overlay().showControls(util.vector().topOf(BURNER), Pointing.DOWN, 30).rightClick()
            .withItem(new ItemStack(Items.AMETHYST_SHARD));
        scene.idle(7);
        lightBurner(scene);
        scene.idle(20);

        scene.overlay().showText(70)
            .attachKeyFrame()
            .text("Right-click the burner with Amethyst Shards, Amethyst Dust, or Charged Amethyst to add burn time")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(80);

        scene.overlay().showText(70)
            .colored(PonderPalette.MEDIUM)
            .text("The burn time scales with the media value of the amethyst")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(80);
    }

    /** Scene 2: fueling the burner with Liquid Media. */
    public static void liquidMedia(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("allay_burner/liquid_media", "Fueling with Liquid Media");
        showBase(scene, util);

        scene.world().showSection(util.select().position(BURNER), Direction.DOWN);
        scene.idle(10);

        scene.overlay().showText(60)
            .attachKeyFrame()
            .text("Liquid Media can be poured or piped into the burner's internal tank")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(70);

        scene.overlay().showControls(util.vector().topOf(BURNER), Pointing.DOWN, 30).rightClick()
            .withItem(new ItemStack(CMIFluids.LIQUID_MEDIA.get().getBucket()));
        scene.idle(7);
        lightBurner(scene);
        scene.idle(20);

        scene.overlay().showText(70)
            .attachKeyFrame()
            .text("The tank holds up to one bucket of Liquid Media, which is used once solid fuel runs out")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(80);

        scene.overlay().showText(70)
            .colored(PonderPalette.MEDIUM)
            .text("Solid fuel always takes priority over Liquid Media")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(80);
    }

    /** Scene 3: capturing an allay, inserting a record, and heating a basin. */
    public static void captureAllay(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("allay_burner/capture", "Capturing Allays");
        showBase(scene, util);

        BlockPos captureSpot = util.grid().at(2, 1, 2);
        scene.world().createEntity(w -> {
            Allay allay = EntityType.ALLAY.create(w);
            Vec3 v = util.vector().topOf(captureSpot.below());
            allay.setPosRaw(v.x, v.y, v.z);
            allay.setYRot(allay.yRotO = 180);
            return allay;
        });
        scene.idle(20);

        scene.overlay().showControls(util.vector().centerOf(captureSpot), Pointing.DOWN, 40).rightClick()
            .withItem(CMIBlocks.EMPTY_ALLAY_BURNER.asStack());
        scene.idle(10);
        scene.overlay().showText(60)
            .attachKeyFrame()
            .text("Right-click an Allay with the empty burner to capture it")
            .pointAt(util.vector().blockSurface(captureSpot, Direction.WEST))
            .placeNearTarget();
        scene.idle(50);

        scene.world().modifyEntities(Allay.class, Entity::discard);
        scene.idle(20);

        scene.world().showSection(util.select().position(BURNER), Direction.DOWN);
        scene.idle(20);

        scene.overlay().showText(70)
            .attachKeyFrame()
            .text("The captured Allay will provide Heat to the burner and dance while it burns")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(80);

        scene.overlay().showControls(util.vector().topOf(BURNER), Pointing.DOWN, 30).rightClick()
            .withItem(new ItemStack(Items.MUSIC_DISC_13));
        scene.idle(7);
        scene.world().modifyBlock(BURNER, s -> s.setValue(AllayBurnerBlock.HAS_RECORD, true), false);
        scene.idle(20);

        scene.overlay().showText(70)
            .attachKeyFrame()
            .text("Music Discs can be inserted and played like a Jukebox")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(80);

        scene.world().showSection(util.select().position(BASIN), Direction.DOWN);
        scene.idle(10);
        lightBurner(scene);
        scene.idle(20);

        scene.overlay().showText(80)
            .attachKeyFrame()
            .colored(PonderPalette.MEDIUM)
            .text("Place the burner below a Basin to process recipes requiring Allay Heat")
            .pointAt(util.vector().blockSurface(BASIN, Direction.WEST))
            .placeNearTarget();
        scene.idle(90);
    }

    /** Scene 4: Liquid Soul mist emission while burning. */
    public static void mistEmission(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("allay_burner/mist", "Allay Burner Mist");
        showBase(scene, util);

        scene.world().showSection(util.select().position(BURNER), Direction.DOWN);
        scene.idle(10);

        // The scene is drawn in the GUI phase after every Veil post pass, so
        // the real volumetric mist can never render in this viewport — draw a
        // billboarded mist volume as a scene element instead. Elements must be
        // registered via an instruction: PonderScene.begin() clears them and
        // replays all instructions on every scene restart.
        //PonderMistElement mist = new PonderMistElement(BURNER, Config.allayBurnerMistRadius, 0.28f);
        //scene.addInstruction(s -> s.addElement(mist));
        scene.idle(5);

        scene.overlay().showText(60)
            .attachKeyFrame()
            .text("While burning, the Allay Burner emits a field of Liquid Soul mist")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(70);

        lightBurner(scene);
        //scene.addInstruction(s -> mist.setActive(true));
        scene.idle(60);

        scene.overlay().showText(80)
            .attachKeyFrame()
            .colored(PonderPalette.MEDIUM)
            .text("The mist can be reclaimed with a Condenser and is required for mist-based recipes")
            .pointAt(util.vector().blockSurface(BURNER, Direction.WEST))
            .placeNearTarget();
        scene.idle(90);

        //scene.addInstruction(s -> mist.setActive(false));
        scene.idle(20);
    }

    private AllayBurnerPonderScenes() {}
}
