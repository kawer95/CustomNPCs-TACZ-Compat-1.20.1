package com.arxyt.customnpcstaczcompat;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.GunTabType;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Makes a native CNPC an ordinary TaCZ {@link IGunOperator}; TaCZ remains the
 * authority for magazines, cooldowns, recoil, projectile spawning, and reload state.
 */
public final class NativeTaczGunFacade {
    public enum RangeClass { NEAR, MEDIUM, LONG }
    public record Action(int delayTicks, boolean fired) {
        public static Action waitFor(int ticks) { return new Action(Math.max(1, ticks), false); }
    }

    private final Map<EntityNPCInterface, String> equippedGuns =
            Collections.synchronizedMap(new WeakHashMap<>());

    public boolean isMachineGun(ItemStack stack) {
        return gunIndex(stack).map(index -> GunTabType.MG.name().equalsIgnoreCase(index.getType())).orElse(false);
    }

    public RangeClass rangeClass(ItemStack stack) {
        return gunIndex(stack).map(index -> switch (index.getType().toUpperCase(java.util.Locale.ROOT)) {
            case "SNIPER" -> RangeClass.LONG;
            case "PISTOL", "SHOTGUN", "SMG" -> RangeClass.NEAR;
            default -> RangeClass.MEDIUM;
        }).orElse(RangeClass.MEDIUM);
    }

    public Action operate(EntityNPCInterface shooter, LivingEntity target) {
        ItemStack stack = shooter.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        Optional<CommonGunIndex> index = gunIndex(stack);
        if (gun == null || index.isEmpty() || target == null || !target.isAlive()) {
            NativeGunDiagnostics.operate(shooter, target, "INVALID_GUN_OR_TARGET");
            return Action.waitFor(20);
        }
        GunData data = index.get().getGunData();
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        String gunKey = String.valueOf(gun.getGunId(stack));
        if (!gunKey.equals(equippedGuns.put(shooter, gunKey))) {
            operator.draw(shooter::getMainHandItem);
            NativeGunDiagnostics.operate(shooter, target, "DRAW_REQUESTED");
            return Action.waitFor(seconds(data.getDrawTime()) + 2);
        }
        if (!operator.getSynIsAiming()) {
            operator.aim(true);
            NativeGunDiagnostics.operate(shooter, target, "ADS_REQUESTED");
            return Action.waitFor(seconds(data.getAimTime()) + 2);
        }

        NpcGunAimLock.AimSolution aim = NpcGunAimLock.solutionFor(shooter, target);
        if (!aim.valid()) return Action.waitFor(1);
        double horizontalDistance = Math.sqrt(Math.pow(target.getX() - shooter.getX(), 2.0D)
                + Math.pow(target.getZ() - shooter.getZ(), 2.0D));
        DominionCombatBalance.Settings balance = DominionCombatBalance.settings();
        boolean machineGun = GunTabType.MG.name().equalsIgnoreCase(index.get().getType());
        boolean sniperRifle = GunTabType.SNIPER.name().equalsIgnoreCase(index.get().getType());
        int effectiveAccuracy = effectiveAccuracy(shooter.stats.ranged.getAccuracy(),
                balance.available() && balance.customNpcStandingMachineGunAccuracyPenalty(), machineGun,
                sniperRifle, operator.getDataHolder().isCrawling);
        float adjustedYaw = aim.yaw() + accuracyError(shooter, target, horizontalDistance, effectiveAccuracy);
        ShootResult result = operator.shoot(aim::pitch, () -> adjustedYaw);
        NativeGunDiagnostics.operate(shooter, target, "SHOOT_" + result.name());
        return switch (result) {
            case SUCCESS -> new Action(successDelay(gun, stack, shooter), true);
            case NOT_DRAW -> {
                operator.draw(shooter::getMainHandItem);
                yield Action.waitFor(seconds(data.getDrawTime()) + 2);
            }
            case NEED_BOLT -> {
                operator.bolt();
                yield Action.waitFor(seconds(data.getBoltActionTime()) + 2);
            }
            case NO_AMMO -> {
                operator.reload();
                float reload = data.getReloadData() == null || data.getReloadData().getCooldown() == null
                        ? 1.0F : data.getReloadData().getCooldown().getEmptyTime();
                yield Action.waitFor(seconds(reload) + 2);
            }
            case COOL_DOWN, IS_RELOADING, IS_DRAWING, IS_BOLTING, IS_MELEE, IS_SPRINTING -> Action.waitFor(1);
            default -> Action.waitFor(20);
        };
    }

    public void stop(EntityNPCInterface shooter, boolean forceExitAim) {
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        if (operator.getSynIsAiming() && (forceExitAim || !DominionCommandBridge.hasQueuedAttack(shooter))) {
            operator.aim(false);
        }
    }

    /** Mirrors the visible CNPC crawl animation into TaCZ only when the weapon accepts it. */
    public void syncCrawlState(EntityNPCInterface shooter) {
        if (!NativeNpcEligibility.active(shooter)) return;
        ItemStack stack = shooter.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        boolean requested = gun != null && gun.isCanCrawl(stack) && NpcCrawlState.isCrawling(shooter)
                && shooter.onGround() && !shooter.isPassenger() && !shooter.isSwimming() && !shooter.isSpectator();
        if (operator.getDataHolder().isCrawling != requested) operator.crawl(requested);
    }

    private static Optional<CommonGunIndex> gunIndex(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return Optional.empty();
        ResourceLocation id = gun.getGunId(stack);
        return id == null ? Optional.empty() : TimelessAPI.getCommonGunIndex(id);
    }

    private static int seconds(float seconds) { return Math.max(1, Math.round(seconds * 20.0F)); }

    private static int successDelay(IGun gun, ItemStack stack, EntityNPCInterface shooter) {
        FireMode mode = gun.getFireMode(stack);
        return mode == FireMode.SEMI || mode == FireMode.BURST ? 10 + shooter.getRandom().nextInt(5) : 2;
    }

    /** Preserves CNPC accuracy as a hit probability while leaving TaCZ weapon spread intact. */
    private static float accuracyError(EntityNPCInterface shooter, LivingEntity target, double distance, int accuracy) {
        accuracy = Mth.clamp(accuracy, 0, 100);
        if (shooter.getRandom().nextInt(100) < accuracy) return 0.0F;
        double safeDistance = Math.max(0.1D, distance);
        double safeWidth = Math.max(0.35D, target.getBbWidth() * 0.65D);
        float miss = (float) (Math.toDegrees(Math.atan2(safeWidth, safeDistance)) + 2.0D
                + shooter.getRandom().nextDouble() * 3.0D);
        return shooter.getRandom().nextBoolean() ? miss : -miss;
    }

    private static int effectiveAccuracy(int baseAccuracy, boolean penaltyEnabled, boolean machineGun,
                                         boolean sniperRifle, boolean crawling) {
        int safeAccuracy = Mth.clamp(baseAccuracy, 0, 100);
        if (sniperRifle) {
            return Mth.clamp(Math.round(safeAccuracy * (crawling ? 1.35F : 0.80F)), 0, 100);
        }
        if (!penaltyEnabled || !machineGun || crawling) return safeAccuracy;
        return Mth.clamp(Math.round(safeAccuracy * 0.5F), 0, 100);
    }
}
