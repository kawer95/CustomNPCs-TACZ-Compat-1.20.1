package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import noppes.npcs.entity.EntityNPCInterface;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** LOOK-only firing path for Dominion's standing watch order. */
public final class WatchTaczGunGoal extends Goal {
    private final EntityNPCInterface npc;
    private int cooldown;

    public WatchTaczGunGoal(EntityNPCInterface npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override public boolean canUse() { var command=DominionCommandBridge.snapshot(npc); return target(command)!=null||continuousSession(command); }
    @Override public boolean canContinueToUse() { var command=DominionCommandBridge.snapshot(npc); return target(command)!=null||continuousSession(command); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { cooldown = 0; }

    @Override public void tick() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        LivingEntity target = target(command);
        stationary();
        if (target == null) { NativeGunRuntime.tacz().continueWatchFire(npc); return; }
        double distance = npc.distanceTo(target);
        double range = DominionCommandBridge.watchRange(npc,
                Math.max(2.0D, npc.stats.ranged.getRange() * 2.0D));
        if (DominionCommandBridge.isReloadActive(npc)) {
            NativeNpcTargetReaction.satisfyDuringReload(npc, target, DominionCombatBalance.settings());
            NativeGunDiagnostics.gate(npc, "WATCH", "DOMINION_RELOAD_ACTIVE", command, target,
                    false, false, false, distance, range, cooldown);
            return;
        }
        if (npc.getTarget() != target) npc.setTarget(target);
        DominionCombatBalance.Settings settings = DominionCombatBalance.settings();
        if (NativeNpcTargetReaction.blocks(npc, target, settings, command.directAttackOrder())) {
            NativeGunDiagnostics.gate(npc, "WATCH", "TARGET_REACTION_DELAY", command, target,
                    false, false, false, distance, range, cooldown);
            if (continuousSession(command)) NativeGunRuntime.tacz().continueWatchFire(npc);
            else NativeGunRuntime.tacz().stop(npc, false);
            return;
        }
        NativeNpcTargetReaction.noteTarget(npc, target, settings);
        npc.getLookControl().setLookAt(target, 90.0F, 90.0F);
        boolean vanillaCanSee = npc.getSensing().hasLineOfSight(target);
        Vec3 aimPoint = DominionCommandBridge.watchAimPoint(npc, target,
                vanillaCanSee ? target.getEyePosition() : null);
        boolean canSee = aimPoint != null;
        boolean aimReady = canSee && NpcGunAimLock.prepareForShot(npc, target, true, aimPoint);
        if (!aimReady) {
            NativeGunDiagnostics.gate(npc, "WATCH", "AIM_LOCK_NOT_READY", command, target,
                    false, vanillaCanSee, canSee, distance, range, cooldown);
            if (continuousSession(command)) NativeGunRuntime.tacz().continueWatchFire(npc);
            return;
        }
        if (!canSee) {
            NativeGunDiagnostics.gate(npc, "WATCH", "NO_CLEAR_SHOT", command, target,
                    true, vanillaCanSee, false, distance, range, cooldown);
            if (continuousSession(command)) NativeGunRuntime.tacz().continueWatchFire(npc);
            return;
        }
        boolean continuous = continuousSession(command) && NativeGunRuntime.tacz().isMachineGun(npc.getMainHandItem());
        if (!continuous && --cooldown > 0) {
            NativeGunDiagnostics.gate(npc, "WATCH", "GOAL_COOLDOWN", command, target,
                    true, vanillaCanSee, true, distance, range, cooldown);
            return;
        }
        cooldown = continuous ? NativeGunRuntime.tacz().operateWatch(npc, target).delayTicks()
                : NativeGunRuntime.tacz().operate(npc, target).delayTicks();
    }

    @Override public void stop() { stationary(); NativeGunRuntime.tacz().stop(npc, false); }

    private void stationary() {
        npc.setSprinting(false);
        npc.getNavigation().stop();
        npc.getMoveControl().strafe(0.0F, 0.0F);
    }

    private LivingEntity target(DominionCommandBridge.Snapshot command) {
        LivingEntity target = command.attackTarget();
        return NativeNpcEligibility.active(npc) && command.commandedAttack() && command.watching()
                && !command.prone() && target != null && target != npc && target.isAlive() ? target : null;
    }

    private boolean continuousSession(DominionCommandBridge.Snapshot command) {
        return NativeNpcEligibility.active(npc) && command.watching()
                && DominionCommandBridge.watchContinuousFireRequested(npc);
    }
}
