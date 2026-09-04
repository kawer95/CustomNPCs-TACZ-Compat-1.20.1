package com.arxyt.customnpcstaczcompat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.server.level.ServerPlayer;
import noppes.npcs.ai.selector.NPCAttackSelector;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.Comparator;

/** Acquires the nearest CNPC-native enemy for an otherwise idle gunner. */
final class IdleNpcTargeting {
    static final double RANGE = 16.0D;

    private IdleNpcTargeting() {
    }

    static LivingEntity find(EntityNPCInterface npc) {
        if (npc == null || npc.isPassenger()) return null;
        NPCAttackSelector selector = new NPCAttackSelector(npc);
        return npc.level().getEntitiesOfClass(LivingEntity.class,
                        npc.getBoundingBox().inflate(RANGE), candidate -> eligible(npc, candidate, selector))
                .stream()
                .min(Comparator.comparingDouble(npc::distanceToSqr))
                .orElse(null);
    }

    static boolean retained(EntityNPCInterface npc, LivingEntity target) {
        return npc != null && !npc.isPassenger() && target != null && target != npc && target.isAlive()
                && target.level() == npc.level() && npc.distanceToSqr(target) <= RANGE * RANGE
                && !technical(target) && !sharesVehicle(npc, target);
    }

    private static boolean eligible(EntityNPCInterface npc, LivingEntity target, NPCAttackSelector selector) {
        if (!retained(npc, target) || !npc.getSensing().hasLineOfSight(target)) return false;
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

    private static boolean sharesVehicle(EntityNPCInterface npc, LivingEntity target) {
        return npc.getRootVehicle() == target.getRootVehicle() && npc.getRootVehicle() != npc;
    }

    private static boolean technical(LivingEntity target) {
        if (target instanceof ArmorStand) return true;
        ResourceLocation id = target.getType().builtInRegistryHolder().key().location();
        return "spore".equals(id.getNamespace()) && "scent".equals(id.getPath());
    }
}
