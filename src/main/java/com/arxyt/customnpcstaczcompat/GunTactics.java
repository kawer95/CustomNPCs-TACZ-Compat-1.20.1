package com.arxyt.customnpcstaczcompat;

/** Small deterministic movement policy for native CNPC TaCZ combat. */
public final class GunTactics {
    public static final double RETREAT_DISTANCE = 10.0D;

    private GunTactics() { }

    public static Maneuver decide(boolean canSee, double distance, double range, boolean closeQuarters) {
        double safeRange = Math.max(1.0D, range);
        if (!canSee || distance > safeRange) return Maneuver.PURSUE;
        if (!closeQuarters && distance < RETREAT_DISTANCE) return Maneuver.RETREAT;
        return Maneuver.HOLD;
    }

    public static Maneuver decideControlled(boolean commandedAttack, boolean canSee, double distance,
                                            double range, boolean closeQuarters, boolean prone) {
        if (!commandedAttack) return Maneuver.SENTRY;
        return prone ? Maneuver.HOLD : decide(canSee, distance, range, closeQuarters);
    }

    public static boolean canFire(boolean prone, boolean canSee, double distance, double range) {
        return canSee && (prone || distance <= Math.max(1.0D, range));
    }

    /** Dominion watch owns an authoritative multi-point ray result. */
    public static boolean effectiveLineOfSight(boolean watching, boolean vanillaCanSee, boolean watchHasClearShot) {
        return watching ? watchHasClearShot : vanillaCanSee;
    }

    public enum Maneuver { PURSUE, RETREAT, HOLD, SENTRY }
}
