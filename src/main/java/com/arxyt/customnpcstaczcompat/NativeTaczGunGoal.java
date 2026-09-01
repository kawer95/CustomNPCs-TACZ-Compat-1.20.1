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

    public NativeTaczGunGoal(EntityNPCInterface npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        LivingEntity target = target(command);
        return NativeNpcEligibility.active(npc) && target != null && target.isAlive()
                && !command.nativeCombatBlocked()
                && (command.commandedAttack() || npc.distanceTo(target) <= range(command));
    }

    @Override public boolean canContinueToUse() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        LivingEntity target = target(command);
        // Keep the goal alive across a preferred-range boundary: Dominion's reload service and
        // TaCZ's own weapon state must not be torn down while the NPC navigates back into range.
        return NativeNpcEligibility.active(npc) && target != null && target.isAlive()
                && !command.nativeCombatBlocked();
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { cooldown = 0; strafeTime = -1; }

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
        LivingEntity target = target(command);
        if (DominionCommandBridge.isReloadActive(npc)) {
            NativeNpcTargetReaction.satisfyDuringReload(npc, target, DominionCombatBalance.settings());
            NativeGunDiagnostics.gate(npc, "REGULAR", "DOMINION_RELOAD_ACTIVE", command, target,
                    false, false, false, target == null ? Double.NaN : npc.distanceTo(target), range(command), cooldown);
            hold(command);
            return;
        }
        if (target == null) return;
        if (command.commandedAttack() && npc.getTarget() != target) npc.setTarget(target);

        DominionCombatBalance.Settings settings = DominionCombatBalance.settings();
        if (NativeNpcTargetReaction.blocks(npc, target, settings, command.directAttackOrder())) {
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
        Vec3 aimPoint = command.watching()
                ? DominionCommandBridge.watchAimPoint(npc, target, vanillaCanSee ? target.getEyePosition() : null)
                : target.getEyePosition();
        boolean canSee = command.watching() ? aimPoint != null : vanillaCanSee;
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
        if (command.active()) npc.setSprinting(false);
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
            GunTactics.Maneuver maneuver = command.watching() ? GunTactics.Maneuver.SENTRY
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
                    npc.getNavigation().moveTo(target, speed);
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
            npc.getNavigation().moveTo(target, 1.0D);
            return;
        }
        npc.getNavigation().stop();
        if (++strafeTime >= 20) {
            if (npc.getRandom().nextFloat() < 0.3F) clockwise = !clockwise;
            if (npc.getRandom().nextFloat() < 0.3F) backwards = !backwards;
            strafeTime = 0;
        }
        if (distance > range * 0.65D) backwards = false;
        else if (distance < range * 0.60D) backwards = true;
        npc.getMoveControl().strafe((float) (backwards ? -NativeGunConfig.FORWARD_SPEED.get()
                : NativeGunConfig.FORWARD_SPEED.get()),
                (float) (clockwise ? NativeGunConfig.SIDEWAYS_SPEED.get() : -NativeGunConfig.SIDEWAYS_SPEED.get()));
    }

    private void hold(DominionCommandBridge.Snapshot command) {
        if (command.active()) npc.setSprinting(false);
        npc.getNavigation().stop();
        npc.getMoveControl().strafe(0.0F, 0.0F);
    }

    private LivingEntity target(DominionCommandBridge.Snapshot command) {
        return command.active() ? command.attackTarget() : npc.getTarget();
    }

    private double range(DominionCommandBridge.Snapshot command) {
        if (command.watching()) return DominionCommandBridge.watchRange(npc,
                Math.max(2.0D, npc.stats.ranged.getRange() * 2.0D));
        if (command.active()) return Math.max(1.0D, npc.stats.ranged.getRange());
        return switch (NativeGunRuntime.tacz().rangeClass(npc.getMainHandItem())) {
            case NEAR -> Math.min(NativeGunConfig.NEAR_DISTANCE.get(), npc.stats.aggroRange);
            case MEDIUM -> Math.min(NativeGunConfig.MEDIUM_DISTANCE.get(), npc.stats.aggroRange);
            case LONG -> Math.min(NativeGunConfig.LONG_DISTANCE.get(), npc.stats.aggroRange);
        };
    }
}
