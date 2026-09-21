package com.arxyt.customnpcstaczcompat;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
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
        if (!NativeDiagnosticLog.enabled()) return;
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

    /**
     * Temporary, deliberately intrusive forensic trail for unexpected CNPC casualties.
     *
     * <p>This is intentionally not guarded by the broad developer diagnostic switch: it is
     * needed during ordinary play to catch a rare death. It observes only damage whose
     * originating attacker resolves to a native CNPC, so general environmental/player/mob
     * combat remains out of the log.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void auditNpcOriginDamage(LivingDamageEvent event) {
        if (!NativeDiagnosticLog.enabled() || event.getEntity().level().isClientSide) return;
        EntityNPCInterface attacker = originatingNpc(event.getSource());
        if (attacker == null) return;
        logAudit("DAMAGE", attacker, event.getEntity(), event.getSource(), event.getAmount());
    }

    /** Records the terminal source separately, including deaths caused by one final large hit. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void auditNpcOriginDeath(LivingDeathEvent event) {
        if (!NativeDiagnosticLog.enabled() || event.getEntity().level().isClientSide) return;
        EntityNPCInterface attacker = originatingNpc(event.getSource());
        if (attacker == null) return;
        logAudit("DEATH", attacker, event.getEntity(), event.getSource(), 0.0F);
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

    /** Resolves both direct CNPC melee sources and a CNPC-owned projectile source. */
    private static EntityNPCInterface originatingNpc(DamageSource source) {
        EntityNPCInterface directAttacker = asNpcOrProjectileOwner(source.getEntity());
        return directAttacker != null ? directAttacker : asNpcOrProjectileOwner(source.getDirectEntity());
    }

    private static EntityNPCInterface asNpcOrProjectileOwner(Entity entity) {
        if (entity instanceof EntityNPCInterface npc) return npc;
        if (entity instanceof Projectile projectile && projectile.getOwner() instanceof EntityNPCInterface npc) {
            return npc;
        }
        return null;
    }

    private static void logAudit(String phase, EntityNPCInterface attacker, LivingEntity victim,
                                 DamageSource source, float amount) {
        CustomNpcsTaczCompat.LOGGER.warn(
                "[CNPC-DAMAGE-AUDIT] phase={} gameTime={} source={} amount={} attacker={{id={},uuid={},name={},type={},weapon={},pos={}}} victim={{id={},uuid={},name={},type={},healthBefore={},maxHealth={},pos={},relation={}}} direct={}",
                phase, victim.level().getGameTime(), source.getMsgId(), decimal(amount),
                attacker.getId(), attacker.getUUID(), attacker.getName().getString(),
                attacker.getType().builtInRegistryHolder().key().location(), weapon(attacker), position(attacker),
                victim.getId(), victim.getUUID(), victim.getName().getString(),
                victim.getType().builtInRegistryHolder().key().location(), decimal(victim.getHealth()),
                decimal(victim.getMaxHealth()), position(victim), relation(attacker, victim),
                describe(source.getDirectEntity()));
    }

    private static String weapon(EntityNPCInterface npc) {
        return npc.getMainHandItem().isEmpty() ? "empty"
                : String.valueOf(BuiltInRegistries.ITEM.getKey(npc.getMainHandItem().getItem()));
    }

    /** Makes an unexpected CNPC-on-CNPC hit diagnosable from one line of log output. */
    private static String relation(EntityNPCInterface attacker, LivingEntity victim) {
        if (!(victim instanceof EntityNPCInterface npc)) return "not_cnpc";
        return "cnpc,aggressive=" + attacker.faction.isAggressiveToNpc(npc)
                + ",allied=" + attacker.isAlliedTo(npc)
                + ",currentTarget=" + isCurrentTarget(attacker, npc);
    }

    private static String describe(Entity entity) {
        if (entity == null) return "none";
        return entity.getType().builtInRegistryHolder().key().location() + "#" + entity.getId()
                + "@" + entity.getUUID();
    }

    private static String position(Entity entity) {
        return "(" + decimal(entity.getX()) + "," + decimal(entity.getY()) + "," + decimal(entity.getZ()) + ")";
    }

    private static String decimal(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
