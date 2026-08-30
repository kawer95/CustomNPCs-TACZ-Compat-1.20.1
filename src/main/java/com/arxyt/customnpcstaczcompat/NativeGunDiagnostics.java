package com.arxyt.customnpcstaczcompat;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Rate-limited server diagnostics for a native CNPC which visibly holds a gun but does not fire.
 * These records deliberately observe state only; they must never alter goal arbitration or TaCZ.
 */
public final class NativeGunDiagnostics {
    private static final int SAME_STATE_INTERVAL = 20;
    private static final Map<EntityNPCInterface, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private NativeGunDiagnostics() {
    }

    public static void gate(EntityNPCInterface npc, String goal, String reason,
                            DominionCommandBridge.Snapshot command, LivingEntity target,
                            boolean aimReady, boolean vanillaCanSee, boolean effectiveCanSee,
                            double distance, double range, int cooldown) {
        if (npc == null) return;
        State state = state(npc);
        String targetId = target == null ? "none" : target.getUUID().toString();
        String signature = goal + "|" + reason + "|" + targetId;
        if (!state.shouldLogGate(npc.tickCount, signature)) return;

        GunStatus gun = gunStatus(npc);
        AimError aim = aimError(npc, target);
        CustomNpcsTaczCompat.LOGGER.info(
                "[CNPC-TACZ-FIRE-GATE] npcId={} tick={} goal={} reason={} target={} command={active={},attack={},watch={},prone={},blocked={}} distance={} range={} visibility={vanilla={},effective={}} aim={ready={},bodyError={},headError={},pitchError={}} goalCooldown={} reloadTracker={} gun={id={},ammo={},aiming={},aimProgress={},reload={},drawCooldown={},shootCooldown={},bolting={},sprinting={}}",
                npc.getId(), npc.tickCount, goal, reason, targetId,
                command.active(), command.commandedAttack(), command.watching(), command.prone(),
                command.nativeCombatBlocked(), decimal(distance), decimal(range), vanillaCanSee, effectiveCanSee,
                aimReady, decimal(aim.bodyError()), decimal(aim.headError()), decimal(aim.pitchError()), cooldown,
                DominionCommandBridge.isReloadActive(npc), gun.id(), gun.ammo(), gun.aiming(),
                decimal(gun.aimProgress()), gun.reloadState(), gun.drawCooldown(), gun.shootCooldown(),
                gun.bolting(), gun.sprinting());
    }

    /** Records TaCZ entry-state changes and failed/recovered shoot attempts without logging every bullet. */
    public static void operate(EntityNPCInterface npc, LivingEntity target, String outcome) {
        if (npc == null) return;
        State state = state(npc);
        if (!state.shouldLogOperation(npc.tickCount, outcome)) return;
        GunStatus gun = gunStatus(npc);
        CustomNpcsTaczCompat.LOGGER.info(
                "[CNPC-TACZ-OPERATE] npcId={} tick={} target={} outcome={} gun={id={},ammo={},aiming={},aimProgress={},reload={},drawCooldown={},shootCooldown={},bolting={},sprinting={}}",
                npc.getId(), npc.tickCount, target == null ? "none" : target.getUUID(), outcome,
                gun.id(), gun.ammo(), gun.aiming(), decimal(gun.aimProgress()), gun.reloadState(),
                gun.drawCooldown(), gun.shootCooldown(), gun.bolting(), gun.sprinting());
    }

    /** Confirms that a new Ctrl/area queue discarded only stale first-target reaction state. */
    public static void attackQueueStarted(EntityNPCInterface npc) {
        if (npc == null) return;
        CustomNpcsTaczCompat.LOGGER.info(
                "[CNPC-TACZ-ATTACK-QUEUE] npcId={} tick={} action=clear_stale_first_target_reaction",
                npc.getId(), npc.tickCount);
    }

    private static State state(EntityNPCInterface npc) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(npc, ignored -> new State());
        }
    }

    private static GunStatus gunStatus(EntityNPCInterface npc) {
        try {
            ItemStack stack = npc.getMainHandItem();
            IGun gun = IGun.getIGunOrNull(stack);
            IGunOperator operator = IGunOperator.fromLivingEntity(npc);
            ResourceLocation id = gun == null ? null : gun.getGunId(stack);
            int ammo = gun == null ? -1 : gun.getCurrentAmmoCount(stack);
            return new GunStatus(String.valueOf(id), ammo, operator.getSynIsAiming(),
                    operator.getSynAimingProgress(), String.valueOf(operator.getSynReloadState()),
                    operator.getSynDrawCoolDown(), operator.getSynShootCoolDown(), operator.getSynIsBolting(),
                    npc.isSprinting());
        } catch (Throwable error) {
            return new GunStatus("diagnostic-error:" + error.getClass().getSimpleName(), -1, false,
                    Float.NaN, "unknown", -1L, -1L, false, npc.isSprinting());
        }
    }

    private static AimError aimError(EntityNPCInterface npc, LivingEntity target) {
        NpcGunAimLock.AimSolution solution = NpcGunAimLock.solutionFor(npc, target);
        if (!solution.valid()) return AimError.INVALID;
        return new AimError(Math.abs(Mth.wrapDegrees(solution.yaw() - npc.yBodyRot)),
                Math.abs(Mth.wrapDegrees(solution.yaw() - npc.getYHeadRot())),
                Math.abs(Mth.wrapDegrees(solution.pitch() - npc.getXRot())));
    }

    private static String decimal(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.2f", value) : "n/a";
    }

    private record GunStatus(String id, int ammo, boolean aiming, float aimProgress, String reloadState,
                             long drawCooldown, long shootCooldown, boolean bolting, boolean sprinting) {
    }

    private record AimError(float bodyError, float headError, float pitchError) {
        private static final AimError INVALID = new AimError(Float.NaN, Float.NaN, Float.NaN);
    }

    private static final class State {
        private int lastGateTick = Integer.MIN_VALUE;
        private String lastGateSignature = "";
        private int lastOperationTick = Integer.MIN_VALUE;
        private String lastOutcome = "";

        private boolean shouldLogGate(int tick, String signature) {
            if (signature.equals(lastGateSignature) && tick - lastGateTick < SAME_STATE_INTERVAL) return false;
            lastGateTick = tick;
            lastGateSignature = signature;
            return true;
        }

        private boolean shouldLogOperation(int tick, String outcome) {
            boolean success = "SHOOT_SUCCESS".equals(outcome);
            boolean changed = !outcome.equals(lastOutcome);
            if (success && !changed) return false;
            if (!changed && tick - lastOperationTick < SAME_STATE_INTERVAL) return false;
            lastOperationTick = tick;
            lastOutcome = outcome;
            return true;
        }
    }
}
