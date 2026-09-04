package com.arxyt.customnpcstaczcompat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional access to Dominion's authoritative faction/hostility policy. */
final class DominionIdleTargetBridge {
    private static final AtomicBoolean ERROR_REPORTED = new AtomicBoolean();
    private static volatile Method method;
    private static volatile boolean resolved;

    private DominionIdleTargetBridge() {
    }

    /** Returns null when Dominion is absent; otherwise returns its authoritative decision. */
    static Boolean isEnemy(Mob unit, LivingEntity candidate) {
        Method current = resolve();
        if (current == null) return null;
        try {
            return (Boolean) current.invoke(null, unit, candidate);
        } catch (Throwable error) {
            if (ERROR_REPORTED.compareAndSet(false, true)) {
                CustomNpcsTaczCompat.LOGGER.error("Dominion idle-target policy failed; using CNPC policy", error);
            }
            method = null;
            return null;
        }
    }

    private static Method resolve() {
        if (resolved) return method;
        synchronized (DominionIdleTargetBridge.class) {
            if (resolved) return method;
            resolved = true;
            try {
                Class<?> service = Class.forName("com.arxyt.dominionsword.api.DominionTargetApi", false,
                        DominionIdleTargetBridge.class.getClassLoader());
                Method found = service.getMethod("isAutonomousEnemy", Mob.class, LivingEntity.class);
                method = found;
            } catch (ClassNotFoundException ignored) {
                method = null;
            } catch (Throwable error) {
                if (ERROR_REPORTED.compareAndSet(false, true)) {
                    CustomNpcsTaczCompat.LOGGER.error(
                            "Unable to bind Dominion idle-target policy; using CNPC policy", error);
                }
            }
            return method;
        }
    }
}
