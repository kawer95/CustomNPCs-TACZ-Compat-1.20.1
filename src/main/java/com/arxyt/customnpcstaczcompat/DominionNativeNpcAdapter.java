package com.arxyt.customnpcstaczcompat;

import com.arxyt.dominionsword.api.DominionUnitAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import noppes.npcs.entity.EntityNPCInterface;

/** Gives Dominion the same gun-command boundaries for native-model CNPCs as for YSM CNPCs. */
public final class DominionNativeNpcAdapter implements DominionUnitAdapter {
    @Override
    public int priority() {
        return 500;
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof EntityNPCInterface npc && !NativeNpcEligibility.usesYsmRenderer(npc);
    }

    @Override
    public boolean supportsOfflineTasks(Entity entity) {
        return entity instanceof EntityNPCInterface npc && NativeNpcEligibility.active(npc);
    }

    @Override
    public boolean supportsWatch(Entity entity) {
        return entity instanceof EntityNPCInterface npc && NativeNpcEligibility.active(npc);
    }

    @Override
    public boolean beginOfflineTask(ServerPlayer player, Entity entity) {
        return entity instanceof EntityNPCInterface npc && NativeNpcEligibility.active(npc);
    }

    @Override
    public boolean attack(ServerPlayer player, Entity entity, LivingEntity target) {
        if (!(entity instanceof EntityNPCInterface npc) || !NativeNpcEligibility.active(npc)
                || target == null || !target.isAlive()) return false;
        LivingEntity previous = npc.getTarget();
        if (previous != null && previous.isAlive() && !previous.getUUID().equals(target.getUUID())) {
            NativeNpcTargetReaction.clear(npc);
        }
        npc.setTarget(target);
        return true;
    }

    @Override
    public boolean hold(ServerPlayer player, Entity entity) {
        if (!(entity instanceof EntityNPCInterface npc) || !NativeNpcEligibility.active(npc)) return false;
        npc.getNavigation().stop();
        npc.getMoveControl().strafe(0.0F, 0.0F);
        // HOLD is a sentry stance.  Do not cancel the shot prepared by the LOOK-only gun goal.
        return true;
    }

    @Override
    public boolean clearAttack(ServerPlayer player, Entity entity) {
        if (!(entity instanceof EntityNPCInterface npc)) return false;
        npc.setTarget(null);
        NativeNpcTargetReaction.clear(npc);
        NativeGunRuntime.tacz().stop(npc, true);
        NpcGunAimLock.clear(npc);
        return true;
    }
}
