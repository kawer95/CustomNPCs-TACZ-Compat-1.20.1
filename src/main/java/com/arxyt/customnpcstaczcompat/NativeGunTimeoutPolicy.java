package com.arxyt.customnpcstaczcompat;

/** Dependency-free timeout policy for TaCZ results that should normally clear themselves. */
public final class NativeGunTimeoutPolicy {
    private NativeGunTimeoutPolicy() { }

    public static boolean transientFailure(String result) {
        return "COOL_DOWN".equals(result) || "IS_RELOADING".equals(result)
                || "IS_DRAWING".equals(result) || "IS_BOLTING".equals(result)
                || "IS_MELEE".equals(result) || "IS_SPRINTING".equals(result);
    }

    public static int timeoutTicks(String result) {
        if ("IS_SPRINTING".equals(result)) return 40;
        if ("COOL_DOWN".equals(result) || "IS_DRAWING".equals(result)
                || "IS_BOLTING".equals(result) || "IS_MELEE".equals(result)) return 120;
        if ("IS_RELOADING".equals(result)) return 600;
        return Integer.MAX_VALUE;
    }
}
