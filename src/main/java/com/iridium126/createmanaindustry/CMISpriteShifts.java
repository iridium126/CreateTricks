package com.iridium126.createmanaindustry;

import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SpriteShifter;

public final class CMISpriteShifts {

    public static final SpriteShiftEntry ALLAY_BURNER_FLAME =
        SpriteShifter.get(CreateManaIndustry.modLoc("block/allay_burner/allay_burner_flame"),
            CreateManaIndustry.modLoc("block/allay_burner/allay_burner_flame_scroll"));

    /**
     * Forced early class-load: catnip resolves {@link SpriteShiftEntry} sprites
     * only when the texture atlas reloads ({@code StitchedSprite.onTextureStitchPost}),
     * so every entry must be registered before the first atlas reload. Called
     * from {@code CreateManaIndustry} alongside {@code CMIPartialModels.register()}.
     */
    public static void register() {}

    private CMISpriteShifts() {}
}
