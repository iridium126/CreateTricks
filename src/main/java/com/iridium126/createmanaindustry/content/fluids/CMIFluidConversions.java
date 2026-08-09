package com.iridium126.createmanaindustry.content.fluids;

import com.iridium126.createmanaindustry.config.ServerConfig;

public final class CMIFluidConversions {
    private CMIFluidConversions() {}

    public static int manaToFluidAmount(float manaAmount) {
        if (manaAmount <= 0)
            return 0;

        double amount = Math.ceil(manaAmount * 1000.0 / ServerConfig.manaPerBucket);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, amount));
    }

    public static int manaToFluidAmount(float manaAmount, int maxAmount) {
        if (maxAmount <= 0)
            return 0;
        return Math.min(manaToFluidAmount(manaAmount), maxAmount);
    }

    public static float fluidAmountToMana(int fluidAmount) {
        if (fluidAmount <= 0)
            return 0;
        return fluidAmount * ServerConfig.manaPerBucket / 1000f;
    }

    public static int mediaToFluidAmount(long media) {
        if (media <= 0)
            return 0;
        double amount = Math.ceil(media * 1000.0 / ServerConfig.mediaPerBucket);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, amount));
    }

    public static long fluidAmountToMedia(int fluidAmount) {
        if (fluidAmount <= 0)
            return 0;
        return (long) fluidAmount * ServerConfig.mediaPerBucket / 1000;
    }

    public static int sourceToFluidAmount(int source) {
        if (source <= 0)
            return 0;
        double amount = Math.ceil(source * 1000.0 / ServerConfig.sourcePerBucket);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, amount));
    }

    public static int fluidAmountToSource(int fluidAmount) {
        if (fluidAmount <= 0)
            return 0;
        return fluidAmount * ServerConfig.sourcePerBucket / 1000;
    }
}
