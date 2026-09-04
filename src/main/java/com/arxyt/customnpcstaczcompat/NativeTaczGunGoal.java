package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import noppes.npcs.entity.EntityNPCInterface;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Regular MOVE+LOOK gun goal for native-model CNPCs. */
public final class NativeTaczGunGoal extends Goal {
    private final EntityNPCInterface npc;
    private int cooldown;
    private int strafeTime = -1;
    private boolean clockwise;
    private boolean backwards;
    private double lastPursuitX;
    private double lastPursuitZ;
    private boolean pursuitPositionReady;
    private int pursuitStalledTicks;
    private Vec3 autonomousOrigin;
    private LivingEntity autonomousTarget;
    private boolean autonomousEngagement;
    private boolean returningToOrigin;
    private int nextAutonomousScanTick;

    public NativeTaczGunGoal(EntityNPCInterface npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        if (!NativeNpcEligibility.active(npc) || command.nativeCombatBlocked()
                || command.prone() || command.watching()) return false;
        LivingEntity target = target(command, true);
        // Acquisition range and weapon range are different. A target found within the fixed
        // 16-block alert radius must start this goal even when this NPC's configured gun range
        // is shorter; maneuver() will pursue until the weapon can legally fire.
        return (target != null && target.isAlive()) || (!command.active() && returningToOrigin);
    }

    @Override public boolean canContinueToUse() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        LivingEntity target = target(command, true);
        // Keep the goal alive across a preferred-range boundary: Dominion's reload service and
        // TaCZ's own weapon state must not be torn down while the NPC navigates back into range.
        return NativeNpcEligibility.active(npc) && !command.nativeCombatBlocked()
                && !command.prone() && !command.watching()
                && ((target != null && target.isAlive()) || (!command.active() && returningToOrigin));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        cooldown = 0;
        strafeTime = -1;
        pursuitPositionReady = false;
        pursuitStalledTicks = 0;
    }

    @Override
    public void stop() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        if (command.active() && DominionCommandBridge.hasQueuedAttack(npc)) {
            NativeGunDiagnostics.gate(npc, "REGULAR", "GOAL_STOPPED_AWAITING_QUEUED_TARGET", command,
                    command.attackTarget(), false, false, false, Double.NaN, range(command), cooldown);
        }
        // Do not erase a newly issued Dominion path in the selector update that stops this goal.
        if (!command.nativeCombatBlocked()) npc.getNavigation().stop();
        if (command.active()) npc.setSprinting(false);
        npc.getMoveControl().strafe(0.0F, 0.0F);
        NativeGunRuntime.tacz().stop(npc, false);
        // Command queues deliberately retain their final aim between targets. Autonomous
        // combat has no such queue ownership, so release its completed/lost-target lock.
        if (!command.active() || !DominionCommandBridge.hasQueuedAttack(npc)) NpcGunAimLock.clear(npc);
    }

    @Override
    public void tick() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        if (command.active()) npc.setSprinting(false);
        LivingEntity target = target(command, true);
        if (DominionCommandBridge.isReloadActive(npc)) {
            NativeNpcTargetReaction.satisfyDuringReload(npc, target, DominionCombatBalance.settings());
            NativeGunDiagnostics.gate(npc, "REGULAR", "DOMINION_RELOAD_ACTIVE", command, target,
                    false, false, false, target == null ? Double.NaN : npc.distanceTo(target), range(command), cooldown);
            hold(command);
            return;
        }
        if (target == null) {
            tickReturnToOrigin(command);
            return;
        }
        if (command.commandedAttack() && npc.getTarget() != target) npc.setTarget(target);

        DominionCombatBalance.Settings settings = DominionCombatBalance.settings();
        if (NativeNpcTargetReaction.blocks(npc, target, settings,
                command.directAttackOrder() || DominionCommandBridge.bypassesTargetReaction(npc))) {
            NativeGunDiagnostics.gate(npc, "REGULAR", "TARGET_REACTION_DELAY", command, target,
                    false, false, false, npc.distanceTo(target), range(command), cooldown);
            hold(command);
            NativeGunRuntime.tacz().stop(npc, false);
            return;
        }
        NativeNpcTargetReaction.noteTarget(npc, target, settings);
        npc.getLookControl().setLookAt(target, 90.0F, 90.0F);
        // A new target gets one replicated server tick before its first projectile. The facade
        // consumes this exact same solution, so model, muzzle and bullet cannot disagree.
        double desired = range(command);
        double distance = npc.distanceTo(target);
        boolean vanillaCanSee = npc.getSensing().hasLineOfSight(target);
        boolean breach = DominionCommandBridge.isBreachAssault(npc);
        Vec3 aimPoint = breach
                ? DominionCommandBridge.breachAimPoint(npc, target, vanillaCanSee ? target.getEyePosition() : null)
                : command.watching()
                ? DominionCommandBridge.watchAimPoint(npc, target, vanillaCanSee ? target.getEyePosition() : null)
                : target.getEyePosition();
        boolean canSee = command.watching() || breach ? aimPoint != null : vanillaCanSee;
        boolean aimReady = canSee && NpcGunAimLock.prepareForShot(npc, target,
                command.commandedAttack(), aimPoint);
        maneuver(command, target, canSee, distance, desired);

        boolean canFire = command.watching()
                ? canSee : GunTactics.canFire(command.prone(), canSee, distance, desired);
        if (!aimReady) {
            NativeGunDiagnostics.gate(npc, "REGULAR", "AIM_LOCK_NOT_READY", command, target,
                    false, vanillaCanSee, canSee, distance, desired, cooldown);
            return;
        }
        if (!canFire) {
            NativeGunDiagnostics.gate(npc, "REGULAR", canSee ? "OUT_OF_RANGE" : "NO_CLEAR_SHOT", command, target,
                    true, vanillaCanSee, canSee, distance, desired, cooldown);
            return;
        }
        if (--cooldown > 0) {
            NativeGunDiagnostics.gate(npc, "REGULAR", "GOAL_COOLDOWN", command, target,
                    true, vanillaCanSee, canSee, distance, desired, cooldown);
            return;
        }
        NativeTaczGunFacade.Action action = NativeGunRuntime.tacz().operate(npc, target);
        cooldown = action.delayTicks();
    }

    private void maneuver(DominionCommandBridge.Snapshot command, LivingEntity target,
                          boolean canSee, double distance, double range) {
        if (npc.isPassenger()) {
            hold(command);
            return;
        }
        if (command.active()) {
            if (DominionCommandBridge.isBreachEntering(npc)) {
                // Dominion owns doorway navigation until this NPC crosses the threshold. Keep
                // firing, but never pursue, retreat or strafe away from that entry path.
                strafeTime = -1;
                backwards = false;
                return;
            }
            GunTactics.Maneuver maneuver = command.watching() || DominionCommandBridge.isBreachStationary(npc)
                    ? GunTactics.Maneuver.SENTRY
                    : GunTactics.decideControlled(command.commandedAttack(), canSee, distance,
                    GunTactics.commandedApproachDistance(range),
                    command.closeQuarters(), command.prone());
            switch (maneuver) {
                case PURSUE -> {
                    strafeTime = -1;
                    double speed = DominionCommandBridge.commandMovementSpeed(npc, 1.0D);
                    // Sprint remains the ecosystem-wide run-animation signal. The gun facade
                    // clears it, together with TaCZ's cached sprint transition, only when this
                    // unit actually reaches a firing boundary.
                    npc.setSprinting(speed > 1.0D && !command.prone());
                    pursue(target, speed, distance, range);
                    npc.getMoveControl().strafe(0.0F, 0.0F);
                }
                case RETREAT -> {
                    npc.setSprinting(false);
                    npc.getNavigation().stop();
                    npc.getMoveControl().strafe(-0.5F, 0.0F);
                    NpcGunAimLock.alignForShot(npc, target);
                }
                case HOLD, SENTRY -> hold(command);
            }
            return;
        }
        if (!canSee || distance > range) {
            strafeTime = -1;
            pursue(target, 1.0D, distance, range);
            return;
        }
        npc.setSprinting(false);
        npc.getNavigation().stop();
        if (distance < 10.0D) {
            npc.getMoveControl().strafe(-0.5F, 0.0F);
            NpcGunAimLock.alignForShot(npc, target);
        } else {
            npc.getMoveControl().strafe(0.0F, 0.0F);
        }
    }

    /**
     * Entity-target paths can finish at a stale target node and then remain "accepted" while the
     * commanded shooter is still outside its firing range. Detect that exact state and rebuild a
     * coordinate path. This preserves normal obstacle-aware navigation and never bypasses range.
     */
    private void pursue(LivingEntity target, double speed, double distance, double range) {
        double movedSqr = pursuitPositionReady
                ? (npc.getX() - lastPursuitX) * (npc.getX() - lastPursuitX)
                + (npc.getZ() - lastPursuitZ) * (npc.getZ() - lastPursuitZ)
                : Double.POSITIVE_INFINITY;
        pursuitPositionReady = true;
        lastPursuitX = npc.getX();
        lastPursuitZ = npc.getZ();
        if (movedSqr < 0.0001D) pursuitStalledTicks++;
        else pursuitStalledTicks = 0;

        boolean entityAccepted = npc.getNavigation().moveTo(target, speed);
        boolean coordinateFallback = !entityAccepted
                || pursuitStalledTicks >= 10 && npc.getNavigation().isDone();
        boolean coordinateAccepted = false;
        if (coordinateFallback) {
            coordinateAccepted = npc.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), speed);
        }
        NativeGunDiagnostics.pursuit(npc, target, distance, range, speed, pursuitStalledTicks,
                entityAccepted, coordinateFallback, coordinateAccepted);
    }

    private void hold(DominionCommandBridge.Snapshot command) {
        pursuitPositionReady = false;
        pursuitStalledTicks = 0;
        if (command.active()) npc.setSprinting(false);
        npc.getNavigation().stop();
        npc.getMoveControl().strafe(0.0F, 0.0F);
    }

    private LivingEntity target(DominionCommandBridge.Snapshot command, boolean acquire) {
        if (command.active() && command.attackTarget() != null) {
            clearAutonomousState();
            return command.attackTarget();
        }
        boolean stationary = command.stationarySentry();
        if (command.active() && !stationary || npc.isPassenger()) {
            if (npc.getTarget() == autonomousTarget) npc.setTarget(null);
            clearAutonomousState();
            return null;
        }

        LivingEntity current = npc.getTarget();
        if (current != autonomousTarget && !IdleNpcTargeting.valid(npc, current)) {
            if (current != null) npc.setTarget(null);
            current = null;
        }
        // CNPC's native target selector may win the race before this goal performs its own
        // staggered scan. Adopt that target into the same engagement lifecycle immediately.
        // In particular, TaCZ may clear npc.getTarget() while beginning a reload; retaining the
        // adopted target here is what lets this goal restore it and resume fire afterwards.
        if (current != null && current != autonomousTarget) {
            if (!stationary && !autonomousEngagement) autonomousOrigin = npc.position();
            autonomousEngagement = !stationary;
            returningToOrigin = false;
            autonomousTarget = current;
        }
        if (!IdleNpcTargeting.engaged(npc, current, range(command))
                && IdleNpcTargeting.engaged(npc, autonomousTarget, range(command))) {
            current = autonomousTarget;
            npc.setTarget(current);
        }
        if (!IdleNpcTargeting.engaged(npc, current, range(command))) {
            if (current != null && autonomousEngagement) npc.setTarget(null);
            current = null;
        }
        if (current == null && acquire && npc.tickCount >= nextAutonomousScanTick) {
            nextAutonomousScanTick = npc.tickCount + 5 + Math.floorMod(npc.getId(), 5);
            current = IdleNpcTargeting.find(npc);
            if (current != null) {
                if (!stationary && !autonomousEngagement) autonomousOrigin = npc.position();
                autonomousEngagement = !stationary;
                returningToOrigin = false;
                autonomousTarget = current;
                npc.setTarget(current);
            }
        }
        if (current == null && autonomousEngagement && !stationary) returningToOrigin = true;
        return current;
    }

    private void tickReturnToOrigin(DominionCommandBridge.Snapshot command) {
        if (command.active() || !returningToOrigin || autonomousOrigin == null || npc.isPassenger()) return;
        if (npc.position().distanceToSqr(autonomousOrigin) <= 1.0D) {
            npc.getNavigation().stop();
            npc.getMoveControl().strafe(0.0F, 0.0F);
            clearAutonomousState();
            return;
        }
        npc.setSprinting(false);
        npc.getMoveControl().strafe(0.0F, 0.0F);
        npc.getNavigation().moveTo(autonomousOrigin.x, autonomousOrigin.y, autonomousOrigin.z, 1.0D);
    }

    private void clearAutonomousState() {
        autonomousOrigin = null;
        autonomousTarget = null;
        autonomousEngagement = false;
        returningToOrigin = false;
    }

    private double range(DominionCommandBridge.Snapshot command) {
        if (DominionCommandBridge.isBreachAssault(npc)) return 64.0D;
        if (command.watching()) return DominionCommandBridge.watchRange(npc,
                Math.max(2.0D, NpcTaczCombatApi.range(npc) * 2.0D));
        // The dedicated TaCZ page owns firing range for both ordered and autonomous combat.
        // CNPC's aggro range still decides who is acquired; it no longer silently replaces the
        // weapon range after a target has been acquired.
        return Math.max(1.0D, NpcTaczCombatApi.range(npc));
    }
}
