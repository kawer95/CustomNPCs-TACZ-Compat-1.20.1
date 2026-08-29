package com.arxyt.customnpcstaczcompat;

import com.arxyt.dominionsword.api.DominionControlApi;
import com.arxyt.dominionsword.api.DominionTaczReloadApi;

/** Loaded reflectively only after Forge confirms that Dominion Sword is present. */
public final class DominionCompatBootstrap {
    private DominionCompatBootstrap() {
    }

    public static void load() {
        DominionCommandBridge.load();
        DominionCombatBalance.load();
        DominionControlApi.registerAdapter(new DominionNativeNpcAdapter());
        try {
            DominionTaczReloadApi.registerAdapter(new DominionNativeNpcReloadAdapter());
        } catch (LinkageError error) {
            CustomNpcsTaczCompat.LOGGER.warn(
                    "Dominion Sword lacks the shared TACZ reload API; native-CNPC reload integration is disabled", error);
        }
    }
}
