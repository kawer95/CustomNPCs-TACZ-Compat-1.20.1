package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import noppes.npcs.entity.EntityNPCInterface;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** LOOK-only firing path while Dominion keeps a native CNPC in Crawl(7). */
public final class ProneTaczGunGoal extends Goal {
    private final EntityNPCInterface npc;
    private int cooldown;

    public ProneTaczGunGoal(EntityNPCInterface npc) {
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
        if (DominionCommandBridge.isReloadActive(npc)) {
            NativeNpcTargetReaction.satisfyDuringReload(npc, target, DominionCombatBalance.settings());
            NativeGunDiagnostics.gate(npc, "PRONE", "DOMINION_RELOAD_ACTIVE", command, target,
                    false, false, false, distance, Double.POSITIVE_INFINITY, cooldown);
            return;
        }
        DominionCombatBalance.Settings settings = DominionCombatBalance.settings();
        if (NativeNpcTargetReaction.blocks(npc, target, settings, command.directAttackOrder())) {
            NativeGunDiagnostics.gate(npc, "PRONE", "TARGET_REACTION_DELAY", command, target,
                    false, false, false, distance, Double.POSITIVE_INFINITY, cooldown);
            if (continuousSession(command)) NativeGunRuntime.tacz().continueWatchFire(npc);
            else NativeGunRuntime.tacz().stop(npc, false);
            if (continuousSession(command)) NativeGunRuntime.tacz().continueWatchFire(npc);
            return;
        }
        NativeNpcTargetReaction.noteTarget(npc, target, settings);
        npc.getLookControl().setLookAt(target, 90.0F, 90.0F);
        boolean vanillaCanSee = npc.getSensing().hasLineOfSight(target);
        Vec3 aimPoint = command.watching()
                ? DominionCommandBridge.watchAimPoint(npc, target, vanillaCanSee ? target.getEyePosition() : null)
                : target.getEyePosition();
        boolean canSee = command.watching() ? aimPoint != null : vanillaCanSee;
        boolean aimReady = canSee && NpcGunAimLock.prepareForShot(npc, target, true, aimPoint);
        // Prone commands deliberately ignore the normal ranged-AI distance cap, but never shoot
        // through terrain or before the first target-facing orientation has synchronized.
        if (!aimReady) {
            NativeGunDiagnostics.gate(npc, "PRONE", "AIM_LOCK_NOT_READY", command, target,
                    false, vanillaCanSee, canSee, distance, Double.POSITIVE_INFINITY, cooldown);
            return;
        }
        if (!canSee) {
            NativeGunDiagnostics.gate(npc, "PRONE", "NO_CLEAR_SHOT", command, target,
                    true, vanillaCanSee, false, distance, Double.POSITIVE_INFINITY, cooldown);
            if (continuousSession(command)) NativeGunRuntime.tacz().continueWatchFire(npc);
            return;
        }
        boolean continuous = continuousSession(command) && NativeGunRuntime.tacz().isMachineGun(npc.getMainHandItem());
        if (!continuous && --cooldown > 0) {
            NativeGunDiagnostics.gate(npc, "PRONE", "GOAL_COOLDOWN", command, target,
                    true, vanillaCanSee, true, distance, Double.POSITIVE_INFINITY, cooldown);
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
        return NativeNpcEligibility.active(npc) && command.commandedAttack() && command.prone()
                && target != null && target != npc && target.isAlive() ? target : null;
    }

    private boolean continuousSession(DominionCommandBridge.Snapshot command) {
        return NativeNpcEligibility.active(npc) && command.prone() && command.watching()
                && DominionCommandBridge.watchContinuousFireRequested(npc);
    }
}
