package com.arxyt.customnpcstaczcompat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.server.level.ServerPlayer;
import noppes.npcs.ai.selector.NPCAttackSelector;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Acquires the nearest CNPC-native enemy for an otherwise idle gunner. */
final class IdleNpcTargeting {
    static final double RANGE = 16.0D;

    private IdleNpcTargeting() {
    }

    static LivingEntity find(EntityNPCInterface npc) {
        if (npc == null || npc.isPassenger()) return null;
        NPCAttackSelector selector = new NPCAttackSelector(npc);
        List<LivingEntity> nearby = npc.level().getEntitiesOfClass(LivingEntity.class,
                npc.getBoundingBox().inflate(RANGE), candidate -> candidate != npc && candidate.isAlive());
        LivingEntity selected = nearby.stream()
                .filter(candidate -> eligible(npc, candidate, selector))
                .min(Comparator.comparingDouble(npc::distanceToSqr))
                .orElse(null);
        if (selected == null && !nearby.isEmpty() && Math.floorMod(npc.tickCount + npc.getId(), 40) < 10) {
            String details = nearby.stream().sorted(Comparator.comparingDouble(npc::distanceToSqr)).limit(6)
                    .map(candidate -> describe(npc, candidate, selector)).reduce((a, b) -> a + ";" + b).orElse("none");
            CustomNpcsTaczCompat.LOGGER.info("[CNPC-IDLE-SCAN] npcId={} tick={} candidates={} rejected=[{}]",
                    npc.getId(), npc.tickCount, nearby.size(), details);
        } else if (selected != null) {
            CustomNpcsTaczCompat.LOGGER.info("[CNPC-IDLE-ACQUIRE] npcId={} tick={} targetId={} type={} distance={}",
                    npc.getId(), npc.tickCount, selected.getId(), selected.getType().builtInRegistryHolder().key().location(),
                    String.format(Locale.ROOT, "%.2f", npc.distanceTo(selected)));
        }
        return selected;
    }

    static boolean retained(EntityNPCInterface npc, LivingEntity target) {
        return npc != null && !npc.isPassenger() && target != null && target != npc && target.isAlive()
                && target.level() == npc.level() && npc.distanceToSqr(target) <= RANGE * RANGE
                && !technical(target) && !sharesVehicle(npc, target);
    }

    static boolean valid(EntityNPCInterface npc, LivingEntity target) {
        return npc != null && target != null
                && eligible(npc, target, new NPCAttackSelector(npc));
    }

    /** Keeps an acquired target through pursuit and retreat, even after line of sight is lost. */
    static boolean engaged(EntityNPCInterface npc, LivingEntity target, double maximumRange) {
        if (!basic(npc, target, Math.max(RANGE, maximumRange))) return false;
        return hostile(npc, target, new NPCAttackSelector(npc));
    }

    private static boolean eligible(EntityNPCInterface npc, LivingEntity target, NPCAttackSelector selector) {
        if (!retained(npc, target) || !npc.getSensing().hasLineOfSight(target)) return false;
        return hostile(npc, target, selector);
    }

    private static boolean hostile(EntityNPCInterface npc, LivingEntity target, NPCAttackSelector selector) {
        Boolean dominionDecision = DominionIdleTargetBridge.isEnemy(npc, target);
        if (Boolean.TRUE.equals(dominionDecision)) return true;
        if (selector.isEntityApplicable(target)) return true;
        // CNPC's selector also folds its configurable aggro radius into the hostility test.
        // Idle gun sentry range is deliberately fixed at 16, so repeat only the native faction
        // decision here when the selector rejected an otherwise valid target for distance.
        if (target instanceof EntityNPCInterface other) {
            return !other.isKilled() && npc.advanced.attackOtherFactions
                    && npc.faction.isAggressiveToNpc(other);
        }
        return target instanceof ServerPlayer player && !player.getAbilities().invulnerable
                && npc.faction.isAggressiveToPlayer(player);
    }

    private static boolean basic(EntityNPCInterface npc, LivingEntity target, double maximumRange) {
        return npc != null && !npc.isPassenger() && target != null && target != npc && target.isAlive()
                && target.level() == npc.level() && npc.distanceToSqr(target) <= maximumRange * maximumRange
                && !technical(target) && !sharesVehicle(npc, target);
    }

    private static String describe(EntityNPCInterface npc, LivingEntity target, NPCAttackSelector selector) {
        boolean retained = retained(npc, target);
        boolean lineOfSight = retained && npc.getSensing().hasLineOfSight(target);
        Boolean dominion = retained ? DominionIdleTargetBridge.isEnemy(npc, target) : null;
        boolean nativeAllowed = retained && selector.isEntityApplicable(target);
        return target.getId() + "@" + target.getType().builtInRegistryHolder().key().location()
                + ",d=" + String.format(Locale.ROOT, "%.2f", npc.distanceTo(target))
                + ",los=" + lineOfSight + ",dominion=" + dominion + ",native=" + nativeAllowed;
    }

    private static boolean sharesVehicle(EntityNPCInterface npc, LivingEntity target) {
        return npc.getRootVehicle() == target.getRootVehicle() && npc.getRootVehicle() != npc;
    }

    private static boolean technical(LivingEntity target) {
        if (target instanceof ArmorStand) return true;
        ResourceLocation id = target.getType().builtInRegistryHolder().key().location();
        return "spore".equals(id.getNamespace()) && "scent".equals(id.getPath());
    }
}
