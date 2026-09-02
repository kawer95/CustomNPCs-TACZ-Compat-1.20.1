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
        if (settings.cadenceMode() == NpcTaczCombatSettings.CadenceMode.NATIVE_AUTO) return true;
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
        if (settings.cadenceMode() == NpcTaczCombatSettings.CadenceMode.NATIVE_AUTO) {
            STATES.remove(npc);
            return 1;
        }

        if (settings.cadenceMode() == NpcTaczCombatSettings.CadenceMode.CONTINUOUS_POINT) {
            int delay = random(npc, settings.shotIntervalMin(), settings.shotIntervalMax());
            state.remainingShots = 0;
            state.remainingGroups = 0;
            state.nextShotTick = npc.tickCount + Math.max(1, delay);
            NativeGunDiagnostics.cadence(npc, settings, delay, state.nextShotTick,
                    state.remainingShots, state.remainingGroups);
            return Math.max(1, delay);
        }

        if (state.remainingGroups == 0) {
            state.remainingGroups = random(npc, settings.burstGroupsMin(), settings.burstGroupsMax());
            state.remainingShots = random(npc, settings.burstShotsMin(), settings.burstShotsMax());
        }

        state.remainingShots--;
        // The shot interval is the base delay between every pair of trigger pulls. The old
        // implementation skipped it for the final (and therefore for a one-shot) group, so a
        // default group size of one silently used only GroupInterval.  That made a visible
        // five-tick "single interval" have no effect at all.
        int delay = random(npc, settings.shotIntervalMin(), settings.shotIntervalMax());
        if (state.remainingShots <= 0) {
            state.remainingGroups--;
            if (state.remainingGroups > 0) {
                state.remainingShots = random(npc, settings.burstShotsMin(), settings.burstShotsMax());
                // Group spacing is an additional pause between distinct groups, never a
                // replacement for the configured interval between shots.
                delay = delayAfterShot(delay,
                        random(npc, settings.groupIntervalMin(), settings.groupIntervalMax()), true);
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

    /** Pure cadence rule retained for regression checks of one-shot groups. */
    static int delayAfterShot(int shotInterval, int groupInterval, boolean hasAnotherGroup) {
        return Math.max(1, shotInterval) + (hasAnotherGroup ? Math.max(1, groupInterval) : 0);
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
