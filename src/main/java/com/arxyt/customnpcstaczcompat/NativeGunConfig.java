package com.arxyt.customnpcstaczcompat;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server policy shared by all native-model gun goals. */
public final class NativeGunConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue LONG_DISTANCE;
    public static final ForgeConfigSpec.IntValue MEDIUM_DISTANCE;
    public static final ForgeConfigSpec.IntValue NEAR_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FORWARD_SPEED;
    public static final ForgeConfigSpec.DoubleValue SIDEWAYS_SPEED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("nativeTaczGunControl");
        LONG_DISTANCE = builder.defineInRange("longDistance", 64, 1, 512);
        MEDIUM_DISTANCE = builder.defineInRange("mediumDistance", 48, 1, 512);
        NEAR_DISTANCE = builder.defineInRange("nearDistance", 32, 1, 512);
        FORWARD_SPEED = builder.defineInRange("strafeForward", 0.4D, 0.0D, 1.0D);
        SIDEWAYS_SPEED = builder.defineInRange("strafeSideways", 0.2D, 0.0D, 1.0D);
        builder.pop();
        SPEC = builder.build();
    }

    private NativeGunConfig() { }
}
