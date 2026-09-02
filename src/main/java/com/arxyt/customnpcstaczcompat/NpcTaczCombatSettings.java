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
    public static final int SCHEMA = 1;
    public static final String ROOT_KEY = "customnpcs_tacz_compat_combat";
    private static final String SCHEMA_KEY = "Schema";

    /** Mirrors a newly-created CNPC's native ranged defaults. */
    public static final NpcTaczCombatSettings DEFAULT = new NpcTaczCombatSettings(
            15, 60, 5, 5, 1, 1, 1, 1, 20, 40);

    public NpcTaczCombatSettings {
        range = clamp(range, 1, 256);
        accuracy = clamp(accuracy, 0, 100);
        shotIntervalMin = clamp(shotIntervalMin, 1, 7_200);
        shotIntervalMax = Math.max(shotIntervalMin, clamp(shotIntervalMax, 1, 7_200));
        burstShotsMin = clamp(burstShotsMin, 1, 64);
        burstShotsMax = Math.max(burstShotsMin, clamp(burstShotsMax, 1, 64));
        burstGroupsMin = clamp(burstGroupsMin, 1, 64);
        burstGroupsMax = Math.max(burstGroupsMin, clamp(burstGroupsMax, 1, 64));
        groupIntervalMin = clamp(groupIntervalMin, 1, 7_200);
        groupIntervalMax = Math.max(groupIntervalMin, clamp(groupIntervalMax, 1, 7_200));
    }

    public static boolean isConfigured(EntityNPCInterface npc) {
        return npc != null && npc.getPersistentData().contains(ROOT_KEY, Tag.TAG_COMPOUND)
                && npc.getPersistentData().getCompound(ROOT_KEY).getInt(SCHEMA_KEY) == SCHEMA;
    }

    /**
     * A first access snapshots legacy CNPC ranged fields into this add-on's own data. From that
     * point onward TaCZ never reads the native ranged values again. This is both a lossless
     * upgrade for existing NPCs and the required separation from CNPC projectile settings.
     */
    public static NpcTaczCombatSettings resolve(EntityNPCInterface npc) {
        if (npc == null) return DEFAULT;
        CompoundTag root = npc.getPersistentData();
        if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            NpcTaczCombatSettings migrated = fromNativeRanged(npc);
            root.put(ROOT_KEY, migrated.toTag());
            return migrated;
        }
        CompoundTag tag = root.getCompound(ROOT_KEY);
        if (tag.getInt(SCHEMA_KEY) != SCHEMA) {
            NpcTaczCombatSettings migrated = fromNativeRanged(npc);
            root.put(ROOT_KEY, migrated.toTag());
            return migrated;
        }
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
}
