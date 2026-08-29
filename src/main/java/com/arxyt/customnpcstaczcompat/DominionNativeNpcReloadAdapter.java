package com.arxyt.customnpcstaczcompat;

import com.arxyt.dominionsword.api.DominionTaczReloadAdapter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import noppes.npcs.entity.EntityNPCInterface;

/** Supplies only native-CNPC eligibility and combat danger to Dominion's shared reload service. */
public final class DominionNativeNpcReloadAdapter implements DominionTaczReloadAdapter {
    @Override
    public int priority() {
        return 500;
    }

    @Override
    public boolean supports(Mob unit) {
        return unit instanceof EntityNPCInterface npc && NativeNpcEligibility.active(npc);
    }

    @Override
    public boolean hasCombatTarget(Mob unit) {
        if (!(unit instanceof EntityNPCInterface npc)) return false;
        LivingEntity target = npc.getTarget();
        if (target != null && target.isAlive()) return true;
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(npc);
        return command.attackTarget() != null && command.attackTarget().isAlive()
                || DominionCommandBridge.hasQueuedAttack(npc);
    }

    @Override
    public Profile profile(Mob unit) {
        return Profile.CUSTOM_NPC;
    }
}
