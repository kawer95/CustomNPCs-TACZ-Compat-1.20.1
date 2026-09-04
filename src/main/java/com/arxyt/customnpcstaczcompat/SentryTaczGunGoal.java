package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.EnumSet;

/** Acquires and fires at a native enemy while an otherwise idle NPC remains stationary. */
public final class SentryTaczGunGoal extends Goal {
    private final EntityNPCInterface npc;
    private int cooldown;
    private int nextScanTick;
    private LivingEntity idleTarget;

    public SentryTaczGunGoal(EntityNPCInterface npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() { return target(DominionCommandBridge.snapshot(npc)) != null; }
    @Override public boolean canContinueToUse() { return target(DominionCommandBridge.snapshot(npc)) != null; }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { cooldown = 0; }

    @Override public void tick() {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        LivingEntity target = target(command);
        if (target == null) return;
        npc.getNavigation().stop();
        npc.getMoveControl().strafe(0.0F, 0.0F);
        double range = Math.max(1.0D, NpcTaczCombatApi.range(npc));
        double distance = npc.distanceTo(target);
        if (DominionCommandBridge.isReloadActive(npc)) {
            NativeGunDiagnostics.gate(npc, "SENTRY", "DOMINION_RELOAD_ACTIVE", command, target,
                    false, false, false, distance, range, cooldown);
            return;
        }
        DominionCombatBalance.Settings settings = DominionCombatBalance.settings();
        if (NativeNpcTargetReaction.blocks(npc, target, settings,
                DominionCommandBridge.bypassesTargetReaction(npc))) {
            NativeGunDiagnostics.gate(npc, "SENTRY", "TARGET_REACTION_DELAY", command, target,
                    false, false, false, distance, range, cooldown);
            NativeGunRuntime.tacz().stop(npc, false);
            return;
        }
        NativeNpcTargetReaction.noteTarget(npc, target, settings);
        npc.getLookControl().setLookAt(target, 90.0F, 90.0F);
        boolean aimReady = NpcGunAimLock.prepareForShot(npc, target, false);
        boolean canSee = npc.getSensing().hasLineOfSight(target);
        if (!aimReady) {
            NativeGunDiagnostics.gate(npc, "SENTRY", "AIM_LOCK_NOT_READY", command, target,
                    false, canSee, canSee, distance, range, cooldown);
            return;
        }
        if (distance > range) {
            NativeGunDiagnostics.gate(npc, "SENTRY", "OUT_OF_RANGE", command, target,
                    true, canSee, canSee, distance, range, cooldown);
            return;
        }
        if (!canSee) {
            NativeGunDiagnostics.gate(npc, "SENTRY", "NO_CLEAR_SHOT", command, target,
                    true, false, false, distance, range, cooldown);
            return;
        }
        if (--cooldown > 0) {
            NativeGunDiagnostics.gate(npc, "SENTRY", "GOAL_COOLDOWN", command, target,
                    true, true, true, distance, range, cooldown);
            return;
        }
        cooldown = NativeGunRuntime.tacz().operate(npc, target).delayTicks();
    }

    @Override public void stop() {
        npc.getNavigation().stop();
        npc.getMoveControl().strafe(0.0F, 0.0F);
        NativeGunRuntime.tacz().stop(npc, false);
    }

    private LivingEntity target(DominionCommandBridge.Snapshot command) {
        if (command.watching() || !NativeNpcEligibility.active(npc) || npc.isPassenger()) return null;
        // Explicit Dominion movement/attack tasks always win. An unselected NPC, or a selected
        // NPC whose stationary policy has no target, receives the same 16-block idle sentry scan.
        if (command.active() && !command.stationarySentry()) return null;
        LivingEntity current = idleTarget;
        if (!IdleNpcTargeting.retained(npc, current)) {
            current = npc.getTarget();
            if (!IdleNpcTargeting.retained(npc, current)) current = null;
        }
        if (current == null && npc.tickCount >= nextScanTick) {
            nextScanTick = npc.tickCount + 5 + Math.floorMod(npc.getId(), 5);
            current = IdleNpcTargeting.find(npc);
            if (current != null) npc.setTarget(current);
        }
        idleTarget = current;
        return current;
    }
}
