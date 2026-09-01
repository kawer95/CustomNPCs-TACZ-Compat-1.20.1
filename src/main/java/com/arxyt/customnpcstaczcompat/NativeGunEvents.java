package com.arxyt.customnpcstaczcompat;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import noppes.npcs.entity.EntityNPCInterface;

/** Server event bridge for crawl-state, faction safety, and TaCZ bullet splash. */
public final class NativeGunEvents {
    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof EntityNPCInterface npc && !npc.level().isClientSide) {
            NativeGunRuntime.tacz().syncCrawlState(npc);
        }
    }

    @SubscribeEvent
    public void protectFactionRelations(EntityHurtByGunEvent.Pre event) {
        if (shouldCancel(event.getAttacker(), event.getHurtEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void protectExplosionRelations(ExplosionEvent.Detonate event) {
        if (!(event.getExplosion().getDirectSourceEntity() instanceof EntityKineticBullet bullet)) return;
        if (!(bullet.getOwner() instanceof LivingEntity attacker)) return;
        event.getAffectedEntities().removeIf(entity -> shouldCancel(attacker, entity));
    }

    /** Keeps one precise trace for the intermittent prone near-muzzle ground collision. */
    @SubscribeEvent
    public void traceNearMuzzleBlockHit(AmmoHitBlockEvent event) {
        EntityKineticBullet bullet = event.getAmmo();
        if (!(bullet.getOwner() instanceof EntityNPCInterface npc) || !NativeNpcEligibility.active(npc)) return;
        double distance = event.getHitResult().getLocation().distanceTo(npc.position());
        if (distance > 3.0D) return;
        CustomNpcsTaczCompat.LOGGER.info(
                "[CNPC-TACZ-BLOCK-HIT] npcId={} tick={} prone={} pose={} crawling={} npcPos={} eyeY={} hit={} distance={}",
                npc.getId(), npc.tickCount, NpcCrawlState.isCrawling(npc), npc.getPose(),
                IGunOperator.fromLivingEntity(npc).getDataHolder().isCrawling,
                npc.position(), npc.getEyeY(), event.getHitResult().getLocation(),
                String.format(java.util.Locale.ROOT, "%.2f", distance));
    }

    private static boolean shouldCancel(LivingEntity attacker, Entity hurt) {
        if (attacker instanceof EntityNPCInterface shooter && NativeNpcEligibility.active(shooter)) {
            if (isCurrentTarget(shooter, hurt)) return false;
            if (hurt instanceof Player player) return !shooter.faction.isAggressiveToPlayer(player);
            if (hurt instanceof EntityNPCInterface npc) return !shooter.faction.isAggressiveToNpc(npc);
            return true;
        }
        if (hurt instanceof EntityNPCInterface npc && NativeNpcEligibility.active(npc)) {
            return npc.isAlliedTo(attacker);
        }
        return false;
    }

    private static boolean isCurrentTarget(EntityNPCInterface shooter, Entity hurt) {
        Entity target = shooter.getTarget();
        return target == hurt || hurt instanceof PartEntity<?> part && part.getParent() == target;
    }
}
