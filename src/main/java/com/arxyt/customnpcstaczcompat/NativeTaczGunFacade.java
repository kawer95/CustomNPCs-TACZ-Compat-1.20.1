package com.arxyt.customnpcstaczcompat;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.GunTabType;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private final Map<EntityNPCInterface, FailureState> failures =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<EntityNPCInterface, Integer> lastAmmoWarning =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<EntityNPCInterface, WatchTriggerState> watchTriggers =
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
        watchTriggers.remove(shooter);
        return operate(shooter, target, false);
    }

    public Action operateWatch(EntityNPCInterface shooter, LivingEntity target) {
        return operate(shooter, target, true);
    }

    private Action operate(EntityNPCInterface shooter, LivingEntity target, boolean watchFire) {
        ItemStack stack = shooter.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        Optional<CommonGunIndex> index = gunIndex(stack);
        if (gun == null || index.isEmpty() || target == null || !target.isAlive()) {
            NativeGunDiagnostics.operate(shooter, target, "INVALID_GUN_OR_TARGET");
            return Action.waitFor(20);
        }
        GunData data = index.get().getGunData();
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        prepareImmediateFire(shooter, operator);
        String gunKey = String.valueOf(gun.getGunId(stack));
        boolean gunChanged = !gunKey.equals(equippedGuns.put(shooter, gunKey));
        prepareImmediateEngagement(shooter, operator, gunChanged);

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
        ShootResult result = watchFire && DominionCommandBridge.watchContinuousFireRequested(shooter) && machineGun
                ? heldTriggerShoot(shooter, operator, gun, stack, data, aim.pitch(), adjustedYaw)
                : operator.shoot(aim::pitch, () -> adjustedYaw);
        if (result == ShootResult.IS_SPRINTING) {
            // A later hook may have rebuilt the transition after the first preparation. Do not
            // turn that compatibility race into a one-or-two-second first-shot delay.
            prepareImmediateFire(shooter, operator);
            result = watchFire && DominionCommandBridge.watchContinuousFireRequested(shooter) && machineGun
                    ? heldTriggerShoot(shooter, operator, gun, stack, data, aim.pitch(), adjustedYaw)
                    : operator.shoot(aim::pitch, () -> adjustedYaw);
        }
        if (result == ShootResult.NOT_DRAW) {
            // A late TaCZ capability reset must not reintroduce the old full draw delay.
            // Rebind the already-visible main-hand gun and retry in this same server tick.
            prepareImmediateEngagement(shooter, operator, true);
            result = watchFire && DominionCommandBridge.watchContinuousFireRequested(shooter) && machineGun
                    ? heldTriggerShoot(shooter, operator, gun, stack, data, aim.pitch(), adjustedYaw)
                    : operator.shoot(aim::pitch, () -> adjustedYaw);
        }
        NativeGunDiagnostics.operate(shooter, target, "SHOOT_" + result.name());
        if (transientFailure(result)) {
            if (failureTimedOut(shooter, result)) {
                recover(shooter, operator, result);
                return Action.waitFor(2);
            }
        } else {
            failures.remove(shooter);
        }
        return switch (result) {
            case SUCCESS -> new Action(successDelay(gun, stack, shooter), true);
            case NOT_DRAW -> Action.waitFor(1);
            case NEED_BOLT -> {
                operator.bolt();
                yield Action.waitFor(seconds(data.getBoltActionTime()) + 2);
            }
            case NO_AMMO -> {
                boolean ammunitionMissing = ammunitionMissing(shooter, stack, gun, operator);
                if (ammunitionMissing) {
                    warnCommanderAmmoMissing(shooter);
                    yield Action.waitFor(20);
                }
                operator.reload();
                float reload = data.getReloadData() == null || data.getReloadData().getCooldown() == null
                        ? 1.0F : data.getReloadData().getCooldown().getEmptyTime();
                yield Action.waitFor(seconds(reload) + 2);
            }
            case COOL_DOWN, IS_RELOADING, IS_DRAWING, IS_BOLTING, IS_MELEE, IS_SPRINTING -> Action.waitFor(1);
            default -> Action.waitFor(20);
        };
    }

    public Action continueWatchFire(EntityNPCInterface shooter) {
        WatchTriggerState state = watchTriggers.get(shooter);
        ItemStack stack = shooter.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        Optional<CommonGunIndex> index = gunIndex(stack);
        if (state == null || gun == null || index.isEmpty() || !isMachineGun(stack)
                || !DominionCommandBridge.watchContinuousFireRequested(shooter)) {
            watchTriggers.remove(shooter);
            return Action.waitFor(1);
        }
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        prepareImmediateFire(shooter, operator);
        ShootResult result = heldTriggerShoot(shooter, operator, gun, stack, index.get().getGunData(), state.pitch, state.yaw);
        return new Action(1, result == ShootResult.SUCCESS);
    }

    private ShootResult heldTriggerShoot(EntityNPCInterface shooter, IGunOperator operator, IGun gun,
                                         ItemStack stack, GunData data, float pitch, float yaw) {
        String key = String.valueOf(gun.getGunId(stack));
        WatchTriggerState state = watchTriggers.computeIfAbsent(shooter, ignored -> new WatchTriggerState());
        if (!key.equals(state.gunKey)) { state.gunKey = key; state.chargeProgress = 0.0F; }
        state.pitch = pitch;
        state.yaw = yaw;
        ChargeView charge = chargeData(data, gun.getFireMode(stack));
        if (charge == null) return operator.shoot(() -> pitch, () -> yaw);
        state.chargeProgress = Math.min(charge.maxCharge,
                state.chargeProgress + Math.max(0.0F, charge.increasePerTick));
        float threshold = charge.autoOrDelay() ? charge.maxCharge : charge.fireThreshold;
        if (state.chargeProgress + 0.001F < threshold) return ShootResult.COOL_DOWN;
        long timestamp = System.currentTimeMillis() - operator.getDataHolder().baseTimestamp;
        ShootResult result = chargedShoot(operator, () -> pitch, () -> yaw, timestamp, state.chargeProgress);
        if (result == ShootResult.SUCCESS) {
            state.chargeProgress = charge.delay() ? 0.0F
                    : Math.max(0.0F, state.chargeProgress - charge.decreaseOnFire);
        } else if (result == ShootResult.NO_AMMO || result == ShootResult.IS_RELOADING
                || result == ShootResult.IS_DRAWING || result == ShootResult.IS_BOLTING) {
            state.chargeProgress = 0.0F;
        }
        return result;
    }

    public void stop(EntityNPCInterface shooter, boolean forceExitAim) {
        failures.remove(shooter);
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        if (operator.getSynIsAiming() && (forceExitAim || !DominionCommandBridge.hasQueuedAttack(shooter))) {
            operator.aim(false);
        }
        if (forceExitAim || !DominionCommandBridge.watchContinuousFireRequested(shooter)) watchTriggers.remove(shooter);
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

    private boolean failureTimedOut(EntityNPCInterface shooter, ShootResult result) {
        FailureState previous = failures.get(shooter);
        if (previous == null || previous.result() != result) {
            failures.put(shooter, new FailureState(result, shooter.tickCount));
            return false;
        }
        return shooter.tickCount - previous.sinceTick() >= timeoutTicks(result);
    }

    private void recover(EntityNPCInterface shooter, IGunOperator operator, ShootResult result) {
        failures.remove(shooter);
        shooter.setSprinting(false);
        try { operator.cancelReload(); } catch (RuntimeException ignored) { }
        operator.initialData();
        operator.draw(shooter::getMainHandItem);
        operator.aim(true);
        NativeGunDiagnostics.operate(shooter, shooter.getTarget(), "TIMEOUT_RECOVERY_" + result.name());
    }

    /** Leaves visual locomotion only at the real weapon boundary and skips TaCZ's sprint unwind. */
    private static void prepareImmediateFire(EntityNPCInterface shooter, IGunOperator operator) {
        shooter.setSprinting(false);
        operator.getDataHolder().sprintTimeS = 0.0F;
        operator.getDataHolder().sprintTimestamp = System.currentTimeMillis();
    }

    /**
     * CNPC guns are already represented in the visible main hand, so a command must not replay
     * the player hotbar draw delay. Reinitialization is limited to a real gun change or missing
     * TaCZ binding; ADS is requested without delaying the authoritative server shot.
     */
    private static void prepareImmediateEngagement(EntityNPCInterface shooter, IGunOperator operator,
                                                   boolean gunChanged) {
        if (gunChanged || operator.getDataHolder().currentGunItem == null) operator.initialData();
        operator.getDataHolder().drawTimestamp = -1L;
        if (!operator.getDataHolder().isAiming) operator.aim(true);
        if (gunChanged) NativeGunDiagnostics.operate(shooter, shooter.getTarget(), "IMMEDIATE_BIND_NO_DRAW");
    }

    static boolean transientFailure(ShootResult result) {
        return result != null && NativeGunTimeoutPolicy.transientFailure(result.name());
    }

    static int timeoutTicks(ShootResult result) {
        return result == null ? Integer.MAX_VALUE : NativeGunTimeoutPolicy.timeoutTicks(result.name());
    }

    private static boolean ammunitionMissing(EntityNPCInterface shooter, ItemStack stack,
                                               IGun gun, IGunOperator operator) {
        if (!operator.needCheckAmmo()) return false;
        if (gun.useInventoryAmmo(stack)) return !gun.hasInventoryAmmo(shooter, stack, true);
        return stack.getItem() instanceof AbstractGunItem abstractGun && !abstractGun.canReload(shooter, stack);
    }

    private void warnCommanderAmmoMissing(EntityNPCInterface shooter) {
        DominionCommandBridge.Snapshot command = DominionCommandBridge.snapshot(shooter);
        if (!command.active() || !(shooter.level() instanceof ServerLevel level)) return;
        int previous = lastAmmoWarning.getOrDefault(shooter, Integer.MIN_VALUE);
        if (shooter.tickCount - previous < 20) return;
        lastAmmoWarning.put(shooter, shooter.tickCount);
        var data = shooter.getPersistentData();
        String controllerKey = "dominionsword_controller_player";
        if (!data.hasUUID(controllerKey)) return;
        ServerPlayer commander = level.getServer().getPlayerList().getPlayer(data.getUUID(controllerKey));
        if (commander != null) {
            commander.displayClientMessage(Component.translatable(
                    "message.customnpcs_tacz_compat.ammo_insufficient", shooter.getDisplayName())
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    private record FailureState(ShootResult result, int sinceTick) { }
    private static final class WatchTriggerState {
        private String gunKey = "";
        private float pitch;
        private float yaw;
        private float chargeProgress;
    }
    private static ChargeView chargeData(GunData data, FireMode mode) {
        try {
            Object charge = data.getClass().getMethod("getChargeData", FireMode.class).invoke(data, mode);
            if (charge == null) return null;
            Class<?> type = charge.getClass();
            return new ChargeView(number(type, charge, "getMaxCharge"), number(type, charge, "getIncreasePerTick"),
                    number(type, charge, "getFireThreshold"), number(type, charge, "getDecreaseOnFire"),
                    String.valueOf(type.getMethod("getChargeType").invoke(charge)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }
    private static float number(Class<?> type, Object value, String method) throws ReflectiveOperationException {
        return ((Number) type.getMethod(method).invoke(value)).floatValue();
    }
    private static ShootResult chargedShoot(IGunOperator operator, java.util.function.Supplier<Float> pitch,
                                             java.util.function.Supplier<Float> yaw, long timestamp, float progress) {
        try {
            Object result = operator.getClass().getMethod("shoot", java.util.function.Supplier.class,
                    java.util.function.Supplier.class, long.class, float.class)
                    .invoke(operator, pitch, yaw, timestamp, progress);
            if (result instanceof ShootResult shootResult) return shootResult;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        return operator.shoot(pitch, yaw, timestamp);
    }
    private record ChargeView(float maxCharge, float increasePerTick, float fireThreshold,
                              float decreaseOnFire, String type) {
        boolean autoOrDelay() { return "AUTO".equals(type) || "DELAY".equals(type); }
        boolean delay() { return "DELAY".equals(type); }
    }
}
