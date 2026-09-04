package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Keeps native CNPC aim, replicated rotations, and TaCZ projectile input coherent. */
public final class NpcGunAimLock {
    private static final float BODY_YAW_DEGREES_PER_TICK = 20.0F;
    private static final float HEAD_YAW_DEGREES_PER_TICK = 34.0F;
    private static final float PITCH_DEGREES_PER_TICK = 26.0F;
    private static final float FIRE_YAW_TOLERANCE = 2.0F;
    private static final float FIRE_PITCH_TOLERANCE = 2.0F;
    private static final Map<EntityNPCInterface, AimState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private NpcGunAimLock() {
    }

    /**
     * Steers toward an exact target solution before a gun goal can shoot. A target hand-off waits
     * at least one complete server tick, and the muzzle remains gated until yaw and pitch have
     * physically caught up; this prevents a first bullet from leaving before its visual aim.
     */
    public static boolean prepareForShot(EntityNPCInterface npc, LivingEntity target, boolean commanded) {
        return prepareForShot(npc, target, commanded, target == null ? null : target.getEyePosition());
    }

    /** Uses the exact world-space point that passed the corresponding visibility test. */
    public static boolean prepareForShot(EntityNPCInterface npc, LivingEntity target, boolean commanded,
                                         Vec3 aimPoint) {
        AimSolution solution = solve(npc, target, aimPoint);
        if (solution == null) return false;
        AimState previous = STATES.get(npc);
        boolean changedTarget = previous == null || !previous.targetId().equals(solution.targetId());
        int readyAtTick = nextReadyTick(changedTarget, npc.tickCount,
                previous == null ? Integer.MIN_VALUE : previous.readyAtTick());
        AimState state = new AimState(solution.targetId(), target, aimPoint, solution, readyAtTick, commanded,
                previous == null ? Integer.MIN_VALUE : previous.lastSteeredTick(),
                previous == null ? Integer.MIN_VALUE : previous.lastDiagnosticTick(),
                changedTarget || previous == null ? Integer.MIN_VALUE : previous.lastSynchronizedTick());
        // LookAI is retained for CNPC command ownership, but calling rotate every gun-goal pass
        // makes the renderer snap and bypasses our first-shot barrier.  Establish it once only.
        if (changedTarget && commanded && npc.lookAi != null) npc.lookAi.rotate(target);
        STATES.put(npc, state);
        boolean aligned = aligned(npc, solution);
        boolean fireReady = npc.tickCount >= readyAtTick && aligned;
        trace(npc, state, changedTarget, aligned, fireReady);
        return fireReady;
    }

    /** Updates a live Dominion lock without forcing the fire gate to be queried. */
    public static void track(EntityNPCInterface npc, LivingEntity target) {
        prepareForShot(npc, target, true);
    }

    /** Aligns autonomous aim immediately without retaining a forced CustomNPC look-AI state. */
    public static void alignForShot(EntityNPCInterface npc, LivingEntity target) {
        prepareForShot(npc, target, false);
    }

    /** Returns this tick's exact unspread projectile direction, or recomputes it safely. */
    public static AimSolution solutionFor(EntityNPCInterface npc, LivingEntity target) {
        AimState state = npc == null ? null : STATES.get(npc);
        if (state != null && target != null && target.getUUID().equals(state.targetId())
                && state.solution().tick() == npc.tickCount) {
            return state.solution();
        }
        AimSolution solution = solve(npc, target);
        return solution == null ? AimSolution.INVALID : solution;
    }

    static AimSolution solve(EntityNPCInterface npc, LivingEntity target) {
        return solve(npc, target, target == null ? null : target.getEyePosition());
    }

    static AimSolution solve(EntityNPCInterface npc, LivingEntity target, Vec3 aimPoint) {
        if (npc == null || target == null || !target.isAlive() || aimPoint == null) return null;
        double dx = aimPoint.x - npc.getX();
        double dy = aimPoint.y - npc.getEyeY();
        double dz = aimPoint.z - npc.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(horizontal) || horizontal < 1.0E-6D || !Double.isFinite(dy)) return null;
        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return Float.isFinite(yaw) && Float.isFinite(pitch)
                ? new AimSolution(target.getUUID(), yaw, pitch, npc.tickCount) : null;
    }

    /** Retains the previous command-facing direction while Dominion advances a target queue. */
    public static void maintain(EntityNPCInterface npc) {
        if (npc == null || npc.level().isClientSide || !NativeNpcEligibility.active(npc)) {
            clear(npc);
            return;
        }
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        // Autonomous CNPC combat also uses prepareForShot's one-tick first-shot barrier.  Its
        // state must survive this tail hook: clearing it whenever Dominion is inactive made
        // every autonomous tick look like a fresh target and perpetually deferred fire to the
        // following tick. Command maintenance below remains Dominion-only.
        AimState state = STATES.get(npc);
        if (!command.active()) {
            LivingEntity target = npc.getTarget();
            if (state == null || target == null || !target.isAlive()
                    || !target.getUUID().equals(state.targetId())) {
                clear(npc);
                return;
            }
            advanceAndSynchronize(npc, state, target);
            return;
        }
        if (shouldClearDuringTail(command.active(), command.nativeCombatBlocked())) {
            clear(npc);
            return;
        }
        LivingEntity target = command.attackTarget();
        // Dominion's stationary sentry order deliberately has no commanded attack target: the
        // core latches a nearby threat into Mob#getTarget instead.  Clearing the aim state here
        // made the same living target look "new" on every following gun-goal tick, so readyAt was
        // perpetually advanced and the NPC could aim forever without firing.
        LivingEntity nativeTarget = npc.getTarget();
        if (shouldMaintainNativeTarget(command.active(), command.nativeCombatBlocked(), target != null,
                nativeTarget != null && nativeTarget.isAlive())) {
            target = nativeTarget;
        }
        if (target != null && target.isAlive()) {
            AimState current = STATES.get(npc);
            if (current == null || !target.getUUID().equals(current.targetId())) {
                track(npc, target);
                current = STATES.get(npc);
            }
            if (current != null) advanceAndSynchronize(npc, current, target);
            return;
        }
        if (DominionCommandBridge.hasQueuedAttack(npc)) {
            AimState retainedState = STATES.get(npc);
            if (retainedState != null) {
                LivingEntity retained = retainedState.target();
                if (retained != null && retained.isAlive()) advanceAndSynchronize(npc, retainedState, retained);
            }
            return;
        }
        clear(npc);
    }

    static float targetYaw(double dx, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || dx * dx + dz * dz < 1.0E-8D) return Float.NaN;
        return (float) -Math.toDegrees(Math.atan2(dx, dz));
    }

    static float stepAngle(float current, float target, float maximumChange) {
        return Mth.approachDegrees(current, target, maximumChange);
    }

    static boolean alignedAngles(float bodyYaw, float headYaw, float pitch,
                                 float targetYaw, float targetPitch) {
        return Math.abs(Mth.wrapDegrees(targetYaw - bodyYaw)) <= FIRE_YAW_TOLERANCE
                && Math.abs(Mth.wrapDegrees(targetYaw - headYaw)) <= FIRE_YAW_TOLERANCE
                && Math.abs(Mth.wrapDegrees(targetPitch - pitch)) <= FIRE_PITCH_TOLERANCE;
    }

    /** Pure gate used by tests and by the live target hand-off. */
    static int nextReadyTick(boolean targetChanged, int tick, int previousReadyAtTick) {
        return targetChanged ? tick + 1 : previousReadyAtTick;
    }

    /** Command-only tail maintenance must never discard an autonomous target's ready tick. */
    static boolean shouldClearDuringTail(boolean commandActive, boolean nativeCombatBlocked) {
        return commandActive && nativeCombatBlocked;
    }

    /** Allows the stationary sentry's latched native target to retain one continuous aim lock. */
    static boolean shouldMaintainNativeTarget(boolean commandActive, boolean nativeCombatBlocked,
                                              boolean hasCommandTarget, boolean nativeTargetAlive) {
        return commandActive && !nativeCombatBlocked && !hasCommandTarget && nativeTargetAlive;
    }

    /** Releases only the forced look state created by this compatibility layer. */
    public static void clear(EntityNPCInterface npc) {
        if (npc != null && STATES.remove(npc) != null && npc.lookAi != null) npc.lookAi.stop();
    }

    /** Applies one smooth, server-authoritative orientation step at most once each tick. */
    private static void steer(EntityNPCInterface npc, AimState state) {
        if (state.lastSteeredTick() == npc.tickCount) return;
        AimSolution solution = state.solution();
        float body = stepAngle(npc.yBodyRot, solution.yaw(), BODY_YAW_DEGREES_PER_TICK);
        float head = stepAngle(npc.getYHeadRot(), solution.yaw(), HEAD_YAW_DEGREES_PER_TICK);
        float yaw = stepAngle(npc.getYRot(), solution.yaw(), BODY_YAW_DEGREES_PER_TICK);
        float pitch = stepAngle(npc.getXRot(), solution.pitch(), PITCH_DEGREES_PER_TICK);
        npc.setYRot(yaw);
        npc.yBodyRot = body;
        npc.setYHeadRot(head);
        npc.setXRot(pitch);
        state.lastSteeredTick = npc.tickCount;
    }

    private static boolean aligned(EntityNPCInterface npc, AimSolution solution) {
        return alignedAngles(npc.yBodyRot, npc.getYHeadRot(), npc.getXRot(),
                solution.yaw(), solution.pitch());
    }

    /**
     * Runs after CNPC's own AI and body controllers.  It advances by one bounded angular step
     * and sends that real intermediate pose to clients.  Never copy the target solution directly
     * into the entity: the following gun-goal tick must observe the same visible turn and may only
     * fire after that turn actually reaches the configured tolerance.
     */
    private static void advanceAndSynchronize(EntityNPCInterface npc, AimState state, LivingEntity target) {
        AimSolution solution = solve(npc, target, state.aimPoint());
        if (solution == null) return;
        state.solution = solution;
        steer(npc, state);
        state.lastSynchronizedTick = npc.tickCount;
        NativeGunNetwork.syncAim(npc, npc.getYRot(), npc.yBodyRot, npc.getYHeadRot(), npc.getXRot());
    }

    /** A compact server-side trace for verifying the first-shot barrier in a real encounter. */
    private static void trace(EntityNPCInterface npc, AimState state, boolean targetChanged,
                              boolean aligned, boolean fireReady) {
        if (!targetChanged && state.lastDiagnosticTick() != Integer.MIN_VALUE
                && npc.tickCount - state.lastDiagnosticTick() < 10) return;
        state.lastDiagnosticTick = npc.tickCount;
        AimSolution solution = state.solution();
        CustomNpcsTaczCompat.LOGGER.info(
                "[CNPC-TACZ-AIM] npcId={} tick={} target={} changed={} commanded={} desired=({}, {}) actual=(yaw={}, body={}, head={}, pitch={}) readyAt={} aligned={} fireReady={}",
                npc.getId(), npc.tickCount, solution.targetId(), targetChanged, state.commanded(),
                decimal(solution.yaw()), decimal(solution.pitch()), decimal(npc.getYRot()), decimal(npc.yBodyRot),
                decimal(npc.getYHeadRot()), decimal(npc.getXRot()), state.readyAtTick(), aligned, fireReady);
    }

    private static String decimal(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public record AimSolution(UUID targetId, float yaw, float pitch, int tick) {
        private static final AimSolution INVALID = new AimSolution(new UUID(0L, 0L), 0.0F, 0.0F, Integer.MIN_VALUE);
        public boolean valid() {
            return tick != Integer.MIN_VALUE;
        }
    }

    private static final class AimState {
        private final UUID targetId;
        private final LivingEntity target;
        private final Vec3 aimPoint;
        private AimSolution solution;
        private final int readyAtTick;
        private final boolean commanded;
        private int lastSteeredTick;
        private int lastDiagnosticTick;
        private int lastSynchronizedTick;

        private AimState(UUID targetId, LivingEntity target, Vec3 aimPoint, AimSolution solution, int readyAtTick,
                         boolean commanded, int lastSteeredTick, int lastDiagnosticTick,
                         int lastSynchronizedTick) {
            this.targetId = targetId;
            this.target = target;
            this.aimPoint = aimPoint;
            this.solution = solution;
            this.readyAtTick = readyAtTick;
            this.commanded = commanded;
            this.lastSteeredTick = lastSteeredTick;
            this.lastDiagnosticTick = lastDiagnosticTick;
            this.lastSynchronizedTick = lastSynchronizedTick;
        }

        private UUID targetId() { return targetId; }
        private LivingEntity target() { return target; }
        private Vec3 aimPoint() { return aimPoint; }
        private AimSolution solution() { return solution; }
        private int readyAtTick() { return readyAtTick; }
        private boolean commanded() { return commanded; }
        private int lastSteeredTick() { return lastSteeredTick; }
        private int lastDiagnosticTick() { return lastDiagnosticTick; }
        private int lastSynchronizedTick() { return lastSynchronizedTick; }
    }
}
