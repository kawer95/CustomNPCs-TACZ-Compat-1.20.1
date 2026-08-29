package com.arxyt.customnpcstaczcompat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.UUID;

/**
 * Persistent, post-kill target-acquisition delay for native-model CNPC guns.
 * The names are separate from the YSM companion's NBT keys, so both companions
 * can be installed without sharing state.
 */
public final class NativeNpcTargetReaction {
    private static final String LAST_TARGET = "DominionNativeNpcLastGunTarget";
    private static final String REACTION_TARGET = "DominionNativeNpcReactionTarget";
    private static final String REACTION_UNTIL = "DominionNativeNpcReactionUntil";
    private static final String REACTION_STARTED = "DominionNativeNpcReactionStarted";
    private static final String VIEW_X = "DominionNativeNpcReactionViewX";
    private static final String VIEW_Y = "DominionNativeNpcReactionViewY";
    private static final String VIEW_Z = "DominionNativeNpcReactionViewZ";
    private static final String CANDIDATE = "DominionNativeNpcReactionCandidate";
    private NativeNpcTargetReaction() { }

    /** Returns true during an automatic replacement-target reaction window. */
    public static boolean blocks(EntityNPCInterface npc, LivingEntity candidate,
                                 DominionCombatBalance.Settings settings, boolean directAttackOrder) {
        if (npc == null || directAttackOrder || candidate == null || !candidate.isAlive()
                || settings == null || !settings.available() || !settings.targetReactionEnabled()) {
            clear(npc);
            return false;
        }
        CompoundTag data = npc.getPersistentData();
        if (!data.hasUUID(LAST_TARGET)) return false;
        UUID previousId = data.getUUID(LAST_TARGET);
        if (previousId.equals(candidate.getUUID())) {
            clearWindow(data);
            return false;
        }
        Entity previous = npc.level() instanceof ServerLevel level ? level.getEntity(previousId) : null;
        if (previous instanceof LivingEntity living && living.isAlive()) {
            clearWindow(data);
            return false;
        }

        long now = npc.level().getGameTime();
        boolean machineGun = NativeGunRuntime.tacz().isMachineGun(npc.getMainHandItem());
        if (!data.hasUUID(REACTION_TARGET) || !previousId.equals(data.getUUID(REACTION_TARGET))
                || !data.contains(REACTION_STARTED)) {
            Vec3 view = npc.getViewVector(1.0F).normalize();
            data.putUUID(REACTION_TARGET, previousId);
            data.putLong(REACTION_STARTED, now);
            data.putDouble(VIEW_X, view.x);
            data.putDouble(VIEW_Y, view.y);
            data.putDouble(VIEW_Z, view.z);
            data.remove(CANDIDATE);
            data.putLong(REACTION_UNTIL, now + TargetReactionTactics.duration(
                    settings.dynamicTargetReaction(), 0.0D, machineGun));
        }
        if (settings.dynamicTargetReaction() && (!data.hasUUID(CANDIDATE)
                || !candidate.getUUID().equals(data.getUUID(CANDIDATE)))) {
            Vec3 oldView = new Vec3(data.getDouble(VIEW_X), data.getDouble(VIEW_Y), data.getDouble(VIEW_Z)).normalize();
            Vec3 targetDirection = candidate.getEyePosition().subtract(npc.getEyePosition()).normalize();
            double angle = Math.toDegrees(Math.acos(Mth.clamp(oldView.dot(targetDirection), -1.0D, 1.0D)));
            data.putUUID(CANDIDATE, candidate.getUUID());
            data.putLong(REACTION_UNTIL, data.getLong(REACTION_STARTED)
                    + TargetReactionTactics.duration(true, angle, machineGun));
        }
        return data.getLong(REACTION_UNTIL) > now;
    }

    /** Records the current target after its acquisition window has completed. */
    public static void noteTarget(EntityNPCInterface npc, LivingEntity target, DominionCombatBalance.Settings settings) {
        if (npc == null || target == null || !target.isAlive() || settings == null
                || !settings.available() || !settings.targetReactionEnabled()) {
            clear(npc);
            return;
        }
        npc.getPersistentData().putUUID(LAST_TARGET, target.getUUID());
    }

    /** Removes only this companion's NBT state. */
    public static void clear(EntityNPCInterface npc) {
        if (npc == null) return;
        CompoundTag data = npc.getPersistentData();
        data.remove(LAST_TARGET);
        clearWindow(data);
    }

    private static void clearWindow(CompoundTag data) {
        data.remove(REACTION_TARGET);
        data.remove(REACTION_UNTIL);
        data.remove(REACTION_STARTED);
        data.remove(VIEW_X);
        data.remove(VIEW_Y);
        data.remove(VIEW_Z);
        data.remove(CANDIDATE);
    }
}
