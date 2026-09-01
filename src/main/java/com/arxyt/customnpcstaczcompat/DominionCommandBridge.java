package com.arxyt.customnpcstaczcompat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, read-only bridge to Dominion Sword's public command and reload APIs. */
public final class DominionCommandBridge {
    private static final String DOMINION_ORDER = "DominionOrder";
    private static final String DOMINION_ATTACK_QUEUE = "DominionAttackQueue";
    private static final String DOMINION_DIRECT_ATTACK = "DominionOfflineAttack";
    private static final String ATTACK_ORDER = "attack";
    private static final String WATCH_ORDER = "watch";
    private static final Snapshot UNAVAILABLE = new Snapshot(false, false, false, false, false, false, false, false, null);
    private static final AtomicBoolean ERROR_REPORTED = new AtomicBoolean();
    private static volatile Access access;

    private DominionCommandBridge() {
    }

    public static void load() {
        try {
            ClassLoader loader = DominionCommandBridge.class.getClassLoader();
            Class<?> api = Class.forName("com.arxyt.dominionsword.api.DominionControlApi", false, loader);
            Class<?> reloadApi = optionalClass("com.arxyt.dominionsword.api.DominionTaczReloadApi", loader);
            Class<?> watchService = optionalClass("com.arxyt.dominionsword.control.WatchService", loader);
            Class<?> view = Class.forName("com.arxyt.dominionsword.api.DominionUnitCommandView", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            access = new Access(
                    unreflect(lookup, api.getMethod("commandView", Mob.class)),
                    unreflect(lookup, view.getMethod("active")),
                    unreflect(lookup, view.getMethod("nativeCombatBlocked")),
                    unreflect(lookup, view.getMethod("autonomousMovementBlocked")),
                    unreflect(lookup, view.getMethod("nativeApproachBlocked")),
                    unreflect(lookup, view.getMethod("closeQuarters")),
                    optionalUnreflect(lookup, view, "prone"),
                    optionalUnreflect(lookup, view, "watching"),
                    unreflect(lookup, view.getMethod("attackTarget")),
                    optionalUnreflect(lookup, api, "commandMovementSpeed", Mob.class),
                    optionalUnreflect(lookup, api, "watchRange", Mob.class, int.class),
                    optionalUnreflect(lookup, api, "watchHasClearShot", Mob.class, LivingEntity.class),
                    optionalUnreflect(lookup, watchService, "continuousFireRequested", Mob.class),
                    optionalUnreflect(lookup, reloadApi, "isReloadActive", Mob.class));
            CustomNpcsTaczCompat.LOGGER.info("Dominion Sword command coordination enabled for native CNPCs");
        } catch (ReflectiveOperationException | LinkageError error) {
            report(error);
        }
    }

    public static Snapshot snapshot(Mob unit) {
        Access current = access;
        if (current == null || unit == null || unit.level().isClientSide) return UNAVAILABLE;
        try {
            Object view = current.commandView.invoke(unit);
            if (view == null || !(boolean) current.active.invoke(view)) return UNAVAILABLE;
            Object target = current.attackTarget.invoke(view);
            CompoundTag data = unit.getPersistentData();
            return new Snapshot(true,
                    (boolean) current.nativeCombatBlocked.invoke(view),
                    (boolean) current.autonomousMovementBlocked.invoke(view),
                    (boolean) current.nativeApproachBlocked.invoke(view),
                    (boolean) current.closeQuarters.invoke(view),
                    current.prone != null && (boolean) current.prone.invoke(view),
                    current.watching != null && (boolean) current.watching.invoke(view),
                    isDirectSingleTargetAttack(data.getString(DOMINION_ORDER), data.hasUUID(DOMINION_DIRECT_ATTACK)),
                    target instanceof LivingEntity living ? living : null);
        } catch (Throwable error) {
            report(error);
            access = null;
            return UNAVAILABLE;
        }
    }

    public static boolean allowsAttack(Mob unit, net.minecraft.world.entity.Entity target) {
        Snapshot command = snapshot(unit);
        if (!command.active()) return true;
        return !command.nativeCombatBlocked() && command.attackTarget() != null
                && target != null && command.attackTarget().getUUID().equals(target.getUUID());
    }

    /** True while Dominion still owns a follow-up target and ADS must not be dropped. */
    public static boolean hasQueuedAttack(Mob unit) {
        if (unit == null || !snapshot(unit).active()) return false;
        CompoundTag data = unit.getPersistentData();
        return hasQueuedAttack(data.getString(DOMINION_ORDER),
                data.getList(DOMINION_ATTACK_QUEUE, Tag.TAG_COMPOUND).size());
    }

    static boolean hasQueuedAttack(String order, int queueEntries) {
        return (ATTACK_ORDER.equals(order) || WATCH_ORDER.equals(order)) && queueEntries > 0;
    }

    public static double commandMovementSpeed(Mob unit, double fallback) {
        Access current = access;
        if (current == null || current.movementSpeed == null || unit == null || unit.level().isClientSide) return fallback;
        try {
            Object value = current.movementSpeed.invoke(unit);
            if (value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() > 0.0D) {
                return number.doubleValue();
            }
        } catch (Throwable error) {
            report(error);
        }
        return fallback;
    }

    public static double watchRange(Mob unit, double fallback) {
        Access current = access;
        if (current == null || current.watchRange == null || unit == null || unit.level().isClientSide) return fallback;
        try {
            Object value = current.watchRange.invoke(unit, (int) Math.round(fallback));
            if (value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() > 0.0D) {
                return number.doubleValue();
            }
        } catch (Throwable error) {
            report(error);
        }
        return fallback;
    }

    public static boolean watchHasClearShot(Mob unit, LivingEntity target, boolean fallback) {
        Access current = access;
        if (current == null || current.watchHasClearShot == null || unit == null || target == null || unit.level().isClientSide) {
            return fallback;
        }
        try {
            Object value = current.watchHasClearShot.invoke(unit, target);
            return value instanceof Boolean result ? result : fallback;
        } catch (Throwable error) {
            report(error);
            return fallback;
        }
    }

    /** Lets all native gun goals yield while Dominion's shared reload service owns TaCZ state. */
    public static boolean isReloadActive(Mob unit) {
        Access current = access;
        if (current == null || current.reloadActive == null || unit == null || unit.level().isClientSide) return false;
        try {
            Object value = current.reloadActive.invoke(unit);
            return value instanceof Boolean active && active;
        } catch (Throwable error) {
            report(error);
            return false;
        }
    }

    public static boolean watchContinuousFireRequested(Mob unit) {
        Access current = access;
        if (current == null || current.watchContinuousFire == null || unit == null || unit.level().isClientSide) return false;
        try {
            Object value = current.watchContinuousFire.invoke(unit);
            return value instanceof Boolean active && active;
        } catch (Throwable error) {
            report(error);
            return false;
        }
    }

    static boolean isDirectSingleTargetAttack(String order, boolean hasDirectTarget) {
        return ATTACK_ORDER.equals(order) && hasDirectTarget;
    }

    private static MethodHandle unreflect(MethodHandles.Lookup lookup, Method method) throws IllegalAccessException {
        return lookup.unreflect(method);
    }

    private static MethodHandle optionalUnreflect(MethodHandles.Lookup lookup, Class<?> owner, String method,
                                                  Class<?>... parameterTypes) throws IllegalAccessException {
        if (owner == null) return null;
        try {
            return unreflect(lookup, owner.getMethod(method, parameterTypes));
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Class<?> optionalClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static void report(Throwable error) {
        if (ERROR_REPORTED.compareAndSet(false, true)) {
            CustomNpcsTaczCompat.LOGGER.error(
                    "Dominion Sword command bridge failed; standalone native-CNPC behavior remains enabled", error);
        }
    }

    public record Snapshot(boolean active, boolean nativeCombatBlocked,
                           boolean autonomousMovementBlocked, boolean nativeApproachBlocked,
                           boolean closeQuarters, boolean prone, boolean watching,
                           boolean directSingleTargetAttack, LivingEntity attackTarget) {
        public boolean commandedAttack() {
            return active && !nativeCombatBlocked && attackTarget != null;
        }

        public boolean directAttackOrder() {
            return commandedAttack() && directSingleTargetAttack;
        }

        public boolean stationarySentry() {
            return watching || active && autonomousMovementBlocked && !nativeCombatBlocked && attackTarget == null;
        }
    }

    private record Access(MethodHandle commandView, MethodHandle active, MethodHandle nativeCombatBlocked,
                          MethodHandle autonomousMovementBlocked, MethodHandle nativeApproachBlocked,
                          MethodHandle closeQuarters, MethodHandle prone, MethodHandle watching,
                          MethodHandle attackTarget, MethodHandle movementSpeed, MethodHandle watchRange,
                          MethodHandle watchHasClearShot, MethodHandle watchContinuousFire,
                          MethodHandle reloadActive) {
    }
}
