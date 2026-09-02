package com.arxyt.customnpcstaczcompat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import noppes.npcs.entity.EntityNPCInterface;

/**
 * Per-NPC TaCZ firing policy.  It deliberately lives outside CNPC's {@code DataRanged}: TaCZ
 * fires real gun operations rather than CNPC projectile volleys, so {@code ShotCount} cannot be
 * represented faithfully by the native ranged data.
 */
public record NpcTaczCombatSettings(int range, int accuracy,
                                    int shotIntervalMin, int shotIntervalMax,
                                    int burstShotsMin, int burstShotsMax,
                                    int burstGroupsMin, int burstGroupsMax,
                                    int groupIntervalMin, int groupIntervalMax) {
    /**
     * Schema three preserves zero-valued cadence controls as explicit firing-mode switches.
     * Schema two separates an editor's reference values from an explicitly enabled firing
     * policy. Schema one was created automatically on first fire, which accidentally made a
     * normal TaCZ automatic weapon inherit CNPC's five-tick projectile rate.
     */
    public static final int SCHEMA = 3;
    public static final String ROOT_KEY = "customnpcs_tacz_compat_combat";
    private static final String SCHEMA_KEY = "Schema";
    private static final String CONFIGURED_KEY = "Configured";
    private static final int PREVIOUS_SCHEMA = 2;
    private static final int LEGACY_SCHEMA = 1;

    /** Mirrors a newly-created CNPC's native ranged defaults. */
    public static final NpcTaczCombatSettings DEFAULT = new NpcTaczCombatSettings(
            15, 60, 5, 5, 1, 1, 1, 1, 20, 40);

    public NpcTaczCombatSettings {
        range = clamp(range, 1, 256);
        accuracy = clamp(accuracy, 0, 100);
        shotIntervalMin = clampCadence(shotIntervalMin, 7_200);
        shotIntervalMax = clampCadence(shotIntervalMax, 7_200);
        if (shotIntervalMin == 0 || shotIntervalMax == 0) {
            shotIntervalMin = 0;
            shotIntervalMax = 0;
        } else {
            shotIntervalMax = Math.max(shotIntervalMin, shotIntervalMax);
        }
        burstShotsMin = clampCadence(burstShotsMin, 64);
        burstShotsMax = clampCadence(burstShotsMax, 64);
        if (burstShotsMin == 0 || burstShotsMax == 0) {
            burstShotsMin = 0;
            burstShotsMax = 0;
        } else {
            burstShotsMax = Math.max(burstShotsMin, burstShotsMax);
        }
        burstGroupsMin = clampCadence(burstGroupsMin, 64);
        burstGroupsMax = clampCadence(burstGroupsMax, 64);
        if (burstGroupsMin == 0 || burstGroupsMax == 0) {
            burstGroupsMin = 0;
            burstGroupsMax = 0;
        } else {
            burstGroupsMax = Math.max(burstGroupsMin, burstGroupsMax);
        }
        groupIntervalMin = clampCadence(groupIntervalMin, 7_200);
        groupIntervalMax = clampCadence(groupIntervalMax, 7_200);
        if (groupIntervalMin == 0 || groupIntervalMax == 0) {
            groupIntervalMin = 0;
            groupIntervalMax = 0;
        } else {
            groupIntervalMax = Math.max(groupIntervalMin, groupIntervalMax);
        }
    }

    /**
     * Returns values for the editor and for range/accuracy fallback. Reading them is deliberately
     * side-effect free: merely equipping a TaCZ weapon must never enable a custom burst policy.
     */
    public static NpcTaczCombatSettings resolve(EntityNPCInterface npc) {
        if (npc == null) return DEFAULT;
        CompoundTag root = npc.getPersistentData();
        if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) return fromNativeRanged(npc);
        CompoundTag tag = root.getCompound(ROOT_KEY);
        int schema = tag.getInt(SCHEMA_KEY);
        if (schema != SCHEMA && schema != PREVIOUS_SCHEMA && schema != LEGACY_SCHEMA) return fromNativeRanged(npc);
        return fromTag(tag);
    }

    /**
     * Only an explicit click on the TaCZ editor's Done button may change firing cadence.
     *
     * <p>Schema-one data did not record that distinction. It is considered enabled only when it
     * differs from the exact CNPC values it was initially copied from. This restores untouched
     * NPCs to TaCZ's native cadence while retaining real legacy edits.</p>
     */
    public static boolean isConfigured(EntityNPCInterface npc) {
        if (npc == null) return false;
        CompoundTag root = npc.getPersistentData();
        if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag tag = root.getCompound(ROOT_KEY);
        int schema = tag.getInt(SCHEMA_KEY);
        if (schema == SCHEMA || schema == PREVIOUS_SCHEMA) return tag.getBoolean(CONFIGURED_KEY);
        return schema == LEGACY_SCHEMA && !fromTag(tag).equals(fromNativeRanged(npc));
    }

    /**
     * Zero is an intentional mode selector, never a one-tick value:
     * native automatic fire takes precedence over continuous point fire.
     */
    public CadenceMode cadenceMode() {
        if (shotIntervalMax == 0 || burstShotsMax == 0) return CadenceMode.NATIVE_AUTO;
        if (groupIntervalMax == 0 || burstGroupsMax == 0) return CadenceMode.CONTINUOUS_POINT;
        return CadenceMode.INTERMITTENT_POINT;
    }

    public enum CadenceMode {
        /** Leave cadence completely to TaCZ's selected fire mode and weapon cooldown. */
        NATIVE_AUTO,
        /** Repeat the configured shot interval without group pauses. */
        CONTINUOUS_POINT,
        /** Apply shot interval, shots per group, group count and group pause. */
        INTERMITTENT_POINT
    }

    private static NpcTaczCombatSettings fromTag(CompoundTag tag) {
        return new NpcTaczCombatSettings(
                tag.getInt("Range"), tag.getInt("Accuracy"),
                tag.getInt("ShotIntervalMin"), tag.getInt("ShotIntervalMax"),
                tag.getInt("BurstShotsMin"), tag.getInt("BurstShotsMax"),
                tag.getInt("BurstGroupsMin"), tag.getInt("BurstGroupsMax"),
                tag.getInt("GroupIntervalMin"), tag.getInt("GroupIntervalMax"));
    }

    public static void save(EntityNPCInterface npc, NpcTaczCombatSettings settings) {
        if (npc == null || settings == null) return;
        npc.getPersistentData().put(ROOT_KEY, settings.toTag());
        npc.updateClient();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SCHEMA_KEY, SCHEMA);
        tag.putBoolean(CONFIGURED_KEY, true);
        tag.putInt("Range", range);
        tag.putInt("Accuracy", accuracy);
        tag.putInt("ShotIntervalMin", shotIntervalMin);
        tag.putInt("ShotIntervalMax", shotIntervalMax);
        tag.putInt("BurstShotsMin", burstShotsMin);
        tag.putInt("BurstShotsMax", burstShotsMax);
        tag.putInt("BurstGroupsMin", burstGroupsMin);
        tag.putInt("BurstGroupsMax", burstGroupsMax);
        tag.putInt("GroupIntervalMin", groupIntervalMin);
        tag.putInt("GroupIntervalMax", groupIntervalMax);
        return tag;
    }

    private static NpcTaczCombatSettings fromNativeRanged(EntityNPCInterface npc) {
        var ranged = npc.stats.ranged;
        int shotInterval = clamp(ranged.getBurstDelay(), 1, 7_200);
        return new NpcTaczCombatSettings(
                ranged.getRange(), ranged.getAccuracy(),
                shotInterval, shotInterval,
                ranged.getBurst(), ranged.getBurst(),
                ranged.getShotCount(), ranged.getShotCount(),
                ranged.getDelayMin(), ranged.getDelayMax());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Zero is valid syntax; a malformed negative packet must not silently select a mode. */
    private static int clampCadence(int value, int max) {
        return value == 0 ? 0 : clamp(value, 1, max);
    }
}
