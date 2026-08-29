package com.arxyt.customnpcstaczcompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Reads Dominion Sword's optional TaCZ reaction settings without linking the
 * standalone CNPC bridge to Dominion Sword.
 */
public final class DominionCombatBalance {
    private static final AtomicBoolean ERROR_REPORTED = new AtomicBoolean();
    private static volatile Access access;

    private DominionCombatBalance() { }

    /** Resolves optional configuration only after Forge has confirmed the mod exists. */
    public static void load() {
        try {
            Class<?> config = Class.forName("com.arxyt.dominionsword.config.ServerConfig", true,
                    DominionCombatBalance.class.getClassLoader());
            access = new Access(config.getField("BALANCE_CNPC_TARGET_ACQUISITION"),
                    config.getField("DYNAMIC_CNPC_TARGET_ACQUISITION"),
                    config.getField("STANDING_CNPC_MACHINE_GUN_ACCURACY_PENALTY"));
            CustomNpcsTaczCompat.LOGGER.info("Dominion Sword TaCZ reaction balance enabled for native CNPCs");
        } catch (ReflectiveOperationException | LinkageError error) {
            access = null;
            report(error);
        }
    }

    /** Returns unavailable when the optional API or its settings changed. */
    public static Settings settings() {
        Access current = access;
        if (current == null) return Settings.UNAVAILABLE;
        try {
            return new Settings(true, booleanValue(current.targetReaction()), booleanValue(current.dynamicReaction()),
                    booleanValue(current.standingMachineGunPenalty()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            access = null;
            report(error);
            return Settings.UNAVAILABLE;
        }
    }

    private static boolean booleanValue(Field field) throws ReflectiveOperationException {
        Object configValue = field.get(null);
        Object value;
        if (configValue instanceof Supplier<?> supplier) value = supplier.get();
        else if (configValue != null) value = configValue.getClass().getMethod("get").invoke(configValue);
        else throw new ReflectiveOperationException("Missing config value " + field.getName());
        if (value instanceof Boolean enabled) return enabled;
        throw new ReflectiveOperationException("Expected boolean from " + field.getName());
    }

    private static void report(Throwable error) {
        if (ERROR_REPORTED.compareAndSet(false, true)) {
            CustomNpcsTaczCompat.LOGGER.warn(
                    "Dominion Sword reaction settings unavailable; native CNPC firing remains unchanged", error);
        }
    }

    /** Immutable snapshot, safe to use while the optional integration is absent. */
    public record Settings(boolean available, boolean targetReactionEnabled, boolean dynamicTargetReaction,
                           boolean customNpcStandingMachineGunAccuracyPenalty) {
        static final Settings UNAVAILABLE = new Settings(false, false, false, false);
    }

    private record Access(Field targetReaction, Field dynamicReaction, Field standingMachineGunPenalty) { }
}
