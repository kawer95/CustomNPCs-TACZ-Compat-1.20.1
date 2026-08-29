package com.arxyt.customnpcstaczcompat.client;

/**
 * Render-side X/Z movement sampler. CNPC model limb swing is not reliable for scripted
 * navigation, so loop selection follows replicated position instead.
 */
public final class NativeNpcMovementTracker {
    private static final double WALK_START_DISTANCE = 0.01D;
    private static final double WALK_STOP_DISTANCE = 0.003D;
    private static final double TELEPORT_DISTANCE = 1.0D;
    private static final int WALK_STOP_GRACE_TICKS = 4;

    /*
     * EntityNPCInterface.tickCount is not advanced by every GBPort client replica.  This has to
     * be the client world's clock: it is the clock used by AnimationStack.tick() as well.
     */
    private long lastTick = Long.MIN_VALUE;
    private double lastX;
    private double lastZ;
    private boolean moving;
    private int idleTicks;
    private float speed;
    private float movementYaw;
    private double distancePerTick;
    private boolean teleported;

    public Sample sample(long tick, double x, double z) {
        if (lastTick == Long.MIN_VALUE || tick < lastTick || tick - lastTick > 5L) {
            reset(tick, x, z);
            return Sample.STOPPED;
        }
        if (tick == lastTick) return sample();

        long elapsedTicks = tick - lastTick;
        double dx = x - lastX;
        double dz = z - lastZ;
        distancePerTick = Math.sqrt(dx * dx + dz * dz) / elapsedTicks;
        lastTick = tick;
        lastX = x;
        lastZ = z;
        teleported = distancePerTick > TELEPORT_DISTANCE;
        if (teleported) {
            moving = false;
            idleTicks = 0;
            speed = 0.0F;
        } else {
            if (!moving) {
                moving = distancePerTick > WALK_START_DISTANCE;
                idleTicks = 0;
            } else if (distancePerTick > WALK_STOP_DISTANCE) {
                idleTicks = 0;
            } else if ((idleTicks += elapsedTicks) >= WALK_STOP_GRACE_TICKS) {
                moving = false;
                idleTicks = 0;
            }
            speed = moving ? clamp((float) distancePerTick * 4.0F, 0.1F, 1.0F) : 0.0F;
            if (moving) movementYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        return sample();
    }

    private void reset(long tick, double x, double z) {
        lastTick = tick;
        lastX = x;
        lastZ = z;
        moving = false;
        idleTicks = 0;
        speed = 0.0F;
        movementYaw = 0.0F;
        distancePerTick = 0.0D;
        teleported = false;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private Sample sample() {
        return new Sample(moving, speed, movementYaw, distancePerTick, idleTicks, teleported);
    }

    public record Sample(boolean walking, float speed, float movementYaw, double distancePerTick,
                         int idleTicks, boolean teleported) {
        public static final Sample STOPPED = new Sample(false, 0.0F, 0.0F, 0.0D, 0, false);

        /** True when displacement is predominantly opposite the aim-facing direction. */
        public boolean backpedalling(float facingYaw) {
            return walking && Math.abs(wrapDegrees(movementYaw - facingYaw)) > 100.0F;
        }

        private static float wrapDegrees(float degrees) {
            float wrapped = degrees % 360.0F;
            if (wrapped >= 180.0F) wrapped -= 360.0F;
            if (wrapped < -180.0F) wrapped += 360.0F;
            return wrapped;
        }
    }
}
