package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.EnumSet;

/** LOOK-only firing path for Dominion's standing watch order. */
public final class WatchTaczGunGoal extends Goal {
    private final EntityNPCInterface npc;
    private int cooldown;

    public WatchTaczGunGoal(EntityNPCInterface npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override public boolean canUse() { return target(DominionCommandBridge.snapshot(npc)) != null; }
    @Override public boolean canContinueToUse() { return target(DominionCommandBridge.snapshot(npc)) != null; }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { cooldown = 0; }

    @Override public void tick() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        LivingEntity target = target(command);
        if (target == null) return;
        stationary();
        double distance = npc.distanceTo(target);
        double range = DominionCommandBridge.watchRange(npc, 64.0D);
        if (DominionCommandBridge.isReloadActive(npc)) {
            NativeGunDiagnostics.gate(npc, "WATCH", "DOMINION_RELOAD_ACTIVE", command, target,
                    false, false, false, distance, range, cooldown);
            return;
        }
        if (npc.getTarget() != target) npc.setTarget(target);
        DominionCombatBalance.Settings settings = DominionCombatBalance.settings();
        if (NativeNpcTargetReaction.blocks(npc, target, settings, command.directAttackOrder())) {
            NativeGunDiagnostics.gate(npc, "WATCH", "TARGET_REACTION_DELAY", command, target,
                    false, false, false, distance, range, cooldown);
            NativeGunRuntime.tacz().stop(npc, false);
            return;
        }
        NativeNpcTargetReaction.noteTarget(npc, target, settings);
        npc.getLookControl().setLookAt(target, 90.0F, 90.0F);
        boolean aimReady = NpcGunAimLock.prepareForShot(npc, target, true);
        boolean vanillaCanSee = npc.getSensing().hasLineOfSight(target);
        boolean canSee = GunTactics.effectiveLineOfSight(true, vanillaCanSee,
                DominionCommandBridge.watchHasClearShot(npc, target, vanillaCanSee));
        if (!aimReady) {
            NativeGunDiagnostics.gate(npc, "WATCH", "AIM_LOCK_NOT_READY", command, target,
                    false, vanillaCanSee, canSee, distance, range, cooldown);
            return;
        }
        if (!canSee) {
            NativeGunDiagnostics.gate(npc, "WATCH", "NO_CLEAR_SHOT", command, target,
                    true, vanillaCanSee, false, distance, range, cooldown);
            return;
        }
        if (--cooldown > 0) {
            NativeGunDiagnostics.gate(npc, "WATCH", "GOAL_COOLDOWN", command, target,
                    true, vanillaCanSee, true, distance, range, cooldown);
            return;
        }
        cooldown = NativeGunRuntime.tacz().operate(npc, target).delayTicks();
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
}
