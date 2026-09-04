package com.arxyt.customnpcstaczcompat;

import noppes.npcs.entity.EntityNPCInterface;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Transient server-authoritative run-animation state for autonomous combat return.
 *
 * <p>CustomNPCs recalculates the vanilla sprint bit after goals tick, so setting it only inside
 * the gun goal is not stable. The entity-tail mixin reapplies this marker after CNPC has finished
 * its own update. A weak identity set deliberately avoids persistent NBT: an interrupted return
 * must never leave a saved NPC permanently sprinting after a chunk or world reload.</p>
 */
public final class AutonomousReturnSprint {
    private static final Set<EntityNPCInterface> ACTIVE =
            Collections.newSetFromMap(new WeakHashMap<>());

    private AutonomousReturnSprint() { }

    public static void activate(EntityNPCInterface npc) {
        if (npc == null || npc.level().isClientSide) return;
        ACTIVE.add(npc);
        npc.setSprinting(true);
    }

    public static void deactivate(EntityNPCInterface npc) {
        if (npc == null || npc.level().isClientSide) return;
        if (ACTIVE.remove(npc)) npc.setSprinting(false);
    }

    public static void maintain(EntityNPCInterface npc) {
        if (npc == null || npc.level().isClientSide || !ACTIVE.contains(npc)) return;
        if (!npc.isAlive() || npc.isPassenger()) {
            deactivate(npc);
            return;
        }
        npc.setSprinting(true);
    }
}
