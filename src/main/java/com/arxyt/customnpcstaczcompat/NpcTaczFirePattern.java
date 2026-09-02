package com.arxyt.customnpcstaczcompat;

import noppes.npcs.entity.EntityNPCInterface;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-side random burst state, shared by every native CNPC TaCZ goal for one NPC. */
final class NpcTaczFirePattern {
    private static final Map<EntityNPCInterface, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private NpcTaczFirePattern() { }

    static boolean allowsShot(EntityNPCInterface npc) {
        if (npc == null) return false;
        NpcTaczCombatSettings settings = NpcTaczCombatSettings.resolve(npc);
        State state = STATES.get(npc);
        if (state == null || !state.settings.equals(settings)) return true;
        return npc.tickCount >= state.nextShotTick;
    }

    static int recordSuccessfulShot(EntityNPCInterface npc) {
        if (npc == null) return 1;
        NpcTaczCombatSettings settings = NpcTaczCombatSettings.resolve(npc);
        State state = STATES.computeIfAbsent(npc, ignored -> new State(settings));
        if (!state.settings.equals(settings)) {
            state = new State(settings);
            STATES.put(npc, state);
        }
        if (state.remainingGroups == 0) {
            state.remainingGroups = random(npc, settings.burstGroupsMin(), settings.burstGroupsMax());
            state.remainingShots = random(npc, settings.burstShotsMin(), settings.burstShotsMax());
        }

        state.remainingShots--;
        int delay;
        if (state.remainingShots > 0) {
            delay = random(npc, settings.shotIntervalMin(), settings.shotIntervalMax());
        } else {
            state.remainingGroups--;
            delay = random(npc, settings.groupIntervalMin(), settings.groupIntervalMax());
            if (state.remainingGroups > 0) {
                state.remainingShots = random(npc, settings.burstShotsMin(), settings.burstShotsMax());
            }
        }
        state.nextShotTick = npc.tickCount + Math.max(1, delay);
        NativeGunDiagnostics.cadence(npc, settings, delay, state.nextShotTick,
                state.remainingShots, state.remainingGroups);
        return Math.max(1, delay);
    }

    static void reset(EntityNPCInterface npc) {
        if (npc != null) STATES.remove(npc);
    }

    private static int random(EntityNPCInterface npc, int min, int max) {
        return min == max ? min : min + npc.getRandom().nextInt(max - min + 1);
    }

    private static final class State {
        private final NpcTaczCombatSettings settings;
        private int remainingGroups;
        private int remainingShots;
        private int nextShotTick;

        private State(NpcTaczCombatSettings settings) {
            this.settings = settings;
        }
    }
}
