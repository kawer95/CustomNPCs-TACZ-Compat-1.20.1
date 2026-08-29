package com.arxyt.customnpcstaczcompat;

/** Pure timing rules for CNPC target replacement; deliberately unit-testable without Minecraft. */
public final class TargetReactionTactics {
    private TargetReactionTactics() { }

    /** Static timing is twenty ticks; dynamic timing maps 0–180° onto the specified range. */
    public static int duration(boolean dynamic, double angleDegrees, boolean machineGun) {
        if (!dynamic) return 20;
        double safeAngle = Double.isFinite(angleDegrees) ? Math.max(0.0D, Math.min(180.0D, angleDegrees)) : 0.0D;
        int minimum = machineGun ? 1 : 10;
        return minimum + (int) Math.round(safeAngle / 180.0D * (40 - minimum));
    }

    /** A commander-selected, one-target attack must never wait for automatic acquisition. */
    public static boolean bypassesReactionWindow(boolean directAttackOrder) {
        return directAttackOrder;
    }
}
