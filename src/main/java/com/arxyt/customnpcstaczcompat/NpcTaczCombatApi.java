package com.arxyt.customnpcstaczcompat;

import noppes.npcs.entity.EntityNPCInterface;

/**
 * Stable optional API for other CNPC add-ons.  CNPC-YSM reaches this class reflectively, so it
 * never becomes a runtime requirement for the YSM-only installation.
 */
public final class NpcTaczCombatApi {
    private NpcTaczCombatApi() { }

    public static boolean configured(EntityNPCInterface npc) {
        return NpcTaczCombatSettings.isConfigured(npc);
    }

    public static int range(EntityNPCInterface npc) {
        return NpcTaczCombatSettings.resolve(npc).range();
    }

    public static int accuracy(EntityNPCInterface npc) {
        return NpcTaczCombatSettings.resolve(npc).accuracy();
    }

    /** Whether the configured burst state currently permits another TaCZ trigger pull. */
    public static boolean allowsShot(EntityNPCInterface npc) {
        return !configured(npc) || NpcTaczFirePattern.allowsShot(npc);
    }

    /** Records a successful TaCZ trigger pull and returns the configured minimum wait in ticks. */
    public static int recordSuccessfulShot(EntityNPCInterface npc) {
        // With no explicit page policy, request the weapon every AI tick and let TaCZ's own
        // fire mode/cooldown implementation provide its normal continuous-fire cadence.
        return !configured(npc) ? 1 : NpcTaczFirePattern.recordSuccessfulShot(npc);
    }

    public static void resetPattern(EntityNPCInterface npc) {
        NpcTaczFirePattern.reset(npc);
    }
}
